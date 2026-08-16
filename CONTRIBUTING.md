# Contribuer / déployer sa propre instance

Ce projet est auto-hébergé — il n'y a pas de service central géré par quelqu'un d'autre. Chaque personne qui l'utilise fait tourner son propre relais et compile sa propre app.

## Déployer le relais

Avec Docker (recommandé) :

```bash
git clone https://github.com/nic01asFr/MCP-Phone-Use.git
cd MCP-Phone-Use
cp relais/.env.example relais/.env   # editer DEVICE_AGENT_PASSWORD et DEVICE_AGENT_SERVER_URL
docker build -t mcp-phone-use-relay .
docker run -d --env-file relais/.env -p 8000:8000 mcp-phone-use-relay
```

Le conteneur écoute en HTTP simple sur le port 8000 — placez un reverse-proxy (Caddy, nginx, Traefik) devant pour le HTTPS public, obligatoire pour OAuth. `DEVICE_AGENT_SERVER_URL` doit correspondre à l'URL publique finale (celle après le reverse-proxy), pas à `localhost`.

Sans Docker : voir [`relais/README.md`](relais/README.md).

## Compiler l'app Android

```bash
cd android-device-agent
./gradlew :app:assembleDebug :installer:assembleDebug
```

APK générés dans `app/build/outputs/apk/debug/` et `installer/build/outputs/apk/debug/`.

Pour une signature release (recommandé si vous distribuez l'app au-delà de votre propre téléphone) :

```bash
keytool -genkey -v -keystore ma-cle-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias mcp-phone-use
cp android-device-agent/app/keystore.properties.example android-device-agent/app/keystore.properties
# editer keystore.properties avec le chemin et les mots de passe de la cle generee
cd android-device-agent && ./gradlew :app:assembleRelease
```

`keystore.properties` est exclu par `.gitignore` — ne jamais le committer.

## Installer sur le téléphone

1. Sideload direct de l'APK `installer`
2. Ouvrir l'installateur, entrer l'URL de **votre** relais (celle configurée dans `DEVICE_AGENT_SERVER_URL`), installer `MCP Phone Use`
3. Ouvrir l'app, saisir la même URL dans le champ prévu, puis le code d'appairage donné par votre assistant IA
4. Activer l'accessibilité et, si besoin, armer la capture d'écran

Voir le [README](README.md) pour le détail de l'architecture et de la sécurité.

## Contribuer du code

Pull requests bienvenues. Pas de process formel pour l'instant — ouvrez une issue si le changement est substantiel avant d'investir du temps dedans.
