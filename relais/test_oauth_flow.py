
import base64, hashlib, secrets, sys
import httpx

BASE = "http://localhost:8010"

def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()

# 1. Dynamic client registration (RFC7591, auto-active via FastMCP)
r = httpx.post(f"{BASE}/register", json={
    "redirect_uris": ["http://127.0.0.1:9999/callback"],
    "client_name": "test-client",
    "grant_types": ["authorization_code", "refresh_token"],
    "response_types": ["code"],
    "token_endpoint_auth_method": "none",
})
assert r.status_code in (200, 201), (r.status_code, r.text)
client = r.json()
client_id = client["client_id"]
print("1. client enregistre:", client_id)

# 2. PKCE
verifier = b64url(secrets.token_bytes(32))
challenge = b64url(hashlib.sha256(verifier.encode()).digest())

# 3. /authorize -> doit rediriger vers /login (302, sans suivre)
authorize_params = {
    "response_type": "code",
    "client_id": client_id,
    "redirect_uri": "http://127.0.0.1:9999/callback",
    "state": "xyz123",
    "code_challenge": challenge,
    "code_challenge_method": "S256",
    "resource": BASE + "/",
}
r = httpx.get(f"{BASE}/authorize", params=authorize_params, follow_redirects=False)
assert r.status_code in (302, 307), (r.status_code, r.text)
login_url = r.headers["location"]
print("2. /authorize -> redirige vers login:", login_url[:60], "...")

# 4. GET /login (page HTML servie)
r = httpx.get(login_url)
assert r.status_code == 200 and "form" in r.text.lower(), r.status_code
state = login_url.split("state=")[1].split("&")[0]
print("3. page de login recue, state=", state[:12], "...")

# 5. POST credentials (mauvais mot de passe -> doit echouer)
r = httpx.post(f"{BASE}/login/callback", data={
    "username": "nicolas", "password": "WRONG", "state": state,
}, follow_redirects=False)
assert r.status_code == 401, ("mauvais mdp aurait du etre rejete", r.status_code)
print("4. mauvais mot de passe rejete (401) -- OK")

# 6. POST credentials correctes
r = httpx.post(f"{BASE}/login/callback", data={
    "username": "nicolas", "password": "test-passphrase-CHANGE-ME-123", "state": state,
}, follow_redirects=False)
assert r.status_code in (302, 307), (r.status_code, r.text)
redirect = r.headers["location"]
code = redirect.split("code=")[1].split("&")[0]
print("5. login OK, code d'autorisation obtenu:", code[:12], "...")

# 7. Exchange code -> token, avec PKCE verifier
r = httpx.post(f"{BASE}/token", data={
    "grant_type": "authorization_code",
    "code": code,
    "redirect_uri": "http://127.0.0.1:9999/callback",
    "client_id": client_id,
    "code_verifier": verifier,
})
assert r.status_code == 200, (r.status_code, r.text)
token = r.json()["access_token"]
print("6. token obtenu:", token[:12], "...")

# 8. Appel MCP authentifie (initialize)
r = httpx.post(f"{BASE}/mcp", headers={
    "Authorization": f"Bearer {token}",
    "Content-Type": "application/json",
    "Accept": "application/json, text/event-stream",
}, json={
    "jsonrpc": "2.0", "id": 1, "method": "initialize",
    "params": {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "test", "version": "0"}},
})
print("7. appel MCP authentifie -> HTTP", r.status_code)
assert r.status_code == 200, (r.status_code, r.text[:300])
print("   reponse:", r.text[:200])

# 9. Verifie qu'un mauvais token est bien rejete
r = httpx.post(f"{BASE}/mcp", headers={"Authorization": "Bearer faux-token-invalide"}, json={"jsonrpc":"2.0","id":2,"method":"initialize","params":{}})
assert r.status_code == 401, r.status_code
print("8. token invalide rejete (401) -- OK")

print("\\nTOUS LES TESTS PASSENT")
