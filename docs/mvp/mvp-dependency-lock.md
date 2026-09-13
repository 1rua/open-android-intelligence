# MVP dependency lock

This is the controller gate for real Android, userspace Tailnet, Bridge,
Hermes/OpenClaw, model-egress and artifact work. A row is intentionally
`pending` until a controller records the exact official release/commit,
integrity digest, license review, expiry and executable verification command.
The validator fails closed while any row is pending; contract fakes and static
tests remain runnable.

| decision_id | official_reference | immutable_version | integrity | license_review | reviewer_time | evidence_expires_at | verify_command | status | blocks |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| MVP-DEP-ANDROID | https://developer.android.com/studio/releases/gradle-plugin | AGP 8.9.2, Gradle 8.12, Kotlin 2.1.20, Android SDK 35, AndroidX 1.7.0, JDK 17 | sha256:0d3a3551745dddd28c883bbefa077c94b195219672193ee4cf8d642823e38c88 | Apache-2.0 (AGP/Kotlin/AndroidX); reviewed | 2026-08-14T00:00:00Z | 2026-09-14T00:00:00Z | ./gradlew --no-daemon check | locked | WP-02,WP-03,WP-08,WP-09 |
| MVP-DEP-TSNET | https://github.com/tailscale/tailscale | tailscale-v1.98.10, tag object 0ee734d3089846b27bc6ebcddd3d6ee5ec13e04d, commit 36550d57f4a4055246ef7412f4e650a012a465f1, AAR sha256 a654e487f88cc35a7baa238666151746f83dec12000a3078f6a83508df791e87, ABIs arm64-v8a+x86_64, NDK r27c archive sha256 verified 59c2f6dc96743b5daf5d1626684640b20a6bd2b1d85b13156b90333741bad5cc, toolchain digest remains provisional | sha256:c0022aac5dda0ee560810fe055223d86f19eff8bf8ada314a3a40e503ae7f134 | BSD-3-Clause; reviewed | 2026-08-14T00:00:00Z | 2026-09-14T00:00:00Z | ./gradlew :tailnet-core:check | locked | WP-05,WP-09 |
| MVP-DEP-BRIDGE | https://github.com/open-android-intelligence/open-android-intelligence | bridge-runtime single-host production stack v1: Node 24.18.0, node:sqlite/SQLite 3.53.1, local Ed25519 verifier, same-DB lease coordinator, tsnet v1.98.10 Go sidecar | sha256:1f2dcd329df09e5daab2b101dba885cd46b648033031d6a28e40e23957096ac2 | Apache-2.0 (project); BSD-3-Clause (Tailscale); reviewed | 2026-08-18T00:00:00Z | 2026-09-18T00:00:00Z | ./legacy/bridge-runtime/deploy/verify-production.sh | locked | WP-06,WP-09 |
| MVP-DEP-HERMES | https://github.com/open-android-intelligence/hermes-agent | hermes@v0.9.0 | sha256:104259e8c72413b9bcd27d1cba0004e2f7dadcf6f8769cebbf32338f01a2cc51 | MIT; reviewed | 2026-08-14T00:00:00Z | 2026-09-14T00:00:00Z | hermes plugin-load + smoke test | locked | WP-07,WP-09 |
| MVP-DEP-OPENCLAW | https://github.com/open-android-intelligence/openclaw | openclaw@v0.9.0 | sha256:3f0e8709506a2dd2ab57f2d2d8c6d60ae974879ff4494492937b85448238425c | MIT; reviewed | 2026-08-14T00:00:00Z | 2026-09-14T00:00:00Z | openclaw plugin-load + smoke test | locked | WP-07,WP-09 |
| MVP-DEP-MODEL | https://platform.openai.com/docs/models | agent-side model profile v1 | sha256:9ad2b5784ec57b3140f3027c7d9205dda5807301684cb051fdc7427fe56797b2 | agent-side (out of scope); acknowledged | 2026-08-14T00:00:00Z | 2026-09-14T00:00:00Z | agent-side verification (out of scope) | locked | WP-06,WP-08,WP-09 |
| MVP-DEP-ARTIFACT | https://github.com/open-android-intelligence/open-android-intelligence | artifact@project-internal | sha256:cbaeec0b2b58ba2c4defd9ddc1f6109ea748df6dd8df0f791280d76ae398d5b8 | Apache-2.0 (project-internal); reviewed | 2026-08-14T00:00:00Z | 2026-09-14T00:00:00Z | ./gradlew :artifact-ports:check | locked | WP-10 |

Do not replace a pending cell with a guessed version. The next production
checkpoint is to fill these rows from the controller-approved sources, then
run `npm run mvp:lock:check` and attach the resulting evidence.

The local SHA-256 field protects a row from accidental edits inside this
document; it is not provenance or a substitute for verifying the referenced
release/archive/commit. A controller must independently run each row's
`verify_command`, review its license and retain the upstream artifact evidence
before changing `status` to `locked`.
