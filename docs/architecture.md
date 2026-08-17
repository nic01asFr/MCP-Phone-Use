# Architecture & decision log

## Topology

The phone connects **outbound** to the relay — any public HTTPS hosting works (VPS, cloud pod, container...), see [`Dockerfile`](../Dockerfile) and [`relais/README.md`](../relais/README.md). No ADB, no NAT to traverse, no open port on the phone's side. Real deployment example used during development: SSPCloud pod, `nic01asfr` namespace — a hosting choice, not a project constraint.

Any MCP-compatible AI assistant connects to the same relay as an MCP client — tested so far with Claude (claude.ai Custom Connector, or Claude Code), with no structural dependency on that specific client.

```
MCP-compatible AI assistant
        │  OAuth 2.1 + PKCE, Streamable HTTP
        ▼
   MCP relay (your own hosting)
   auth server + resource server
        ▲
        │  outbound connection, challenge-response (Keystore key)
   Android app (MCP Phone Use)
   AccessibilityService + MediaProjection
```

## Two distinct authentication mechanisms

### MCP client ↔ pod: OAuth 2.1 + PKCE

Same standard as other existing MCP servers (mcp-server-grist, BigMCP, QgisStreamMCP): Authorization Code + PKCE (MCP spec 2025-06-18), RFC 8707 for audience binding, OIDC for identity. Single authorized user. Streamable HTTP transport.

The relay acts as both authorization server AND resource server — **no external dependency** (no third-party IdP required, no external auth service). Works identically regardless of the chosen hosting.

### App ↔ pod: key pair + challenge-response

No static token sent on every call.

- **Enrollment (once)**: the app generates a key pair in the Android Keystore (StrongBox/TEE when available, non-exportable private key). A single-use enrollment code, generated pod-side, links the public key to the device.
- **Connection (every session)**: the pod sends a nonce, the app signs it with its private key, the pod verifies the signature against the registered public key. No secret ever travels in the clear.
- **Revocation**: independent from OAuth auth — public key removal on the pod's side, without touching OAuth tokens.

## Double lock

No control tool (`get_screen`, `get_ui_tree`, `device_action`, ...) is available unless both conditions are met simultaneously: a valid OAuth session (regardless of MCP client) + the app enabled and authenticated on the phone's side. The app only opens its channel on manual action ("Connect"), never as a persistent background task.

## Generic app

The APK has no service URL hardcoded — configurable at pairing / first launch. A single binary, reusable.

## Real phone control (existing, proven on SURFAC²E)

- `AccessibilityService` — reads the UI tree of ANY app, injects tap/swipe/text/key events (`dumpUi`, `tap`, `swipe`, `typeText`, `key`, `launchApp`)
- `MediaProjection` — real screenshot capture (JPEG frames), foreground service
- **Capacitor alone (webview CDP) is insufficient** for this level of control — only useful for inspecting YOUR OWN webview, not the OS or other apps.

These two system services are the part already validated under real conditions (SURFAC²E usage): to reuse/adapt, not to reinvent. The layer that changes is authentication (see above).

## Hosting

Required, regardless of the chosen hosting: a long-lived Python process, reachable over public HTTPS, able to install `relais/requirements.txt`'s dependencies. See [`Dockerfile`](../Dockerfile) for the generic containerized version.

Hosting used during this project's development (example, not a constraint): SSPCloud pod, `nic01asfr` namespace, `device-agent` Onyxia project — Android SDK installable directly inside it (`dl.google.com`, `maven.google.com`, `services.gradle.org`, `repo.maven.apache.org` reachable, 1 TB RAM / 6.5 TB disk available), which allowed building the APK in the same environment as the relay. None of this is required elsewhere — a regular VPS with Docker is enough.

## APK distribution

Once built, distributed via a temporary download link (`expose_public` on the pod), disabled (`unexpose_public`) right after retrieval — no permanent exposure channel for the binary, given what the app allows once installed.

## History

This repo succeeds a local project ("MCP apk", `device-agent-relay/` + `android-device-agent/` folders) that had laid the same architectural foundations (outbound topology, AccessibilityService + MediaProjection) and already served as a reference — used for developing SURFAC²E's 3D capture module. The Python relay was functionally validated there; the Kotlin was scaffolding, never compiled. The existing code (`ControlService.kt`, `ScreenCaptureService.kt`, `BridgeClient.kt`, `MainActivity.kt`) still needed retrieving and adapting to the new authentication model (OAuth on Claude's side, challenge-response on the app's side — the local project used a single static bearer token).

## Backlog

The entire initial backlog is done and validated under real conditions: OAuth 2.1/PKCE + Streamable HTTP relay, enrollment + challenge-response, complete Android app (AccessibilityService + MediaProjection), SSPCloud pod deployment, dedicated installer (Restricted Settings workaround without ADB), claude.ai Custom Connector, end-to-end validation of `get_ui_tree`/`device_action`/`get_screen`.

Hardening added along the way, beyond the initial plan:
- Per-IP rate-limiting on entry points with a guessable secret
- Wake lock during screen capture (avoids session loss from the screen turning off)
- Built-in crash reporter (`Thread.UncaughtExceptionHandler` + `ApplicationExitInfo`, no ADB needed)
- `versionCode`/`versionName` derived from timestamp — a fixed `versionCode` caused silent, ineffective updates
- `canary` build channel (distinct app identifier) to test a risky version without ever touching the installed stable version

Still open:
- [ ] Persistence of the device registry on the relay side (currently in-memory — lost on every process restart, requiring re-enrollment)
