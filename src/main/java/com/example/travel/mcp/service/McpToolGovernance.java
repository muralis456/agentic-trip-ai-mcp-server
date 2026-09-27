package com.example.travel.mcp.service;

import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class McpToolGovernance {

    private static final Logger log = LoggerFactory.getLogger(McpToolGovernance.class);

    private final Set<String> allowedTools;

    public McpToolGovernance(@Value("${travel.mcp.allowed-tools:search_flights,search_hotels,get_weather,resolve_airport,search_travel_research}") String configuredTools) {
        this.allowedTools = Set.of(configuredTools.split(","));
    }

    public void check(String toolName) {
        if (!allowedTools.contains(toolName)) {
            log.warn("mcp.tool.denied name={}", toolName);
            throw new IllegalStateException("MCP tool is not allowed: " + toolName);
        }
    }
}
