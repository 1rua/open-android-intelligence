<div align="center">

<a href="#quick-start"><img src="assets/readme/hero-banner.svg" alt="Open Android Intelligence: A unified Android client for connecting multiple mainstream Agents" width="100%"></a>

# Open Android Intelligence

<p>
  <strong>A unified Android client for connecting multiple mainstream Agents.</strong><br>
  The client only provides device capabilities; decisions are entirely handled by Agents.
</p>

<h3>
  <a href="https://github.com/1rua/open-android-intelligence/releases/latest/download/app-full-release.apk">One-Click APK Install</a> · Connect Multiple Mainstream Agents · Privacy & Safety Controlled
</h3>

<p>
  <sub>
    Zero relay servers · Direct connection to self-hosted gateways · No root required · Production-grade permission isolation<br>
    <a href="README.md">简体中文</a> · English
  </sub>
</p>

<p>
  <a href="https://github.com/1rua/open-android-intelligence/releases/latest"><img src="https://img.shields.io/github/v/release/1rua/open-android-intelligence?style=flat&label=Release&color=34D399" alt="Latest Release"></a>
  <a href="#quick-start"><img src="https://img.shields.io/badge/Android-12%2B%20(minSdk%2031)-10B981?style=flat&logo=android&logoColor=white" alt="Android 12+"></a>
  <a href="#how-it-works"><img src="https://img.shields.io/badge/Gateway-Direct%20to%20Mainstream%20Agents-38BDF8?style=flat" alt="Direct Connection to Mainstream Agents"></a>
  <a href="#current-status"><img src="https://img.shields.io/badge/Contract-70%20Shared%20Vectors-34D399?style=flat" alt="Hermes & OpenClaw 70 Contract Vectors"></a>
  <a href="#security-boundaries"><img src="https://img.shields.io/badge/Security-No%20Root%20%C2%B7%20Default%20Deny-F59E0B?style=flat" alt="Security Boundaries"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-94A3B8?style=flat" alt="MIT License"></a>
</p>

<p>
  <a href="#30-second-overview">30-Second Overview</a> ·
  <a href="#key-features">Key Features</a> ·
  <a href="#how-it-works">How It Works</a> ·
  <a href="#security-boundaries">Security Boundaries</a> ·
  <a href="#quick-start">Quick Start</a> ·
  <a href="#current-status">Current Status</a> ·
  <a href="#documentation-navigation">Documentation Navigation</a>
</p>

</div>

---

## 30-Second Overview

> [!TIP]
> ### Open Android Intelligence (OAI) is **a unified Android client for connecting multiple mainstream Agents** (such as Hermes, OpenClaw, DSH, etc.).
>
> It securely and controllability exposes phone hardware capabilities to AI Agents, allowing multiple remote Agents to share phone control without deploying standalone Agents on the phone.

The project consists of three main components, with host adapter plugins maintained in separate repositories:

| Core Component | Role & Description | Repository / Path |
| --- | --- | --- |
| **Android App** | Connects to Gateways, manages accounts and multi-sessions, sends user-selected multimodal attachments, and hosts the device capability sandbox | [`apps/android`](apps/android) |
| **Gateway Protocol** | Defines authentication, messaging, and closed-schema data contracts between the phone and Agent hosts | [`gateway-contract`](gateway-contract) |
| **Third-Party Host Plugins** | Provide standard Gateway adapters for Hermes, OpenClaw, and DSH (DeepSeek Harness) | [Hermes Plugin](https://github.com/1rua/hermes-gateway-plugin) / [OpenClaw Plugin](https://github.com/1rua/openclaw-gateway-plugin) / [DSH Plugin](https://github.com/1rua/dsh-android-gateway) |

> [!NOTE]
> **Zero Relay Architecture**: The Android App communicates directly with your self-hosted Agent Gateway. The project does not operate any public cloud message relay servers.<br>
> **Plugin-Based Authorization**: Device capabilities are installed and authorized as isolated plugins. Sensitive operations such as sending SMS must be manually confirmed by the user on the phone screen.

---

## Key Features

<table>
  <tr>
    <td width="50%" valign="top">
      <h3>Native Multi-Session Chat <img src="https://img.shields.io/badge/Ready-10B981?style=flat-square" alt="Ready"></h3>
      <p>Enjoy fluid conversations with your connected mainstream Agents on your phone. Features independent multi-session management, session archiving, folder categorization, and flexible sorting; comprehensive support for system photo picker, file selector, clipboard image pasting, and system-level multi-file share attachments.</p>
    </td>
    <td width="50%" valign="top">
      <h3>Direct Multi-Host Gateway <img src="https://img.shields.io/badge/Supported-38BDF8?style=flat-square" alt="Supported"></h3>
      <p>Seamlessly connect to <strong>Hermes</strong>, <strong>OpenClaw</strong>, and <strong>DSH (DeepSeek Harness)</strong> through a unified Gateway Protocol v2. Connect directly to your own server, retaining full sovereignty over model execution and history without cloud data hijacking.</p>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <h3>WebAssembly Plugin Sandbox <img src="https://img.shields.io/badge/Architecture%20Ready-34D399?style=flat-square" alt="Architecture Ready"></h3>
      <p>Everything is a plugin. Protected device plugins are packaged as standard <code>.alp</code> bundles, running inside a high-performance WASM sandbox with strict constraints on CPU execution time, memory ceilings (hard quotas), storage capacity, and concurrent network connections to prevent privilege abuse.</p>
    </td>
    <td width="50%" valign="top">
      <h3>Zero-Trust Security & Screen Confirmation <img src="https://img.shields.io/badge/Security%20First-F59E0B?style=flat-square" alt="Security First"></h3>
      <p>Follows the "Default Deny" security principle: plugin installation, plugin enablement, and Gateway account authorization are strictly decoupled across tiers. High-risk operations like sending SMS must be confirmed manually per item on the phone screen, strictly preventing silent execution.</p>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <h3>Closed Contracts & Anti-Replay Verification <img src="https://img.shields.io/badge/70%20Vectors-10B981?style=flat-square" alt="70 Vectors"></h3>
      <p>Strict closed contract validation powered by JSON Schema 2020-12, dropping any extraneous unknown fields; critical commands are equipped with Ed25519 signatures, timestamps, and one-time nonces for anti-replay protection, ensuring idempotent execution across network jitter.</p>
    </td>
    <td width="50%" valign="top">
      <h3>Native System Notifications & Live Updates <img src="https://img.shields.io/badge/Android%2012--16-8B5CF6?style=flat-square" alt="Android 12-16"></h3>
      <p>Deeply integrated into the native Android ecosystem: supports conversational notifications (MessagingStyle) and floating Bubbles on Android 12–15, with native Android 16 Live Updates integration for real-time task progress tracking.</p>
    </td>
  </tr>
</table>

---

## How It Works

<div align="center">
  <img src="assets/readme/architecture.svg" alt="Architecture: Android client connects to Agent hosts via Gateway Protocol" width="100%">
</div>

The entire system operates across three tightly integrated layers:

1. **Android Native Host Client** (Frontline)
   - **Interaction & Sessions**: Provides Gateway connection configuration, multi-session management, and system-level attachment selection (via system SAF pickers and share interfaces).
   - **Platform Kernel**: Manages device identity based on Ed25519 keys, plugin lifecycles, and permission arbitration. Acts strictly as an enforcement gatekeeper without business backdoors.
   - **Secure Plugin Sandbox**: Isolates device extension plugins inside a WebAssembly runtime, enforcing strict bounds on CPU, memory, persistent local storage, and concurrent network resources.
2. **Gateway Protocol v2 Contract Layer** (Bridge)
   - **Transport Pipeline**: Bidirectional communication via HTTP(S) request/response + WebSocket / SSE event streams.
   - **Strict Validation**: Closed-schema field validation based on JSON Schema 2020-12, rejecting any unexpected fields immediately.
   - **Cryptographic Guardrails**: Critical requests carry Ed25519 signature credentials, timestamps, and nonce anti-replay verification, supported by idempotent state machines and bounded automatic retries.
3. **Agent Host Brain** (Backend)
   - **Ecosystem Adapters**: Hermes and OpenClaw integrate via dedicated standalone adapter plugins; DSH deeply reuses its native web service port without requiring a separate bridge process.
   - **Unified Capability Declaration**: All three backends align with the same Gateway protocol specification, independently determining model orchestration and tool execution pipelines while keeping your data entirely on your host.

---

## Security Boundaries

<div align="center">
  <img src="assets/readme/security-model.svg" alt="Security Model: Agent submits requests, kernel checks permissions, user confirms on screen" width="100%">
</div>

Security and privacy are the foundation of this project. All hardware device capabilities are **completely disabled by default**. Enabling any capability requires passing three lines of defense: **Install plugin on phone → Manually enable plugin → Authorize individually for a specific Gateway account**.

### Device Permission Control Matrix

| Sensitive Capability | Strict Limits & Permitted Boundaries |
| --- | --- |
| **Call Logs** | **Basic metadata read-only** (contacts/numbers, timestamps, call duration); recording, answering, hanging up, or auto-dialing is strictly prohibited in any form. |
| **SMS Records** | Controlled read access to sent and received records; **modifying operations like sending SMS must be manually clicked and confirmed on the phone screen one by one**, background dispatch is strictly prohibited. |
| **App Notifications** | **Denied by default**; requires the user to manually select specific app package names and fields in a whitelist. In-memory end-to-end encrypted buffering without plaintext logging. |

---

## Quick Start

### 0. Prerequisites

| Environment | Minimum Requirement | Recommended |
| --- | --- | --- |
| **Android Phone** | Android 12+ (`minSdk 31`) | Android 14+, 8GB RAM recommended |
| **Agent Host Environment** | Running Hermes, OpenClaw, or DSH (DeepSeek Harness) | Accessible via public internet or Tailscale LAN |
| **Developer Build Toolchain** *(Only needed for source development)* | Node.js `24.18.0` + npm `11.16.0`, JDK 21, Android SDK (`compileSdk 36` / `targetSdk 35`) | Rust 1.80+ (with `wasm32-unknown-unknown` target) |

---

### 1. Install App on Phone

Go to the project [Releases Page](https://github.com/1rua/open-android-intelligence/releases) to download and install the APK:

| Release Channel | Target Audience | Installation Notes |
| --- | --- | --- |
| **Developer Preview** | Early adopters, developers | Uses unified Debug keystore built into repo, supports in-place upgrade, directly downloadable from daily CI builds |
| **Stable Release** | General daily use | Signed with official private production keys, cross-installing over Developer Preview is strictly prohibited (see [Android Signing Configuration](apps/android/README.md#release-signing)) |

---

### 2. Install Gateway Plugin on Agent Host

Choose and configure the corresponding host plugin according to your Agent framework. To ensure end-to-end security, Hermes and OpenClaw require configuring an account Master Key for symmetric encryption and storage of message payloads.

<details>
<summary><b>Configure <a href="https://github.com/1rua/hermes-gateway-plugin">Hermes</a> Host (Click to expand)</b></summary>

```bash
# 1. Install and enable the specified Gateway plugin version (strictly locked to current App version)
hermes plugins install 1rua/hermes-gateway-plugin --ref 190441415652ae34fbd1cb8cc485f6208fff944b --enable

# 2. Guided wizard to configure mobile connection to gateway
hermes gateway setup

# 3. Routine account and gateway status inspection
hermes open-android-intelligence account list
hermes open-android-intelligence account create -u my_user -p <set_password> --confirm-local
hermes open-android-intelligence status
hermes open-android-intelligence contract status
```

> **Version Pin Notice**: Currently paired with version `2.2.0` (SHA256: `032f5802...`). If previously locked to an older commit (`9d642b4`), append `--force` to reinstall and run `hermes gateway restart` for changes to take effect.
</details>

<details>
<summary><b>Configure <a href="https://github.com/1rua/openclaw-gateway-plugin">OpenClaw</a> Host (Click to expand)</b></summary>

Prepare a 32-byte raw key file (permissions set to `0600`), and configure the system environment variable:

```bash
# Set master key file path
export OPEN_ANDROID_INTELLIGENCE_GATEWAY_MASTER_KEY_FILE=/private/path/gateway-master-key.bin

# Install and verify plugin runtime status
openclaw plugins install git:github.com/1rua/openclaw-gateway-plugin@c7c087b9fe28adcc658a1cbdb71689a377b65214
openclaw plugins inspect open-android-intelligence-gateway --runtime --json
```

> **Version Pin Notice**: Currently locked to version `1.1.0` (commit `c7c087b`), protocol specification `2.1.0`, host API `2026.7.1`.
</details>

<details>
<summary><b>Configure <a href="https://github.com/1rua/dsh-android-gateway">DSH (DeepSeek Harness)</a> Host (Click to expand)</b></summary>

[dsh-android-gateway](https://github.com/1rua/dsh-android-gateway) is a standalone Bundle plugin built for DSH, version `0.2.0`, implementing Gateway Protocol v2.1. It directly reuses DSH's native web port:

```bash
# Clone and build DSH gateway plugin
git clone https://github.com/1rua/dsh-android-gateway.git
cd dsh-android-gateway
npm ci
npm run build
```

Execute the Bundle installation command inside the DSH session:
```text
plugin_manager action=install_bundle target=/absolute/path/to/dsh-android-gateway
```

On first startup, if the account store is empty, the plugin will automatically generate one-time initial credentials in `$DSH_HOME/dsh-android-gateway/setup-account.json` (`0600` permissions). Please delete this temporary file promptly after the phone connects successfully. See [DSH Plugin Documentation](https://github.com/1rua/dsh-android-gateway#readme) for details.
</details>

<details>
<summary><b>Offline Account Pre-provisioning (CLI Tool)</b></summary>

If you don't have an active host runtime environment yet, you can pre-provision account credentials locally using the standalone CLI bundled with the Hermes plugin:

```bash
python3 tools/hermes-account.py init-key        # Generate 0600 master key file
python3 tools/hermes-account.py create my_user  # Interactively set password
python3 tools/hermes-account.py status          # Inspect gateway hosting and pairing status
```
</details>

---

### 3. Connect Phone to Gateway

1. Open the mobile app and enter your Gateway access address (supports HTTP/HTTPS; internal network IP or reverse-proxy domain recommended).
2. Enter the account username and password created above, then tap "Pair & Connect".
3. After the handshake completes successfully on the phone, certificate information and credentials will be safely stored in the local Android Keystore, so subsequent launches do not require re-entering passwords.

<details>
<summary><b>HTTPS Public Key Pinning (SPKI SHA-256) Setup Guide</b></summary>

When enabling HTTPS, to prevent Man-in-the-Middle (MitM) attacks, configure the server TLS certificate's SPKI SHA-256 digest in the Gateway plugin:

```bash
# Calculate server certificate public key SHA-256 fingerprint
openssl x509 -in gateway.crt -pubkey -noout | openssl pkey -pubin -outform DER | openssl dgst -sha256
```

Set the calculated public key fingerprint into the gateway environment variable:
```bash
OPEN_ANDROID_INTELLIGENCE_GATEWAY_TLS_SPKI_SHA256=sha256:<64-hex-characters>
```
If unset, the handshake will return `null`, and the client will refuse to submit password credentials. While plaintext HTTP allows degraded connection, the App UI will persistently display an orange unencrypted security warning.
</details>

---

### 4. Enable Device Capabilities on Demand

All device capabilities remain dormant by default. When you need an Agent to perceive SMS or notifications, execute the standard three-tier authorization:

```bash
# Inspect capability authorization for a specified account
hermes open-android-intelligence capabilities list <username>
```

> [!WARNING]
> **In-Development Status Notice**: Device plugin packaging, Ed25519 signature verification, WASM sandbox engine, and quota constraints have all been implemented and passed tests; however, the internal execution pipeline assembly within the App is actively underway, and physical full-loop verification is pending final acceptance. For progress, please refer to [Current Status](#current-status) and [Notification Capability Sync Record](docs/reviews/2026-10-20-agent-notification-capabilities.zh-CN.md).

---

### 5. Build and Debug from Source (Developers)

<details>
<summary><b>Contract Conformance Tests (70 Test Cases for Hermes / OpenClaw)</b></summary>

```bash
npm ci

# Check out pinned OpenClaw plugin source code and install dependencies
git clone https://github.com/1rua/openclaw-gateway-plugin.git .openclaw-gateway-plugin
git -C .openclaw-gateway-plugin checkout "$(python3 -c 'import json; print(json.load(open("openclaw-plugin-pin.json"))["revision"])')"
npm ci --prefix .openclaw-gateway-plugin --ignore-scripts

# Run conformance comparison tests across TypeScript and Python implementations
OPENCLAW_PLUGIN_ROOT="$PWD/.openclaw-gateway-plugin" npm run gateway:v2:conformance
```
</details>

<details>
<summary><b>Build Official Device Plugins (.alp)</b></summary>

```bash
# Compile Rust/WASM artifacts
cargo build --locked --offline --target wasm32-unknown-unknown --release --manifest-path plugins/Cargo.toml
cargo test --locked --offline --manifest-path plugins/Cargo.toml

# Deterministically package and sign using release signing key
npm ci --prefix plugin-tooling
OPEN_ANDROID_INTELLIGENCE_PLUGIN_SIGNING_KEY_FILE=/private/path/plugin-seed.bin \
  npm run build:references --prefix plugin-tooling
```
Artifacts are output to the `plugins/dist/` directory: including official reference plugins like `notifications`, `sms`, `call-log`, etc.
</details>

<details>
<summary><b>Compile Android Client</b></summary>

```bash
cd apps/android
./gradlew --no-daemon check :app:assembleFullDebug
```

All APK modules uniformly use the repository's pre-configured `debug.keystore` signature, ensuring local development, CI builds, and test packages share identical fingerprints, completely eliminating `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

Verify debug package signature fingerprint:
```bash
apps/android/tools/verify-debug-signing.sh apps/android/app/build/outputs/apk/full/debug/app-full-debug.apk
```
</details>

---

## Current Status

This table genuinely records the automated testing, host integration, and physical device verification status of each capability. We strictly distinguish test doubles from real verification, never disguising simulated passes as physical device capabilities.

| Domain & Core Capability | Verification Basis & Scope | Real-World Status & Completeness |
| --- | --- | --- |
| **Hermes / OpenClaw Contract Conformance** | 70 shared TS/Python contract test cases verified item by item | ✅ Covers protocol consistency across gateway adapters; does not represent end-to-end device execution |
| **Hermes Gateway Adapter** | pytest unit tests + real aiohttp SSE/WS handshake, signing, and credential revocation tests | ✅ Protocol layer covered; host context history and tool execution result callbacks pending completion |
| **OpenClaw Adapter Plugin** | Standalone repo pinned version, loaded in live host runtime, 70 shared test cases | ✅ Installation and communication protocol verified; physical Android end-to-end loop pending |
| **DSH (DeepSeek Harness) Plugin** | Version 0.2.0; 201 automated tests; [DSH & Android Emulator Integration Record](https://github.com/1rua/dsh-android-gateway/blob/main/docs/reviews/2026-10-21-feature-completion.md) | ✅ Core session flow validated; device capability loop pending mobile plugins and physical device verification |
| **Device Plugin Packaging & WASM Runtime** | Deterministic packaging and signing, WASM ABI conformance, memory/storage quota budget and recovery tests | ✅ Core underlying libraries passed tests; App business-layer execution scheduling pipeline being assembled |
| **Official Reference Plugins** | Notification reading, SMS, call logs reviewed by Platform Kernel permission interception | ⚠️ Requires physical device plugin installation and tiered authorization; actual loop pending separate acceptance |
| **Android Client UI & Accounts** | Gradle build, Robolectric tests, and module automated tests | ✅ Account management, authentication, event bus, and encrypted offline storage integrated; physical testing pending |
| **Companion Private Network** | tsnet pump transport pipeline and trusted IPC token issuance not ready | ⛔ Entrypoint disabled; private network tunnel provisioning not issued |
| **Physical Device End-to-End Experience** | Android Keystore hardware crypto, silent certificate rotation, SAF dynamic authorization, live host connectivity | ⏳ Pending physical device acceptance test loop |

---

## Documentation Navigation

All project specifications and implementation records are completely archived within the repository. Please refer to the corresponding documents as needed:

### User Guides & Configuration

| Document | Core Content | Quick Link |
| --- | --- | --- |
| **Device Permissions & Reading Configuration** | Enabling phone notification and call log reading permissions and whitelist configuration | [View Guide](docs/mvp/device-reads-and-reply-notifications.md) |
| **Android Development Guide** | Android App architectural separation, signing configuration, and flavor build descriptions | [View Guide](apps/android/README.md) |
| **App Update Mechanics** | Dual-channel update strategy for Stable and Preview versions and version comparison logic | [View Overview](docs/reviews/2026-10-21-app-update-check.md) |
| **System Notifications Compatibility Report** | Notification and bubble behavior across Android 12–16 versions and OEM ROM restriction boundaries | [View Report](docs/reviews/2026-10-18-session-notifications-compatibility.zh-CN.md) |

### Developer & Architecture Specifications

| Document | Core Content | Quick Link |
| --- | --- | --- |
| **Domain Terminology & Conceptual Boundaries** | Unified terminology definitions, core entity interactions, and security responsibility boundaries | [`CONTEXT.md`](CONTEXT.md) |
| **Modular Plugin Architecture Specification** | Overall architectural design specification for the plugin system | [View Specification](docs/superpowers/specs/2026-08-24-modular-plugin-architecture.md) |
| **Gateway Protocol v2 Specification** | Communication contract and JSON Schema closed specification between phone and Agent Gateway | [View Contract](docs/contracts/gateway-protocol-v2.md) |
| **Device Plugin Package v1 Specification** | `.alp` plugin package metadata definition, Ed25519 signing, and sandbox constraints | [View Specification](docs/contracts/device-plugin-package-v1.md) |
| **DSH Host Gateway Plugin** | Official adapter plugin and test suites built for DeepSeek Harness | [Visit Repository](https://github.com/1rua/dsh-android-gateway) |
| **Gateway Key Management (ADR 0023)** | SQLite and explicit Gateway Secret management architecture decision record | [View ADR](docs/adr/0023-use-sqlite-with-explicit-gateway-secret-management.md) |
| **Plaintext Pairing Security Warning (ADR 0047)** | Architectural decision on allowing LAN plaintext pairing with persistent prominent warnings | [View ADR](docs/adr/0047-allow-plaintext-gateway-pairing-with-explicit-warning.md) |
| **Visual Assets & Design Specifications** | Color tokens and font size baseline rules for README static vector graphics | [Design Specifications](assets/readme/README.md) |
| **Agent Code of Conduct** | Developer and contributor guidelines, test verification baselines, and Git commit standards | [`AGENTS.md`](AGENTS.md) |

---

## Project Structure

<details>
<summary><b>Click to expand complete repository structure</b></summary>

```text
open-android-intelligence/
├── apps/android/                  # Native Android Host application (AGP 8.9.2, SDK 36, Kotlin 2.1.20)
│   ├── app/                       # Main entry APK: Multi-session chat, account credentials, settings, attachments
│   ├── platform-kernel/           # Platform Kernel: Permission arbitrator, plugin lifecycles, security crypto primitives
│   ├── plugin-package/            # .alp plugin package parser, Ed25519 signature validator, and installer
│   ├── plugin-runtime-wasm/       # High-performance WebAssembly sandboxed runtime
│   ├── conversation-ui/           # Material 3 conversational UI interaction component library
│   ├── assistant-holder/          # System-level assistant holder role and button/gesture triggers
│   ├── tailscale-companion/       # Optional user-space Tailscale connectivity plugin (Companion APK)
│   └── ...                        # Domain models, Room data storage, and contract port modules
├── gateway-contract/              # Gateway Protocol v2 contract & Hermes / OpenClaw conformance test suite
│   ├── schemas/                   # Strict closed-schema definitions based on JSON Schema 2020-12
│   ├── vectors/                   # Cross-language standard test vectors (70 golden test cases)
│   ├── src/                       # TypeScript contract reference implementation
│   └── tools/                     # Automated Hermes / OpenClaw conformance test runners
├── plugins/                       # Official reference device plugin source code (Rust/WASM + .alp artifacts)
├── plugin-tooling/                # Plugin packaging and deterministic signing toolchain (Packaging CLI)
├── integrations/                  # Shared integration test environments and fixed fixtures
├── docs/                          # Product specifications, ADR architectural decision records, and technical reviews
├── assets/readme/                 # Beautiful vector SVG assets and design token specs for README
├── legacy/                        # Frozen and archived legacy bridge code and transitional components
├── openclaw-plugin-pin.json       # Pinned OpenClaw plugin version, tag, and Git commit SHA
└── CONTEXT.md                     # Unified domain terminology and design boundary specification
```
</details>

## License
MIT
