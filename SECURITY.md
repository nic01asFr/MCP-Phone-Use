# Security

This document honestly describes MCP Phone Use's security model — what's tested and confirmed, and what remains a knowingly accepted limitation. The code is public: this project's security relies on the robustness of the model and the secrecy of the keys, never on the secrecy of the design itself.

## Security model

**Double lock** — no control tool (`get_ui_tree`, `device_action`, `get_screen`) responds unless both conditions are met simultaneously:
1. A valid OAuth 2.1/PKCE session on the MCP client side
2. The Android app actively connected on the phone's side (recent challenge-response, not expired)

**After enrollment, no shared secret is ever retransmitted.** The server only knows the device's *public* key; every connection proves possession of the private key (stored in the Android Keystore, non-exportable) by signing a single-use nonce.

**Sensitive comparisons run in constant time** (password) — no information leak through response-time measurement.

## What has been actively tested, not just assumed

- Resistance to a real brute-force attack (fast, repeated attempts) on entry points with a guessable secret
- TLS configuration: protocol version, cipher strength, certificate validity and issuer, rejection of outdated protocols, HTTP → HTTPS redirect
- Behavior against deliberately malformed or oversized inputs (no error-trace leak, no exploitable crash)
- Resistance to memory exhaustion through entry points that are unauthenticated by nature (OAuth client registration)
- Real distinction between a light disconnect and a device revocation (tested separately, confirmed distinct behaviors)

## Known, accepted limitations

- **Single-user design**: one account, no multi-factor authentication. Suited to a personal, self-hosted use, not to a service shared between several people as-is.
- **No persistence**: state (enrolled devices, sessions) lives in memory — restarting the relay wipes everything. Trade-off: this also acts, de facto, as a full revocation mechanism in case of doubt.
- **A newly enrolled device silently replaces the previous one**, with no notification or dedicated log for now.
- **App signing**: distributed APKs are debug-signed. A production signature is the responsibility of whoever deploys their own instance (see `CONTRIBUTING.md`).

## Reporting a vulnerability

Personal project, no dedicated security team: open an issue on the repo, or contact the maintainer directly via their GitHub profile for any sensitive report before public disclosure.
