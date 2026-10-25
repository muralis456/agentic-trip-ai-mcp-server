package com.example.travel.mcp.tool;

import com.example.travel.mcp.service.FlightService;
import com.example.travel.mcp.service.McpToolGovernance;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class FlightMcpToolsValidationTest {

    private final FlightService flightService = mock(FlightService.class);
    private final McpToolGovernance governance = mock(McpToolGovernance.class);
    private final FlightMcpTools tools = new FlightMcpTools(flightService, governance);

    @Test
    void rejectsInvalidAirportCodesBeforeCallingProvider() {
        assertThrows(IllegalArgumentException.class,
                () -> tools.searchFlights("BANGALORE", "BOM", null, null, 1, null));
        verifyNoInteractions(flightService, governance);
    }

    @Test
    void rejectsSameOriginAndDestination() {
        assertThrows(IllegalArgumentException.class,
                () -> tools.searchFlights("BLR", "blr", null, null, 1, null));
        verifyNoInteractions(flightService, governance);
    }

    @Test
    void rejectsPastDepartureDate() {
        assertThrows(IllegalArgumentException.class,
                () -> tools.searchFlights("BLR", "BOM", LocalDate.now().minusDays(1).toString(),
                        null, 1, null));
        verifyNoInteractions(flightService, governance);
    }

    @Test
    void rejectsMalformedDateAndInvalidPassengerCount() {
        assertThrows(IllegalArgumentException.class,
                () -> tools.searchFlights("BLR", "BOM", "25-12-2026", null, 1, null));
        assertThrows(IllegalArgumentException.class,
                () -> tools.searchFlights("BLR", "BOM", null, null, 10, null));
        verifyNoInteractions(flightService, governance);
    }

    @Test
    void rejectsReturnDateBeforeDeparture() {
        assertThrows(IllegalArgumentException.class,
                () -> tools.searchFlights("BLR", "BOM", "2026-12-20", "2026-12-19", 2, null));
        verifyNoInteractions(flightService, governance);
    }
}
