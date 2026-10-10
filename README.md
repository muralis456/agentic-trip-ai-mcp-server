# AgenticTripAI MCP Server

Spring AI 2.0.1 / Spring Boot 4.1.1 MCP server for the AgenticTripAI platform.

## MCP transport

The server runs **stateless MCP over HTTP by default**:

```yaml
spring:
  ai:
    mcp:
      server:
        protocol: ${MCP_SERVER_PROTOCOL:STATELESS}
        streamable-http:
          mcp-endpoint: /mcp
```

Stateless mode means the MCP transport does not keep server-side session state between requests. Each request can be handled by any application instance, so the endpoint can sit behind a normal load balancer without MCP session affinity.

The existing tool/business state is still externalized where required:

- Flight/hotel/provider state is owned by the provider integrations.
- Governance/circuit-breaker state is application state and must use a shared store if it needs to be consistent across replicas.
- The MCP transport itself does not use an in-memory MCP session as a source of truth.

Spring AI's Streamable-HTTP MCP client transport can connect to stateless MCP servers, so the AgenticTripAI production application continues to use:

```yaml
spring:
  ai:
    mcp:
      client:
        streamable-http:
          connections:
            travel:
              url: http://localhost:8090
              endpoint: /mcp
```

## Security

The MCP endpoint is protected by the project's `X-MCP-API-KEY` filter when:

```text
MCP_SECURITY_ENABLED=true
MCP_API_KEY=<strong-secret>
```

Tool-level governance remains enabled independently.

## Compatibility

Stateful Streamable-HTTP can still be enabled for a legacy integration:

```text
MCP_SERVER_PROTOCOL=STREAMABLE
```

Use this only when the client explicitly depends on MCP session state. The default is `STATELESS`.

## Local run

```bash
mvn spring-boot:run
```

Server:

```text
http://localhost:8090
```

MCP endpoint:

```text
http://localhost:8090/mcp
```

## Stateless smoke test

With the server running, the following request should return HTTP 200 and should not return an `Mcp-Session-Id` response header:

```bash
curl -i -X POST http://localhost:8090/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
```

A standalone `GET /mcp` is intentionally not a persistent SSE session in stateless mode.

## Why stateless

Stateful MCP HTTP requires session affinity or shared session storage when horizontally scaled. Stateless MCP removes that transport-level dependency:

```text
Load Balancer
     |
  +--+--+--+
  |  |  |  |
 MCP MCP MCP
  |  |  |  |
Tool/provider integrations
```

Any replica can process any MCP request.



## Production profile: fail-closed security and shared governance

Run with `SPRING_PROFILES_ACTIVE=prod` in production. This profile defaults to:

- MCP API-key authentication enabled.
- A non-empty `MCP_API_KEY` required at startup.
- PostgreSQL-backed distributed governance state required at startup.
- Distributed rate limiting enabled, so replicas share the same request counters.
- The existing Flyway migrations create the shared governance tables.

Configure `MCP_DB_URL`, `MCP_DB_USERNAME`, `MCP_DB_PASSWORD`, and a strong `MCP_API_KEY` through your secret manager. Do not commit secrets to source control.

For local development, keep the default profile; it does not require production-only API-key configuration. If the production profile cannot initialize the shared state store or API-key protection, startup fails rather than silently falling back to per-instance state or running the MCP endpoint without authentication.
