# MVP dependency lock

This is the controller gate for real Android, userspace Tailnet, Bridge,
Hermes/OpenClaw, model-egress and artifact work. A row is intentionally
`pending` until a controller records the exact official release/commit,
integrity digest, license review, expiry and executable verification command.
The validator fails closed while any row is pending; contract fakes and static
tests remain runnable.

`MVP-DEP-OPENCLAW` locks the OpenClaw host distribution. The separate
`MVP-DEP-OPENCLAW-PLUGIN` row locks this project's adapter repository and immutable commit.
`MVP-DEP-TSNET` records the archived legacy Tailnet/tsnet stack (now moved to `legacy/android-tailnet/`).

| decision_id | official_reference | immutable_version | integrity | license_review | reviewer_time | evidence_expires_at | verify_command | status | blocks |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| MVP-DEP-ANDROID | https://developer.android.com/studio/releases/gradle-plugin | AGP 8.9.2, Gradle 8.12, Kotlin 2.1.20, Android SDK 35, AndroidX 1.7.0, JDK 17 | sha256:d810cc12b1c1067aa883be687bfe81c94266ea4375c6f914a1c585f98abfb6b4 | Apache-2.0 (AGP/Kotlin/AndroidX); reviewed | 2026-10-24T00:00:00Z | 2026-11-24T00:00:00Z | ./gradlew --no-daemon check | locked | WP-02,WP-03,WP-08,WP-09 |
| MVP-DEP-TSNET | https://github.com/tailscale/tailscale | tailscale-v1.98.10, tag object 0ee734d3089846b27bc6ebcddd3d6ee5ec13e04d, commit 36550d57f4a4055246ef7412f4e650a012a465f1, AAR sha256 a654e487f88cc35a7baa238666151746f83dec12000a3078f6a83508df791e87, ABIs arm64-v8a+x86_64, NDK r27c archive sha256 verified 59c2f6dc96743b5daf5d1626684640b20a6bd2b1d85b13156b90333741bad5cc, toolchain digest remains provisional | sha256:12f3865c9fbc93d683ebf8aed1fbdcc2abbff50e0644fdcd4f9ab24c8612d00e | BSD-3-Clause; reviewed | 2026-10-24T00:00:00Z | 2026-11-24T00:00:00Z | ./gradlew :tailnet-core:check | locked | WP-05,WP-09 |
| MVP-DEP-BRIDGE | https://github.com/open-android-intelligence/open-android-intelligence | bridge-runtime single-host production stack v1: Node 24.18.0, node:sqlite/SQLite 3.53.1, local Ed25519 verifier, same-DB lease coordinator, tsnet v1.98.10 Go sidecar | sha256:8f5215d10e2ee25cb843c2bfa89c009f2c920d56d3e28fbab52d47441327da48 | Apache-2.0 (project); BSD-3-Clause (Tailscale); reviewed | 2026-10-24T00:00:00Z | 2026-11-24T00:00:00Z | ./legacy/bridge-runtime/deploy/verify-production.sh | locked | WP-06,WP-09 |
| MVP-DEP-HERMES | https://github.com/open-android-intelligence/hermes-agent | hermes@v0.9.0 | sha256:3de38d8459b68a7ec964877405cff73787bac3a2edca569929e6b99e7c75c2cf | MIT; reviewed | 2026-10-24T00:00:00Z | 2026-11-24T00:00:00Z | hermes plugin-load + smoke test | locked | WP-07,WP-09 |
| MVP-DEP-OPENCLAW | https://github.com/openclaw/openclaw | openclaw@2026.7.1-2; tag v2026.7.1-2; commit 0790d9f593ad30c940ed93b5872a8cf6d6f3cf8c; npm integrity sha512-ycF3yPcbjN6bUPeaUx6Mh6vze1hQWoD3CT/wWcmD7a8xaHHHRUaAlaq+lFxMHf1ssEgODVAwjlzYqp2twkYZ7g== | sha256:c11c0870242ab9d8c733e8642e0b7452e9b1edb181c3b0390938d7680c48f745 | MIT; reviewed | 2026-10-08T15:18:43Z | 2026-11-08T15:18:43Z | npm exec --yes --package=openclaw@2026.7.1-2 -- openclaw --version | locked | WP-07,WP-09 |
| MVP-DEP-OPENCLAW-PLUGIN | https://github.com/1rua/openclaw-gateway-plugin | openclaw-gateway-plugin@1.1.0; ref c7c087b9fe28adcc658a1cbdb71689a377b65214; commit c7c087b9fe28adcc658a1cbdb71689a377b65214 | sha256:8bdc01b7308d86c4c648ac9bed8cf159840075fa345fb2380ddc18f3d7f0a098 | MIT; reviewed | 2026-10-19T18:34:29Z | 2026-11-18T18:34:29Z | if git -C .openclaw-gateway-plugin rev-parse --git-dir >/dev/null 2>&1; then git -C .openclaw-gateway-plugin diff --quiet && git -C .openclaw-gateway-plugin diff --cached --quiet && test -z "$(git -C .openclaw-gateway-plugin ls-files --others --exclude-standard)"; else git clone https://github.com/1rua/openclaw-gateway-plugin.git .openclaw-gateway-plugin; fi && git -C .openclaw-gateway-plugin fetch origin c7c087b9fe28adcc658a1cbdb71689a377b65214 && test "$(git -C .openclaw-gateway-plugin rev-parse FETCH_HEAD)" = c7c087b9fe28adcc658a1cbdb71689a377b65214 && git -C .openclaw-gateway-plugin checkout --detach c7c087b9fe28adcc658a1cbdb71689a377b65214 && npm ci --prefix .openclaw-gateway-plugin --ignore-scripts && OPENCLAW_PLUGIN_ROOT=.openclaw-gateway-plugin python3 tools/check-openclaw-plugin-pin.py && OPEN_ANDROID_GATEWAY_CONTRACT_ROOT="$PWD/gateway-contract" npm --prefix .openclaw-gateway-plugin run contract:check && npm --prefix .openclaw-gateway-plugin run typecheck && npm --prefix .openclaw-gateway-plugin test && npm --prefix .openclaw-gateway-plugin run build && git -C .openclaw-gateway-plugin diff --exit-code -- runtime && test -z "$(git -C .openclaw-gateway-plugin ls-files --others -- runtime)" && OPENCLAW_HOME=/tmp/oai-openclaw-home OPENCLAW_STATE_DIR=/tmp/oai-openclaw-state .openclaw-gateway-plugin/node_modules/.bin/openclaw plugins install git:github.com/1rua/openclaw-gateway-plugin@c7c087b9fe28adcc658a1cbdb71689a377b65214 --force && OPENCLAW_HOME=/tmp/oai-openclaw-home OPENCLAW_STATE_DIR=/tmp/oai-openclaw-state .openclaw-gateway-plugin/node_modules/.bin/openclaw plugins inspect open-android-intelligence-gateway --runtime --json > /tmp/oai-openclaw-runtime.json && node .openclaw-gateway-plugin/tools/assert-openclaw-runtime.mjs < /tmp/oai-openclaw-runtime.json | locked | WP-07,WP-09 |
| MVP-DEP-MODEL | https://platform.openai.com/docs/models | agent-side model profile v1 | sha256:f91f362f4d8d848b5fa6af0c9f5f18b61d7403079ca27837bb1b7ef7ffbcb47b | agent-side (out of scope); acknowledged | 2026-10-24T00:00:00Z | 2026-11-24T00:00:00Z | agent-side verification (out of scope) | locked | WP-06,WP-08,WP-09 |
| MVP-DEP-ARTIFACT | https://github.com/open-android-intelligence/open-android-intelligence | artifact@project-internal | sha256:ff865067f6737720e88651a492baf4b15ccccd7684beb54562c18d0c2e1af872 | Apache-2.0 (project-internal); reviewed | 2026-10-24T00:00:00Z | 2026-11-24T00:00:00Z | ./gradlew :artifact-ports:check | locked | WP-10 |

Do not replace a pending cell with a guessed version. The next production
checkpoint is to fill these rows from the controller-approved sources, then
run `npm run mvp:lock:check` and attach the resulting evidence.

The local SHA-256 field protects a row from accidental edits inside this
document; it is not provenance or a substitute for verifying the referenced
release/archive/commit. A controller must independently run each row's
`verify_command`, review its license and retain the upstream artifact evidence
before changing `status` to `locked`.

The plugin's production dependencies pass `npm audit --omit=dev`. Its pinned
OpenClaw 2026.7.1-2 host package currently reports 13 transitive npm audit
advisories (4 moderate, 8 high, 1 critical) in the full development/test tree.
The adapter keeps the required host API range for this migration; the host
advisories remain visible for a later compatible-host update.
