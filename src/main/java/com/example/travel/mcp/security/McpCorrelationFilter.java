package com.example.travel.mcp.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Creates one bounded correlation id per MCP HTTP request.
 *
 * <p>The id is returned to the caller and placed in MDC so logs from
 * authentication, governance and tool execution can be joined across
 * application replicas. Client-provided ids are accepted only when they
 * match a conservative UUID-like format.</p>
 */
@Component
@Order(1)
public class McpCorrelationFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Correlation-ID";
    static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String supplied = request.getHeader(HEADER);
        String correlationId = isSafe(supplied) ? supplied : UUID.randomUUID().toString();

        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private boolean isSafe(String value) {
        return value != null && value.length() <= 64
                && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,63}");
    }
}
