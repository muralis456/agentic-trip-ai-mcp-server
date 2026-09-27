package com.example.travel.mcp.service;

import java.math.BigDecimal;
import java.time.LocalDate;

public record HotelSearchRequest(
        String destination,
        String travelStyle,
        boolean cheaper,
        LocalDate checkIn,
        LocalDate checkOut,
        int adults,
        int children,
        BigDecimal maxPricePerNight
) {}
