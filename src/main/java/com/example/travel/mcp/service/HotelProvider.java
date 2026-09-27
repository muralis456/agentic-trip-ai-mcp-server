package com.example.travel.mcp.service;

import com.example.travel.mcp.dto.SearchHotelsResponse;

public interface HotelProvider {
    String name();
    boolean enabled();
    SearchHotelsResponse search(HotelSearchRequest request);
}
