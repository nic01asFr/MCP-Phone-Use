# Architecture & journal de décisions

## Topologie

Le téléphone se connecte **en sortant** vers le relais — n'importe quel hébergement HTTPS public convient (VPS, pod cloud, conteneur...), voir [`Dockerfile`](../Dockerfile) et [`relais/README.md`](../relais/README.md). Aucun ADB, aucun NAT à percer, aucun port ouvert côté téléphone. Exemple de déploiement réel utilisé pendant le développement : pod SSPCloud, namespace `nic01asfr` — un choix d'hébergement, pas une contrainte du projet.

Claude (via Custom Connector claude.ai, ou Claude Code) se connecte au même relais comme client MCP.

```
Claude (claude.ai / Claude Code)
        │  OAuth 2.1 + PKCE, Streamable HTTP
        ▼
   Relais MCP (votre hébergement)
   auth server + resource server
        ▲
        │  connexion sortante, challenge-response (clé Keystore)
   App Android (MCP Phone Use)
   AccessibilityService + MediaProjection
```

## Deux mécanismes d'authentification distincts

### Claude ↔ pod : OAuth 2.1 + PKCE

Même standard que les autres serveurs MCP existants (mcp-server-grist, BigMCP, QgisStreamMCP) : Authorization Code + PKCE (spec MCP 2025-06-18), RFC 8707 pour l'audience binding, OIDC pour l'identité. Un seul utilisateur autorisé. Transport Streamable HTTP.

Le relais joue authorization server ET resource server — **pas de dépendance externe** (pas d'IdP tiers requis, pas de service d'auth externe). Fonctionne identique quel que soit l'hébergement choisi.

### App ↔ pod : paire de clés + challenge-response

Pas de token statique transmis à chaque appel.

- **Enrôlement (une fois)** : l'app génère une paire de clés dans l'Android Keystore (StrongBox/TEE si disponible, clé privée non-exportable). Un code d'enrôlement à usage unique, généré côté pod, associe la clé publique au device.
- **Connexion (à chaque session)** : le pod envoie un nonce, l'app le signe avec sa clé privée, le pod vérifie la signature avec la clé publique enregistrée. Aucun secret ne transite en clair sur le fil.
- **Révocation** : indépendante de l'auth Claude — retrait de la clé publique côté pod, sans toucher aux tokens OAuth.

## Double verrou

Aucun outil de contrôle (`get_screen`, `get_ui_tree`, `device_action`, ...) n'est disponible sans les deux conditions réunies simultanément : session OAuth Claude valide + app activée et authentifiée côté téléphone. L'app n'ouvre son canal que sur action manuelle ("Connecter"), jamais en tâche de fond permanente.

## App générique

L'APK n'a pas d'URL de service figée en dur — configurable au pairing / premier lancement. Un seul binaire, réutilisable.

## Contrôle réel du téléphone (existant, prouvé sur SURFAC²E)

- `AccessibilityService` — lecture de l'arbre UI de N'IMPORTE QUELLE app, injection tap/swipe/texte/touches (`dumpUi`, `tap`, `swipe`, `typeText`, `key`, `launchApp`)
- `MediaProjection` — capture d'écran réelle (frames JPEG), service premier plan
- **Capacitor seul (webview CDP) est insuffisant** pour ce niveau de contrôle — utile seulement pour inspecter SA PROPRE webview, pas l'OS ni les autres apps.

Ces deux services système sont la partie déjà validée en conditions réelles (usage SURFAC²E) : à réutiliser/adapter, pas à réinventer. La couche qui change est l'authentification (voir ci-dessus).

## Hébergement

Requis, quel que soit l'hébergement choisi : un process Python long-vivant, joignable en HTTPS public, capable d'installer les dépendances de `relais/requirements.txt`. Voir [`Dockerfile`](../Dockerfile) pour la version conteneurisée générique.

Hébergement utilisé pendant le développement de ce projet (exemple, pas une contrainte) : pod SSPCloud, namespace `nic01asfr`, projet Onyxia `device-agent` — SDK Android installable directement dedans (`dl.google.com`, `maven.google.com`, `services.gradle.org`, `repo.maven.apache.org` joignables, 1 To RAM / 6,5 To disque disponibles), ce qui permettait de compiler l'APK dans le même environnement que le relais. Rien de tout ça n'est requis ailleurs — un VPS classique avec Docker suffit.

## Distribution de l'APK

Une fois buildée, distribution par lien de téléchargement temporaire (`expose_public` sur le pod), désactivé (`unexpose_public`) juste après récupération par Nicolas — pas de canal permanent d'exposition du binaire, vu ce que l'app permet une fois installée.

## Historique

Ce repo succède à un projet local ("MCP apk", dossier `device-agent-relay/` + `android-device-agent/`) qui avait posé les mêmes fondations d'architecture (topologie sortante, AccessibilityService + MediaProjection) et servait déjà de référence — utilisé pour le développement du module capture 3D de SURFAC²E. Le relais Python y était validé fonctionnellement ; le Kotlin était un scaffold jamais compilé. Le code existant (`ControlService.kt`, `ScreenCaptureService.kt`, `BridgeClient.kt`, `MainActivity.kt`) reste à récupérer et adapter au nouveau modèle d'authentification (OAuth côté Claude, challenge-response côté app — le projet local utilisait un bearer token statique unique).

## Backlog

Tout le backlog initial est réalisé et validé en conditions réelles : relais OAuth 2.1/PKCE + Streamable HTTP, enrôlement + challenge-response, app Android complète (AccessibilityService + MediaProjection), déploiement pod SSPCloud, installateur dédié (contournement Restricted Settings sans ADB), Custom Connector claude.ai, validation bout-en-bout de `get_ui_tree`/`device_action`/`get_screen`.

Durcissements ajoutés en cours de route, au-delà du plan initial :
- Rate-limiting par IP (5 tentatives/15min) sur les points d'entrée à secret devinable
- Wake lock pendant la capture d'écran (évite les pertes de session liées à l'extinction d'écran)
- Rapporteur de crash intégré (`Thread.UncaughtExceptionHandler` + `ApplicationExitInfo`, sans ADB)
- `versionCode`/`versionName` dérivés de l'horodatage — un `versionCode` figé causait des mises à jour silencieusement sans effet
- Canal de build `canary` (identifiant d'app distinct) pour tester une version à risque sans jamais toucher à la version stable installée

Reste ouvert :
- [ ] Persistance du registre d'appareils côté relais (actuellement en mémoire — perdu à chaque redémarrage du process, oblige à ré-enrôler)
