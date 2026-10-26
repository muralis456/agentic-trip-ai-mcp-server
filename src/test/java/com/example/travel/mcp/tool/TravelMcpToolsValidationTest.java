package com.example.travel.mcp.tool;

import com.example.travel.mcp.service.McpToolGovernance;
import com.example.travel.mcp.service.TravelMcpServices;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class TravelMcpToolsValidationTest {
    private final TravelMcpServices services = mock(TravelMcpServices.class);
    private final McpToolGovernance governance = mock(McpToolGovernance.class);
    private final TravelMcpTools tools = new TravelMcpTools(services, governance);

    @Test
    void rejectsBlankHotelDestinationBeforeServiceInvocation() {
        assertThrows(IllegalArgumentException.class,
                () -> tools.searchHotels(" ", "balanced", false, null, null, 2, 0, null));
        verifyNoInteractions(services, governance);
    }

    @Test
    void rejectsItineraryDaysOutsideSupportedRange() {
        assertThrows(IllegalArgumentException.class,
                () -> tools.generateItinerary("Tokyo", 15, null, null, 2,
                        "balanced", false, false, false, "medium"));
        verifyNoInteractions(services, governance);
    }

    @Test
    void rejectsBlankResearchQueryBeforeServiceInvocation() {
        assertThrows(IllegalArgumentException.class,
                () -> tools.searchTravelResearch("   "));
        verifyNoInteractions(services, governance);
    }

    @Test
    void rejectsMalformedDateInsteadOfSilentlyUsingFallback() {
        assertThrows(IllegalArgumentException.class,
                () -> tools.getWeather("Tokyo", "25-12-2026", null));
        verifyNoInteractions(services, governance);
    }

    @Test
    void rejectsWeatherEndDateBeforeStartDate() {
        assertThrows(IllegalArgumentException.class,
                () -> tools.getWeather("Tokyo", "2026-12-10", "2026-12-09"));
        verifyNoInteractions(services, governance);
    }

    @Test
    void rejectsHotelCheckoutBeforeCheckin() {
        assertThrows(IllegalArgumentException.class,
                () -> tools.searchHotels("Tokyo", "balanced", false,
                        "2026-12-10", "2026-12-09", 2, 0, null));
        verifyNoInteractions(services, governance);
    }

    @Test
    void rejectsOverlongResearchQueryBeforeServiceInvocation() {
        assertThrows(IllegalArgumentException.class,
                () -> tools.searchTravelResearch("x".repeat(501)));
        verifyNoInteractions(services, governance);
    }

    @Test
    void rejectsNonPositiveHotelPriceLimit() {
        assertThrows(IllegalArgumentException.class,
                () -> tools.searchHotels("Tokyo", "balanced", false,
                        "2026-12-10", "2026-12-12", 2, 0,
                        java.math.BigDecimal.ZERO));
        verifyNoInteractions(services, governance);
    }
}
