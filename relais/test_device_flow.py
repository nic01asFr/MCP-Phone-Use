
import base64, hashlib, json, secrets
import httpx
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

BASE = "https://user-nic01asfr-device-agent.user.lab.sspcloud.fr"
PASSWORD = open("/home/onyxia/work/projects/device-agent/relais/.env").read().split("DEVICE_AGENT_PASSWORD=")[1].split("\n")[0]

def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()

# --- 1. Obtenir un access token OAuth (comme Claude le ferait) -----------
r = httpx.post(f"{BASE}/register", json={
    "redirect_uris": ["http://127.0.0.1:9999/callback"], "client_name": "sim-device-test",
    "grant_types": ["authorization_code", "refresh_token"], "response_types": ["code"],
    "token_endpoint_auth_method": "none",
})
client_id = r.json()["client_id"]
verifier = b64url(secrets.token_bytes(32))
challenge = b64url(hashlib.sha256(verifier.encode()).digest())
r = httpx.get(f"{BASE}/authorize", params={
    "response_type": "code", "client_id": client_id, "redirect_uri": "http://127.0.0.1:9999/callback",
    "state": "s1", "code_challenge": challenge, "code_challenge_method": "S256", "resource": BASE + "/",
}, follow_redirects=False)
state = r.headers["location"].split("state=")[1].split("&")[0]
r = httpx.post(f"{BASE}/login/callback", data={"username": "nicolas", "password": PASSWORD, "state": state}, follow_redirects=False)
code = r.headers["location"].split("code=")[1].split("&")[0]
r = httpx.post(f"{BASE}/token", data={"grant_type": "authorization_code", "code": code,
    "redirect_uri": "http://127.0.0.1:9999/callback", "client_id": client_id, "code_verifier": verifier})
access_token = r.json()["access_token"]
print("1. token OAuth obtenu")

_session_id = {"value": None}

def mcp_call(method, params=None, id_=1):
    headers = {
        "Authorization": f"Bearer {access_token}", "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
    }
    if _session_id["value"]:
        headers["Mcp-Session-Id"] = _session_id["value"]
    r = httpx.post(f"{BASE}/mcp", headers=headers,
        json={"jsonrpc": "2.0", "id": id_, "method": method, "params": params or {}}, timeout=10)
    assert r.status_code == 200, (r.status_code, r.text[:300])
    if "mcp-session-id" in r.headers:
        _session_id["value"] = r.headers["mcp-session-id"]
    for line in r.text.splitlines():
        if line.startswith("data: "):
            return json.loads(line[6:])
    raise RuntimeError("pas de data: dans la reponse SSE: " + r.text[:300])

def tool_result(resp):
    return json.loads(resp["result"]["content"][0]["text"])

init = mcp_call("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "sim", "version": "0"}})
print("2. initialize OK")

httpx.post(f"{BASE}/mcp", headers={
    "Authorization": f"Bearer {access_token}", "Content-Type": "application/json",
    "Accept": "application/json, text/event-stream", "Mcp-Session-Id": _session_id["value"],
}, json={"jsonrpc": "2.0", "method": "notifications/initialized"}, timeout=10)

status0 = tool_result(mcp_call("tools/call", {"name": "device_status", "arguments": {}}, id_=2))
print("3. device_status avant enrolement:", status0)
assert status0["connected"] is False

enroll_resp = tool_result(mcp_call("tools/call", {"name": "generate_enrollment_code", "arguments": {}}, id_=3))
enrollment_code = enroll_resp["enrollment_code"]
print("4. code d'enrolement genere via outil MCP:", enrollment_code)

# --- 2. Simuler l'appareil Android ---------------------------------------
device_id = "sim-" + secrets.token_hex(4)
private_key = ec.generate_private_key(ec.SECP256R1())
public_key_der = private_key.public_key().public_bytes(
    encoding=serialization.Encoding.DER, format=serialization.PublicFormat.SubjectPublicKeyInfo
)
public_key_b64 = base64.b64encode(public_key_der).decode()

r = httpx.post(f"{BASE}/device/enroll", json={
    "enrollment_code": enrollment_code, "device_id": device_id, "public_key_der_b64": public_key_b64,
})
assert r.status_code == 200, (r.status_code, r.text)
print("5. appareil enrole:", device_id)

r = httpx.post(f"{BASE}/device/challenge", json={"device_id": device_id})
assert r.status_code == 200, (r.status_code, r.text)
nonce = r.json()["nonce"]
print("6. nonce recu")

signature = private_key.sign(nonce.encode(), ec.ECDSA(hashes.SHA256()))
signature_b64 = base64.b64encode(signature).decode()
r = httpx.post(f"{BASE}/device/session", json={"device_id": device_id, "nonce": nonce, "signature_b64": signature_b64})
assert r.status_code == 200, (r.status_code, r.text)
session_token = r.json()["session_token"]
print("7. challenge signe, session appareil ouverte")

# --- 3. device_status doit maintenant refleter connected=true -----------
sc = tool_result(mcp_call("tools/call", {"name": "device_status", "arguments": {}}, id_=4))
print("8. device_status apres connexion:", sc)
assert sc["connected"] is True and sc["device_id"] == device_id

# --- 4. Signature invalide (mauvaise cle) doit etre rejetee --------------
other_key = ec.generate_private_key(ec.SECP256R1())
r = httpx.post(f"{BASE}/device/challenge", json={"device_id": device_id})
nonce2 = r.json()["nonce"]
bad_sig = base64.b64encode(other_key.sign(nonce2.encode(), ec.ECDSA(hashes.SHA256()))).decode()
r = httpx.post(f"{BASE}/device/session", json={"device_id": device_id, "nonce": nonce2, "signature_b64": bad_sig})
assert r.status_code == 401, (r.status_code, r.text)
print("9. signature d'une mauvaise cle rejetee (401) -- OK")

# --- 5. Deconnexion --------------------------------------------------------
r = httpx.post(f"{BASE}/device/disconnect", json={"session_token": session_token})
assert r.status_code == 200
status2 = tool_result(mcp_call("tools/call", {"name": "device_status", "arguments": {}}, id_=5))
print("10. device_status apres deconnexion:", status2)
assert status2["connected"] is False

print("\nTOUS LES TESTS DEVICE-REGISTRY PASSENT")
