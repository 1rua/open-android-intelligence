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
    "tools/ci/android_tasks.py",
    "tools/test_ci_planning.py",
    "tools/test_smart_test.py",
    ".github/workflows/oai-core.yml",
    ".github/workflows/release-gate.yml",
    "apps/android/ci/app-regression.txt",
    "apps/android/ci/hermes-fixture-requirements.txt",
}

# Test modules that only the Hermes lane executes. The Core job explicitly
# `--ignore`s them, so classifying them as "core" alone would let a change to a
# test file pass CI without ever running that file.
HERMES_ONLY_TESTS = {
    "oai-core/tests/test_hermes.py",
    "oai-core/tests/test_hermes_native.py",
    "oai-core/tests/test_hermes_native_origin.py",
    "oai-core/tests/test_hermes_zero_config_native.py",
    "oai-core/tests/test_hermes_session_key_recovery.py",
    "oai-core/tests/test_hermes_plugin_zero_config.py",
    "oai-core/tests/test_hermes_interactions.py",
    "oai-core/tests/test_hermes_approval_prompts.py",
    "oai-core/tests/test_hermes_dispatch_delivery.py",
    "oai-core/tests/test_hermes_communication_reliability.py",
    "oai-core/tests/test_hermes_bridge_heartbeat.py",
    "oai-core/tests/test_agent_registration.py",
    "oai-core/tests/test_native_event_sync.py",
    "oai-core/tests/test_native_tool_events.py",
    "oai-core/tests/test_structured_tool_history.py",
    "oai-core/tests/test_interaction_pipeline.py",
    "oai-core/tests/test_communication_process_recovery.py",
    # The measurement suite runs only in the Hermes job.
    "tools/test_measure_communication.py",
}

# oai-core authentication, authorization, sync and gateway code invalidates the
# security assumptions of every other lane.
CORE_SECURITY_PREFIXES = (
    "oai-core/src/oai_core/gateway/",
    "oai-core/src/oai_core/auth",
    "oai-core/src/oai_core/security",
    "oai-core/src/oai_core/sessions",
)

# Module lane -> app-test section. Modules without an entry are cross-cutting and
# widen the app suite to every section (they are compiled into every screen).
MODULE_LANE_TO_APP_SECTION = {
    "notifications": "settings",
    "conversation": "conversations",
    "plugins": "plugins",
}

# Reverse dependencies: changing a library must also run the lanes whose tests
# exercise it. Derived from `implementation(project(":x"))` in each module's
# build script (verified by tools/test_ci_planning.py against the real files).
MODULE_DEPENDENTS = {
    "gateway-client": ("device", "conversation", "plugins"),
    "control-ports": ("conversation", "plugins", "notifications"),
    "capability-ports": ("plugins", "notifications"),
    "platform-kernel": ("conversation", "plugins", "notifications", "device"),
    "plugin-package": ("plugins",),
    "plugin-runtime-wasm": ("plugins",),
    "encrypted-store": ("notifications", "conversation"),
    "policy-engine": ("notifications", "plugins"),
    "device-primitives": ("device", "plugins"),
    "conversation-domain": ("conversation", "plugins"),
}

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
    "core-model": "platform",
    "capability-sync-runtime": "platform",
    "artifact-ports": "platform",
    "assistant-holder": "platform",
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
    "android-plugins",        # plugin package/runtime/UI modules
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
    # App-test sections this diff touches. "core" is always included; the CI app
    # job runs [core] plus these sections so a UI copy change does not drag the
    # whole app regression set into the critical path.
    sections: set[str] = {"core"}
    result: dict[str, object] = {
        "lanes": lanes,
        "unknown": False,
        "full_required": False,
        "app_sections": sections,
        "paths": sorted({path for _, path in pairs}),
    }
    if not pairs:
        # No diff information at all: never claim "nothing to test".
        result["unknown"] = True
        result["full_required"] = True
        for lane in LANES:
            lanes[lane] = True
        result["app_sections"] = ALL_APP_SECTIONS
        return result

    for _, path in pairs:
        if path in SECURITY_OR_CONTRACT_PATHS or path.startswith(SECURITY_OR_CONTRACT_PREFIXES):
            result["full_required"] = True

    if result["full_required"]:
        for lane in LANES:
            lanes[lane] = True
        result["app_sections"] = ALL_APP_SECTIONS
        return result

    unclassified: list[str] = []
    for status, path in pairs:
        if not classify_path(status, path, lanes, sections):
            unclassified.append(path)

    if unclassified:
        # A single path we cannot place widens the matrix, even when a sibling
        # path (docs, a known module) was classified: otherwise one recognised
        # file would mask an unrecognised executable change.
        result["unknown"] = True
        result["full_required"] = True
        result["unclassified_paths"] = unclassified
        for lane in LANES:
            lanes[lane] = True
        result["app_sections"] = ALL_APP_SECTIONS
    return result


# Sections of apps/android/ci/app-regression.txt. "core" is always run.
APP_SECTIONS = ("core", "settings", "conversations", "plugins", "push-notifications")
ALL_APP_SECTIONS = set(APP_SECTIONS)


def classify_path(
    status: str, path: str, lanes: dict[str, bool], sections: set[str]
) -> bool:
    """Mark the lanes a single path affects. Returns True when it was classified."""
    # Documentation and review artefacts never gate code lanes.
    if path.startswith(("docs/", "tmp/")) or path.endswith((".md", ".txt")):
        return True

    if path.startswith(("plugins/", "plugin-tooling/")):
        lanes["fast"] = True
        # Plugin payloads are installed and executed by the app tests.
        lanes["android-app"] = True
        lanes["android-play"] = True
        sections.add("plugins")
        return True

    if path.startswith("integrations/dsh-session-adapter/"):
        lanes["dsh"] = True
        lanes["fast"] = True
        return True

    if path.startswith("integrations/hermes-session-adapter/") or "hermes" in path:
        lanes["hermes"] = True
        lanes["core"] = True
        return True

    if path in HERMES_ONLY_TESTS:
        # The Core job ignores these files; only the Hermes job runs them.
        lanes["hermes"] = True
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
        # Implementation and its tests both live in the Hermes job.
        lanes["hermes"] = True
        lanes["core"] = True
        return True

    if path.startswith(("tests/", "scripts/")):
        # Installer and distribution entrypoints are validated by the core lane.
        lanes["core"] = True
        return True

    if path.startswith("apps/android/"):
        return classify_android(status, path, lanes, sections)

    return False


def classify_android(
    status: str, path: str, lanes: dict[str, bool], sections: set[str]
) -> bool:
    rest = path[len("apps/android/"):]
    module = rest.split("/", 1)[0]

    # Gradle wiring itself is already full-scope via SECURITY_OR_CONTRACT_PATHS.
    lane = ANDROID_MODULE_LANES.get(module)
    if lane:
        lanes[f"android-{lane}"] = True
        # Consumers' tests do not run in the producer's module task.
        for dependent in MODULE_DEPENDENTS.get(module, ()):
            lanes[f"android-{dependent}"] = True
        # Modules are compiled into the app; the app lane must follow them, and
        # the module lane name doubles as the app-test section for the domains we
        # model (settings/conversations/plugins/push-notifications).
        lanes["android-app"] = True
        lanes["android-play"] = True
        section = MODULE_LANE_TO_APP_SECTION.get(lane)
        if section:
            sections.add(section)
        else:
            # device/platform/gateway modules are cross-cutting for the app suite.
            sections.update(ALL_APP_SECTIONS)
        return True

    if rest.startswith("app/"):
        # The app module always needs its own unit tests, but the *feature* lane
        # is chosen from the touched package so a copy tweak does not drag in
        # gateway/notification suites.
        lanes["android-app"] = True
        lanes["android-play"] = True
        for section, lane, hints in APP_FEATURE_HINTS:
            if any(hint in rest for hint in hints):
                lanes[lane] = True
                sections.add(section)
                break
        return True

    if rest.startswith(("tools/", "keystore/")):
        # Release tooling is exercised by the release workflows, not the PR gate.
        return True

    return False


# Package/directory hints inside apps/android/app. Order matters: the first
# match wins, so put the narrow capabilities before the broad ones.
#
# Each hint maps to both a module lane and an app-test section
# (apps/android/ci/app-regression.txt). The section is what lets a UI copy
# change run only its own app tests instead of the whole app regression set.
APP_FEATURE_HINTS = (
    # Narrower sections first: `/push/` and `/notifications/` also live under the
    # broad "settings" umbrella, and first match wins.
    ("push-notifications", "android-notifications", (
        "/push/", "/notifications/",
    )),
    ("settings", "android-notifications", (
        "/settings/", "SettingsScreen", "SettingsNavHost", "SettingsViewModel",
    )),
    ("conversations", "android-conversation", (
        "/conversations/", "/conversation/", "Conversation",
    )),
    ("plugins", "android-device", (
        "/plugins/", "Plugin",
    )),
    ("core", "android-gateway", (
        "/gateway/", "/connection/", "/auth/", "Gateway",
    )),
    ("core", "android-device", (
        "/screen/", "/onboarding/", "Screen",
    )),
)


def app_section(rest: str) -> str | None:
    """Which app-test section a file under apps/android/app belongs to."""
    for section, _lane, hints in APP_FEATURE_HINTS:
        if any(hint in rest for hint in hints):
            return section
    return None


def to_serializable(result: dict[str, object]) -> dict[str, object]:
    """JSON-safe copy: `app_sections` is an internal set, exported as a list."""
    copy = dict(result)
    sections = copy.get("app_sections")
    if isinstance(sections, set):
        copy["app_sections"] = sorted(sections)
    return copy


def to_github_output(result: dict[str, object]) -> str:
    lanes = result["lanes"]
    assert isinstance(lanes, dict)
    # GitHub job conditions cannot reference dashed output names reliably, so
    # lane ids are exported with underscores.
    lines = [
        f"runs_{lane.replace('-', '_')}={'true' if value else 'false'}"
        for lane, value in lanes.items()
    ]
    sections = result.get("app_sections") or set()
    assert isinstance(sections, set)
    lines.append("app_sections=" + ",".join(sorted(sections)))
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
        Path(args.json_out).write_text(
            json.dumps(to_serializable(result), indent=2, sort_keys=True), encoding="utf-8"
        )
    if args.json:
        print(json.dumps(to_serializable(result), indent=2, sort_keys=True))
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
