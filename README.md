# MCP Phone Use

Donne à un assistant IA compatible MCP un accès réel et sécurisé à un téléphone Android — perception (lire l'écran, capturer une image) et action (taper, glisser, saisir du texte, lancer des apps) — sans jamais avoir besoin d'ADB ni d'ordinateur local, ni pour l'usage quotidien, ni pour le déboguer.

<p align="center">
  <img src="docs/screenshots/home-screen-icon.jpg" width="260" alt="Icône MCP Phone Use sur l'écran d'accueil" />
  &nbsp;&nbsp;
  <img src="docs/screenshots/main-screen.jpg" width="260" alt="Écran principal, connecté" />
</p>

## Ce que ça fait

Trois outils exposés via MCP :

- **`get_ui_tree`** — lit la hiérarchie d'accessibilité de l'app au premier plan (texte, éléments cliquables, position), quelle que soit l'app active
- **`device_action`** — `tap`, `swipe`, `type_text`, `key` (back/home/recents/notifications), `launch_app`
- **`get_screen`** — capture d'écran réelle (JPEG), pour tout ce que l'accessibilité seule ne peut pas voir (rendu visuel, contenu WebView, apps peu accessibles)

Les deux premiers reposent sur un `AccessibilityService` ; le troisième sur `MediaProjection` (armée séparément, consentement système à chaque session, jamais groupée avec la connexion).

## Architecture

```
Claude (claude.ai / Claude Code)
        │  OAuth 2.1 + PKCE, Streamable HTTP
        ▼
   Relais MCP (pod SSPCloud)
   auth server + resource server
        ▲
        │  connexion sortante, challenge-response (clé Keystore)
   App Android (MCP Phone Use)
   AccessibilityService + MediaProjection
```

Le téléphone se connecte **en sortant** vers le relais — aucun ADB, aucun NAT à percer, aucun port ouvert côté téléphone. Détail complet dans [`docs/architecture.md`](docs/architecture.md).

## Sécurité

- **Double verrou** — aucun outil ne répond sans les deux conditions réunies : session OAuth Claude valide *et* app connectée côté téléphone
- **Pas de token statique** — l'app prouve son identité par signature cryptographique (paire de clés Android Keystore, non exportable) à chaque connexion, jamais par un secret qui transite en clair
- **Rate-limiting** — 5 tentatives / 15 min par IP sur les points d'entrée à secret devinable (connexion, enrôlement)
- **Consentement humain non contournable** — Claude peut amener l'utilisateur jusqu'à une popup de consentement système (accessibilité, capture d'écran), mais ne tape jamais lui-même dessus ; le dernier geste reste toujours humain, par choix, pas par limite technique

## Installation

L'app est distribuée hors Play Store, ce qui déclenche par défaut le blocage Android "Paramètres restreints" empêchant l'activation de l'accessibilité. Contournement propre, sans ADB : un **installateur dédié** utilisant `PackageInstaller` en mode session (le même mécanisme que Play Store/F-Droid), qui installe `MCP Phone Use` avec un statut de confiance suffisant pour éviter ce blocage.

1. Sideload direct de l'installateur (`installer/`)
2. Depuis l'installateur, entrer l'URL du relais et installer `MCP Phone Use`
3. Ouvrir l'app, entrer le code d'appairage donné par Claude (usage unique, 10 min)
4. Activer l'accessibilité et, si besoin, armer la capture d'écran

## État

Socle complet et validé en conditions réelles : OAuth 2.1/PKCE, double verrou, challenge-response, rate-limiting, wake lock (empêche l'écran de s'éteindre pendant une capture active), rapporteur de crash intégré (`Thread.UncaughtExceptionHandler` + `ApplicationExitInfo`, sans ADB).

Backlog restant : persistance du registre d'appareils côté relais (actuellement en mémoire, perdu à chaque redémarrage du process).
