# Contributing / deploying your own instance

*[Lire en français](CONTRIBUTING.fr.md)*

This project is self-hosted — there is no central service managed by someone else. Each person using it runs their own relay and compiles their own app.

## How it actually works, before the commands

**GitHub plays two distinct roles here.** On one hand, it hosts the source code, public and freely browsable. On the other, via its "Releases" tab, it also hosts **pre-built, ready-to-use files** — the Android APKs, as downloadable attachments, no compiling required. Both live on GitHub, but they're not the same thing: the code is the recipe, the Release is the dish already cooked.

**What GitHub does NOT do** is run a program for you continuously. The relay (the small server that bridges Claude and your phone) needs to live somewhere that stays on — a few-euros-a-month VPS, a cloud pod, or any hosting capable of running a Docker container continuously. That's the only piece you need to host yourself; everything else (the code, the APKs) comes straight from GitHub.

**The concrete path, start to finish:**
1. You deploy the relay on your own hosting (see below) — you get a public HTTPS URL (e.g. `https://my-relay.example.com`)
2. On your phone, you install the "installer" app — downloadable directly from the [GitHub Release](https://github.com/nic01asFr/MCP-Phone-Use/releases/latest), nothing to compile
3. In the installer, you confirm the main APK's link (already pre-filled with the latest version published on GitHub) — it downloads and installs "MCP Phone Use" for you
4. You open the app, enter the URL of **your** relay (from step 1)
5. You connect your AI assistant (Claude or another) to that same URL as an MCP connector, and generate a single-use pairing code
6. You enter that code in the app — the connection is established, with cryptographic authentication on every future reconnection

## Deploy the relay

With Docker (recommended):

```bash
git clone https://github.com/nic01asFr/MCP-Phone-Use.git
cd MCP-Phone-Use
cp relais/.env.example relais/.env   # edit DEVICE_AGENT_PASSWORD and DEVICE_AGENT_SERVER_URL
docker build -t mcp-phone-use-relay .
docker run -d --env-file relais/.env -p 8000:8000 mcp-phone-use-relay
```

The container listens on plain HTTP on port 8000 — put a reverse proxy (Caddy, nginx, Traefik) in front for public HTTPS, required for OAuth. `DEVICE_AGENT_SERVER_URL` must match the final public URL (the one after the reverse proxy), not `localhost`.

Without Docker: see [`relais/README.md`](relais/README.md).

## Compile the Android app (optional)

Not needed for regular use — ready-to-use APKs are on the [GitHub Release](https://github.com/nic01asFr/MCP-Phone-Use/releases/latest). Compiling yourself is only useful for modifying the code, or for a clean production signature (see below).

```bash
cd android-device-agent
./gradlew :app:assembleDebug :installer:assembleDebug
```

APKs are generated in `app/build/outputs/apk/debug/` and `installer/build/outputs/apk/debug/`.

For a release signature (recommended if you distribute the app beyond your own phone):

```bash
keytool -genkey -v -keystore my-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias mcp-phone-use
cp android-device-agent/app/keystore.properties.example android-device-agent/app/keystore.properties
# edit keystore.properties with the path and passwords of the generated key
cd android-device-agent && ./gradlew :app:assembleRelease
```

`keystore.properties` is excluded via `.gitignore` — never commit it.

## Install on the phone

1. Download `mcp-phone-use-installer-debug.apk` from the [GitHub Release](https://github.com/nic01asFr/MCP-Phone-Use/releases/latest), sideload directly
2. Open the installer — the main APK's link is already pre-filled (latest published version), tap "Download and install"
3. Open `MCP Phone Use`, enter the URL of **your** relay (the one configured in `DEVICE_AGENT_SERVER_URL`), then the pairing code given by your AI assistant
4. Enable accessibility and, if needed, arm screen capture

See the [README](README.md) for details on architecture and security.

## Contributing code

Pull requests welcome. No formal process for now — open an issue if the change is substantial before investing time in it.
