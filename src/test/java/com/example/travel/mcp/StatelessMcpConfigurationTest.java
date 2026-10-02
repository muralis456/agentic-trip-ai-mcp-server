package com.example.travel.mcp;

import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class StatelessMcpConfigurationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void toolsListDoesNotCreateOrRequireAnMcpSession() throws Exception {
        String request = """
                {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "method": "tools/list",
                  "params": {}
                }
                """;

        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(header().string("Mcp-Session-Id", emptyOrNullString()));
    }

    @Test
    void statelessEndpointDoesNotExposeAStandaloneGetStream() throws Exception {
        mockMvc.perform(get("/mcp"))
                .andExpect(status().isMethodNotAllowed());
    }
}
