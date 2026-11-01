package com.example.travel.mcp.tool;

import com.example.travel.mcp.dto.SearchFlightsRequest;
import com.example.travel.mcp.dto.SearchFlightsResponse;
import com.example.travel.mcp.service.FlightService;
import com.example.travel.mcp.service.McpToolGovernance;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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

    @Test
    void rejectsPastReturnDateEvenWhenDepartureIsUnspecified() {
        assertThrows(IllegalArgumentException.class,
                () -> tools.searchFlights("BLR", "BOM", null,
                        LocalDate.now().minusDays(1).toString(), 1, null));
        verifyNoInteractions(flightService, governance);
    }

    @Test
    void normalizesValidatedFieldsBeforeCallingFlightService() {
        LocalDate departure = LocalDate.now().plusDays(2);
        LocalDate returning = departure.plusDays(4);
        when(flightService.search(any())).thenReturn(SearchFlightsResponse.success(List.of(), "No options"));

        tools.searchFlights(" blr ", "bom", " " + departure + " ",
                " " + returning + " ", 2, " IGNAV ");

        var request = org.mockito.ArgumentCaptor.forClass(SearchFlightsRequest.class);
        verify(flightService).search(request.capture());
        assertEquals("BLR", request.getValue().origin());
        assertEquals("BOM", request.getValue().destination());
        assertEquals(departure.toString(), request.getValue().departureDate());
        assertEquals(returning.toString(), request.getValue().returnDate());
        assertEquals("IGNAV", request.getValue().preferredProvider());
        verify(governance).check("search_flights");
    }

    @Test
    void rejectsOversizedOrControlCharacterPreferredProvider() {
        assertThrows(IllegalArgumentException.class,
                () -> tools.searchFlights("BLR", "BOM", null, null, 1, "x".repeat(81)));
        assertThrows(IllegalArgumentException.class,
                () -> tools.searchFlights("BLR", "BOM", null, null, 1, "IGNAV\ninjected"));
        verifyNoInteractions(flightService, governance);
    }
}
