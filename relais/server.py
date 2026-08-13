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
import time

from pydantic import AnyHttpUrl
from starlette.requests import Request
from starlette.responses import Response

from mcp.server.auth.settings import AuthSettings, ClientRegistrationOptions
from mcp.server.fastmcp import FastMCP

from auth_provider import DeviceAgentAuthSettings, SingleUserOAuthProvider
from device_registry import DeviceRegistry

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("device-agent")

SERVER_URL = os.environ.get("DEVICE_AGENT_SERVER_URL", "http://localhost:8000")
HOST = os.environ.get("DEVICE_AGENT_HOST", "0.0.0.0")
PORT = int(os.environ.get("DEVICE_AGENT_PORT", "8000"))

auth_settings = DeviceAgentAuthSettings()

device_registry = DeviceRegistry()

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



# --- Routes appareil (enrolement + challenge-response) -------------------
# Ne transitent jamais via MCP/OAuth : c'est le canal de l\'app Android, pas
# celui de Claude. Voir docs/architecture.md pour la separation des deux
# mecanismes d\'authentification.

@mcp.custom_route("/device/enroll", methods=["POST"])
async def device_enroll(request: Request) -> Response:
    from starlette.responses import JSONResponse

    body = await request.json()
    code = body.get("enrollment_code")
    device_id = body.get("device_id")
    public_key = body.get("public_key_der_b64")
    if not (code and device_id and public_key):
        return JSONResponse({"error": "champs manquants"}, status_code=400)
    ok = device_registry.complete_enrollment(code, device_id, public_key)
    if not ok:
        return JSONResponse({"error": "code invalide, expire, ou cle publique invalide"}, status_code=400)
    return JSONResponse({"ok": True})


@mcp.custom_route("/device/challenge", methods=["POST"])
async def device_challenge(request: Request) -> Response:
    from starlette.responses import JSONResponse

    body = await request.json()
    device_id = body.get("device_id")
    nonce = device_registry.create_challenge(device_id) if device_id else None
    if not nonce:
        return JSONResponse({"error": "appareil inconnu"}, status_code=404)
    return JSONResponse({"nonce": nonce, "expires_in": 60})


@mcp.custom_route("/device/session", methods=["POST"])
async def device_session(request: Request) -> Response:
    from starlette.responses import JSONResponse

    body = await request.json()
    device_id = body.get("device_id")
    nonce = body.get("nonce")
    signature = body.get("signature_b64")
    if not (device_id and nonce and signature):
        return JSONResponse({"error": "champs manquants"}, status_code=400)
    token = device_registry.verify_and_create_session(device_id, nonce, signature)
    if not token:
        return JSONResponse({"error": "signature invalide ou challenge expire"}, status_code=401)
    return JSONResponse({"session_token": token, "expires_in": 1800})


@mcp.custom_route("/device/disconnect", methods=["POST"])
async def device_disconnect(request: Request) -> Response:
    from starlette.responses import JSONResponse

    body = await request.json()
    token = body.get("session_token")
    if token:
        device_registry.disconnect(token)
    return JSONResponse({"ok": True})


# --- Outils --------------------------------------------------------------
# Placeholder de validation du pipeline OAuth. Les vrais outils
# (get_screen, get_ui_tree, device_action, ...) arrivent une fois l\'app
# Android connectee — voir le backlog dans docs/architecture.md.

@mcp.tool()
async def device_status() -> dict:
    """Etat de la connexion device-agent.

    connected=true seulement si un appareil a complete le challenge-response
    recemment (session non expiree). C\'est le double verrou : meme avec une
    session Claude valide, aucun outil de controle n\'agira si l\'app n\'est
    pas activement connectee cote telephone.
    """
    session = device_registry.active_session()
    if not session:
        return {
            "connected": False,
            "reason": "Aucun appareil enrole et actif pour le moment.",
        }
    return {
        "connected": True,
        "device_id": session.device_id,
        "expires_in": max(0, int(session.expires_at - time.time())),
    }




@mcp.tool()
async def generate_enrollment_code() -> dict:
    """Genere un code d\'enrolement a usage unique pour appairer l\'app Android.

    Le code expire dans 10 minutes. A saisir dans l\'app device-agent lors du
    premier lancement pour qu\'elle enregistre sa cle publique aupres du relais.
    """
    code = device_registry.generate_enrollment_code()
    return {"enrollment_code": code, "expires_in": 600}


if __name__ == "__main__":
    logger.info(f"device-agent demarre sur {SERVER_URL} (host={HOST} port={PORT})")
    mcp.run(transport="streamable-http")
