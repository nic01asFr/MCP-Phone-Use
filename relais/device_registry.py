"""Registre des appareils — enrolement par code a usage unique, puis
authentification par challenge-response (paire de cles EC P-256).

Aucun secret partage n'est jamais transmis apres l'enrolement : seule la
cle PUBLIQUE de l'appareil est connue du serveur. Chaque connexion prouve
la possession de la cle privee en signant un nonce a usage unique.
"""

import base64
import secrets
import time
from dataclasses import dataclass, field

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, padding
from cryptography.hazmat.primitives.asymmetric.utils import Prehashed

ENROLLMENT_CODE_TTL_SECONDS = 600       # 10 min pour coller le code dans l'app
CHALLENGE_TTL_SECONDS = 60              # 1 min pour signer le nonce
DEVICE_SESSION_TTL_SECONDS = 60 * 30    # 30 min, renouvelable par un nouveau challenge


@dataclass
class DeviceSession:
    device_id: str
    expires_at: float


class DeviceRegistry:
    def __init__(self) -> None:
        self.enrollment_codes: dict[str, float] = {}          # code -> expires_at
        self.devices: dict[str, bytes] = {}                    # device_id -> public key (DER)
        self.challenges: dict[str, tuple[str, float]] = {}     # nonce -> (device_id, expires_at)
        self.sessions: dict[str, DeviceSession] = {}            # session_token -> DeviceSession

    # --- Enrolement (bootstrap via appel MCP authentifie) -----------------
    def generate_enrollment_code(self) -> str:
        # Nettoyage paresseux des codes expires jamais utilises -- sinon ils
        # s'accumulent indefiniment (seul un usage reussi les supprimait avant).
        now = time.time()
        expired = [c for c, exp in self.enrollment_codes.items() if exp <= now]
        for c in expired:
            del self.enrollment_codes[c]

        code = secrets.token_hex(4)  # 8 caracteres hex, facile a retaper
        self.enrollment_codes[code] = now + ENROLLMENT_CODE_TTL_SECONDS
        return code

    def complete_enrollment(self, enrollment_code: str, device_id: str, public_key_der_b64: str) -> bool:
        expires_at = self.enrollment_codes.get(enrollment_code)
        if not expires_at or expires_at < time.time():
            return False
        try:
            public_key_der = base64.b64decode(public_key_der_b64)
            # Valide que c'est bien une cle EC exploitable avant de la stocker
            key = serialization.load_der_public_key(public_key_der)
            if not isinstance(key, ec.EllipticCurvePublicKey):
                return False
        except Exception:
            return False
        self.devices[device_id] = public_key_der
        del self.enrollment_codes[enrollment_code]  # usage unique
        return True

    # --- Challenge-response -------------------------------------------------
    def create_challenge(self, device_id: str) -> str | None:
        if device_id not in self.devices:
            return None
        nonce = secrets.token_urlsafe(24)
        self.challenges[nonce] = (device_id, time.time() + CHALLENGE_TTL_SECONDS)
        return nonce

    def verify_and_create_session(self, device_id: str, nonce: str, signature_b64: str) -> str | None:
        entry = self.challenges.get(nonce)
        if not entry:
            return None
        expected_device_id, expires_at = entry
        del self.challenges[nonce]  # usage unique, meme si la verif echoue ensuite
        if expected_device_id != device_id or expires_at < time.time():
            return None

        public_key_der = self.devices.get(device_id)
        if not public_key_der:
            return None

        try:
            public_key = serialization.load_der_public_key(public_key_der)
            signature = base64.b64decode(signature_b64)
            public_key.verify(signature, nonce.encode(), ec.ECDSA(hashes.SHA256()))
        except (InvalidSignature, Exception):
            return None

        # Un seul appareil actif a la fois : usage personnel mono-device.
        # Une nouvelle connexion remplace toute session precedente (evite
        # qu'un vieil appareil de test reste "actif" et masque le vrai).
        self.sessions.clear()
        token = secrets.token_urlsafe(32)
        self.sessions[token] = DeviceSession(device_id=device_id, expires_at=time.time() + DEVICE_SESSION_TTL_SECONDS)
        return token

    def disconnect(self, session_token: str) -> None:
        """Ferme la session, mais laisse la cle de l'appareil enregistree --
        un simple 'Connecter' suffit ensuite a revenir, sans nouveau code."""
        self.sessions.pop(session_token, None)

    def revoke(self, session_token: str) -> bool:
        """Ferme la session ET retire la cle publique de l'appareil -- une
        vraie deconnexion, pas juste une pause. Reconnexion future = nouveau
        code d'appairage obligatoire. Retourne True si un appareil a bien ete
        revoque."""
        session = self.sessions.pop(session_token, None)
        if session is None:
            return False
        self.devices.pop(session.device_id, None)
        return True

    def active_session(self) -> DeviceSession | None:
        """Retourne la session appareil active la plus recente, si elle n'a pas expire."""
        now = time.time()
        # purge des sessions expirees au passage
        for tok in [t for t, s in self.sessions.items() if s.expires_at < now]:
            del self.sessions[tok]
        if not self.sessions:
            return None
        return max(self.sessions.values(), key=lambda s: s.expires_at)
