package com.example.travel.mcp.security;

import com.example.travel.mcp.service.McpDistributedStateStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Transport-level authentication and distributed request throttling for MCP.
 *
 * <p>Authentication remains independent from tool authorization. When enabled,
 * rate-limit counters are stored in the shared governance store so multiple
 * MCP replicas enforce the same limit. Without the store, the filter falls
 * back to a per-instance fixed-window limiter.</p>
 */
@Component
@Order(10)
public class McpAuthorizationFilter extends OncePerRequestFilter {

    private final boolean securityEnabled;
    private final String expectedKey;
    private final boolean rateLimitEnabled;
    private final int requestsPerWindow;
    private final long windowMs;
    private final McpDistributedStateStore distributedStateStore;
    private final Map<String, LocalBucket> localBuckets = new ConcurrentHashMap<>();

    public McpAuthorizationFilter(
            @Value("${travel.mcp.security.enabled:false}") boolean securityEnabled,
            @Value("${travel.mcp.security.api-key:}") String expectedKey,
            @Value("${travel.mcp.distributed-state.rate-limit.enabled:false}") boolean rateLimitEnabled,
            @Value("${travel.mcp.distributed-state.rate-limit.requests-per-window:120}") int requestsPerWindow,
            @Value("${travel.mcp.distributed-state.rate-limit.window-seconds:60}") long windowSeconds,
            ObjectProvider<McpDistributedStateStore> distributedStateStore) {
        this.securityEnabled = securityEnabled;
        this.expectedKey = expectedKey == null ? "" : expectedKey.trim();
        this.rateLimitEnabled = rateLimitEnabled;
        this.requestsPerWindow = Math.max(1, requestsPerWindow);
        this.windowMs = Math.max(1000, windowSeconds * 1000L);
        this.distributedStateStore = distributedStateStore.getIfAvailable();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/mcp")) {
            filterChain.doFilter(request, response);
            return;
        }

        if (securityEnabled) {
            String supplied = request.getHeader("X-MCP-API-KEY");
            if (expectedKey.isBlank() || supplied == null || !constantTimeEquals(expectedKey, supplied.trim())) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "MCP authorization failed.");
                return;
            }
        }

        if (rateLimitEnabled) {
            String bucketKey = bucketKey(request);
            boolean allowed = distributedStateStore != null
                    ? distributedStateStore.allowRequest(bucketKey, requestsPerWindow, windowMs,
                    System.currentTimeMillis())
                    : allowLocal(bucketKey);
            if (!allowed) {
                response.setHeader("Retry-After", Long.toString(Math.max(1, windowMs / 1000)));
                response.sendError(429, "MCP rate limit exceeded.");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean allowLocal(String key) {
        long now = System.currentTimeMillis();
        long windowStart = now - (now % windowMs);
        LocalBucket bucket = localBuckets.compute(key, (ignored, current) -> {
            if (current == null || current.windowStart != windowStart) {
                return new LocalBucket(windowStart, new AtomicInteger(1));
            }
            current.count.incrementAndGet();
            return current;
        });
        return bucket.count.get() <= requestsPerWindow;
    }

    private String bucketKey(HttpServletRequest request) {
        String principal = request.getHeader("X-MCP-API-KEY");
        if (principal == null || principal.isBlank()) {
            principal = request.getRemoteAddr();
        }
        return "mcp:" + sha256(principal.trim());
    }

    private boolean constantTimeEquals(String expected, String supplied) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                result.append(String.format("%02x", b));
            }
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash MCP rate-limit key", exception);
        }
    }

    private static final class LocalBucket {
        private final long windowStart;
        private final AtomicInteger count;

        private LocalBucket(long windowStart, AtomicInteger count) {
            this.windowStart = windowStart;
            this.count = count;
        }
    }
}
