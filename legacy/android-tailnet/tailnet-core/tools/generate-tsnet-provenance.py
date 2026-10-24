#!/usr/bin/env python3
"""Generate the provenance sidecar for the locked tsnet Android AAR."""

import argparse
import json
import pathlib
import subprocess
import sys

_REPO_ROOT = pathlib.Path(__file__).resolve().parents[4]
TOOLS = {
    "go": "/usr/sbin/go",
    "gomobile": str(_REPO_ROOT / ".toolchains/go-workspace/bin/gomobile"),
    "gobind": str(_REPO_ROOT / ".toolchains/go-workspace/bin/gobind"),
}


def sha256(path: str) -> str:
    return subprocess.run(["sha256sum", path], capture_output=True, text=True).stdout.split()[0]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    parser.add_argument("--aar", required=True)
    parser.add_argument("--raw-aar", required=True)
    parser.add_argument("--aar-sha256", required=True)
    parser.add_argument("--raw-sha256", required=True)
    parser.add_argument("--source-manifest", required=True)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--source-tag-object", required=True)
    parser.add_argument("--source-release", required=True)
    parser.add_argument("--source-date-epoch", required=True, type=int)
    args = parser.parse_args()

    tool = {}
    for name, path in TOOLS.items():
        if name == "go":
            ver = subprocess.run([path, "version"], capture_output=True, text=True).stdout.splitlines()
            version_line = ver[0] if ver else ""
        else:
            mod = subprocess.run(["go", "version", "-m", path], capture_output=True, text=True).stdout
            mod_line = [l for l in mod.splitlines() if l.startswith("\tmod\t") and "golang.org/x/mobile" in l]
            version_line = mod_line[0].strip() if mod_line else "unknown"
        tool[name] = {"binarySha256": sha256(path), "versionLine": version_line}

    prov = {
        "schemaVersion": 1,
        "artifact": {
            "name": "tsnet-android-1.98.10.aar",
            "sha256": args.aar_sha256,
            "rawAarSha256": args.raw_sha256,
            "sizeBytes": pathlib.Path(args.aar).stat().st_size,
        },
        "source": {
            "upstreamUrl": "https://github.com/tailscale/tailscale.git",
            "release": args.source_release,
            "tagObject": args.source_tag_object,
            "commit": args.source_commit,
            "manifestSha256": pathlib.Path(args.source_manifest).read_text().strip(),
        },
        "toolchain": tool,
        "build": {
            "argumentVector": [
                "gomobile", "bind",
                "-target=android/arm64,android/amd64",
                "-androidapi=34",
                "-trimpath",
                "-tags=ts_omit_cachenetmap",
                "-ldflags=-buildid= -linkmode=external -extldflags=-Wl,-z,max-page-size=16384",
                "-o", "tsnet-android-1.98.10.raw.aar",
                "./tsnetbridge",
            ],
            "sourceDateEpoch": args.source_date_epoch,
            "environment": {
                "TZ": "UTC", "LC_ALL": "C", "LANG": "C",
                "GOPROXY": "file://<toolchains>/gomodcache/cache/download",
                "GOSUMDB": "off", "CGO_ENABLED": "1",
            },
        },
        "outputs": {
            "aar": "apps/android/tailnet-core/libs/tsnet-android-1.98.10.aar",
            "sha256": "apps/android/tailnet-core/libs/tsnet-android-1.98.10.aar.sha256",
            "provenance": "apps/android/tailnet-core/libs/tsnet-android-1.98.10.provenance.json",
            "sbom": "apps/android/tailnet-core/libs/tsnet-android-1.98.10.sbom.json",
        },
    }
    pathlib.Path(args.output).write_text(json.dumps(prov, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"provenance: {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
