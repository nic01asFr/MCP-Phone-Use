# relay — device-agent (OAuth 2.1/PKCE + MCP Streamable HTTP)

## Requirements for a real deployment

- Python 3.11+
- A **public HTTPS URL** pointing to this process — required: OAuth demands HTTPS, and the phone needs to reach the relay from any network (4G included). In practice: a reverse proxy (Caddy, nginx, Traefik...) in front of the Python process, with a valid certificate (Let's Encrypt or equivalent).
- The process needs to stay **continuously alive** — this isn't a script you run occasionally. Plan for a systemd-managed service, a container with a restart policy, or your platform's equivalent.
- **No persistence currently**: the device registry and sessions live in memory — restarting the process wipes everything, requiring re-enrollment. Known backlog item, see `docs/architecture.md`.

## Running locally (dev / test)

```bash
cp .env.example .env   # then edit DEVICE_AGENT_PASSWORD
set -a; source .env; set +a
pip install -r requirements.txt
python server.py
```

This starts the server on plain HTTP on the configured port — enough for development, **not enough as-is for real use** (no HTTPS, no automatic restart). See "Requirements" above to move to a real deployment.

## Testing

- `GET /healthz` -> `{"status": "ok"}`
- `GET /.well-known/oauth-authorization-server` -> OAuth metadata (automatic, via FastMCP)
- An MCP client with no token on `/mcp` -> 401 with `WWW-Authenticate: Bearer ...`
- Full flow: see `test_oauth_flow.py` (simulates the authorize -> login -> code -> token -> tool call journey)
