# Agent memory — MCP-capacitor / device-agent

Avant toute action : lis `docs/architecture.md` en entier. Les décisions d'architecture y sont **arrêtées** (double auth OAuth/challenge-response, topologie sortante, hébergement SSPCloud nic01asfr, pas de lien Hostinger) — tu construis dessus, tu ne les remets pas en cause sans le signaler explicitement.

Le backlog priorisé est en fin de `docs/architecture.md`.

Garde-fous non négociables (hérités du projet précédent) :
- Outils MCP à retour immédiat uniquement, jamais de long-poll côté serveur
- Aucun secret en dur ni commité (`.env`, exclu par `.gitignore`)
- Commits atomiques, messages clairs
- Demander avant toute action destructive, tout déploiement public, ou tout changement d'architecture
