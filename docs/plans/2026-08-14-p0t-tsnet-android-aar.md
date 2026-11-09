# P0t Tailscale Android Userspace AAR Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a byte-reproducible, pinned Tailscale `v1.98.10` Android AAR from `third_party/tailscale`, integrate its narrow tsnet bridge into `tailnet-core`, make production composition use only `TsnetPairedBridgeTransport`, and close P0t only with complete real-device evidence.

**Architecture:** The work is one sequential, three-gate delivery because every later gate consumes cryptographically identified output from the previous one. Gate A builds and verifies the AAR from immutable source and toolchain inputs. Gate B exposes only typed node/channel/path operations to Kotlin, persists node state with Android Keystore in `noBackupFilesDir`, and seals production composition against fakes or fallback networking. Gate C runs the frozen Open Android Intelligence connect protocol over a real Tailnet WSS peer and emits a secret-free, machine-validated evidence inventory for all required Android, network, lifecycle, VPN, 16 KiB, and resource cases.

**Tech Stack:** Bash and Python 3 supply-chain tooling; stock Go `1.26.5`; `golang.org/x/mobile` gomobile/gobind at `v0.0.0-20240806205939-81131f6468ab`; Tailscale `v1.98.10`; `github.com/coder/websocket v1.8.12`; Android Gradle Plugin `8.9.2`; Gradle `8.12`; Kotlin `2.1.20`; Android API 34/compile SDK 35; NDK `27.2.12479018` (`r27c`); Temurin JDK `17.0.20+8`; JUnit; Android instrumentation; Node `24.18.0`; npm `11.16.0`; TypeScript `7.0.2`; Vitest `4.1.10`; `ws 8.21.3` for the P0t-only Bridge harness.

---

## Global execution constraints

These constraints apply to every task and are not deferred acceptance criteria:

- Work from `/home/djbd/项目/open-android-intelligence`. Preserve unrelated user changes. Never stage `third_party/tailscale` or mutate/switch its worktree.
- Use one implementation commit per task. Every commit message must be Chinese and follow the repository convention shown in each task.
- Red-green-refactor is mandatory: add the named failing test, run it and record the expected failure, make only the smallest implementation change, rerun the focused test, then run the stated regression command.
- No build or test script may download dependencies. A controller-only bootstrap command may download to `.toolchains/downloads`, verify a lock digest, and then populate the exact pinned location. Normal `build`, `verify`, Gradle `preBuild`, and evidence commands run offline.
- Do not trust ambient `go`, `gomobile`, `ANDROID_NDK_HOME`, or Java. Resolve every executable from the lock and reject path/version mismatches. In particular, do not use the currently installed custom Tailscale Go toolchain or the misnamed NDK directory.
- Never pass an auth key, enrollment ticket, node state, runtime private JWK, or reusable endpoint credential in a command-line argument, environment variable, log, evidence file, Gradle property, or Git-tracked fixture. Secret runtime inputs enter through standard input or an app-private `run-as` file and are deleted after consumption. The repository's pre-existing, publicly known `protocol/test-only/keys/*-private.jwk.json` vectors are noncredential test material and may be used only inside the P0t harness/tests; they never enter Android or evidence.
- Do not expose generic `Dial`, `Listen`, `LocalClient`, `HTTPClient`, socket, route, DNS, proxy, fd, TUN, or LocalAPI APIs outside Go package internals. Kotlin imports generated `tsnetbridge.*` classes in exactly one source file.
- Do not add `VpnService`, a system TUN, Android proxy, public-Internet transport fallback, ordinary HTTPS fallback, a foreground keepalive service, an unbounded wake lock, or an Android/product listener. The Tailnet-only P0t Bridge harness is the sole listener in this plan and is never packaged into the product. A transport failure must remain closed.
- A source-only test, fake native boundary, emulator-only run, skipped matrix row, manually narrated observation, or command whose exit code was ignored can never produce P0t `PASS`.
- If a required external asset is unavailable (official source object, SDK image, physical device, Tailnet policy, P0t Bridge, or five-minute auth key), stop at that gate and write `BLOCKED` with the exact missing prerequisite. Do not weaken the matrix or mark the dependency row locked.

### Immutable input values

| Input | Exact accepted value |
| --- | --- |
| Tailscale upstream | `https://github.com/tailscale/tailscale.git` |
| release / tag object / commit | `v1.98.10` / `0ee734d3089846b27bc6ebcddd3d6ee5ec13e04d` / `36550d57f4a4055246ef7412f4e650a012a465f1` |
| `VERSION.txt` / module / Go directive | `1.98.10` / `tailscale.com` / `1.26.5` |
| stock Go archive | `go1.26.5.linux-amd64.tar.gz`, SHA-256 `5c2c3b16caefa1d968a94c1daca04a7ca301a496d9b086e17ad77bb81393f053` |
| gomobile and gobind | `golang.org/x/mobile@v0.0.0-20240806205939-81131f6468ab`, module sum `h1:KONOFF8Uy3b60HEzOsGnNghORNhY4ImyOx0PGm73K9k=` |
| Android NDK | `android-ndk-r27c-linux.zip`, revision `27.2.12479018`, SHA-256 `59c2f6dc96743b5daf5d1626684640b20a6bd2b1d85b13156b90333741bad5cc` |
| Android/JVM build | gomobile API 34, compile SDK 35, AGP 8.9.2, Gradle 8.12, Kotlin 2.1.20, Temurin 17.0.20+8 |
| ABI | `android/arm64` and `android/amd64`, yielding only `arm64-v8a` and `x86_64` |
| native alignment | every native ELF LOAD segment has `p_align >= 16384` |
| AAR size | normalized dual-ABI artifact `<= 80 MiB` |
| wire frame | one binary WSS message, `1..262144` bytes inclusive |

The NDK archive digest above must be rechecked against the downloaded bytes during Task 2 before it is treated as verified provenance. A mismatch is a supply-chain failure, not permission to update the lock opportunistically.

### Fixed runtime limits and storage identifiers

- native state blob: at most `4,194,304` bytes before encryption;
- first-enrollment `Up` context: 45 seconds; warm-state `Up` context: 30 seconds;
- TCP/TLS/WSS establishment: 15 seconds; backend path ping: 10 seconds;
- ordinary frame send/receive: 30 seconds; close/stop: 5 seconds;
- direct 64 MiB workload: 120 seconds overall; relay workload: 600 seconds overall;
- Android Keystore aliases: `open_android_intelligence_tailnet_state_v1` and `open_android_intelligence_tailnet_generation_v1`;
- Android no-backup files: `tailnet/node-state-v1.aesgcm`, `tailnet/connection-generation-v1.aesgcm`, and the pairing subsystem's separately owned sanitized reconnect bundle;
- forced-Doze observation: 15 minutes; Wi-Fi/cellular switch reconnection: at most 15 seconds after the system reports the new validated network.

Timeout is not evidence of meeting a performance budget: the stricter p95 and throughput thresholds remain the P0t pass criteria.

### Runtime budgets

The evidence validator must fail when any of these is absent or exceeds its limit:

| Metric | Hard gate |
| --- | --- |
| per-ABI release APK installed-size increment | `<= 40 MiB` |
| warm-state Wi-Fi online p95 over 10 runs | `<= 15 s` |
| first enrollment online p95 over 10 runs, excluding human approval | `<= 30 s` |
| screen-off idle RSS p95 over 30 minutes | `<= 160 MiB` |
| screen-off steady CPU, 30-minute mean | `<= 1%` of one core |
| idle wakeups | `<= 12/hour` |
| screen-off battery drop | `<= 6 percentage points` over 4 hours |
| direct 64 MiB canonical payload | `>= 10 Mbit/s`, byte-exact |
| relay 64 MiB canonical payload | completes byte-exact; throughput recorded |

## Gate sequence

1. **Gate A — artifact:** Tasks 1–6 end with two clean builds producing the same normalized AAR SHA-256 and a passing independent verifier. Nothing in Gradle consumes the AAR before this gate passes.
2. **Gate B — Android integration:** Tasks 7–10 end with a real native adapter, encrypted no-backup state, backend-reported path, sealed production factory, no main-source fake, and passing JVM/static/Gradle tests. Device claims remain blocked.
3. **Gate C — P0t:** Tasks 11–14 end with real Tailnet connect/control traffic, the complete device/network/resource matrix, secret scans, machine-validated evidence, controller review, and only then the `MVP-DEP-TSNET` row becoming `locked`.

## File responsibility map

### Supply chain and native wrapper

- Create `apps/android/tailnet-core/native/tsnetbridge/tsnet-aar.lock.json`: the single machine-readable immutable-input lock.
- Create `apps/android/tailnet-core/native/tsnetbridge/go.mod.template`: wrapper dependencies with a staging-relative `replace tailscale.com` directive.
- Create `apps/android/tailnet-core/native/tsnetbridge/{bundle,state_store,node,channel,path,errors}.go`: the complete gomobile-visible wrapper and its private helpers.
- Create matching `*_test.go` files beside each native concern; add `cmd/enrollment-bundle/main.go` for stdin-only P0t provisioning.
- Create `apps/android/tailnet-core/native/tsnetbridge/testdata/verified-binding-v1.{json,bin}` as the shared Go/Kotlin binding-codec golden vector.
- Replace `apps/android/tailnet-core/tools/verify-tsnet-aar-inputs.sh`; create `bootstrap-tsnet-toolchain.sh`, `stage-tsnet-source.sh`, `run-tsnet-go-tests.sh`, `build-tsnet-aar.sh`, `normalize-aar.py`, `verify-tsnet-aar.py`, and their host-side tests.
- Create the four tracked outputs in `apps/android/tailnet-core/libs/`: the normalized AAR, `.sha256`, `.provenance.json`, and `.sbom.json`.
- Create `apps/android/tailnet-core/native/tsnetbridge/THIRD_PARTY_NOTICES.md` from verified module/license inventory.

### Android integration and composition

- Modify `apps/android/tailnet-core/build.gradle.kts` to consume exactly the tracked AAR and run the verifier before compilation/check.
- Replace the untyped boundary in `TailscaleUserspaceCore.kt`; create `AndroidTsnetBinding.kt`, `NativeEnrollmentSource.kt`, `AndroidKeystoreTailnetState.kt`, and focused tests.
- Modify `TransportContracts.kt`, `TsnetPairedBridgeTransport.kt`, and `PairedBridgeSessionCoordinator.kt` so path is backend-derived and lifecycle failure is closed.
- Create `ProductionTailnetTransportFactory.kt` and `ProductionPairedBridgeTransport.kt` in the transport module.
- Move `FakeUserspaceTransport.kt` from `src/main` to `src/testFixtures`; update Gradle test-fixture dependencies and all consumers.
- Modify `OpenAndroidIntelligenceApplication.kt` so its registry accepts only the opaque production transport returned by the production factory.
- Strengthen `apps/android/tools/test_transport_boundary.py` and `apps/android/gradle/mvp-forbidden-surfaces.gradle.kts` to inspect Kotlin, generated AAR/API/ELF, manifests, APKs, and runtime classpaths.

### P0t harness and evidence

- Create `p0t/bridge-harness/{package.json,tsconfig.json,src/server.ts,src/fixture.ts,test/server.test.ts}` and add the exact `ws` lock to root `package-lock.json`.
- Create `p0t/bridge-harness/run-server.sh` to open controller-owned TLS material on inherited file descriptors without putting key bytes in arguments or environment.
- Create `apps/android/transport/src/androidTest/kotlin/com/openandroidintelligence/transport/p0t/` tests for native load, connect/control, restore, path, and lifecycle.
- Create `p0t/device/run-p0t.sh`, `p0t/device/provision-run-as.sh`, `p0t/device/network-scenarios.sh`, and `p0t/device/collect-evidence.py`.
- Create `p0t/device/build-input-stream.py` to frame provisioning data entirely in memory and stream it directly to `adb run-as`.
- Create `p0t/evidence/schema/p0t-evidence.schema.json` and `p0t/evidence/test_collect_evidence.py`.
- Store a run under `docs/mvp/evidence/p0t/$P0T_RUN_ID/`, where `P0T_RUN_ID` is generated as UTC `YYYYMMDDTHHMMSSZ`; tracked files contain only redacted summaries and digests.
- Update `docs/mvp/p0t-mvp-evidence.md`, `docs/mvp/mvp-dependency-lock.md`, `mvp-contract/tools/mvp-readiness.ts`, and the dependency-lock tests only after Gate C has a complete PASS inventory.

---

## Task 1: Lock and stage the exact Tailscale source without touching its worktree

**Files:**

- Create: `apps/android/tailnet-core/native/tsnetbridge/tsnet-aar.lock.json`
- Create: `apps/android/tailnet-core/tools/stage-tsnet-source.sh`
- Replace: `apps/android/tailnet-core/tools/verify-tsnet-aar-inputs.sh`
- Replace: `apps/android/tailnet-core/tools/test_verify_tsnet_aar_inputs.sh`
- Create: `apps/android/tailnet-core/tools/test_stage_tsnet_source.sh`
- Modify: `.gitignore`

- [ ] **Step 1: Write failing source-lock tests**

The tests must construct tiny temporary Git repositories and assert all of these independently:

- missing checkout, wrong origin URL, dirty checkout, missing tag object, lightweight/wrong tag object, wrong peeled commit, wrong `VERSION.txt`, wrong module, and wrong Go directive exit `2` with `TSNET_AAR_INPUTS_BLOCKED`;
- a valid checkout whose current `HEAD` is *not* the pinned commit remains unchanged after staging;
- staging is produced only by `git archive 36550d57f4a4055246ef7412f4e650a012a465f1`, contains no `.git`, and writes a SHA-256 inventory sorted by bytewise path;
- `third_party/tailscale` is ignored by the outer repository and never copied into a tracked path;
- missing commit object prints this exact controller action without executing it:

```text
git -C third_party/tailscale fetch --filter=blob:none origin refs/tags/v1.98.10:refs/tags/v1.98.10
```

Run:

```bash
bash apps/android/tailnet-core/tools/test_verify_tsnet_aar_inputs.sh
bash apps/android/tailnet-core/tools/test_stage_tsnet_source.sh
```

Expected red state: the old verifier accepts `HEAD` instead of an immutable tag/archive flow, and `stage-tsnet-source.sh` does not exist.

- [ ] **Step 2: Add the canonical JSON lock**

Use closed keys for upstream/tag/tag object/commit/source files, Go/gomobile/NDK/JDK/SDK/ABI/build flags, dependency versions/sums, and expected output names. Include the exact values from **Immutable input values** and reject unknown or missing lock keys in every script. The lock records verified archive hashes; it never records ambient paths.

- [ ] **Step 3: Implement fail-closed source verification and staging**

`verify-tsnet-aar-inputs.sh --source third_party/tailscale --lock apps/android/tailnet-core/native/tsnetbridge/tsnet-aar.lock.json` must:

1. require a clean Git worktree and exact official normalized origin;
2. use `git cat-file` to verify the pinned annotated tag object and `^{commit}` without checking it out;
3. use `git show COMMIT:VERSION.txt` and `git show COMMIT:go.mod` for content checks;
4. reject shallow/missing objects with the controller fetch instruction;
5. avoid the network and never run `checkout`, `switch`, `reset`, `clean`, `submodule`, or `fetch`.

`stage-tsnet-source.sh --source third_party/tailscale --output "$P0T_STAGE_DIR"` must require an existing empty output directory under a caller-created `mktemp -d`, extract `git archive` there, make the staged tree read-only after wrapper assembly, and emit `source-manifest.sha256` plus machine-readable source metadata.

- [ ] **Step 4: Run focused tests and verify the real checkout non-destructively**

```bash
bash apps/android/tailnet-core/tools/test_verify_tsnet_aar_inputs.sh
bash apps/android/tailnet-core/tools/test_stage_tsnet_source.sh
before="$(git -C third_party/tailscale rev-parse HEAD):$(git -C third_party/tailscale status --porcelain=v1 | sha256sum)"
bash apps/android/tailnet-core/tools/verify-tsnet-aar-inputs.sh \
  --source third_party/tailscale \
  --lock apps/android/tailnet-core/native/tsnetbridge/tsnet-aar.lock.json
after="$(git -C third_party/tailscale rev-parse HEAD):$(git -C third_party/tailscale status --porcelain=v1 | sha256sum)"
test "$before" = "$after"
```

Expected: host tests pass. The real-checkout command either prints `TSNET_AAR_INPUTS_READY` for the pinned object or exits `2` with the exact fetch prerequisite; either result leaves `HEAD` and status unchanged. Do not continue Gate A until the pinned object is locally present.

- [ ] **Step 5: Commit**

```bash
git add .gitignore \
  apps/android/tailnet-core/native/tsnetbridge/tsnet-aar.lock.json \
  apps/android/tailnet-core/tools/verify-tsnet-aar-inputs.sh \
  apps/android/tailnet-core/tools/stage-tsnet-source.sh \
  apps/android/tailnet-core/tools/test_verify_tsnet_aar_inputs.sh \
  apps/android/tailnet-core/tools/test_stage_tsnet_source.sh
git commit -m "新增: 锁定 Tailscale AAR 源码输入"
```

## Task 2: Bootstrap and verify only the pinned Go, gomobile, NDK, SDK, and JDK

**Files:**

- Create: `apps/android/tailnet-core/tools/bootstrap-tsnet-toolchain.sh`
- Create: `apps/android/tailnet-core/tools/verify-tsnet-toolchain.sh`
- Create: `apps/android/tailnet-core/tools/test_verify_tsnet_toolchain.sh`
- Modify: `apps/android/tailnet-core/native/tsnetbridge/tsnet-aar.lock.json` mechanically with verified Go/gomobile/NDK/JDK/SDK binary or directory-manifest digests; if an expected archive digest disagrees, stop for controller review instead of rewriting it
- Modify: `.gitignore`

- [ ] **Step 1: Write failing hermetic-toolchain tests**

Use temporary fake executables and package metadata to prove rejection of custom Go version suffixes, Go `1.25`, wrong gomobile module revision, unexpected module sum, an NDK directory whose basename differs from `Pkg.Revision`, missing API 34/35 platforms, JDK other than Temurin `17.0.20+8`, and any executable resolved from `PATH` instead of the lock-derived directory.

```bash
bash apps/android/tailnet-core/tools/test_verify_tsnet_toolchain.sh
```

Expected red state: verifier/bootstrap scripts do not exist.

- [ ] **Step 2: Implement a controller-only download/bootstrap path**

`bootstrap-tsnet-toolchain.sh --download` may fetch the stock Go archive only from `https://go.dev/dl/go1.26.5.linux-amd64.tar.gz`, the NDK only from `https://dl.google.com/android/repository/android-ndk-r27c-linux.zip`, and Go modules only through `https://proxy.golang.org` with `sum.golang.org` verification. It stores archives under `.toolchains/downloads/`, verifies the locked digest/module sums before use, and installs atomically into versioned paths:

```text
.toolchains/go-1.26.5/
.toolchains/gomobile-v0.0.0-20240806205939-81131f6468ab/bin/
.toolchains/android-sdk/ndk/27.2.12479018/
```

It populates a dedicated module cache by running `go mod download all` against the archived pinned Tailscale `go.mod`/`go.sum`, installs gomobile/gobind with the pinned stock Go and that cache, records `go version -m` output, and never changes global Go/SDK state. The existing `.toolchains/jdk-17.0.20+8/` and Android SDK platforms are controller-provided immutable inputs: the bootstrap verifies their version plus directory/binary manifests but does not download or repair them. Downloads are ignored by Git. If `.toolchains/android-sdk/ndk/27.0.12077973/source.properties` reports `27.2.12479018`, the script must reject it and require a correctly named atomic install; no symlink or path fallback is accepted.

- [ ] **Step 3: Implement the offline verifier**

`verify-tsnet-toolchain.sh --lock apps/android/tailnet-core/native/tsnetbridge/tsnet-aar.lock.json --emit-env "$P0T_TOOLCHAIN_ENV"` reads exact paths from repository-relative conventions, hashes archives and binaries, validates `source.properties`, `java -version`, `go version`, `go version -m`, SDK packages, and emits a shell file containing only nonsecret absolute tool paths and cache directories. The emitted file is run-local and ignored.

The bootstrap writes a candidate resolved-digest section atomically; the offline verifier recomputes it independently before the candidate replaces the lock. Path names, mtimes, ownership, and absolute installation roots are excluded from directory manifests; sorted relative paths, file modes, sizes, and content hashes are included. An expected archive/module hash mismatch never triggers automatic lock mutation.

- [ ] **Step 4: Run tests, bootstrap if controller assets are available, then verify offline**

```bash
bash apps/android/tailnet-core/tools/test_verify_tsnet_toolchain.sh
bash apps/android/tailnet-core/tools/bootstrap-tsnet-toolchain.sh --download
P0T_TOOLCHAIN_ENV="$(mktemp)"
bash apps/android/tailnet-core/tools/verify-tsnet-toolchain.sh \
  --lock apps/android/tailnet-core/native/tsnetbridge/tsnet-aar.lock.json \
  --emit-env "$P0T_TOOLCHAIN_ENV"
bash -n "$P0T_TOOLCHAIN_ENV"
```

Expected: tests pass and verifier prints `TSNET_TOOLCHAIN_READY`. If a download is unavailable, stop with `BLOCKED` and the exact locked asset name; do not use ambient tools.

- [ ] **Step 5: Commit**

```bash
git add .gitignore \
  apps/android/tailnet-core/native/tsnetbridge/tsnet-aar.lock.json \
  apps/android/tailnet-core/tools/bootstrap-tsnet-toolchain.sh \
  apps/android/tailnet-core/tools/verify-tsnet-toolchain.sh \
  apps/android/tailnet-core/tools/test_verify_tsnet_toolchain.sh
git commit -m "新增: 校验 tsnet Android 固定工具链"
```

## Task 3: Define and test the closed native bundle and callback-backed state store

**Files:**

- Create: `apps/android/tailnet-core/native/tsnetbridge/go.mod.template`
- Create: `apps/android/tailnet-core/native/tsnetbridge/bundle.go`
- Create: `apps/android/tailnet-core/native/tsnetbridge/bundle_test.go`
- Create: `apps/android/tailnet-core/native/tsnetbridge/state_store.go`
- Create: `apps/android/tailnet-core/native/tsnetbridge/state_store_test.go`
- Create: `apps/android/tailnet-core/native/tsnetbridge/errors.go`
- Create: `apps/android/tailnet-core/native/tsnetbridge/cmd/enrollment-bundle/main.go`
- Create: `apps/android/tailnet-core/tools/run-tsnet-go-tests.sh`

- [ ] **Step 1: Write bundle parser tests before implementation**

Freeze `NativeEnrollmentBundleV1` as binary magic `ALTSNET1`, one-byte version `1`, and unsigned big-endian 32-bit length-prefixed fields in this exact order: hostname, HTTPS control URL, auth-key bytes, pinned IPv4, pinned IPv6, MagicDNS/SNI, Bridge application-key fingerprint, ticket digest, device ID, pairing generation, policy revision, policy digest, expiry epoch seconds. Text is strict UTF-8; IPs use canonical `netip.Addr.String`; fingerprint/ticket/policy digests are canonical unpadded base64url encodings of exactly 32 bytes; unsigned integers are canonical decimal ASCII with no sign or leading zero. A zero-length address is allowed only when the other address family is valid. Auth-key bytes may be zero-length only for a warm start with valid restored state; all other fields are required.

Test exact success bytes plus truncation, trailing bytes, unknown version, length overflow, invalid UTF-8, non-HTTPS control URL, URL userinfo/query/fragment, expired attestation, invalid hostname/SNI, non-Tailnet IPv4, non-Tailscale ULA IPv6, malformed digests, zero generation, and a bundle that supplies port/path/proxy/route/DNS-like endpoint material.

- [ ] **Step 2: Write state-store tests before implementation**

Freeze the exported state blob as magic `ALSTATE1`, version `1`, sorted state-key entries with length prefixes, and a trailing SHA-256 over all preceding bytes. Reject an encoded blob over `4,194,304` bytes. Test deterministic ordering, corrupted digest, duplicate key, truncation, defensive copies, a synchronous callback on every `WriteState`, serialization of concurrent writes, callback failure propagation without memory mutation, restored multi-key reads, `ipn.ErrStateNotExist` for an absent key, and zeroization on close.

Run in a temporary staged module produced by Tasks 1–2:

```bash
bash apps/android/tailnet-core/tools/run-tsnet-go-tests.sh './...'
```

Expected red state: native files and the test runner do not yet exist.

- [ ] **Step 3: Implement only the codecs, state seam, and closed error codes**

Expose the gomobile-compatible interface:

```go
type StateSink interface {
	PersistState([]byte) error
}
```

The internal store implements `ipn.StateStore` with `ReadState(id ipn.StateKey)` and `WriteState(id ipn.StateKey, value []byte) error`. `WriteState` builds a prospective full blob, invokes `PersistState`, and commits in-memory state only when the callback returns nil. Errors exported to Kotlin are stable uppercase codes (`INVALID_BUNDLE`, `STATE_RESTORE_FAILED`, `STATE_PERSIST_FAILED`, `APPROVAL_REQUIRED`, `CONTROL_UNREACHABLE`, `NETWORK_BLOCKED`, `INVALID_BINDING`, `STALE_GENERATION`, `FRAME_INVALID`, `CHANNEL_CLOSED`) with no upstream/log/parser text.

The P0t CLI reads a JSON description and auth key as two length-delimited records from standard input, emits only the binary bundle on standard output, sends diagnostics to standard error using closed codes, and wipes its mutable auth-key buffers before exit. It is a test/provisioning tool, not an APK API.

- [ ] **Step 4: Run native tests and race tests**

```bash
bash apps/android/tailnet-core/tools/run-tsnet-go-tests.sh './...'
bash apps/android/tailnet-core/tools/run-tsnet-go-tests.sh --race './...'
```

Expected: all parser/store tests pass; `go test -race` reports no races.

- [ ] **Step 5: Commit**

```bash
git add apps/android/tailnet-core/native/tsnetbridge \
  apps/android/tailnet-core/tools/run-tsnet-go-tests.sh
git commit -m "新增: 定义 tsnet 闭合登记与状态边界"
```

## Task 4: Implement a real tsnet node lifecycle with credential cleanup

**Files:**

- Create: `apps/android/tailnet-core/native/tsnetbridge/node.go`
- Create: `apps/android/tailnet-core/native/tsnetbridge/node_test.go`
- Modify: `apps/android/tailnet-core/native/tsnetbridge/errors.go`
- Modify: `apps/android/tailnet-core/native/tsnetbridge/go.mod.template`

- [ ] **Step 1: Add failing lifecycle tests around narrow internal seams**

Tests must prove:

- `envknob.SetNoLogsNoSupport()` runs before server construction;
- `tsnet.Server` configuration fixes `RunWebClient=false`, `Ephemeral=false`, uses the callback state store, and installs code-only `Logf`/`UserLogf` sinks;
- `Start` calls `Up` once and never calls `Loopback`, `Listen`, or filesystem state;
- the input auth-key slice and reachable `Server.AuthKey` reference are cleared after `Up` success, error, timeout, and panic recovery;
- restored state is loaded before `Up`; a first enrollment cannot return until `Up` has produced at least one successful durable state callback, while a warm start may rely on the already durable restored blob if upstream performs no new write;
- approval, control-plane timeout, persistence failure, repeated `Stop`, concurrent `Stop`, and open-after-stop map to closed codes;
- a node can own at most one Bridge channel.

Use narrow constructors/function variables for unit tests; do not claim these fakes prove Tailscale integration.

```bash
bash apps/android/tailnet-core/tools/run-tsnet-go-tests.sh './...'
```

Expected red state: lifecycle tests fail because `Start`/`Node` do not exist.

- [ ] **Step 2: Implement the only exported node entrypoint**

```go
func Start(bootstrapBytes, restoredStateBytes []byte, sink StateSink) (*Node, error)
func (n *Node) Stop() error
```

Implementation order is: parse/copy bundle; disable log upload/support; restore callback store; configure `tsnet.Server`; call `Up` with the fixed first-enrollment or warm-state context; classify backend state; require durable state as defined above; clear all reachable enrollment material in a `defer`; return a node containing only pinned peer/binding facts and private tsnet handles. `Stop` fences/closes the active channel, closes the server once, clears state copies, and is idempotent.

Clearing means overwriting mutable byte buffers, clearing `Server.AuthKey`, releasing references, and never persisting/logging the credential. Because converting the key to a Go string can leave a garbage-collected runtime copy, tests and evidence must not claim forensic process-memory zeroization.

The log sinks may emit only timestamp + closed error/event code. They must not format arbitrary upstream arguments, peer addresses, auth URLs, node state, hostnames, tickets, keys, or control frames.

- [ ] **Step 3: Add static export/API tests**

Extend the native tests to parse Go exports. The allowed wrapper surface is exactly `Start`, `StateSink.PersistState`, `Node.OpenPairedBridge`, `Node.Path`, `Node.Stop`, `Channel.Send`, `Channel.Receive`, and `Channel.Close`. Reject public fields and any exported name containing `Dial`, `Listen`, `Local`, `HTTP`, `Socket`, `Route`, `DNS`, `Proxy`, `Tun`, `Server`, or `Client`. Task 6 applies the same allowlist independently to generated Java bytecode.

- [ ] **Step 4: Run focused, race, and source-forbidden scans**

```bash
bash apps/android/tailnet-core/tools/run-tsnet-go-tests.sh './...'
bash apps/android/tailnet-core/tools/run-tsnet-go-tests.sh --race './...'
python3 apps/android/tools/test_transport_boundary.py
```

Expected: native tests pass. The existing source scan may remain green because private Go internals are not an Android API; Task 7 will extend artifact inspection.

- [ ] **Step 5: Commit**

```bash
git add apps/android/tailnet-core/native/tsnetbridge
git commit -m "新增: 实现 tsnet 节点生命周期"
```

## Task 5: Open only the pinned Bridge WSS channel and report a real peer path

**Files:**

- Create: `apps/android/tailnet-core/native/tsnetbridge/channel.go`
- Create: `apps/android/tailnet-core/native/tsnetbridge/channel_test.go`
- Create: `apps/android/tailnet-core/native/tsnetbridge/path.go`
- Create: `apps/android/tailnet-core/native/tsnetbridge/path_test.go`
- Create: `apps/android/tailnet-core/native/tsnetbridge/testdata/verified-binding-v1.json`
- Create: `apps/android/tailnet-core/native/tsnetbridge/testdata/verified-binding-v1.bin`
- Modify: `apps/android/tailnet-core/native/tsnetbridge/bundle.go`
- Modify: `apps/android/tailnet-core/native/tsnetbridge/node.go`
- Modify: `apps/android/tailnet-core/native/tsnetbridge/errors.go`

- [ ] **Step 1: Freeze the verified binding bytes and write rejection tests**

Define `VerifiedPairingTransportBindingV1` as magic `ALBIND1`, version `1`, then unsigned big-endian 32-bit length-prefixed device ID, Bridge fingerprint, ticket digest, pairing generation, policy revision, and policy digest. It uses the same strict UTF-8, unpadded 32-byte base64url digest, and canonical decimal rules as `NativeEnrollmentBundleV1`. Generate one checked-in JSON/bin golden pair with only synthetic IDs and digests; Go tests parse it now and Task 8 consumes the identical bytes from Kotlin. Tests must reject malformed/trailing input and prove constant-time comparison for the fingerprint/ticket/policy digests plus exact comparison for canonical IDs and integers. A mismatched binding must fail before DNS, dial, TLS, or WSS code is invoked.

- [ ] **Step 2: Write failing endpoint and WSS tests**

With in-memory TLS/WSS fixtures and a narrow fake tsnet dial function, prove all of the following:

- the only request uses the bundle's pinned MagicDNS name with `wss`, TCP port 443, and `/v1/control`; it has no query, fragment, userinfo, custom header credential, proxy, or redirect;
- the tsnet dial resolves the pinned MagicDNS name, and the resulting remote address is one of the bundle's pinned Tailnet IPv4/IPv6 values on port 443 before TLS proceeds;
- TLS uses the system trust store and the pinned MagicDNS value as `ServerName`; certificate/SNI errors remain errors and `InsecureSkipVerify` is never enabled;
- redirects, a system-proxy environment, DNS-to-unpinned-IP, a public IP, wrong port, text frame, empty frame, `262145`-byte frame, oversized fragment reassembly, concurrent close, and send/receive after close fail closed;
- each accepted `1..262144`-byte binary WSS message is returned byte-for-byte as one channel receive; the native layer does not parse, retry, reorder, resign, or log it;
- one node rejects a second simultaneous channel and releases the slot after idempotent close.

```bash
bash apps/android/tailnet-core/tools/run-tsnet-go-tests.sh './...'
```

Expected red state: `OpenPairedBridge` and `Channel` are absent.

- [ ] **Step 3: Implement the internally fixed HTTP/TLS/WSS stack**

`Node.OpenPairedBridge(bindingBytes)` builds an `http.Client` with `Proxy=nil`, redirect rejection, finite handshake/response timeouts, and an `http.Transport` whose dial hook accepts only the internally generated `MAGIC_DNS_NAME:443` authority, where `MAGIC_DNS_NAME` is read from the validated bundle rather than caller input. The hook calls the private `tsnet.Server.Dial`, checks `RemoteAddr` against the pinned addresses, and returns the connection for normal certificate-verifying TLS with the pinned SNI. Use `coder/websocket` only inside this file, require binary messages, set the read limit to `262144`, and serialize writers/readers/close with bounded contexts.

The exported surface is exactly:

```go
func (n *Node) OpenPairedBridge(bindingBytes []byte) (*Channel, error)
func (c *Channel) Send(canonicalWireBytes []byte) error
func (c *Channel) Receive() ([]byte, error)
func (c *Channel) Close() error
```

- [ ] **Step 4: Write path-mapping tests, then implement backend-only path reporting**

After a channel is open, `Node.Path(bindingBytes)` validates the same binding and calls only the in-memory `Server.LocalClient().Ping` for the pinned peer with disco ping semantics and the fixed 10-second context. Map a non-empty direct endpoint to `DIRECT`, an explicit peer-relay or DERP result to `RELAY`, and timeout/error/no verifiable peer route to `OFFLINE`. Direct wins only when the backend explicitly reports a direct endpoint and no relay field; RTT, WSS success, socket type, request latency, and network type are never path signals. Node approval remains a `Start` error (`APPROVAL_REQUIRED`), not a path value.

```go
func (n *Node) Path(bindingBytes []byte) (string, error)
```

Test direct, DERP, no-route, timeout, closed channel, wrong peer, stale binding, and contradictory results. A contradictory direct+DERP result fails closed to `OFFLINE` and emits a code-only diagnostic.

- [ ] **Step 5: Run all native checks**

```bash
bash apps/android/tailnet-core/tools/run-tsnet-go-tests.sh './...'
bash apps/android/tailnet-core/tools/run-tsnet-go-tests.sh --race './...'
```

Expected: all native tests and race tests pass with no generic networking symbol added to the exported wrapper API.

- [ ] **Step 6: Commit**

```bash
git add apps/android/tailnet-core/native/tsnetbridge
git commit -m "新增: 实现配对 Bridge 的 tsnet WSS 通道"
```

## Task 6: Build twice, normalize, inspect, and commit the locked dual-ABI AAR

**Files:**

- Create: `apps/android/tailnet-core/tools/build-tsnet-aar.sh`
- Create: `apps/android/tailnet-core/tools/normalize-aar.py`
- Create: `apps/android/tailnet-core/tools/verify-tsnet-aar.py`
- Create: `apps/android/tailnet-core/tools/generate-tsnet-sbom.py`
- Create: `apps/android/tailnet-core/tools/test_build_tsnet_aar.sh`
- Create: `apps/android/tailnet-core/tools/test_normalize_aar.py`
- Create: `apps/android/tailnet-core/tools/test_verify_tsnet_aar.py`
- Create: `apps/android/tailnet-core/native/tsnetbridge/THIRD_PARTY_NOTICES.md`
- Create: `apps/android/tailnet-core/libs/tsnet-android-1.98.10.aar`
- Create: `apps/android/tailnet-core/libs/tsnet-android-1.98.10.aar.sha256`
- Create: `apps/android/tailnet-core/libs/tsnet-android-1.98.10.provenance.json`
- Create: `apps/android/tailnet-core/libs/tsnet-android-1.98.10.sbom.json`

- [ ] **Step 1: Write hostile artifact-verifier fixtures first**

Programmatically build tiny AAR/ELF/ZIP fixtures and assert rejection of: a missing file; incorrect sidecar digest; zip-slip/duplicate/unsorted entries; non-normalized timestamps/modes/extras; unexpected ABI; missing ABI; native LOAD alignment below `0x4000`; min SDK other than 34; a generated class/method or JNI export outside the closed API; an Android manifest/component that declares a VPN/TUN/proxy/listener surface; unexpected native dependency; source/toolchain/provenance mismatch; SBOM module mismatch; absent/unclassified license; AAR over 80 MiB; and two clean-build digests that differ.

```bash
python3 -m unittest \
  apps/android/tailnet-core/tools/test_normalize_aar.py \
  apps/android/tailnet-core/tools/test_verify_tsnet_aar.py
bash apps/android/tailnet-core/tools/test_build_tsnet_aar.sh
```

Expected red state: build/normalizer/verifier programs are absent.

- [ ] **Step 2: Implement a hermetic staged build**

For each build, create independent temporary source, wrapper, Go cache, module cache, gomobile cache, Android home, and output directories. Copy the project wrapper into staging, render `go.mod.template` with a relative `replace tailscale.com =>` path to the read-only archived source, and run `go mod verify` with `GOPROXY=off` after the controller bootstrap has populated the pinned module cache. Fix `HOME`, `TZ=UTC`, `LC_ALL=C`, `LANG=C`, `CGO_ENABLED=1`, `SOURCE_DATE_EPOCH` to the pinned source commit timestamp, and remove absolute staging paths with `-trimpath`.

Run the lock-equivalent command using only resolved pinned binaries:

```text
gomobile bind
  -target=android/arm64,android/amd64
  -androidapi=34
  -trimpath
  -tags=ts_omit_cachenetmap
  -ldflags="-buildid= -linkmode=external -extldflags=-Wl,-z,max-page-size=16384"
  -o tsnet-android-1.98.10.raw.aar
  ./tsnetbridge
```

The shell script must pass `-ldflags` as one argument, record the exact argument vector in provenance, and reject any ambient network access or untracked module resolution.

- [ ] **Step 3: Normalize nested ZIPs and generate supply-chain records**

`normalize-aar.py` must normalize `classes.jar` first, then the AAR: bytewise entry ordering, one entry per name, fixed DOS timestamp derived from `SOURCE_DATE_EPOCH`, fixed file modes, no comments/extra fields, stable compression settings, and no absolute paths. It writes atomically and never edits the raw file in place.

Generate CycloneDX JSON listing every `go list -m -json all` module with version and `h1` sum, source manifest digest, Go/NDK/JDK/gomobile binary digests, exact command, SOURCE_DATE_EPOCH, both clean-build raw/normalized hashes, and final AAR hash. `THIRD_PARTY_NOTICES.md` must enumerate every shipped Go module and the detected SPDX license plus license-file digest; unknown or incompatible licenses fail the build for controller review.

- [ ] **Step 4: Independently verify the real artifact**

The verifier must use `zipinfo`, `javap`, Android manifest tooling, `readelf -h/-l/-d/-Ws`, `go version -m` where applicable, and its own ZIP parser. It checks exactly two JNI paths, min SDK 34, `p_align >= 16384` for every LOAD segment, the closed Java/JNI export API, no Android component/permission/API surface for TUN/VPN/proxy/listening or generic caller-controlled networking, native `DT_NEEDED` restricted to Android system libraries, size `<= 80 MiB`, and exact cross-links among lock/SBOM/notices/provenance/digest. Private Go symbols required to implement tsnet are not treated as exported Android API; only reachability through generated Java/JNI exports is an API-boundary failure.

```bash
bash apps/android/tailnet-core/tools/build-tsnet-aar.sh --clean-builds 2
python3 apps/android/tailnet-core/tools/verify-tsnet-aar.py \
  --lock apps/android/tailnet-core/native/tsnetbridge/tsnet-aar.lock.json \
  --aar apps/android/tailnet-core/libs/tsnet-android-1.98.10.aar \
  --provenance apps/android/tailnet-core/libs/tsnet-android-1.98.10.provenance.json \
  --sbom apps/android/tailnet-core/libs/tsnet-android-1.98.10.sbom.json \
  --notices apps/android/tailnet-core/native/tsnetbridge/THIRD_PARTY_NOTICES.md
```

Expected: both normalized build hashes are identical; verifier prints `TSNET_AAR_VERIFIED` and the final SHA-256. Any discrepancy stops Gate A.

- [ ] **Step 5: Re-run host regressions and commit the artifact with provenance**

```bash
python3 -m unittest discover -s apps/android/tailnet-core/tools -p 'test_*.py'
for test_script in apps/android/tailnet-core/tools/test_*.sh; do bash "$test_script"; done
git add apps/android/tailnet-core/native/tsnetbridge/THIRD_PARTY_NOTICES.md \
  apps/android/tailnet-core/tools \
  apps/android/tailnet-core/libs/tsnet-android-1.98.10.aar \
  apps/android/tailnet-core/libs/tsnet-android-1.98.10.aar.sha256 \
  apps/android/tailnet-core/libs/tsnet-android-1.98.10.provenance.json \
  apps/android/tailnet-core/libs/tsnet-android-1.98.10.sbom.json
git commit -m "新增: 构建并锁定 Tailscale Android AAR"
```

## Task 7: Make Gradle verify and consume exactly the locked AAR

**Files:**

- Modify: `apps/android/tailnet-core/build.gradle.kts`
- Modify: `apps/android/build.gradle.kts`
- Modify: `apps/android/gradle/mvp-forbidden-surfaces.gradle.kts`
- Modify: `apps/android/tools/test_transport_boundary.py`
- Create: `apps/android/tailnet-core/src/test/kotlin/com/openandroidintelligence/tailnet/core/TsnetArtifactContractTest.kt`

- [ ] **Step 1: Write failing Gradle/static contract tests**

Tests must require a single explicit `implementation(files("libs/tsnet-android-1.98.10.aar"))`, reject `flatDir`, Maven coordinates/dynamic versions, debug/release substitutions, verifier bypass properties, and compilation when the AAR or any sidecar is absent/tampered. The root no-VPN gate must invoke the independent AAR verifier and scan generated Java API, ELF symbols, merged manifests, packaged APKs, and runtime classpaths in addition to source.

```bash
python3 apps/android/tools/test_transport_boundary.py
cd apps/android && ./gradlew --offline --no-daemon :tailnet-core:check
```

Expected red state: static test reports no locked Gradle AAR dependency/verifier task.

- [ ] **Step 2: Add the artifact verification task before every relevant build**

Register one always-run `verifyTsnetAar` task whose explicit inputs are the lock, AAR, SHA-256 sidecar, provenance, SBOM, and notices files and which deliberately declares no output. It invokes `verify-tsnet-aar.py` using the repository Python, with working directory fixed to the repository root. Make every `preBuild`, `check`, `lint`, and publication/bundle task in `tailnet-core`, plus root `noVpnSurfaceCheck`, depend on it. No property may skip it.

- [ ] **Step 3: Add the exact local dependency and closed runtime test**

Consume only the tracked AAR via `implementation(files("libs/tsnet-android-1.98.10.aar"))`. `TsnetArtifactContractTest` loads the expected generated classes by name, asserts the closed method set, and asserts forbidden generated types are absent. Keep all direct generated imports out of this test so Task 8 can enforce the one-file import boundary.

- [ ] **Step 4: Prove tamper failure and clean success**

Run the verifier and Gradle checks on the real files, then copy the artifact set to a temporary fixture, flip one AAR byte, and assert the standalone verifier exits nonzero. Do not modify the tracked AAR for the negative test.

```bash
python3 apps/android/tools/test_transport_boundary.py
cd apps/android
./gradlew --offline --no-daemon :tailnet-core:check check
```

Expected: all checks pass and Gradle output includes `verifyTsnetAar` before compilation/check.

- [ ] **Step 5: Commit**

```bash
git add apps/android/build.gradle.kts \
  apps/android/gradle/mvp-forbidden-surfaces.gradle.kts \
  apps/android/tools/test_transport_boundary.py \
  apps/android/tailnet-core/build.gradle.kts \
  apps/android/tailnet-core/src/test/kotlin/com/openandroidintelligence/tailnet/core/TsnetArtifactContractTest.kt
git commit -m "新增: 将锁定 AAR 接入 tailnet-core"
```

## Task 8: Replace the conceptual Kotlin seam with the real generated binding adapter

**Files:**

- Modify: `apps/android/core-model/src/main/kotlin/com/openandroidintelligence/core/model/TransportContracts.kt`
- Modify: `apps/android/tailnet-core/src/main/kotlin/com/openandroidintelligence/tailnet/core/VerifiedPairingTransportBinding.kt`
- Replace: `apps/android/tailnet-core/src/main/kotlin/com/openandroidintelligence/tailnet/core/TailscaleUserspaceCore.kt`
- Create: `apps/android/tailnet-core/src/main/kotlin/com/openandroidintelligence/tailnet/core/NativeEnrollmentSource.kt`
- Create: `apps/android/tailnet-core/src/main/kotlin/com/openandroidintelligence/tailnet/core/AndroidTsnetBinding.kt`
- Create: `apps/android/tailnet-core/src/test/kotlin/com/openandroidintelligence/tailnet/core/TailscaleUserspaceCoreTest.kt`
- Create: `apps/android/tailnet-core/src/test/kotlin/com/openandroidintelligence/tailnet/core/AndroidTsnetBindingBoundaryTest.kt`
- Modify: `apps/android/transport/src/main/kotlin/com/openandroidintelligence/transport/PairedBridgeSessionCoordinator.kt`
- Modify: `apps/android/transport/src/test/kotlin/com/openandroidintelligence/transport/TransportBoundaryTest.kt`
- Modify: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/OpenAndroidIntelligenceApplication.kt`
- Modify: `apps/android/notification-collector/src/test/kotlin/com/openandroidintelligence/notifications/NotificationBridgeDispatcherTest.kt`
- Modify: `apps/android/sms-collector/src/test/kotlin/com/openandroidintelligence/sms/SmsAutoSyncCoordinatorTest.kt`
- Modify: `apps/android/tools/test_transport_boundary.py`

- [ ] **Step 1: Write failing typed-boundary and lifecycle tests**

Replace every `Any`-shaped test seam with test doubles for `NativeNodeHandle` and `NativeChannelHandle`. Tests must prove: acquisition of a validated enrollment bundle; restored state copied into native start; a first enrollment has a synchronous durable state callback before `start` returns while warm restore may reuse the durable blob; enrollment bytes wiped and source cleared in success/error/cancellation paths; binding bytes match the frozen `ALBIND1` codec; byte-array copies at every boundary; one node/one channel; serialized open/path/close/stop; idempotent close/stop; stable native-code-to-`TransportFailure` mapping; and no upstream exception text escapes.

Modify `PolicyAttestation` to carry the pairing subsystem's verified `relayAllowed` decision and modify `VerifiedPairingTransportBinding` so it carries that decision plus `enrollmentTicketDigest` and the verified policy digest, never plaintext ticket material. The Boolean is local policy metadata bound to the attestation digest; it is intentionally absent from `ALBIND1` because native only compares the digest/revision. Update every existing Android test fixture to use syntactically valid 32-byte unpadded-base64url Bridge fingerprints/ticket/policy digests and an explicit relay decision. Update factory tests to reject malformed digest input and to preserve device ID, Bridge fingerprint, pairing generation, policy revision, policy digest, ticket digest, and relay decision exactly.

```bash
cd apps/android
./gradlew --offline --no-daemon :core-model:testDebugUnitTest :tailnet-core:testDebugUnitTest
```

Expected red state: old interfaces still accept `Any`, node identity strings, and a caller-supplied state store.

- [ ] **Step 2: Define the final typed Kotlin core contract**

The resulting shape is semantically equivalent to:

```kotlin
interface TailscaleUserspaceCore {
    suspend fun start()
    suspend fun openPairedBridge(binding: VerifiedPairingTransportBinding): UserspaceBridgeChannel
    suspend fun currentPath(binding: VerifiedPairingTransportBinding): TransportPath
    suspend fun stop()
}

interface LibTailscaleBinding {
    fun startNode(
        bootstrap: ByteArray,
        restoredState: ByteArray?,
        stateSink: NativeStateSink,
    ): NativeNodeHandle
}

interface NativeNodeHandle {
    fun openPairedBridge(bindingBytes: ByteArray): NativeChannelHandle
    fun path(bindingBytes: ByteArray): String
    fun stop()
}

interface NativeChannelHandle {
    fun send(canonicalWire: ByteArray)
    fun receive(): ByteArray
    fun close()
}
```

`NativeEnrollmentSource.acquire(restoredStatePresent)` returns a closeable lease containing either a first-enrollment bootstrap or, when durable node state already exists, the separately persisted sanitized reconnect bootstrap whose auth-key field is empty. Closing the lease wipes the first-enrollment bytes and invokes the pairing subsystem's credential-clear callback. `TsnetLibTailscaleCore` owns `LibTailscaleBinding`, this source, and `NoBackupTailnetStateStore`; callers no longer pass a hostname/state store into `start`. A test-local one-shot source supplies deterministic bytes in unit tests. Production receives only a source from the pairing subsystem; this plan does not invent the out-of-scope pairing UI or credential issuer.

Mechanically update `PairedBridgeSessionCoordinator` to call `core.start()` and update its test doubles to implement `currentPath`; leave removal of the hard-coded transport path to Task 10's dedicated behavior tests. Update the app's binding identity comparison to include ticket digest, policy digest/revision, generation, Bridge identity, device ID, and relay decision so this task leaves all downstream modules compiling.

Normalize `TransportPath` to exactly `DIRECT`, `RELAY`, and `OFFLINE`. `APPROVAL_REQUIRED` and `CONTROL_UNREACHABLE` remain `TransportFailure` values, never paths.

- [ ] **Step 3: Implement `AndroidTsnetBinding` as the sole generated-API import site**

Only `AndroidTsnetBinding.kt` may contain `import tsnetbridge.`. It wraps generated node/channel objects behind typed internal handles, copies and wipes arrays, adapts the generated synchronous state callback to `NoBackupTailnetStateStore`, serializes handle operations, rejects use after close, and maps only the closed native error-code set. Its binding encoder must reproduce `testdata/verified-binding-v1.bin` byte-for-byte from the shared JSON vector. It does not expose or reflect the generated `Server`, client, dial, or socket internals.

Use bounded `Dispatchers.IO` calls for blocking start/open/receive/stop operations and preserve coroutine cancellation by closing the current channel/node. Enrollment material is acquired once per `start` and closed in `finally`, including native panic/error mapping.

- [ ] **Step 4: Enforce the one-file import and public API rules**

Extend `test_transport_boundary.py` to fail if any Kotlin/Java file except `AndroidTsnetBinding.kt` references `tsnetbridge`, if a production type mentions endpoint/host/port/URL/socket/route/DNS, or if the old `Any` return type or `startNode(nodeIdentity: String, state: ByteArray?)` seam remains.

```bash
python3 apps/android/tools/test_transport_boundary.py
cd apps/android
./gradlew --offline --no-daemon \
  :core-model:check \
  :tailnet-core:check \
  :transport:check \
  :notification-collector:check \
  :sms-collector:check \
  :app:check
```

Expected: unit, static, artifact, and Gradle checks pass.

- [ ] **Step 5: Commit**

```bash
git add apps/android/core-model/src/main/kotlin/com/openandroidintelligence/core/model/TransportContracts.kt \
  apps/android/tailnet-core/src \
  apps/android/transport/src/main/kotlin/com/openandroidintelligence/transport/PairedBridgeSessionCoordinator.kt \
  apps/android/transport/src/test/kotlin/com/openandroidintelligence/transport/TransportBoundaryTest.kt \
  apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/OpenAndroidIntelligenceApplication.kt \
  apps/android/notification-collector/src/test/kotlin/com/openandroidintelligence/notifications/NotificationBridgeDispatcherTest.kt \
  apps/android/sms-collector/src/test/kotlin/com/openandroidintelligence/sms/SmsAutoSyncCoordinatorTest.kt \
  apps/android/tools/test_transport_boundary.py
git commit -m "重构: 使用真实 tsnet 生成绑定"
```

## Task 9: Persist tsnet node state and connection generation with Android Keystore

**Files:**

- Modify: `apps/android/tailnet-core/src/main/kotlin/com/openandroidintelligence/tailnet/core/NoBackupTailnetStateStore.kt`
- Create: `apps/android/tailnet-core/src/main/kotlin/com/openandroidintelligence/tailnet/core/AndroidKeystoreTailnetState.kt`
- Create: `apps/android/tailnet-core/src/main/kotlin/com/openandroidintelligence/tailnet/core/FileConnectionGenerationPersistence.kt`
- Create: `apps/android/tailnet-core/src/test/kotlin/com/openandroidintelligence/tailnet/core/NoBackupTailnetStateStoreTest.kt`
- Create: `apps/android/tailnet-core/src/androidTest/kotlin/com/openandroidintelligence/tailnet/core/AndroidKeystoreTailnetStateTest.kt`
- Modify: `apps/android/tailnet-core/build.gradle.kts`

- [ ] **Step 1: Write failing format, durability, and failure tests**

Freeze a local ciphertext container with magic/version, key alias version, random 96-bit AES-GCM nonce, ciphertext length, ciphertext+tag, and a trailing SHA-256 over all preceding container bytes for corruption triage. Unit tests use an injected AEAD seam and prove atomic temp-file write + fsync + rename, defensive copies, serialization of concurrent callback writes, rejection above `4,194,304` plaintext bytes, corruption rejection without deletion, callback return only after durable write, failure propagation, clear/wipe semantics, and no auth key written to state.

Generation persistence uses a separate authenticated file and key alias. Tests prove monotonic unsigned generation, fsync before `reserveNext` returns, process reconstruction, corruption fail-closed, overflow rejection, and no reset-to-zero fallback.

```bash
cd apps/android
./gradlew --offline --no-daemon :tailnet-core:testDebugUnitTest
```

Expected red state: only the injectable/in-memory implementation exists.

- [ ] **Step 2: Implement Keystore AES-GCM no-backup storage**

Create nonexportable Android Keystore AES-256 keys with GCM/no-padding, randomized encryption required, and `setUserAuthenticationRequired(false)` so screen-off/Doze reconnect does not prompt. The app remains non-direct-boot-aware and does not start this transport before the first user unlock. Use the exact state/generation aliases from **Fixed runtime limits and storage identifiers**. Store files only below `context.noBackupFilesDir/tailnet/`, set owner-only permissions, and never use SharedPreferences, Room, Android Backup, external storage, logs, or process-only memory as the production source of truth.

`persistNodeState` must return only after encryption, file fsync, atomic rename, and directory fsync complete. Any error leaves the prior valid file intact and synchronously fails the native callback. `restoreNodeState` rejects an unknown key alias/format/digest/tag and preserves the file as evidence.

- [ ] **Step 3: Add real-device Keystore instrumentation tests**

On API 34+, test key nonexportability, ciphertext nonce uniqueness for identical plaintext, process-independent decrypt through a new store instance, corruption failure, no-backup absolute path, owner-only mode, clear behavior, and inability to find a known plaintext marker in the stored bytes. The test records only hashes and status codes.

```bash
cd apps/android
./gradlew --offline --no-daemon :tailnet-core:connectedDebugAndroidTest
```

Expected: tests pass on an attached API 34+ device/emulator. If no device is attached, this task remains `BLOCKED`; do not substitute the unit fake.

- [ ] **Step 4: Run regressions and commit**

```bash
cd apps/android
./gradlew --offline --no-daemon :tailnet-core:check
git add tailnet-core/build.gradle.kts tailnet-core/src
git commit -m "新增: 持久化加密的 Tailnet 节点状态"
```

## Task 10: Seal production composition around `TsnetPairedBridgeTransport` and move the fake to fixtures

**Files:**

- Modify: `apps/android/transport/src/main/kotlin/com/openandroidintelligence/transport/TsnetPairedBridgeTransport.kt`
- Modify: `apps/android/transport/src/main/kotlin/com/openandroidintelligence/transport/PairedBridgeSessionCoordinator.kt`
- Create: `apps/android/transport/src/main/kotlin/com/openandroidintelligence/transport/ProductionPairedBridgeTransport.kt`
- Create: `apps/android/transport/src/main/kotlin/com/openandroidintelligence/transport/ProductionTailnetTransportFactory.kt`
- Move: `apps/android/transport/src/main/kotlin/com/openandroidintelligence/transport/FakeUserspaceTransport.kt` → `apps/android/transport/src/testFixtures/kotlin/com/openandroidintelligence/transport/FakeUserspaceTransport.kt`
- Modify: `apps/android/transport/build.gradle.kts`
- Modify: `apps/android/transport/src/test/kotlin/com/openandroidintelligence/transport/TransportBoundaryTest.kt`
- Modify: `apps/android/notification-collector/build.gradle.kts`
- Modify: `apps/android/notification-collector/src/test/kotlin/com/openandroidintelligence/notifications/NotificationBridgeDispatcherTest.kt`
- Modify: `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/OpenAndroidIntelligenceApplication.kt`
- Modify: `apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/NotificationSettingsStateTest.kt`
- Modify: `apps/android/tools/test_transport_boundary.py`

- [ ] **Step 1: Write failing production-composition tests**

Tests must prove:

- `TsnetPairedBridgeTransport.open` obtains `core.currentPath(binding)` after the native channel opens; no constructor/default path remains;
- `DIRECT`, `RELAY`, and `OFFLINE` are reported exactly; `OFFLINE` closes the channel and returns `NETWORK_BLOCKED` rather than a connected session;
- `APPROVAL_REQUIRED`, `CONTROL_UNREACHABLE`, persistence failure, stale generation, and invalid binding map to the expected failed status and stop node/channel;
- reconnect fences and closes the old generation before opening the new one; start/open/path/close/stop stay under the coordinator mutex; close/stop are idempotent;
- production factory composes `AndroidTsnetBinding`, `TsnetLibTailscaleCore`, Keystore state, persistent generation, coordinator, the binding's digest-bound relay-policy decision, and real transport, with no injectable endpoint/fallback/fake;
- an absent enrollment source leaves the registry unavailable;
- a registry cannot accept an arbitrary `PairedBridgeTransport` implementation.

Define this intentionally opaque app-facing type:

```kotlin
sealed interface ProductionPairedBridgeTransport : PairedBridgeTransport {
    fun status(): PairingTransportStatus
}
```

Only an internal implementation in the transport module may implement it. The factory returns this sealed type; the app can use it but cannot construct an alternative implementation.

```bash
cd apps/android
./gradlew --offline --no-daemon :transport:testDebugUnitTest :app:testDebugUnitTest
```

Expected red state: hard-coded `DIRECT` remains and the app registry accepts any transport.

- [ ] **Step 2: Implement real backend-derived path and sealed composition**

Remove every `path` constructor parameter. Open the channel, query native path for the same verified binding, and publish connected status only for `DIRECT` or `RELAY` when `binding.relayAllowed` is true. The transport consumes this factory-minted, policy-digest-bound decision and cannot reinterpret it. For `OFFLINE` or disallowed relay, close/fence before surfacing failure. `ProductionTailnetTransportFactory.create(context, enrollmentSource)` owns all concrete state paths/key aliases and has no overload taking a fake binding, URL, host, or generic core.

Change `PairedNotificationBridgeRuntime.transport` and the registry install API to the sealed production type. Make replacement/clear lifecycle methods suspending: installing a new generation first closes and fences the previous runtime, and `clear(reason)` awaits transport close before dropping the reference. Keep the registry unavailable until pairing supplies both a verified binding and a validated `NativeEnrollmentSource`; clearing pairing removes enrollment material according to revoke semantics. Do not launch unowned cleanup work from a global coroutine scope.

- [ ] **Step 3: Move the fake and update fixture dependencies**

Enable Gradle `testFixtures` for `transport`, move the file without changing its package, and make fake-consuming modules use:

```kotlin
testImplementation(testFixtures(project(":transport")))
```

No production source or production dependency may reference the fixture. Update source tests to require the fake under `src/testFixtures`, reject it under `src/main`, inspect release runtime classes/AAR entries, and reject the string `FakeUserspaceTransport` in a release APK/AAR.

- [ ] **Step 4: Run the complete Gate B verification**

```bash
python3 apps/android/tools/test_transport_boundary.py
cd apps/android
./gradlew --offline --no-daemon \
  :tailnet-core:check \
  :transport:check \
  :notification-collector:check \
  :app:check \
  :app:assembleRelease \
  check
```

Expected: all checks pass; artifact/runtime scans find the real AAR and no fake, VpnService, TUN, listener, proxy, generic socket API, or fallback transport. Gate B is complete, but P0t remains `BLOCKED` until Tasks 11–14.

- [ ] **Step 5: Commit**

```bash
git add apps/android/core-model \
  apps/android/tailnet-core \
  apps/android/transport \
  apps/android/notification-collector \
  apps/android/app \
  apps/android/tools/test_transport_boundary.py
git commit -m "重构: 生产环境仅使用真实 tsnet 传输"
```

## Task 11: Build a protocol-valid P0t Bridge harness over a real Tailnet WSS endpoint

**Files:**

- Modify: `package.json`
- Modify: `package-lock.json`
- Create: `p0t/bridge-harness/package.json`
- Create: `p0t/bridge-harness/tsconfig.json`
- Create: `p0t/bridge-harness/src/server.ts`
- Create: `p0t/bridge-harness/src/fixture.ts`
- Create: `p0t/bridge-harness/test/server.test.ts`
- Create: `p0t/bridge-harness/run-server.sh`
- Create: `p0t/bridge-harness/README.md`

- [ ] **Step 1: Add the exact test-only WSS dependency and failing harness tests**

Register `p0t/bridge-harness` as an npm workspace. Lock `ws` to `8.21.3` and `@types/ws` to `8.18.1`; keep them inside that P0t workspace/dev surface, and extend the root typecheck script to invoke its dedicated strict tsconfig. Tests run with localhost TLS fixtures and require:

- WSS only, exact `/v1/control`, binary frames only, no redirect, no query, and a hard `262144`-byte frame maximum;
- the first message is a canonical signed `connect_hello` admitted through existing `verifyConnectMessage`, with the expected device key, device ID, pairing generation, ticket-derived binding context, digest, expiry, and version;
- the response is a canonical signed `connect_welcome` produced from the verified hello and P0t Bridge test key;
- subsequent `device_ping` is admitted through existing protocol verification and receives a signed `bridge_ping` whose challenge binds to the request nonce;
- wrong signer, wrong generation, replay, expired wire, noncanonical JSON, text, oversize, wrong path, and second hello close with a nonsecret code;
- logs/receipts contain only run ID, timestamps, closed result/path codes, frame SHA-256, byte counts, and nonce digests—never full envelopes, payloads, keys, tickets, peer credentials, or node state.

```bash
./tools/run-node24 npm test -- --run p0t/bridge-harness/test/server.test.ts
```

Expected red state: harness workspace/files do not exist.

- [ ] **Step 2: Implement the test-only harness by reusing frozen protocol code**

Import canonical encoding, schema/registry verification, signature domains, replay admission, version negotiation, and `signTestOnly` from `protocol/src`; do not reimplement simplified JSON/signature checks. Reuse the repository's explicitly test-only device and Bridge command JWK fixtures; those known test vectors remain on the Bridge/controller host and are never provisioned to Android or copied into evidence. The externally visible server binds only to its configured Tailnet interface/port 443 and has no public listener. `run-server.sh` requires the controller to supply an already-open certificate descriptor 3 and private-key descriptor 4, validates that both exist, disables xtrace, and passes only run ID/Tailnet bind address as nonsecret arguments; key bytes never enter argv or environment.

`fixture.ts` generates unique signed `connect_hello`/`device_ping` frames for the Android test and verifies signed `connect_welcome`/`bridge_ping`. It emits binary fixtures to standard output or an app-private provisioning stream; it never writes private keys to the device.

- [ ] **Step 3: Define a protocol-valid 64 MiB transport workload**

Pre-generate and sign a corpus of unique, schema-valid canonical `device_event` envelopes whose record contains an opaque P0t-only padding field sized so each frame stays below `262144` bytes. Admit each frame through the normal protocol schema/signature/replay pipeline on the harness. The Android side sends the corpus in order until canonical wire byte lengths total at least 64 MiB, then sends a valid signed `device_ping` whose 32-byte challenge equals the corpus SHA-256; the harness returns a valid signed `bridge_ping` with the same challenge after confirming its exact byte count/digest. Measure transport time only after corpus generation/signing and before the final ping receipt, and retain only the cumulative digest/count and signed receipt digest. Do not use custom message types, invalid blobs, or repeated message IDs to satisfy throughput.

- [ ] **Step 4: Run harness tests and typecheck**

```bash
./tools/run-node24 npm test -- --run p0t/bridge-harness/test/server.test.ts
./tools/run-node24 npm run typecheck
```

Expected: tests/typecheck pass. The harness test itself asserts its captured log/receipt fields equal the closed allowlist and that known key/ticket/envelope sentinels are absent; Task 13 repeats the repository-wide evidence secret scan after introducing the independent scanner.

- [ ] **Step 5: Commit**

```bash
git add package.json package-lock.json p0t/bridge-harness
git commit -m "新增: 实现 P0t Bridge WSS 验证器"
```

## Task 12: Add real-AAR connected tests and stdin-only P0t provisioning

**Files:**

- Modify: `apps/android/transport/build.gradle.kts`
- Create: `apps/android/transport/src/androidTest/kotlin/com/openandroidintelligence/transport/p0t/P0tInput.kt`
- Create: `apps/android/transport/src/androidTest/kotlin/com/openandroidintelligence/transport/p0t/TsnetNativeLoadTest.kt`
- Create: `apps/android/transport/src/androidTest/kotlin/com/openandroidintelligence/transport/p0t/TsnetPairedConnectionTest.kt`
- Create: `apps/android/transport/src/androidTest/kotlin/com/openandroidintelligence/transport/p0t/TsnetProcessRestoreTest.kt`
- Create: `apps/android/transport/src/androidTest/kotlin/com/openandroidintelligence/transport/p0t/TsnetFailurePathTest.kt`
- Create: `p0t/device/provision-run-as.sh`
- Create: `p0t/device/build-input-stream.py`
- Create: `p0t/device/run-p0t.sh`
- Create: `p0t/device/test_provisioning.sh`
- Create: `p0t/device/README.md`

- [ ] **Step 1: Write failing host tests for provisioning secrecy and framing**

`test_provisioning.sh` replaces the bundle CLI and `adb` with recording fakes and proves that an auth-key sentinel appears only on the CLI's standard input and the standard-input stream of `adb shell run-as com.openandroidintelligence.transport.test sh -c 'umask 077; mkdir -p no_backup/p0t; cat > no_backup/p0t/input.bin'`. It must not appear in process arguments, environment, `set -x` output, temporary host files, Android shell history, Gradle properties, or generated evidence. Also test truncated framing, a failed CLI, a failed `run-as`, mode other than `0600`, wrong test package, and cleanup after interruption.

```bash
bash p0t/device/test_provisioning.sh
```

Expected red state: provisioning scripts do not exist.

- [ ] **Step 2: Define a closed, short-lived app-private input container**

`P0tInput` decodes magic `ALP0TIN1`, version `1`, and length-delimited fields: enrollment bootstrap containing the one-time key, reconnect bootstrap with an empty key, verified pairing metadata, phase-indexed signed connect-hello/device-ping wires and exact expected welcome/bridge-ping wire hashes, and an optional path to the app-private throughput corpus. The decoder has a `4,194,304`-byte bound excluding the separately streamed throughput corpus, rejects unknown/trailing fields, copies arrays, exposes no `toString`, and wipes/deletes the container after native `Start` consumes the enrollment bootstrap. The sanitized reconnect bootstrap and nonsecret binding digests may be persisted encrypted for process-restore phases; signed full envelopes are never copied into evidence.

The test must not deserialize a `VerifiedPairingTransportBinding` directly. It constructs `EnrollmentTicket`, `BridgeIdentity`, and `PolicyAttestation` from the fixture's already-verified digest/identity/revision facts and invokes `VerifiedPairingTransportBindingFactory.mint`, exercising the same binding checks as production. This is transport P0t material, not a claim that production pairing UI/issuer exists.

- [ ] **Step 3: Implement stdin-only provisioning**

`provision-run-as.sh` accepts only nonsecret arguments: device serial, instrumentation package `com.openandroidintelligence.transport.test`, and a nonsecret JSON descriptor path. It gives its standard input directly to `build-input-stream.py`, which holds the key only in a mutable in-process byte buffer, invokes the native `enrollment-bundle` CLI, combines enrollment/reconnect bundles with freshly generated signed fixtures, wipes intermediate key/bundle buffers, and streams the complete container directly into:

```text
adb -s DEVICE_SERIAL shell run-as com.openandroidintelligence.transport.test sh -c \
  'umask 077; mkdir -p no_backup/p0t; cat > no_backup/p0t/input.bin'
```

`DEVICE_SERIAL` in this notation is the validated nonsecret first argument, not a secret-bearing environment variable. The real script constructs the `adb` argument array without `eval`. No plaintext bundle is written on the host. The Python framer caps non-corpus output at `4,194,304` bytes and never logs exception payloads. On any pipe failure the shell removes the device input file; it disables xtrace and emits only closed status codes.

- [ ] **Step 4: Write connected tests that must load the real AAR**

`TsnetNativeLoadTest` asserts the real generated class and `libgojni.so` load, records API/ABI/page size, starts with the provisioned bundle, requires a durable state callback, and stops twice. It rejects a test-double binding via an artifact-origin assertion based on the generated class code source/native mapping.

`TsnetPairedConnectionTest` uses `ProductionTailnetTransportFactory` and the factory-minted verified binding, then:

1. opens the real tsnet channel to the pinned Bridge;
2. sends the provisioned canonical signed `connect_hello`; the Bridge harness verifies its signature/schema/binding, while Android computes the received canonical `connect_welcome` SHA-256 and compares it to the host-generated fixture hash that was independently verified by the protocol code;
3. sends a unique signed `device_ping`; the harness verifies it, while Android checks the returned `bridge_ping` wire hash and nonce-binding hash against the independently verified expected fixture, recording only those hashes/result;
4. asserts backend path equals the scenario's required `DIRECT` or `RELAY` value;
5. closes session/node twice and verifies no session remains.

`TsnetProcessRestoreTest` has explicit `seedState` and `restoreWithoutAuthKey` methods run in separate instrumentation invocations. The shell runner force-stops the test package between them; phase two uses only encrypted persisted node state plus the sanitized reconnect bootstrap, repeats WSS control round-trip, and asserts no auth-key bytes/file remain.

`TsnetFailurePathTest` has separate methods for backend approval required, control unreachable, fully offline, wrong binding, expired policy, and stale generation. Each asserts the exact failure, closed node/channel, no ordinary HTTPS/public fallback, and no evidence frame sent to the Bridge.

- [ ] **Step 5: Run host tests and the direct connected smoke**

```bash
bash p0t/device/test_provisioning.sh
cd apps/android
./gradlew --offline --no-daemon :transport:assembleDebugAndroidTest
cd ../..
p0t/device/run-p0t.sh provision --serial "$P0T_DEVICE_SERIAL" --descriptor "$P0T_DESCRIPTOR" < "$P0T_AUTH_KEY_PIPE"
p0t/device/run-p0t.sh smoke --serial "$P0T_DEVICE_SERIAL" --required-path DIRECT
```

`P0T_DEVICE_SERIAL` and `P0T_DESCRIPTOR` identify nonsecret controller assets. `P0T_AUTH_KEY_PIPE` names a controller-owned FIFO or mode-`0600` credential file outside the repository; the script opens it as standard input and never copies its contents. Expected: native load, state-before-session, `connect_hello`/`connect_welcome`, nonce-bound control exchange, backend `DIRECT`, and idempotent stop all pass. If the physical/Tailnet prerequisites are absent, record `BLOCKED` and do not continue Gate C.

- [ ] **Step 6: Commit**

```bash
git add apps/android/transport/build.gradle.kts \
  apps/android/transport/src/androidTest \
  p0t/device/provision-run-as.sh \
  p0t/device/build-input-stream.py \
  p0t/device/run-p0t.sh \
  p0t/device/test_provisioning.sh \
  p0t/device/README.md
git commit -m "新增: 验证真实 tsnet 配对连接"
```

## Task 13: Run the complete API/network/lifecycle/resource matrix and collect secret-free evidence

**Files:**

- Create: `p0t/device/network-scenarios.sh`
- Create: `p0t/device/run-emulator-matrix.sh`
- Create: `p0t/device/measure-resources.sh`
- Create: `p0t/device/scan-secrets.py`
- Create: `p0t/device/collect-evidence.py`
- Create: `p0t/device/test_network_scenarios.sh`
- Create: `p0t/device/test_measure_resources.sh`
- Create: `p0t/android-size-probe/settings.gradle.kts`
- Create: `p0t/android-size-probe/build.gradle.kts`
- Create: `p0t/android-size-probe/src/main/AndroidManifest.xml`
- Create: `p0t/android-size-probe/src/main/kotlin/com/openandroidintelligence/p0t/sizeprobe/MainActivity.kt`
- Create: `p0t/evidence/schema/p0t-evidence.schema.json`
- Create: `p0t/evidence/test_collect_evidence.py`
- Create: `docs/mvp/evidence/p0t/.gitkeep`

- [ ] **Step 1: Write a rejecting evidence validator before running devices**

The schema and unit tests must require every scenario, command, exit code, timestamp, device fact, artifact digest, protocol digest, path source, VPN owner, route/DNS comparison, egress class, resource sample, budget calculation, and secret-scan result. Reject unknown keys, `SKIP`, `NOT_RUN`, `N/A`, an empty sample set, stale/mismatched run IDs, nonzero commands, missing before/after evidence, a self-reported path without backend receipt, a non-PASS scenario, a budget at/over the wrong side of its bound, a raw IP/hostname/full envelope/private field/auth URL/key/ticket/state pattern, and any hash that does not match its referenced redacted file.

Fixture tests must include at least one failure for each matrix row and budget, plus a complete synthetic PASS inventory. Synthetic fixtures validate only the collector, and are permanently labeled `synthetic=true`; the final collector rejects that flag.

```bash
python3 -m unittest p0t/evidence/test_collect_evidence.py
```

Expected red state: schema and collector do not exist.

- [ ] **Step 2: Implement network-condition verification without claiming operator actions as evidence**

`network-scenarios.sh verify direct|derp|offline|vpn-allow|vpn-block` does not silently reconfigure the network. A controller places the physical device in the requested controlled condition, then the script captures and validates machine signals:

- `direct`: WSS succeeds and native backend receipt is `DIRECT` with a peer endpoint signal;
- `derp`: controlled gateway flow evidence shows peer UDP blocked, DERP HTTPS allowed, WSS succeeds, and native backend receipt is `RELAY` with a DERP region signal;
- `offline`: control, DERP, and peer paths are blocked; exact closed failure is observed and the Bridge receives no frame;
- `vpn-allow`: `dumpsys vpn` identifies another package as VPN owner, permitted Tailscale control/DERP/peer traffic succeeds, and our package never becomes owner;
- `vpn-block`: another package owns always-on/lockdown VPN, its policy blocks Tailscale, exact network failure occurs, and gateway/Bridge records show no public fallback.

Before and after each case, capture `dumpsys vpn`, `dumpsys connectivity`, all route tables, resolver state, package permissions/services, and controlled-gateway flow summaries. Raw gateway capture stays in controller storage outside Git. The collector stores its SHA-256 and only destination classes (`TAILNET_PEER_443`, `TAILSCALE_CONTROL_443`, `TAILSCALE_DERP_443`, `UNEXPECTED`) plus counts; any `UNEXPECTED` count fails.

Host tests use command fakes to prove that missing VPN ownership, incomplete block conditions, contradictory backend path, route/DNS ownership changes, a Bridge frame during offline, or an unexpected egress destination fails.

- [ ] **Step 3: Implement the mandatory emulator and device orchestration**

`run-emulator-matrix.sh` locates only locked x86_64 system images for API 34, 35, 36, and 37, creates fresh run-scoped AVDs, and for every API runs install, real native load, cold enrollment, warm-state reconnect, WSS connect/welcome/ping, and stop. It checks `ro.build.version.sdk`, ABI, image fingerprint, and command exits. It reads one newly issued, single-use, five-minute, tag-scoped auth key per cold row as a length-framed standard-input record; keys are never reused or placed in arguments/environment. A missing API image or key blocks the matrix; it is never skipped.

Run one Android 15+ image/device whose `getconf PAGE_SIZE` returns exactly `16384`; perform install, native load, complete WSS exchange, force-stop, and state restore. Merely inspecting ELF alignment does not satisfy this row.

On one physical `arm64-v8a` API 34‑37 device, run direct, forced DERP, offline, approval-required, process death, Wi-Fi↔cellular network switch, Doze entry/exit, VPN allow, VPN block, direct throughput, relay throughput, and all resource measurements. Each first-enrollment sample receives a distinct controller-issued single-use key through standard input; the approval case uses a key/policy that produces a real pending-approval backend state. Network switch must first fence/close the old connection generation, then reconnect with a larger durable generation within 15 seconds of the validated-network change.

For Doze, the runner records idle entry, holds the device in forced idle for exactly 15 minutes without a foreground service or wake lock, records channel/failure state, exits idle, and requires reconnection within 15 seconds. The evidence contains the exact interval and device-idle state transitions.

- [ ] **Step 4: Build a controlled per-ABI installed-size probe**

The standalone probe has `native` (`baseline`, `tsnet`) and `abi` (`arm64`, `x86_64`) flavor dimensions; only `tsnetImplementation` includes the locked AAR, and each ABI flavor sets exactly one `abiFilter`. Use equal-length application-ID suffixes and otherwise identical resources/code. Install each flavor, obtain every package path with `pm path`, sum package/native bytes with `stat`, and subtract baseline from tsnet for the same ABI/device. The tsnet flavor also reflection-loads the generated class/native library, while baseline asserts it is absent. Fail if either increment exceeds 40 MiB. This probe is P0t-only and is never a production fallback or app dependency.

- [ ] **Step 5: Measure every hard budget with fixed samples**

`measure-resources.sh` records:

- 10 warm-state online durations and 10 first-enrollment durations, then calculates nearest-rank p95 without dropping valid slow samples;
- `dumpsys meminfo` `TOTAL RSS` samples and process CPU samples every 60 seconds over 30 screen-off minutes;
- per-UID wakeup-alarm plus suspend-exiting wake-lock acquisition deltas from `dumpsys batterystats` over exactly one measured screen-off hour;
- battery percentage over four screen-off hours while unplugged, with model, OS build, battery health, temperature, network, and start/end charge state;
- byte-exact direct and relay transfers over at least 64 MiB of the protocol-valid corpus, excluding fixture generation/signing from the timed interval.

For the 10 first-enrollment timings, the controller supplies 10 distinct single-use keys and the runner clears app/node state and uses a new node identity/pairing generation before each measurement. It starts the timer immediately before native `Start` and stops after durable state plus connected WSS; human approval time is excluded by using preauthorized keys for these 10 samples. Warm timings reuse encrypted state and an empty-key reconnect bundle.

The runner marks a run invalid and rerunnable only for predeclared contamination: thermal throttling status, OS update/reboot, charge-state change, or loss of the controlled network. It retains the invalid sample and reason; it cannot discard a valid slow sample. Host tests cover p95 boundaries, counter wrap, measurement duration, contamination rules, byte mismatch, and every budget threshold.

- [ ] **Step 6: Execute and collect one complete run**

```bash
P0T_RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
p0t/device/run-emulator-matrix.sh --run-id "$P0T_RUN_ID" < "$P0T_EMULATOR_KEY_STREAM"
p0t/device/run-p0t.sh physical-matrix --run-id "$P0T_RUN_ID" --serial "$P0T_DEVICE_SERIAL" < "$P0T_PHYSICAL_KEY_STREAM"
p0t/device/measure-resources.sh --run-id "$P0T_RUN_ID" --serial "$P0T_DEVICE_SERIAL" < "$P0T_TIMING_KEY_STREAM"
python3 p0t/device/collect-evidence.py \
  --run-id "$P0T_RUN_ID" \
  --input "$P0T_RAW_EVIDENCE" \
  --output "docs/mvp/evidence/p0t/$P0T_RUN_ID"
python3 p0t/device/scan-secrets.py "docs/mvp/evidence/p0t/$P0T_RUN_ID"
python3 p0t/device/collect-evidence.py \
  --validate-only "docs/mvp/evidence/p0t/$P0T_RUN_ID/inventory.json"
```

`P0T_EMULATOR_KEY_STREAM`, `P0T_PHYSICAL_KEY_STREAM`, and `P0T_TIMING_KEY_STREAM` are controller-owned FIFOs outside the repository. A credential broker writes each length-framed five-minute key only after the runner signals that its cold row is ready, so queued keys cannot expire before use; contents enter the runner only through standard input. `P0T_RAW_EVIDENCE` is a controller-owned run directory outside the repository containing command outputs and gateway-flow input; it must not contain reusable credentials. Expected: every matrix row is `PASS`, all hashes resolve, all budgets pass, and secret/unexpected-egress counts are zero. Otherwise the run is `FAIL` or `BLOCKED`, never partial PASS.

- [ ] **Step 7: Commit tooling and the complete redacted run**

```bash
git add p0t/device p0t/android-size-probe p0t/evidence \
  "docs/mvp/evidence/p0t/$P0T_RUN_ID"
git commit -m "测试: 采集 P0t 设备与网络证据"
```

Do not commit if the secret scanner fails. A FAIL/BLOCKED inventory may be committed for audit, but Task 14 cannot close the gate from it.

## Task 14: Close P0t documentation and the TSNET dependency row only from validated evidence

**Files:**

- Modify: `docs/mvp/p0t-mvp-evidence.md`
- Modify: `docs/mvp/mvp-dependency-lock.md`
- Modify: `docs/mvp/mvp-readiness-report.md`
- Modify: `mvp-contract/tools/check-lock.ts`
- Modify: `mvp-contract/tools/mvp-readiness.ts`
- Modify: `mvp-contract/test/dependency-lock.test.ts`
- Create: `mvp-contract/test/p0t-readiness.test.ts`
- Create: `p0t/device/finalize-tsnet-lock.py`
- Create: `p0t/device/test_finalize_tsnet_lock.py`

- [ ] **Step 1: Write failing closure tests before editing status documents**

Add `check-lock.ts --decision MVP-DEP-TSNET` without changing the existing all-row default. Focused validation succeeds only when that row alone is complete, immutable, unexpired, integrity-bound, and its verify command validates the exact AAR/evidence run. Global `npm run mvp:lock:check` must continue to fail while unrelated dependency rows remain pending.

Readiness tests must reject a P0t document/inventory that is missing, synthetic, FAIL/BLOCKED, secret-positive, expired, linked to another AAR digest/source commit/run ID, missing any matrix row/budget, or still mentions source-level/SKIPPED evidence. They must also verify release runtime has no fake and the no-VPN/egress checks passed.

```bash
./tools/run-node24 npm test -- --run \
  mvp-contract/test/dependency-lock.test.ts \
  mvp-contract/test/p0t-readiness.test.ts
python3 -m unittest p0t/device/test_finalize_tsnet_lock.py
```

Expected red state: current document is `BLOCKED`, TSNET row is pending, and readiness recognizes only the old source-level evidence.

- [ ] **Step 2: Require explicit controller review data and derive the row mechanically**

`finalize-tsnet-lock.py` accepts a validated PASS inventory and a controller review JSON containing exactly: reviewer identity, ISO review timestamp, ISO evidence expiry, SPDX license-review conclusion, official reference `https://github.com/tailscale/tailscale/tree/v1.98.10`, and approval of the recorded AAR/source/toolchain digests. It has no defaults. It rejects an expiry not later than review time/current validation time, a digest not present in the inventory, a different reference, or an incomplete license conclusion. Reviewer identity is recorded in the generated evidence document and the lock row's license-review field.

The script updates only `MVP-DEP-TSNET` with immutable version `tailscale-v1.98.10@36550d57f4a4055246ef7412f4e650a012a465f1;aar-sha256:$AAR_SHA256`, substituting `$AAR_SHA256` from the validated inventory, plus a row-integrity hash calculated by existing `sha256Evidence`, the explicit controller fields, and a verify command that runs the standalone AAR verifier plus evidence validator for the exact run ID. It regenerates the TSNET/P0t sections of the readiness report from the same inventory while preserving unrelated blockers. It never marks Android/Bridge/Hermes/OpenClaw/artifact rows.

- [ ] **Step 3: Replace the old evidence narrative with generated PASS or honest FAIL/BLOCKED status**

For a complete PASS inventory, `docs/mvp/p0t-mvp-evidence.md` must identify the exact run ID, AAR/source/toolchain digests, two-build reproducibility result, device/API/ABI/page-size matrix, connect/welcome/control digests, backend paths, lifecycle/VPN/no-VPN/egress results, resource values versus budgets, and links to every redacted evidence item. It must explicitly state the P0t Bridge harness is not production Bridge readiness.

If any condition is not PASS, update the document to the generated `FAIL` or `BLOCKED` state with exact failing row(s), leave `MVP-DEP-TSNET` pending, and stop. No prose override is permitted.

- [ ] **Step 4: Finalize and verify the focused dependency gate**

```bash
python3 p0t/device/finalize-tsnet-lock.py \
  --inventory "docs/mvp/evidence/p0t/$P0T_RUN_ID/inventory.json" \
  --review "$P0T_CONTROLLER_REVIEW" \
  --lock docs/mvp/mvp-dependency-lock.md \
  --evidence docs/mvp/p0t-mvp-evidence.md \
  --readiness-report docs/mvp/mvp-readiness-report.md
./tools/run-node24 npm run mvp:lock:check -- --decision MVP-DEP-TSNET
```

`P0T_CONTROLLER_REVIEW` is an explicit controller-owned JSON file; no value is inferred by the implementation agent. Expected: the focused TSNET check passes. The all-row lock command remains fail-closed for other pending dependencies, which is not a P0t failure.

- [ ] **Step 5: Run final repository, Android, native, artifact, and evidence verification**

```bash
./tools/run-node24 npm test
./tools/run-node24 npm run typecheck
python3 apps/android/tools/test_transport_boundary.py
python3 p0t/device/collect-evidence.py \
  --validate-only "docs/mvp/evidence/p0t/$P0T_RUN_ID/inventory.json"
python3 p0t/device/scan-secrets.py "docs/mvp/evidence/p0t/$P0T_RUN_ID"
bash apps/android/tailnet-core/tools/run-tsnet-go-tests.sh './...'
python3 apps/android/tailnet-core/tools/verify-tsnet-aar.py \
  --lock apps/android/tailnet-core/native/tsnetbridge/tsnet-aar.lock.json \
  --aar apps/android/tailnet-core/libs/tsnet-android-1.98.10.aar \
  --provenance apps/android/tailnet-core/libs/tsnet-android-1.98.10.provenance.json \
  --sbom apps/android/tailnet-core/libs/tsnet-android-1.98.10.sbom.json \
  --notices apps/android/tailnet-core/native/tsnetbridge/THIRD_PARTY_NOTICES.md
cd apps/android
./gradlew --offline --no-daemon clean check :app:assembleRelease
```

Then rerun the physical smoke against the committed AAR digest:

```bash
cd ../..
p0t/device/run-p0t.sh smoke \
  --serial "$P0T_DEVICE_SERIAL" \
  --required-path DIRECT \
  --expected-aar-sha256 "$(cut -d' ' -f1 apps/android/tailnet-core/libs/tsnet-android-1.98.10.aar.sha256)"
```

Expected: every command exits zero; the committed artifact digest equals the evidence and device smoke digest. `mvp-readiness --release` may still report unrelated production blockers, but it must no longer report `TSNET-AAR`, `P0T-GATE`, fake production transport, or no-VPN evidence blockers.

- [ ] **Step 6: Commit the gate closure**

```bash
git add docs/mvp/p0t-mvp-evidence.md \
  docs/mvp/mvp-dependency-lock.md \
  docs/mvp/mvp-readiness-report.md \
  mvp-contract/tools/check-lock.ts \
  mvp-contract/tools/mvp-readiness.ts \
  mvp-contract/test/dependency-lock.test.ts \
  mvp-contract/test/p0t-readiness.test.ts \
  p0t/device/finalize-tsnet-lock.py \
  p0t/device/test_finalize_tsnet_lock.py
git commit -m "文档: 完成 P0t 证据与 TSNET 锁定"
```

---

## Spec coverage matrix

| Confirmed design requirement | Implemented and proved by |
| --- | --- |
| immutable upstream/toolchain and no worktree mutation | Tasks 1–2 |
| narrow AAR without generic networking/VPN surfaces | Tasks 3–7 |
| callback-backed durable state and credential cleanup | Tasks 3–4, 8–9, 12 |
| pinned TCP 443/WSS/SNI/IP and frame bounds | Task 5, then real run in Tasks 11–13 |
| real backend DIRECT/RELAY/OFFLINE | Tasks 5, 8, 10, 12–13 |
| tracked dual-ABI reproducible AAR, SBOM, license, provenance, 16 KiB alignment | Task 6, runtime page-size proof in Task 13 |
| real Kotlin adapter and production-only real composition | Tasks 7–10 |
| fake restricted to test fixtures and absent from release | Task 10, reverified in Tasks 13–14 |
| canonical pairing connect/welcome and nonce-bound control round-trip | Tasks 11–12 |
| API 34–37 x86_64, arm64 physical device, Android 15+ 16 KiB | Task 13 |
| direct, DERP, offline, approval, restore, switch, Doze, other VPN | Tasks 12–13 |
| VPN/route/DNS/egress/no-log-upload/no-fallback evidence | Task 13 |
| all size/startup/RSS/CPU/wakeup/battery/throughput budgets | Task 13 |
| evidence cannot pass with missing/skipped/secret data | Tasks 13–14 |
| controller-owned dependency closure | Task 14 |

## Definition of complete

This plan is complete only when all fourteen tasks are checked and the final committed state satisfies every item below at the same AAR SHA-256:

- two independent clean native builds normalize to the tracked AAR digest;
- artifact/source/toolchain/SBOM/license/provenance verification passes offline;
- production code imports generated bindings in one file, builds only a real `TsnetPairedBridgeTransport`, and ships no fake or forbidden VPN/network fallback surface;
- real Tailnet enrollment, encrypted state restore after process death, canonical connect/welcome, nonce-bound control traffic, and backend path reporting pass;
- every mandatory API/ABI/page-size/network/lifecycle/VPN/resource row has non-synthetic PASS evidence with no skips or secrets;
- focused `MVP-DEP-TSNET` lock verification passes using explicit controller review data;
- the final repository/Android/native/artifact/evidence commands pass, and remaining release blockers are unrelated to TSNET/P0t.

Any unmet line leaves P0t `FAIL` or `BLOCKED` and `MVP-DEP-TSNET` pending. There is no fake, system-VPN, public-HTTPS, or source-level fallback path to completion.
