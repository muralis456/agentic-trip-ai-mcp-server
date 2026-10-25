package com.example.travel.mcp.security;

import com.example.travel.mcp.service.McpDistributedStateStore;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class McpAuthorizationFilterSecurityTest {

    private McpAuthorizationFilter filter(boolean enabled, String expectedKey) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        return new McpAuthorizationFilter(enabled, expectedKey, false, 120, 60,
                beans.getBeanProvider(McpDistributedStateStore.class));
    }

    @Test
    void rejectsMissingApiKeyOnMcpEndpoint() throws Exception {
        var filter = filter(true, "strong-test-key");
        var request = new MockHttpServletRequest("POST", "/mcp");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void rejectsIncorrectApiKeyOnMcpEndpoint() throws Exception {
        var filter = filter(true, "strong-test-key");
        var request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("X-MCP-API-KEY", "wrong-key");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
    }

    @Test
    void acceptsCorrectApiKeyOnMcpEndpoint() throws Exception {
        var filter = filter(true, "strong-test-key");
        var request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("X-MCP-API-KEY", "strong-test-key");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    void requiredProductionKeyFailsClosedWhenMissing() {
        var filter = filter(false, " ");
        ReflectionTestUtils.setField(filter, "requireApiKey", true);
        assertThrows(IllegalStateException.class, filter::validateApiKeyConfiguration);
    }

    @Test
    void doesNotApplyMcpAuthenticationToOtherEndpoints() throws Exception {
        var filter = filter(true, "strong-test-key");
        var request = new MockHttpServletRequest("GET", "/actuator/health");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }
}
