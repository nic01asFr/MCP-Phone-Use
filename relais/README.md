# relais — device-agent (OAuth 2.1/PKCE + MCP Streamable HTTP)

## Prérequis pour un vrai déploiement

- Python 3.11+
- Une URL **HTTPS publique** pointant vers ce process — obligatoire : OAuth exige HTTPS, et le téléphone doit pouvoir joindre le relais depuis n'importe quel réseau (4G comprise). En pratique : un reverse-proxy (Caddy, nginx, Traefik...) devant le process Python, avec un certificat valide (Let's Encrypt ou équivalent).
- Le process doit rester **vivant en continu** — ce n'est pas un script qu'on lance ponctuellement. Prévoir un service géré par systemd, un conteneur avec restart policy, ou l'équivalent de votre plateforme.
- **Aucune persistance actuellement** : le registre d'appareils et les sessions vivent en mémoire — un redémarrage du process efface tout, ré-enrôlement nécessaire. Backlog connu, voir `docs/architecture.md`.

## Lancer en local (dev / test)

```bash
cp .env.example .env   # puis editer DEVICE_AGENT_PASSWORD
set -a; source .env; set +a
pip install -r requirements.txt
python server.py
```

Ça démarre le serveur en HTTP simple sur le port configuré — suffisant pour développer, **pas suffisant tel quel pour un usage réel** (pas de HTTPS, pas de redémarrage automatique). Voir "Prérequis" ci-dessus pour passer en déploiement réel.

## Tester

- `GET /healthz` -> `{"status": "ok"}`
- `GET /.well-known/oauth-authorization-server` -> metadonnees OAuth (auto, via FastMCP)
- Un client MCP sans token sur `/mcp` -> 401 avec `WWW-Authenticate: Bearer ...`
- Flux complet : voir `test_oauth_flow.py` (simule le parcours authorize -> login -> code -> token -> appel outil)
