package com.example.travel.mcp.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class McpAuthorizationFilter extends OncePerRequestFilter {

    private final boolean enabled;
    private final String expectedKey;

    public McpAuthorizationFilter(
            @Value("${travel.mcp.security.enabled:false}") boolean enabled,
            @Value("${travel.mcp.security.api-key:}") String expectedKey) {
        this.enabled = enabled;
        this.expectedKey = expectedKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/mcp") || !enabled) {
            filterChain.doFilter(request, response);
            return;
        }
        String supplied = request.getHeader("X-MCP-API-KEY");
        if (expectedKey.isBlank() || !expectedKey.equals(supplied)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "MCP authorization failed.");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
