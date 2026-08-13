# relais — device-agent (OAuth 2.1/PKCE + MCP Streamable HTTP)

## Lancer en local (dans le pod)

```bash
cp .env.example .env   # puis editer DEVICE_AGENT_PASSWORD
set -a; source .env; set +a
pip install -r requirements.txt
python server.py
```

## Tester

- `GET /healthz` -> `{"status": "ok"}`
- `GET /.well-known/oauth-authorization-server` -> metadonnees OAuth (auto, via FastMCP)
- Un client MCP sans token sur `/mcp` -> 401 avec `WWW-Authenticate: Bearer ...`
- Flux complet : voir `test_oauth_flow.py` (simule le parcours authorize -> login -> code -> token -> appel outil)
