"""Limiteur de tentatives, en memoire, par IP.

Objectif : bloquer un brute-force externe sans jamais bloquer l'utilisateur
legitime pour de bon — fenetre glissante courte, auto-expiration, pas de
verrouillage permanent (mono-utilisateur : pas de compte a proteger contre
un vol de compte, juste un secret a proteger contre le devinage).
"""

import time


class RateLimiter:
    def __init__(self, max_attempts: int = 5, window_seconds: int = 900):
        self.max_attempts = max_attempts
        self.window_seconds = window_seconds
        self.attempts: dict[str, list[float]] = {}  # ip -> [timestamps des echecs]

    def _prune(self, ip: str) -> list[float]:
        cutoff = time.time() - self.window_seconds
        recent = [t for t in self.attempts.get(ip, []) if t > cutoff]
        self.attempts[ip] = recent
        return recent

    def is_blocked(self, ip: str) -> bool:
        return len(self._prune(ip)) >= self.max_attempts

    def record_failure(self, ip: str) -> None:
        self._prune(ip)
        self.attempts.setdefault(ip, []).append(time.time())

    def record_success(self, ip: str) -> None:
        # Un succes efface l'ardoise — pas de raison de continuer a compter
        # des echecs anciens une fois que la bonne cle a ete prouvee.
        self.attempts.pop(ip, None)

    def retry_after_seconds(self, ip: str) -> int:
        recent = self._prune(ip)
        if not recent:
            return 0
        oldest = min(recent)
        return max(0, int(oldest + self.window_seconds - time.time()))
