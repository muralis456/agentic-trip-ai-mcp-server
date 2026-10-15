package com.example.travel.mcp.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import static org.junit.jupiter.api.Assertions.*;

class McpToolGovernanceSecurityTest {
    private McpToolGovernance newGovernance() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        return new McpToolGovernance(true, 2048, "",
                "search_flights:READ_ONLY,book_flight:SIDE_EFFECTING", "",
                "USER:READ_ONLY;ADMIN:READ_ONLY,SIDE_EFFECTING", 3, 30_000L,
                beans.getBeanProvider(McpDistributedStateStore.class));
    }

    @Test
    void rejectsMissingAuthenticatedUser() {
        assertThrows(SecurityException.class,
                () -> newGovernance().check("search_flights", " ", "USER", "{}", false));
    }

    @Test
    void rejectsUnconfiguredTool() {
        assertThrows(SecurityException.class,
                () -> newGovernance().check("shell_exec", "user-1", "ADMIN", "{}", true));
    }

    @Test
    void rejectsOversizedArguments() {
        String oversized = "x".repeat(4096);
        assertThrows(SecurityException.class,
                () -> newGovernance().check("search_flights", "user-1", "USER", oversized, false));
    }

    @Test
    void rejectsSideEffectWithoutApproval() {
        assertThrows(SecurityException.class,
                () -> newGovernance().check("book_flight", "user-1", "ADMIN", "{}", false));
    }

    @Test
    void rejectsSideEffectForRegularUserEvenWhenApprovalIsClaimed() {
        assertThrows(SecurityException.class,
                () -> newGovernance().check("book_flight", "user-1", "USER", "{}", true));
    }


    @Test
    void rejectsCallerSuppliedApprovalClaimEvenForAdmin() {
        assertThrows(SecurityException.class,
                () -> newGovernance().check("book_flight", "admin-1", "ADMIN", "{\"approvalGranted\":true}", true));
    }

    @Test
    void rejectsConfiguredApprovalToolEvenWhenCallerClaimsApproval() {
        // Approval-required tools must remain blocked until a server-owned
        // approval record is verified against the exact action and arguments.
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        McpToolGovernance governance = new McpToolGovernance(true, 2048, "",
                "search_flights:READ_ONLY,book_flight:READ_ONLY", "book_flight",
                "USER:READ_ONLY;ADMIN:READ_ONLY,SIDE_EFFECTING", 3, 30_000L,
                beans.getBeanProvider(McpDistributedStateStore.class));
        assertThrows(SecurityException.class,
                () -> governance.check("book_flight", "admin-1", "ADMIN", "{}", true));
    }

    @Test
    void allowsConfiguredReadOnlyToolForAuthenticatedUser() {
        assertDoesNotThrow(
                () -> newGovernance().check("search_flights", "user-1", "USER", "{}", false));
    }
}
