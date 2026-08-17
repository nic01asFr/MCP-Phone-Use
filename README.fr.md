# MCP Phone Use

*[Read this in English](README.md)*

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/nic01asFr/MCP-Phone-Use)](https://github.com/nic01asFr/MCP-Phone-Use/releases/latest)
[![MCP](https://img.shields.io/badge/MCP-compatible-blue)](https://modelcontextprotocol.io)

Donne à un assistant IA compatible MCP un accès réel et sécurisé à un téléphone Android — perception (lire l'écran, capturer une image) et action (taper, glisser, saisir du texte, lancer des apps) — sans jamais avoir besoin d'ADB ni d'ordinateur local, ni pour l'usage quotidien, ni pour le déboguer.

<p align="center">
  <img src="docs/screenshots/home-screen-icon.jpg" width="260" alt="Icône MCP Phone Use sur l'écran d'accueil" />
  &nbsp;&nbsp;
  <img src="docs/screenshots/main-screen.jpg" width="260" alt="Écran principal, connecté" />
</p>

> **Auto-hébergé.** Ce n'est pas un serveur MCP qu'on lance en local avec une seule commande. Il faut héberger le relais soi-même (Python, URL HTTPS publique). Compter 30-60 min pour un premier déploiement — voir [Installation](#installation).
>
> **APK prêtes à l'emploi** : [dernière release](https://github.com/nic01asFr/MCP-Phone-Use/releases/latest) — pas besoin de compiler soi-même l'app Android, seul le relais reste à déployer.

## Ce que ça fait

Trois outils exposés via MCP :

- **`get_ui_tree`** — lit la hiérarchie d'accessibilité de l'app au premier plan (texte, éléments cliquables, position), quelle que soit l'app active
- **`device_action`** — `tap`, `swipe`, `type_text`, `key` (back/home/recents/notifications), `launch_app`
- **`get_screen`** — capture d'écran réelle (JPEG), pour tout ce que l'accessibilité seule ne peut pas voir (rendu visuel, contenu WebView, apps peu accessibles)

Les deux premiers reposent sur un `AccessibilityService` ; le troisième sur `MediaProjection` (armée séparément, consentement système à chaque session, jamais groupée avec la connexion).

## Architecture

```
Assistant IA compatible MCP (Claude, ou tout autre client MCP)
        │  OAuth 2.1 + PKCE, Streamable HTTP
        ▼
   Relais MCP (pod SSPCloud)
   auth server + resource server
        ▲
        │  connexion sortante, challenge-response (clé Keystore)
   App Android (MCP Phone Use)
   AccessibilityService + MediaProjection
```

Le téléphone se connecte **en sortant** vers le relais — aucun ADB, aucun NAT à percer, aucun port ouvert côté téléphone. Détail complet dans [`docs/architecture.md`](docs/architecture.fr.md).

## Sécurité

- **Double verrou** — aucun outil ne répond sans les deux conditions réunies : session OAuth valide *et* app connectée côté téléphone
- **Pas de token statique** — l'app prouve son identité par signature cryptographique (paire de clés Android Keystore, non exportable) à chaque connexion, jamais par un secret qui transite en clair
- **Rate-limiting** — nombre de tentatives limité, par IP, sur les points d'entrée à secret devinable (connexion, enrôlement) — testé sous attaque réelle, voir [`SECURITY.md`](SECURITY.fr.md)
- **Consentement humain non contournable** — L'assistant peut amener l'utilisateur jusqu'à une popup de consentement système (accessibilité, capture d'écran), mais ne tape jamais lui-même dessus ; le dernier geste reste toujours humain, par choix, pas par limite technique

Détail complet du modèle de sécurité, des tests effectués et des limites connues : [`SECURITY.md`](SECURITY.fr.md).

## Installation

### 1. Déployer le relais

Le relais (`relais/`) doit tourner en continu, joignable depuis internet en HTTPS — le téléphone s'y connecte en sortant, Claude l'appelle comme un serveur MCP classique. Un [`Dockerfile`](Dockerfile) est fourni pour un déploiement générique sur n'importe quel hébergement (VPS, pod cloud...). Voir [`CONTRIBUTING.md`](CONTRIBUTING.fr.md) pour les commandes complètes, ou [`relais/README.md`](relais/README.fr.md) pour le détail sans Docker.

### 2. Installer l'app Android

L'app est distribuée hors Play Store, ce qui déclenche par défaut le blocage Android "Paramètres restreints" empêchant l'activation de l'accessibilité. Contournement propre, sans ADB : un **installateur dédié** utilisant `PackageInstaller` en mode session (le même mécanisme que Play Store/F-Droid), qui installe `MCP Phone Use` avec un statut de confiance suffisant pour éviter ce blocage.

1. Télécharger l'installateur depuis la [dernière release](https://github.com/nic01asFr/MCP-Phone-Use/releases/latest), sideload direct
2. Ouvrir l'installateur (l'URL de l'APK principale est déjà pré-remplie), valider "Télécharger et installer"
3. Ouvrir l'app, entrer le code d'appairage donné par ton assistant (usage unique, 10 min)
4. Activer l'accessibilité et, si besoin, armer la capture d'écran

## État

Socle complet et validé en conditions réelles : OAuth 2.1/PKCE, double verrou, challenge-response, rate-limiting, wake lock (empêche l'écran de s'éteindre pendant une capture active), rapporteur de crash intégré (`Thread.UncaughtExceptionHandler` + `ApplicationExitInfo`, sans ADB).

Backlog restant : persistance du registre d'appareils côté relais (actuellement en mémoire, perdu à chaque redémarrage du process).

## Licence

[MIT](LICENSE) — libre d'utilisation, de modification et de redistribution.
