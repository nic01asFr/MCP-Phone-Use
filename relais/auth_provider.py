"""Authorization Server provider — device-agent, mono-utilisateur.

Adapte le pattern officiel du SDK MCP (examples/servers/simple-auth) a un cas
mono-utilisateur : un seul compte autorise (Nicolas), identifiants via variables
d'environnement, pas de credentials par defaut affiches dans le formulaire.

Ne PAS reutiliser tel quel pour un service multi-utilisateurs.
"""

import os
import secrets
import time
from typing import Any

from pydantic import AnyHttpUrl
from starlette.exceptions import HTTPException
from starlette.requests import Request
from starlette.responses import HTMLResponse, RedirectResponse, Response

from mcp.server.auth.provider import (
    AccessToken,
    AuthorizationCode,
    AuthorizationParams,
    OAuthAuthorizationServerProvider,
    RefreshToken,
    construct_redirect_uri,
)
from mcp.shared.auth import OAuthClientInformationFull, OAuthToken

ACCESS_TOKEN_TTL_SECONDS = 3600  # 1h — session de dev active, pas d'acces permanent
REFRESH_TOKEN_TTL_SECONDS = 60 * 60 * 24 * 30  # 30 jours, avec rotation a chaque usage
AUTH_CODE_TTL_SECONDS = 300


class DeviceAgentAuthSettings:
    """Identifiants et scope, charges depuis l'environnement (jamais en dur)."""

    def __init__(self) -> None:
        username = os.environ.get("DEVICE_AGENT_USERNAME")
        password = os.environ.get("DEVICE_AGENT_PASSWORD")
        if not username or not password:
            raise RuntimeError(
                "DEVICE_AGENT_USERNAME et DEVICE_AGENT_PASSWORD doivent etre definies "
                "(voir .env.example) — aucun identifiant par defaut n'est fourni."
            )
        self.username = username
        self.password = password
        self.mcp_scope = "device-control"


class SingleUserOAuthProvider(OAuthAuthorizationServerProvider[AuthorizationCode, RefreshToken, AccessToken]):
    """Authorization Server mono-utilisateur pour device-agent.

    Un seul compte autorise. Authorization Code + PKCE (RFC 7636), audience
    binding RFC 8707 (`resource`). Pas de refresh token dans cette version —
    une session expiree se re-authentifie (acceptable pour un usage dev ponctuel).
    """

    def __init__(self, settings: DeviceAgentAuthSettings, auth_callback_url: str, server_url: str):
        self.settings = settings
        self.auth_callback_url = auth_callback_url
        self.server_url = server_url
        self.clients: dict[str, OAuthClientInformationFull] = {}
        self._registration_timestamps: list[float] = []  # limite globale, protege contre le remplissage memoire via /register (non authentifie par design DCR)
        self.auth_codes: dict[str, AuthorizationCode] = {}
        self.tokens: dict[str, AccessToken] = {}
        self.refresh_tokens: dict[str, RefreshToken] = {}
        self.state_mapping: dict[str, dict[str, Any]] = {}

    async def get_client(self, client_id: str) -> OAuthClientInformationFull | None:
        return self.clients.get(client_id)

    async def register_client(self, client_info: OAuthClientInformationFull) -> None:
        if not client_info.client_id:
            raise ValueError("No client_id provided")
        # /register est ouvert sans authentification par design (DCR standard) --
        # limite globale simple pour empecher un remplissage memoire illimite,
        # generreuse pour un usage legitime multi-clients.
        now = time.time()
        self._registration_timestamps = [t for t in self._registration_timestamps if now - t < 60]
        if len(self._registration_timestamps) >= 30:
            from mcp.server.auth.provider import RegistrationError
            raise RegistrationError(
                error="invalid_client_metadata",
                error_description="Trop d'enregistrements de client recents, reessaie plus tard.",
            )
        self._registration_timestamps.append(now)
        self.clients[client_info.client_id] = client_info

    async def authorize(self, client: OAuthClientInformationFull, params: AuthorizationParams) -> str:
        state = params.state or secrets.token_hex(16)
        self.state_mapping[state] = {
            "redirect_uri": str(params.redirect_uri),
            "code_challenge": params.code_challenge,
            "redirect_uri_provided_explicitly": str(params.redirect_uri_provided_explicitly),
            "client_id": client.client_id,
            "resource": params.resource,  # RFC 8707
        }
        return f"{self.auth_callback_url}?state={state}&client_id={client.client_id}"

    async def get_login_page(self, state: str) -> HTMLResponse:
        if not state:
            raise HTTPException(400, "Missing state parameter")
        html = f"""
        <!DOCTYPE html>
        <html>
        <head>
            <title>MCP Phone Use — connexion</title>
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <link rel="icon" type="image/svg+xml" href="{self.server_url.rstrip('/')}/icon.svg">
            <style>
                * {{ box-sizing: border-box; }}
                body {{
                    font-family: -apple-system, "Segoe UI", Roboto, sans-serif;
                    background: #1C1B1F;
                    color: #E6E1E5;
                    max-width: 380px;
                    margin: 0 auto;
                    padding: 48px 24px;
                }}
                .logo {{
                    width: 64px; height: 64px;
                    border-radius: 50%;
                    background: #4C6EF5;
                    display: flex; align-items: center; justify-content: center;
                    margin: 0 auto 20px;
                }}
                .logo svg {{ width: 28px; height: 28px; }}
                h2 {{ text-align: center; margin: 0 0 4px; font-size: 20px; }}
                .subtitle {{ text-align: center; color: #938F99; font-size: 13px; margin-bottom: 24px; }}
                .hint {{ font-size: 12px; color: #6b6870; margin-bottom: 20px; text-align: center; line-height: 1.5; }}
                .hint code {{ color: #7B9EFF; font-family: monospace; }}
                .form-group {{ margin-bottom: 14px; }}
                label {{ font-size: 12px; color: #938F99; display: block; margin-bottom: 4px; }}
                input {{
                    width: 100%; padding: 12px; box-sizing: border-box;
                    background: transparent; border: 1.5px solid #49454F; border-radius: 8px;
                    color: #E6E1E5; font-size: 15px;
                }}
                input:focus {{ outline: none; border-color: #4C6EF5; }}
                button {{
                    background: #4C6EF5; color: #fff; padding: 13px 16px; border: none;
                    border-radius: 24px; cursor: pointer; width: 100%; font-size: 14px;
                    font-weight: 600; margin-top: 8px;
                }}
            </style>
        </head>
        <body>
            <div class="logo">
                <svg viewBox="0 0 24 24" fill="#fff" fill-rule="evenodd" xmlns="http://www.w3.org/2000/svg">
                    <path d="M15.688 2.343a2.588 2.588 0 0 0 -3.61 0l-9.626 9.44a.863 .863 0 0 1 -1.203 0 .823 .823 0 0 1 0 -1.18l9.626 -9.44a4.313 4.313 0 0 1 6.016 0 4.116 4.116 0 0 1 1.204 3.54 4.3 4.3 0 0 1 3.609 1.18l.05 .05a4.115 4.115 0 0 1 0 5.9l-8.706 8.537a.274 .274 0 0 0 0 .393l1.788 1.754a.823 .823 0 0 1 0 1.18 .863 .863 0 0 1 -1.203 0l-1.788 -1.753a1.92 1.92 0 0 1 0 -2.754l8.706 -8.538a2.47 2.47 0 0 0 0 -3.54l-.05 -.049a2.588 2.588 0 0 0 -3.607 -.003l-7.172 7.034 -.002 .002 -.098 .097a.863 .863 0 0 1 -1.204 0 .823 .823 0 0 1 0 -1.18l7.273 -7.133a2.47 2.47 0 0 0 -.003 -3.537z"/>
                    <path d="M14.485 4.703a.823 .823 0 0 0 0 -1.18 .863 .863 0 0 0 -1.204 0l-7.119 6.982a4.115 4.115 0 0 0 0 5.9 4.314 4.314 0 0 0 6.016 0l7.12 -6.982a.823 .823 0 0 0 0 -1.18 .863 .863 0 0 0 -1.204 0l-7.119 6.982a2.588 2.588 0 0 1 -3.61 0 2.47 2.47 0 0 1 0 -3.54l7.12 -6.982z"/>
                </svg>
            </div>
            <h2>MCP Phone Use</h2>
            <p class="subtitle">Connexion au relais — un seul compte autorise</p>
            <p class="hint">Identifiant et mot de passe = <code>DEVICE_AGENT_USERNAME</code> / <code>DEVICE_AGENT_PASSWORD</code><br>definis dans le <code>.env</code> de ce relais.</p>
            <form action="{self.server_url.rstrip('/')}/login/callback" method="post">
                <input type="hidden" name="state" value="{state}">
                <div class="form-group">
                    <label>Identifiant</label>
                    <input type="text" name="username" required autofocus>
                </div>
                <div class="form-group">
                    <label>Mot de passe</label>
                    <input type="password" name="password" required>
                </div>
                <button type="submit">Se connecter</button>
            </form>
        </body>
        </html>
        """
        return HTMLResponse(
            content=html,
            headers={"Strict-Transport-Security": "max-age=31536000; includeSubDomains"},
        )

    async def handle_login_callback(self, request: Request) -> Response:
        form = await request.form()
        username = form.get("username")
        password = form.get("password")
        state = form.get("state")
        if not isinstance(username, str) or not isinstance(password, str) or not isinstance(state, str):
            raise HTTPException(400, "Missing or invalid form fields")
        redirect_uri = await self._handle_credentials(username, password, state)
        return RedirectResponse(url=redirect_uri, status_code=302)

    async def _handle_credentials(self, username: str, password: str, state: str) -> str:
        state_data = self.state_mapping.get(state)
        if not state_data:
            raise HTTPException(400, "Invalid state parameter")

        # Comparaison a temps constant pour eviter le timing attack sur le mot de passe
        if not (
            secrets.compare_digest(username, self.settings.username)
            and secrets.compare_digest(password, self.settings.password)
        ):
            raise HTTPException(401, "Invalid credentials")

        redirect_uri = state_data["redirect_uri"]
        code_challenge = state_data["code_challenge"]
        redirect_uri_provided_explicitly = state_data["redirect_uri_provided_explicitly"] == "True"
        client_id = state_data["client_id"]
        resource = state_data.get("resource")

        new_code = f"dvc_{secrets.token_hex(16)}"
        self.auth_codes[new_code] = AuthorizationCode(
            code=new_code,
            client_id=client_id,
            redirect_uri=AnyHttpUrl(redirect_uri),
            redirect_uri_provided_explicitly=redirect_uri_provided_explicitly,
            expires_at=time.time() + AUTH_CODE_TTL_SECONDS,
            scopes=[self.settings.mcp_scope],
            code_challenge=code_challenge,
            resource=resource,
            subject=username,
        )
        del self.state_mapping[state]
        return construct_redirect_uri(redirect_uri, code=new_code, state=state)

    async def load_authorization_code(
        self, client: OAuthClientInformationFull, authorization_code: str
    ) -> AuthorizationCode | None:
        return self.auth_codes.get(authorization_code)

    async def exchange_authorization_code(
        self, client: OAuthClientInformationFull, authorization_code: AuthorizationCode
    ) -> OAuthToken:
        if authorization_code.code not in self.auth_codes:
            raise ValueError("Invalid authorization code")
        if not client.client_id:
            raise ValueError("No client_id provided")

        token = f"dvc_{secrets.token_hex(32)}"
        self.tokens[token] = AccessToken(
            token=token,
            client_id=client.client_id,
            scopes=authorization_code.scopes,
            expires_at=int(time.time()) + ACCESS_TOKEN_TTL_SECONDS,
            resource=authorization_code.resource,
            subject=authorization_code.subject,
        )

        refresh_token = f"dvcr_{secrets.token_hex(32)}"
        self.refresh_tokens[refresh_token] = RefreshToken(
            token=refresh_token,
            client_id=client.client_id,
            scopes=authorization_code.scopes,
            expires_at=int(time.time()) + REFRESH_TOKEN_TTL_SECONDS,
        )

        del self.auth_codes[authorization_code.code]
        return OAuthToken(
            access_token=token,
            token_type="Bearer",
            expires_in=ACCESS_TOKEN_TTL_SECONDS,
            scope=" ".join(authorization_code.scopes),
            refresh_token=refresh_token,
        )

    async def load_access_token(self, token: str) -> AccessToken | None:
        access_token = self.tokens.get(token)
        if not access_token:
            return None
        if access_token.expires_at and access_token.expires_at < time.time():
            del self.tokens[token]
            return None
        return access_token

    async def load_refresh_token(self, client: OAuthClientInformationFull, refresh_token: str) -> RefreshToken | None:
        token = self.refresh_tokens.get(refresh_token)
        if not token:
            return None
        if token.expires_at and token.expires_at < time.time():
            del self.refresh_tokens[refresh_token]
            return None
        return token

    async def exchange_refresh_token(
        self, client: OAuthClientInformationFull, refresh_token: RefreshToken, scopes: list[str]
    ) -> OAuthToken:
        """Echange un refresh token contre un nouvel access token, avec rotation.

        Rotation : l'ancien refresh token est invalide des l'usage, un nouveau est
        emis. Detecte un refresh token vole/rejoue (le voleur et le legitime ne
        peuvent pas tous les deux reussir un refresh avec le meme token).
        """
        if refresh_token.token not in self.refresh_tokens:
            raise ValueError("Invalid or already-used refresh token")

        effective_scopes = scopes or refresh_token.scopes

        new_access = f"dvc_{secrets.token_hex(32)}"
        self.tokens[new_access] = AccessToken(
            token=new_access,
            client_id=refresh_token.client_id,
            scopes=effective_scopes,
            expires_at=int(time.time()) + ACCESS_TOKEN_TTL_SECONDS,
            resource=None,
            subject=None,
        )

        new_refresh = f"dvcr_{secrets.token_hex(32)}"
        self.refresh_tokens[new_refresh] = RefreshToken(
            token=new_refresh,
            client_id=refresh_token.client_id,
            scopes=effective_scopes,
            expires_at=int(time.time()) + REFRESH_TOKEN_TTL_SECONDS,
        )
        del self.refresh_tokens[refresh_token.token]

        return OAuthToken(
            access_token=new_access,
            token_type="Bearer",
            expires_in=ACCESS_TOKEN_TTL_SECONDS,
            scope=" ".join(effective_scopes),
            refresh_token=new_refresh,
        )

    async def revoke_token(self, token: str, token_type_hint: str | None = None) -> None:  # type: ignore[override]
        self.tokens.pop(token, None)
