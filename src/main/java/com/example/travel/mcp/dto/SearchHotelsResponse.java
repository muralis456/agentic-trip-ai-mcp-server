package com.example.travel.mcp.dto;

import java.util.List;

public record SearchHotelsResponse(boolean success, List<HotelResult> hotels, String message) {
    public static SearchHotelsResponse success(List<HotelResult> hotels, String message) {
        return new SearchHotelsResponse(true, hotels == null ? List.of() : List.copyOf(hotels), message);
    }

    public static SearchHotelsResponse failure(String message) {
        return new SearchHotelsResponse(false, List.of(), message);
    }
}
