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
from mcp.server.fastmcp import FastMCP, Image
from mcp.types import Icon
from mcp.server.fastmcp import Context
from mcp.server.auth.middleware.auth_context import get_access_token

from auth_provider import DeviceAgentAuthSettings, SingleUserOAuthProvider
from device_registry import DeviceRegistry
from command_bus import CommandBus
from rate_limiter import RateLimiter

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("device-agent")

# Dernier client MCP connu ayant agi sur chaque appareil (affiche cote app,
# purement informatif, jamais utilise pour une decision de securite).
last_client_names: dict[str, str] = {}

SERVER_URL = os.environ.get("DEVICE_AGENT_SERVER_URL", "http://localhost:8000")
HOST = os.environ.get("DEVICE_AGENT_HOST", "0.0.0.0")
PORT = int(os.environ.get("DEVICE_AGENT_PORT", "8000"))

auth_settings = DeviceAgentAuthSettings()

device_registry = DeviceRegistry()
command_bus = CommandBus()
rate_limiter = RateLimiter(max_attempts=5, window_seconds=900)

oauth_provider = SingleUserOAuthProvider(
    settings=auth_settings,
    auth_callback_url=f"{SERVER_URL}/login",
    server_url=SERVER_URL,
)

mcp = FastMCP(
    name="MCP Phone Use",
    instructions=(
        "Relais de controle a distance d\'un telephone Android — perception "
        "(get_ui_tree, get_screen) et action (device_action). Outils "
        "indisponibles tant qu\'aucun appareil n\'est connecte et arme."
    ),
    icons=[Icon(src=f"{SERVER_URL.rstrip('/')}/icon.svg", mimeType="image/svg+xml", sizes=["any"])],
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
    from starlette.responses import PlainTextResponse

    ip = request.client.host if request.client else "unknown"
    if rate_limiter.is_blocked(ip):
        retry_after = rate_limiter.retry_after_seconds(ip)
        return PlainTextResponse(
            f"Trop de tentatives. Reessaie dans {retry_after}s.",
            status_code=429,
            headers={"Retry-After": str(retry_after)},
        )
    try:
        result = await oauth_provider.handle_login_callback(request)
    except Exception:
        rate_limiter.record_failure(ip)
        raise
    rate_limiter.record_success(ip)
    return result


@mcp.custom_route("/icon.svg", methods=["GET"])
async def serve_icon(request: Request) -> Response:
    from starlette.responses import FileResponse
    return FileResponse("static_icon.svg", media_type="image/svg+xml")


@mcp.custom_route("/favicon.ico", methods=["GET"])
async def serve_favicon(request: Request) -> Response:
    # Tentative complementaire au champ icons (SEP-973, non rendu par les
    # clients Claude actuels) : certains clients tombent en repli sur un
    # favicon classique, hors protocole MCP. Sert le meme visuel, meme si
    # des retours d'autres utilisateurs suggerent que ca ne change rien non
    # plus cote Claude -- teste ici plutot que suppose.
    from starlette.responses import FileResponse
    return FileResponse("static_icon.svg", media_type="image/svg+xml")


@mcp.custom_route("/healthz", methods=["GET"])
async def healthz(request: Request) -> Response:
    from starlette.responses import JSONResponse

    return JSONResponse({"status": "ok", "service": "device-agent"})



# --- Routes appareil (enrolement + challenge-response) -------------------
# Ne transitent jamais via MCP/OAuth : c'est le canal de l\'app Android, pas
# celui du client MCP. Voir docs/architecture.md pour la separation des deux
# mecanismes d\'authentification.

@mcp.custom_route("/device/enroll", methods=["POST"])
async def device_enroll(request: Request) -> Response:
    from starlette.responses import JSONResponse

    ip = request.client.host if request.client else "unknown"
    if rate_limiter.is_blocked(ip):
        retry_after = rate_limiter.retry_after_seconds(ip)
        return JSONResponse(
            {"error": f"trop de tentatives, reessaie dans {retry_after}s"},
            status_code=429,
            headers={"Retry-After": str(retry_after)},
        )
    body = await request.json()
    code = body.get("enrollment_code")
    device_id = body.get("device_id")
    public_key = body.get("public_key_der_b64")
    if not (code and device_id and public_key):
        return JSONResponse({"error": "champs manquants"}, status_code=400)
    ok = device_registry.complete_enrollment(code, device_id, public_key)
    if not ok:
        rate_limiter.record_failure(ip)
        return JSONResponse({"error": "code invalide, expire, ou cle publique invalide"}, status_code=400)
    rate_limiter.record_success(ip)
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




@mcp.custom_route("/device/commands/poll", methods=["POST"])
async def device_commands_poll(request: Request) -> Response:
    from starlette.responses import JSONResponse

    body = await request.json()
    device_id = body.get("device_id")
    if not device_id:
        return JSONResponse({"error": "device_id manquant"}, status_code=400)
    commands = command_bus.poll_and_clear(device_id)
    return JSONResponse({
        "commands": commands,
        "client_name": last_client_names.get(device_id),
    })


@mcp.custom_route("/device/commands/result", methods=["POST"])
async def device_commands_result(request: Request) -> Response:
    from starlette.responses import JSONResponse

    body = await request.json()
    command_id = body.get("command_id")
    result = body.get("result")
    if not command_id or result is None:
        return JSONResponse({"error": "command_id ou result manquant"}, status_code=400)
    ok = command_bus.submit_result(command_id, result)
    return JSONResponse({"ok": ok})



@mcp.custom_route("/downloads/{filename}", methods=["GET", "HEAD"])
async def download_file(request: Request) -> Response:
    from starlette.responses import FileResponse, PlainTextResponse
    import os

    filename = request.path_params["filename"]
    # anti path-traversal : nom de fichier simple uniquement
    if "/" in filename or ".." in filename:
        return PlainTextResponse("invalide", status_code=400)
    download_dir = "/home/onyxia/work/download"
    path = os.path.join(download_dir, filename)
    if not os.path.isfile(path):
        return PlainTextResponse("introuvable", status_code=404)
    # FileResponse (Starlette) gere nativement les requetes Range (telechargements
    # repris/partiels) — contrairement a python -m http.server, qui les ignore et
    # peut faire "planter" un telechargement mobile en cours de route.
    return FileResponse(path, media_type="application/vnd.android.package-archive", filename=filename)



@mcp.custom_route("/device/crash", methods=["POST"])
async def device_crash(request: Request) -> Response:
    from starlette.responses import JSONResponse

    body = await request.json()
    device_id = body.get("device_id", "inconnu")
    stack_trace = body.get("stack_trace", "")
    logger.error(f"=== CRASH REPORT ({device_id}) ===\n{stack_trace}\n=== FIN CRASH REPORT ===")
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
    session OAuth valide (quel que soit le client MCP), aucun outil de controle
    n\'agira si l\'app n\'est pas activement connectee cote telephone.
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




async def _require_connected_device(ctx: Context | None = None) -> tuple[str | None, dict | None]:
    """Verifie le double verrou avant toute commande de controle.

    Retourne (device_id, None) si un appareil est connecte, ou (None, erreur)
    sinon — l'erreur est le dict a renvoyer tel quel par l'outil appelant.
    """
    session = device_registry.active_session()
    if not session:
        return None, {
            "error": "Aucun appareil connecte. L'app doit etre ouverte et le bouton "
            "Connecter active cote telephone avant de pouvoir agir.",
        }
    if ctx is not None:
        access_token = get_access_token()
        if access_token:
            client_info = oauth_provider.clients.get(access_token.client_id)
            if client_info and client_info.client_name:
                last_client_names[session.device_id] = client_info.client_name
    return session.device_id, None


@mcp.tool()
async def get_ui_tree(ctx: Context) -> dict:
    """Recupere l'arbre d'accessibilite de l'ecran actif du telephone connecte.

    Retourne le texte, la position et les proprietes (cliquable, focusable) des
    elements visibles a l'ecran — utile pour savoir ce qui est affiche et
    interagissable, sans capture d'image. Echoue si aucun appareil n'est
    connecte (double verrou).
    """
    device_id, error = await _require_connected_device(ctx)
    if error:
        return error
    command_id = command_bus.queue_command(device_id, "dump_ui", {})
    result = await command_bus.wait_for_result(command_id, timeout=8.0)
    if result is None:
        return {"error": "Timeout — l'appareil n'a pas repondu a temps (verifier qu'il est bien connecte)."}
    return result


@mcp.tool()
async def device_action(ctx: Context, action: str, x: int | None = None, y: int | None = None,
                         x2: int | None = None, y2: int | None = None,
                         text: str | None = None, key: str | None = None,
                         package: str | None = None) -> dict:
    """Effectue une action sur le telephone connecte : tap, swipe, type_text, key, launch_app.

    - tap: fournir x, y
    - swipe: fournir x, y, x2, y2
    - type_text: fournir text (agit sur le champ actuellement focus)
    - key: fournir key parmi 'back', 'home', 'recents'
    - launch_app: fournir package (ex: 'com.android.settings')

    Echoue si aucun appareil n'est connecte (double verrou).
    """
    device_id, error = await _require_connected_device(ctx)
    if error:
        return error

    valid_actions = {"tap", "swipe", "type_text", "key", "launch_app"}
    if action not in valid_actions:
        return {"error": f"action invalide, attendu l'un de {sorted(valid_actions)}"}

    params = {"x": x, "y": y, "x2": x2, "y2": y2, "text": text, "key": key, "package": package}
    command_id = command_bus.queue_command(device_id, action, params)
    result = await command_bus.wait_for_result(command_id, timeout=8.0)
    if result is None:
        return {"error": "Timeout — l'appareil n'a pas repondu a temps."}
    return result




@mcp.tool()
async def get_screen(ctx: Context):
    """Capture une image reelle de l'ecran du telephone connecte.

    Complementaire a get_ui_tree : utile pour tout ce que le texte ne capture
    pas (couleurs exactes, rendu visuel, contenu WebView/canvas non expose a
    l'accessibilite). Echoue si aucun appareil n'est connecte (double verrou),
    ou si l'appareil ne supporte pas encore cette commande (MediaProjection
    pas encore implemente cote app — voir docs/architecture.md, backlog).
    """
    device_id, error = await _require_connected_device(ctx)
    if error:
        return error
    command_id = command_bus.queue_command(device_id, "capture_screen", {})
    result = await command_bus.wait_for_result(command_id, timeout=10.0)
    if result is None:
        return {"error": "Timeout — l'appareil n'a pas repondu a temps."}
    if not result.get("ok"):
        return result
    import base64

    image_bytes = base64.b64decode(result["jpeg_base64"])
    return Image(data=image_bytes, format="jpeg")


if __name__ == "__main__":
    logger.info(f"device-agent demarre sur {SERVER_URL} (host={HOST} port={PORT})")
    mcp.run(transport="streamable-http")
