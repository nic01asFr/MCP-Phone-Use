"""device-agent — relais MCP (Streamable HTTP) + Authorization Server OAuth 2.1/PKCE.

Un seul processus, un seul pod : ce serveur est a la fois Authorization Server
et Resource Server MCP (voir docs/architecture.md a la racine du repo pour les
decisions d\'architecture). Mono-utilisateur : un seul compte autorise.

Lancement local :
    DEVICE_AGENT_USERNAME=... DEVICE_AGENT_PASSWORD=... \\
    DEVICE_AGENT_SERVER_URL=http://localhost:8000 \\
    python server.py
"""

import logging
import os

from pydantic import AnyHttpUrl
from starlette.requests import Request
from starlette.responses import Response

from mcp.server.auth.settings import AuthSettings, ClientRegistrationOptions
from mcp.server.fastmcp import FastMCP

from auth_provider import DeviceAgentAuthSettings, SingleUserOAuthProvider

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("device-agent")

SERVER_URL = os.environ.get("DEVICE_AGENT_SERVER_URL", "http://localhost:8000")
HOST = os.environ.get("DEVICE_AGENT_HOST", "0.0.0.0")
PORT = int(os.environ.get("DEVICE_AGENT_PORT", "8000"))

auth_settings = DeviceAgentAuthSettings()

oauth_provider = SingleUserOAuthProvider(
    settings=auth_settings,
    auth_callback_url=f"{SERVER_URL}/login",
    server_url=SERVER_URL,
)

mcp = FastMCP(
    name="device-agent",
    instructions=(
        "Relais de controle a distance d\'un telephone Android (SURFAC2E / device-agent). "
        "Outils indisponibles tant qu\'aucun appareil n\'est connecte et arme."
    ),
    auth_server_provider=oauth_provider,
    auth=AuthSettings(
        issuer_url=AnyHttpUrl(SERVER_URL),
        client_registration_options=ClientRegistrationOptions(
            enabled=True,
            valid_scopes=[auth_settings.mcp_scope],
            default_scopes=[auth_settings.mcp_scope],
        ),
        required_scopes=[auth_settings.mcp_scope],
        resource_server_url=AnyHttpUrl(SERVER_URL),
    ),
    host=HOST,
    port=PORT,
)


@mcp.custom_route("/login", methods=["GET"])
async def login_page(request: Request) -> Response:
    state = request.query_params.get("state")
    if not state:
        from starlette.responses import PlainTextResponse

        return PlainTextResponse("Missing state parameter", status_code=400)
    return await oauth_provider.get_login_page(state)


@mcp.custom_route("/login/callback", methods=["POST"])
async def login_callback(request: Request) -> Response:
    return await oauth_provider.handle_login_callback(request)


@mcp.custom_route("/healthz", methods=["GET"])
async def healthz(request: Request) -> Response:
    from starlette.responses import JSONResponse

    return JSONResponse({"status": "ok", "service": "device-agent"})


# --- Outils --------------------------------------------------------------
# Placeholder de validation du pipeline OAuth. Les vrais outils
# (get_screen, get_ui_tree, device_action, ...) arrivent une fois l\'app
# Android connectee — voir le backlog dans docs/architecture.md.

@mcp.tool()
async def device_status() -> dict:
    """Etat de la connexion device-agent.

    Confirme que l\'authentification OAuth fonctionne. Tant qu\'aucune app
    Android n\'est enrolee et connectee, retourne connected=false — c\'est
    le comportement attendu (double verrou), pas une erreur.
    """
    return {
        "connected": False,
        "reason": "Aucun appareil enrole pour le moment (etape suivante du backlog).",
    }


if __name__ == "__main__":
    logger.info(f"device-agent demarre sur {SERVER_URL} (host={HOST} port={PORT})")
    mcp.run(transport="streamable-http")
