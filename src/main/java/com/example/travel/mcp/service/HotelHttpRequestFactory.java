package com.example.travel.mcp.service;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import java.time.Duration;

final class HotelHttpRequestFactory {
    private HotelHttpRequestFactory() {}
    static SimpleClientHttpRequestFactory create(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(connectTimeout);
        f.setReadTimeout(readTimeout);
        return f;
    }
}
