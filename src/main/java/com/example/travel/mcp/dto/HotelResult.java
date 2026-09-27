package com.example.travel.mcp.dto;

public record HotelResult(
        String name,
        String area,
        String priceRange,
        String rating,
        String suitableFor,
        String notes,
        String imageUrl,
        String bookingUrl,
        String amenities,
        String hotelClass,
        int reviews,
        String totalPrice,
        String currency,
        String deal,
        boolean freeCancellation,
        String propertyToken,
        String provider) {

    public HotelResult(String name, String area, String priceRange, String rating, String suitableFor, String notes) {
        this(name, area, priceRange, rating, suitableFor, notes, "", "", "", "", 0, "", "", "", false, "", "");
    }
}
