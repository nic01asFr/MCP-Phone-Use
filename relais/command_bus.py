"""File de commandes device-agent — pont entre les outils MCP (Claude) et
l'app Android, en short-poll des deux cotes (jamais de long-poll HTTP qui
bloquerait un worker ; l'attente cote outil MCP est un sleep asyncio borne,
qui ne bloque pas les autres requetes grace a asyncio).
"""

import asyncio
import time
import uuid


class CommandBus:
    def __init__(self) -> None:
        self.pending: dict[str, list[dict]] = {}          # device_id -> [commande]
        self.results: dict[str, dict] = {}                 # command_id -> resultat
        self.events: dict[str, asyncio.Event] = {}          # command_id -> event

    def queue_command(self, device_id: str, command_type: str, params: dict) -> str:
        command_id = uuid.uuid4().hex
        self.pending.setdefault(device_id, []).append(
            {"id": command_id, "type": command_type, "params": params, "queued_at": time.time()}
        )
        self.events[command_id] = asyncio.Event()
        return command_id

    def poll_and_clear(self, device_id: str) -> list[dict]:
        """Appele par l'app (short-poll) : recupere et vide la file de ce device."""
        return self.pending.pop(device_id, [])

    def submit_result(self, command_id: str, result: dict) -> bool:
        """Appele par l'app apres execution d'une commande."""
        if command_id not in self.events:
            return False
        self.results[command_id] = result
        self.events[command_id].set()
        return True

    async def wait_for_result(self, command_id: str, timeout: float = 8.0) -> dict | None:
        """Appele par l'outil MCP : attend le resultat sans bloquer le serveur
        (asyncio.wait_for cede la main aux autres requetes pendant l'attente).
        """
        event = self.events.get(command_id)
        if not event:
            return None
        try:
            await asyncio.wait_for(event.wait(), timeout=timeout)
        except TimeoutError:
            return None
        finally:
            self.events.pop(command_id, None)
        return self.results.pop(command_id, None)
