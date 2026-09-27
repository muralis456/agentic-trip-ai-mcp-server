package com.example.travel.mcp.dto;

import java.util.List;

public record ResearchResult(boolean success, List<ResearchHit> hits, String message) {
    public static ResearchResult success(List<ResearchHit> hits, String message) {
        return new ResearchResult(true, hits == null ? List.of() : List.copyOf(hits), message);
    }

    public static ResearchResult failure(String message) {
        return new ResearchResult(false, List.of(), message);
    }
}
