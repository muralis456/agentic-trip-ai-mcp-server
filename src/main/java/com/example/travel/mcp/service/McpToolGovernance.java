package com.example.travel.mcp.service;

import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Deterministic server-side policy boundary for every MCP tool invocation.
 *
 * <p>The MCP server is an independent trust boundary. Even when a client has
 * already performed policy checks, the server validates the tool, authenticated
 * caller, role, arguments and circuit state before executing provider code.</p>
 */
@Service
public class McpToolGovernance {

    public enum ActionClass {
        READ_ONLY,
        SIDE_EFFECTING
    }

    private static final Logger log = LoggerFactory.getLogger(McpToolGovernance.class);

    private final boolean enabled;
    private final int maxArgumentBytes;
    private final Set<String> allowedUsers;
    private final Map<String, ActionClass> toolPolicies;
    private final Map<String, Set<ActionClass>> rolePermissions;
    private final Set<String> approvalTools;
    private final Map<String, Long> cooldownUntil = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();
    private final int circuitFailureThreshold;
    private final long circuitOpenMs;

    public McpToolGovernance(
            @Value("${travel.mcp.governance.enabled:true}") boolean enabled,
            @Value("${travel.mcp.governance.max-argument-bytes:16384}") int maxArgumentBytes,
            @Value("${travel.mcp.governance.allowed-users:}") String configuredUsers,
            @Value("${travel.mcp.governance.tools:search_flights:READ_ONLY,search_hotels:READ_ONLY,get_weather:READ_ONLY,resolve_airport:READ_ONLY,search_travel_research:READ_ONLY,generate_itinerary:READ_ONLY}") String configuredTools,
            @Value("${travel.mcp.governance.approval-tools:}") String configuredApprovalTools,
            @Value("${travel.mcp.governance.roles:USER:READ_ONLY;ADMIN:READ_ONLY,SIDE_EFFECTING}") String configuredRoles,
            @Value("${travel.mcp.governance.circuit-failure-threshold:3}") int circuitFailureThreshold,
            @Value("${travel.mcp.governance.circuit-open-ms:30000}") long circuitOpenMs) {
        this.enabled = enabled;
        this.maxArgumentBytes = Math.max(1024, maxArgumentBytes);
        this.allowedUsers = parseSet(configuredUsers);
        this.toolPolicies = parseToolPolicies(configuredTools);
        this.rolePermissions = parseRolePermissions(configuredRoles);
        this.approvalTools = parseSet(configuredApprovalTools);
        this.circuitFailureThreshold = Math.max(1, circuitFailureThreshold);
        this.circuitOpenMs = Math.max(1000, circuitOpenMs);
    }

    public void check(String toolName) {
        check(toolName, null, "USER", null, false);
    }

    public void check(String toolName, String userId, String role, String argumentsJson, boolean approvalGranted) {
        if (!enabled) {
            return;
        }

        String normalizedTool = normalize(toolName);
        String normalizedUser = userId == null ? "" : userId.trim();
        String normalizedRole = normalizeRole(role);

        if (normalizedTool.isBlank()) {
            deny(normalizedTool, normalizedUser, normalizedRole, "TOOL_REQUIRED");
            throw new SecurityException("MCP tool name is required");
        }
        if (normalizedUser.isBlank()) {
            deny(normalizedTool, normalizedUser, normalizedRole, "AUTHENTICATED_USER_REQUIRED");
            throw new SecurityException("Authenticated MCP user is required");
        }
        if (!allowedUsers.isEmpty() && !allowedUsers.contains(normalizedUser)) {
            deny(normalizedTool, normalizedUser, normalizedRole, "USER_NOT_ALLOWED");
            throw new SecurityException("MCP user is not allowed: " + normalizedUser);
        }

        ActionClass actionClass = toolPolicies.get(normalizedTool);
        if (actionClass == null) {
            deny(normalizedTool, normalizedUser, normalizedRole, "TOOL_NOT_CONFIGURED");
            throw new SecurityException("MCP tool is not configured in the server policy: " + normalizedTool);
        }

        if (argumentsJson != null
                && argumentsJson.getBytes(StandardCharsets.UTF_8).length > maxArgumentBytes) {
            deny(normalizedTool, normalizedUser, normalizedRole, "ARGUMENTS_TOO_LARGE");
            throw new SecurityException("MCP tool arguments exceed the configured server safety limit");
        }

        Long until = cooldownUntil.get(normalizedTool);
        if (until != null && until > System.currentTimeMillis()) {
            deny(normalizedTool, normalizedUser, normalizedRole, "CIRCUIT_OPEN");
            throw new IllegalStateException("MCP tool circuit is open: " + normalizedTool);
        }

        Set<ActionClass> permissions = rolePermissions.getOrDefault(
                normalizedRole, Set.of());
        if (!permissions.contains(actionClass)) {
            deny(normalizedTool, normalizedUser, normalizedRole, "ROLE_NOT_ALLOWED");
            throw new SecurityException("Role " + normalizedRole + " is not allowed to invoke " + normalizedTool);
        }

        boolean requiresApproval = actionClass == ActionClass.SIDE_EFFECTING
                || approvalTools.contains(normalizedTool);
        if (requiresApproval && !approvalGranted) {
            log.warn("mcp.policy tool={} userId={} role={} action={} decision=APPROVAL_REQUIRED",
                    normalizedTool, normalizedUser, normalizedRole, actionClass);
            throw new SecurityException("Human approval is required before executing MCP tool: " + normalizedTool);
        }

        log.info("mcp.policy tool={} userId={} role={} action={} decision=ALLOW",
                normalizedTool, normalizedUser, normalizedRole, actionClass);
    }

    public ActionClass actionClass(String toolName) {
        return toolPolicies.get(normalize(toolName));
    }

    public boolean isConfigured(String toolName) {
        return toolPolicies.containsKey(normalize(toolName));
    }

    public boolean requiresApproval(String toolName) {
        String normalized = normalize(toolName);
        return approvalTools.contains(normalized)
                || toolPolicies.get(normalized) == ActionClass.SIDE_EFFECTING;
    }

    public void recordSuccess(String toolName) {
        String key = normalize(toolName);
        failures.remove(key);
        cooldownUntil.remove(key);
    }

    public void recordFailure(String toolName) {
        String key = normalize(toolName);
        int count = failures.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
        if (count >= circuitFailureThreshold) {
            cooldownUntil.put(key, System.currentTimeMillis() + circuitOpenMs);
            log.warn("mcp.policy circuit-open tool={} failures={} openMs={}",
                    key, count, circuitOpenMs);
        }
    }

    private Map<String, ActionClass> parseToolPolicies(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        Map<String, ActionClass> result = new ConcurrentHashMap<>();
        for (String definition : value.split(",")) {
            String[] parts = definition.trim().split(":", 2);
            if (parts.length != 2) continue;
            try {
                result.put(normalize(parts[0]),
                        ActionClass.valueOf(parts[1].trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                log.warn("mcp.policy invalid-tool-policy entry={}", definition);
            }
        }
        return Map.copyOf(result);
    }

    private Map<String, Set<ActionClass>> parseRolePermissions(String value) {
        Map<String, Set<ActionClass>> result = new ConcurrentHashMap<>();
        if (value == null || value.isBlank()) {
            return Map.of("USER", Set.of(ActionClass.READ_ONLY));
        }
        for (String definition : value.split(";")) {
            String[] parts = definition.split(":", 2);
            if (parts.length != 2) continue;
            Set<ActionClass> actions = Arrays.stream(parts[1].split(","))
                    .map(String::trim)
                    .filter(v -> !v.isBlank())
                    .map(v -> {
                        try {
                            return ActionClass.valueOf(v.toUpperCase(Locale.ROOT));
                        } catch (IllegalArgumentException ignored) {
                            return null;
                        }
                    })
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toUnmodifiableSet());
            result.put(normalizeRole(parts[0]), actions);
        }
        return Map.copyOf(result);
    }

    private Set<String> parseSet(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(v -> !v.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeRole(String value) {
        return value == null || value.isBlank() ? "USER" : value.trim().toUpperCase(Locale.ROOT);
    }

    private void deny(String toolName, String userId, String role, String reason) {
        log.warn("mcp.policy tool={} userId={} role={} decision=DENY reason={}",
                toolName, userId, role, reason);
    }
}
