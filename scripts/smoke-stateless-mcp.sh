#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${MCP_SERVER_URL:-http://localhost:8090}"
ENDPOINT="${MCP_SERVER_ENDPOINT:-/mcp}"

headers_file="$(mktemp)"
body_file="$(mktemp)"
trap 'rm -f "$headers_file" "$body_file"' EXIT

status="$(curl -sS -o "$body_file" -D "$headers_file" -w '%{http_code}' \
  -X POST "${BASE_URL}${ENDPOINT}" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}')"

if [[ "$status" != "200" ]]; then
  echo "Expected HTTP 200 from stateless MCP tools/list, got $status"
  cat "$body_file"
  exit 1
fi

if grep -qi '^Mcp-Session-Id:' "$headers_file"; then
  echo "Unexpected Mcp-Session-Id response header: server is behaving statefully"
  cat "$headers_file"
  exit 1
fi

echo "Stateless MCP smoke test passed: HTTP 200 and no Mcp-Session-Id header."
