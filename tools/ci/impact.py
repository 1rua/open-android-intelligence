#!/usr/bin/env python3
"""
CI change-impact analysis (GitHub Actions job gating).

This is the single source of truth for "which CI lanes must run for this diff".
`tools/smart-test.py` uses the same module for its local fast/pr/full strategy,
so a change classified here cannot be tested differently on a workstation and
on CI.

Usage (CI):
    python3 tools/ci/impact.py --base origin/dev --head HEAD --github-output

Outputs `runs_*` booleans plus `any_change`, and, when every affected lane is
known, `tested=true`. Unknown or security-sensitive paths expand the matrix
instead of narrowing it: a diff we cannot classify must never silently skip a
lane.

Design rules:
  * Deletions, renames and additions are classified by their old *and* new path
    (`git diff --name-status -M`), so deleting a security test still triggers the
    lane that owns it.
  * A path that no rule recognises sets `unknown=true`; callers then run the
    full matrix. Conservative by construction.
  * Empty diff (shallow clone, missing base) also sets `unknown=true`.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

# Contract, protocol, auth, build-config and safety-gate paths.
# A match here widens the matrix to everything: these changes can invalidate any
# lane's assumptions, so narrowing by "which file changed" would be unsound.
SECURITY_OR_CONTRACT_PREFIXES = (
    "gateway-contract/",
    "apps/android/gradle/mvp-forbidden-surfaces.gradle.kts",
    "apps/android/gradle/wrapper/",
    "apps/android/gradle.properties",
)
SECURITY_OR_CONTRACT_PATHS = {
    "apps/android/build.gradle.kts",
    "apps/android/settings.gradle.kts",
    "apps/android/gradle/libs.versions.toml",
    "apps/android/app/build.gradle.kts",
    "package.json",
    "package-lock.json",
    "openclaw-plugin-pin.json",
    "tools/smart-test.py",
    "tools/ci/impact.py",
    "tools/test_smart_test.py",
    ".github/workflows/oai-core.yml",
    ".github/workflows/ci-release-gate.yml",
}

# oai-core authentication, authorization, sync and gateway code invalidates the
# security assumptions of every other lane.
CORE_SECURITY_PREFIXES = (
    "oai-core/src/oai_core/gateway/",
    "oai-core/src/oai_core/auth",
    "oai-core/src/oai_core/security",
    "oai-core/src/oai_core/sessions",
)

ANDROID_MODULE_LANES = {
    "control-ports": "device",
    "capability-ports": "device",
    "screen-control": "device",
    "device-primitives": "device",
    "control-plane": "device",
    "platform-kernel": "platform",
    "plugin-package": "plugins",
    "plugin-runtime-wasm": "plugins",
    "plugin-ui": "plugins",
    "notification-control": "notifications",
    "notification-host": "notifications",
    "notification-collector": "notifications",
    "sms-collector": "notifications",
    "call-log-collector": "notifications",
    "policy-engine": "notifications",
    "encrypted-store": "notifications",
    "conversation-domain": "conversation",
    "conversation-data": "conversation",
    "conversation-ui": "conversation",
    "gateway-client": "gateway",
}

# Lanes are the unit of CI scheduling. Each maps to one or more GitHub jobs.
LANES = (
    "fast",          # protocol/JS/plugin-tooling static + unit gates
    "core",          # oai-core Python suite (3.11 + 3.12)
    "hermes",        # optional Session Adapter + native Hermes SDK
    "dsh",           # DSH Node bridge
    "android-device",       # device primitives, local authorization
    "android-platform",     # platform kernel recovery
    "android-notifications",  # settings/notification policy/encrypted state
    "android-conversation",   # conversation ordering/rendering/persistence
    "android-gateway",        # gateway client transport + connectivity
    "android-app",            # full app unit tests (heaviest lane)
    "android-play",           # Play variant integration
)


def _run_git(args: list[str]) -> str:
    result = subprocess.run(
        ["git", "-C", str(REPO_ROOT), *args], capture_output=True, text=True
    )
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or "git failed")
    return result.stdout


def changed_paths(base: str | None, head: str | None, staged: bool = False) -> list[tuple[str, str]]:
    """Return (status, path) pairs, expanding renames to both sides."""
    args = ["diff", "--name-status", "-M", "--find-renames"]
    if staged:
        args.append("--cached")
    if base:
        args.append(f"{base}...{head or 'HEAD'}")
    elif head:
        args.append(head)
    else:
        # No base: compare worktree to HEAD so local runs stay honest.
        args.append("HEAD")

    pairs: list[tuple[str, str]] = []
    try:
        output = _run_git(args)
    except RuntimeError:
        return pairs

    for line in output.splitlines():
        if not line.strip():
            continue
        fields = line.split("\t")
        status = fields[0][:1]
        if status in {"R", "C"} and len(fields) >= 3:
            pairs.append(("D", fields[1]))
            pairs.append(("A", fields[2]))
        elif len(fields) >= 2:
            pairs.append((status, fields[1]))

    # Untracked files are part of the change even before they are added.
    try:
        untracked = _run_git(["ls-files", "--others", "--exclude-standard"])
    except RuntimeError:
        untracked = ""
    for path in untracked.splitlines():
        if path.strip():
            pairs.append(("A", path.strip()))

    seen: set[tuple[str, str]] = set()
    unique: list[tuple[str, str]] = []
    for pair in pairs:
        if pair not in seen:
            seen.add(pair)
            unique.append(pair)
    return unique


def empty_lanes() -> dict[str, bool]:
    return {lane: False for lane in LANES}


def classify(pairs: list[tuple[str, str]]) -> dict[str, object]:
    lanes = empty_lanes()
    result: dict[str, object] = {
        "lanes": lanes,
        "unknown": False,
        "full_required": False,
        "paths": sorted({path for _, path in pairs}),
    }
    if not pairs:
        # No diff information at all: never claim "nothing to test".
        result["unknown"] = True
        result["full_required"] = True
        for lane in LANES:
            lanes[lane] = True
        return result

    for _, path in pairs:
        if path in SECURITY_OR_CONTRACT_PATHS or path.startswith(SECURITY_OR_CONTRACT_PREFIXES):
            result["full_required"] = True

    if result["full_required"]:
        for lane in LANES:
            lanes[lane] = True
        return result

    matched_any = False
    for status, path in pairs:
        hit = classify_path(status, path, lanes)
        matched_any = matched_any or hit
        if not hit and path.startswith("oai-core/"):
            result["full_required"] = True

    if result["full_required"]:
        for lane in LANES:
            lanes[lane] = True
        return result

    if not matched_any:
        # A diff we cannot place is treated as full-scope, not as a no-op.
        result["unknown"] = True
        result["full_required"] = True
        for lane in LANES:
            lanes[lane] = True
    return result


def classify_path(status: str, path: str, lanes: dict[str, bool]) -> bool:
    """Mark the lanes a single path affects. Returns True when it was classified."""
    # Documentation and review artefacts never gate code lanes.
    if path.startswith(("docs/", "tmp/")) or path.endswith((".md", ".txt")):
        return True

    if path.startswith(("plugins/", "plugin-tooling/")):
        lanes["fast"] = True
        # Plugin payloads are installed and executed by the app tests.
        lanes["android-app"] = True
        lanes["android-play"] = True
        return True

    if path.startswith("integrations/dsh-session-adapter/"):
        lanes["dsh"] = True
        lanes["fast"] = True
        return True

    if path.startswith("integrations/hermes-session-adapter/") or "hermes" in path:
        lanes["hermes"] = True
        lanes["core"] = True
        return True

    if path.startswith("oai-core/"):
        lanes["core"] = True
        # Authentication/authorization/session semantics are shared with every
        # other lane's fixtures; widening is cheaper than a false green.
        return not path.startswith(CORE_SECURITY_PREFIXES)

    if path.startswith("gateway-contract/"):
        lanes["fast"] = True
        return True

    if path.startswith("tools/measure-communication"):
        lanes["core"] = True
        return True

    if path.startswith(("tests/", "scripts/")):
        # Installer and distribution entrypoints are validated by the core lane.
        lanes["core"] = True
        return True

    if path.startswith("apps/android/"):
        return classify_android(status, path, lanes)

    return False


def classify_android(status: str, path: str, lanes: dict[str, bool]) -> bool:
    rest = path[len("apps/android/"):]
    module = rest.split("/", 1)[0]

    # Gradle wiring itself is already full-scope via SECURITY_OR_CONTRACT_PATHS.
    lane = ANDROID_MODULE_LANES.get(module)
    if lane:
        lanes[f"android-{lane}"] = True
        # Modules are compiled into the app; the app lane must follow them.
        lanes["android-app"] = True
        lanes["android-play"] = True
        return True

    if rest.startswith("app/"):
        # The app module always needs its own unit tests, but the *feature* lane
        # is chosen from the touched package so a copy tweak does not drag in
        # gateway/notification suites.
        lanes["android-app"] = True
        lanes["android-play"] = True
        feature = app_feature(rest)
        if feature:
            lanes[feature] = True
        return True

    if rest.startswith(("tools/", "keystore/")):
        # Release tooling is exercised by the release workflows, not the PR gate.
        return True

    return False


# Package/directory hints inside apps/android/app. Order matters: the first
# match wins, so put the narrow capabilities before the broad ones.
APP_FEATURE_HINTS = (
    ("android-notifications", (
        "/settings/", "/notifications/", "/push/",
        "SettingsScreen", "SettingsNavHost", "SettingsViewModel",
    )),
    ("android-conversation", ("/conversations/", "/conversation/", "Conversation")),
    ("android-gateway", ("/gateway/", "/connection/", "/auth/", "Gateway")),
    ("android-device", ("/screen/", "/plugins/", "/onboarding/", "Plugin")),
)


def app_feature(rest: str) -> str | None:
    for lane, hints in APP_FEATURE_HINTS:
        if any(hint in rest for hint in hints):
            return lane
    return None


def to_github_output(result: dict[str, object]) -> str:
    lanes = result["lanes"]
    assert isinstance(lanes, dict)
    # GitHub job conditions cannot reference dashed output names reliably, so
    # lane ids are exported with underscores.
    lines = [
        f"runs_{lane.replace('-', '_')}={'true' if value else 'false'}"
        for lane, value in lanes.items()
    ]
    lines.append(f"full_required={'true' if result['full_required'] else 'false'}")
    lines.append(f"unknown_diff={'true' if result['unknown'] else 'false'}")
    # Any lane running means the aggregate gate has real work to wait for.
    lines.append(f"any_lane={'true' if any(lanes.values()) else 'false'}")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Classify a diff into CI test lanes.")
    parser.add_argument("--base", default=None, help="Base ref/SHA (merge base used)")
    parser.add_argument("--head", default="HEAD", help="Head ref/SHA")
    parser.add_argument("--github-output", action="store_true", help="Emit GITHUB_OUTPUT lines")
    parser.add_argument("--json", action="store_true", help="Emit the raw classification")
    parser.add_argument("--json-out", default=None, help="Also write the classification to this path")
    args = parser.parse_args(argv)

    pairs = changed_paths(args.base, args.head)
    result = classify(pairs)

    if args.json_out:
        Path(args.json_out).write_text(json.dumps(result, indent=2, sort_keys=True), encoding="utf-8")
    if args.json:
        print(json.dumps(result, indent=2, sort_keys=True))
    if args.github_output:
        print(to_github_output(result))

    if not args.json and not args.github_output:
        lanes = result["lanes"]
        assert isinstance(lanes, dict)
        print(f"changed files: {len(pairs)}")
        for path in result["paths"]:
            print(f"  {path}")
        print("lanes:")
        for lane, value in lanes.items():
            print(f"  {lane}: {'yes' if value else 'no'}")
        if result["full_required"]:
            print("full matrix required: yes")
        if result["unknown"]:
            print("unclassified diff: yes (conservative full run)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
