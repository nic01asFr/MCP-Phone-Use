# Contribuer / déployer sa propre instance

*[Read this in English](CONTRIBUTING.md)*

Ce projet est auto-hébergé — il n'y a pas de service central géré par quelqu'un d'autre. Chaque personne qui l'utilise fait tourner son propre relais et compile sa propre app.

## Comment ça marche concrètement, avant les commandes

**GitHub, dans cette histoire, joue deux rôles distincts.** D'un côté, il héberge le code source, public et librement consultable. De l'autre, via son onglet "Releases", il héberge aussi des **fichiers compilés prêts à l'emploi** — les APK Android, en pièces jointes téléchargeables directement, sans avoir besoin de compiler quoi que ce soit soi-même. Les deux sont sur GitHub, mais ce ne sont pas la même chose : le code est la recette, la Release est le plat déjà préparé.

**Ce que GitHub ne fait PAS**, c'est faire tourner un programme pour vous en continu. Le relais (le petit serveur qui fait le pont entre Claude et votre téléphone) doit vivre quelque part qui reste allumé — un VPS à quelques euros par mois, un pod cloud, ou tout hébergement capable de faire tourner un conteneur Docker en continu. C'est la seule pièce que vous devez héberger vous-même ; tout le reste (le code, les APK) vient directement de GitHub.

**Le chemin concret, du début à la fin :**
1. Vous déployez le relais sur votre propre hébergement (voir plus bas) — vous obtenez une URL publique en HTTPS (ex : `https://mon-relais.exemple.com`)
2. Sur votre téléphone, vous installez l'app "installateur" — téléchargeable directement depuis la [Release GitHub](https://github.com/nic01asFr/MCP-Phone-Use/releases/latest), sans rien compiler
3. Dans l'installateur, vous validez le lien de l'APK principale (déjà pré-rempli avec la dernière version publiée sur GitHub) — il télécharge et installe "MCP Phone Use" à votre place
4. Vous ouvrez l'app, entrez l'URL de **votre** relais (celle de l'étape 1)
5. Vous connectez votre assistant IA (Claude ou autre) à cette même URL comme connecteur MCP, et générez un code d'appairage à usage unique
6. Vous saisissez ce code dans l'app — la connexion est établie, avec authentification cryptographique à chaque reconnexion future

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

## Compiler l'app Android (optionnel)

Pas nécessaire pour un usage courant — les APK prêtes à l'emploi sont sur la [Release GitHub](https://github.com/nic01asFr/MCP-Phone-Use/releases/latest). Compiler soi-même n'est utile que pour modifier le code, ou pour une signature de production propre (voir plus bas).

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

1. Télécharger `mcp-phone-use-installer-debug.apk` depuis la [Release GitHub](https://github.com/nic01asFr/MCP-Phone-Use/releases/latest), sideload direct
2. Ouvrir l'installateur — le lien de l'APK principale est déjà pré-rempli (dernière version publiée), valider "Télécharger et installer"
3. Ouvrir `MCP Phone Use`, entrer l'URL de **votre** relais (celle configurée dans `DEVICE_AGENT_SERVER_URL`), puis le code d'appairage donné par votre assistant IA
4. Activer l'accessibilité et, si besoin, armer la capture d'écran

Voir le [README](README.fr.md) pour le détail de l'architecture et de la sécurité.

## Contribuer du code

Pull requests bienvenues. Pas de process formel pour l'instant — ouvrez une issue si le changement est substantiel avant d'investir du temps dedans.
