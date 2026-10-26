#!/usr/bin/env python3
"""Generate the CycloneDX 1.5 SBOM for the locked tsnet Android AAR.

Inputs:
  --modules   <json>  output of `go list -m -json all` from the wrapper module
  --provenance <json> provenance sidecar for the AAR
  --output    <path>  destination SBOM path

Each shipped Go module is a component with its h1 go.sum hash recorded as a
SHA-256 hash (h1 is base64-SHA-256 of the module zip) plus a go:h1 property.
"""

import argparse
import base64
import hashlib
import json
import pathlib
import sys
import uuid


def h1_to_hex(h1: str) -> str:
    if not h1.startswith("h1:"):
        raise ValueError(f"unsupported hash prefix: {h1[:8]!r}")
    return hashlib.sha256(base64.b64decode(h1[3:])).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--modules", required=True)
    parser.add_argument("--provenance", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    text = pathlib.Path(args.modules).read_text()
    decoder = json.JSONDecoder()
    modules = []
    i = 0
    while i < len(text):
        while i < len(text) and text[i].isspace():
            i += 1
        if i >= len(text):
            break
        obj, i = decoder.raw_decode(text, i)
        modules.append(obj)
    provenance = json.loads(pathlib.Path(args.provenance).read_text())

    components = []
    for mod in modules:
        if mod.get("Main"):
            continue
        path = mod["Path"]
        version = mod.get("Version", "")
        if not version:
            continue
        component = {
            "type": "library",
            "bom-ref": f"pkg:golang/{path}@{version}",
            "name": path,
            "version": version,
            "purl": f"pkg:golang/{path}@{version}",
        }
        hashes = []
        if mod.get("Sum"):
            try:
                hashes.append({"alg": "SHA-256", "content": h1_to_hex(mod["Sum"])})
            except ValueError:
                pass
        if hashes:
            component["hashes"] = hashes
        props = []
        if mod.get("Sum"):
            props.append({"name": "go:h1", "value": mod["Sum"]})
        if mod.get("Replace"):
            rep = mod["Replace"]
            props.append({"name": "go:replace", "value": f"{rep.get('Path', '')}@{rep.get('Version', '')}"})
        if props:
            component["properties"] = props
        components.append(component)

    sbom = {
        "bomFormat": "CycloneDX",
        "specVersion": "1.5",
        "serialNumber": f"urn:uuid:{uuid.uuid4()}",
        "version": 1,
        "metadata": {
            "timestamp": provenance.get("build", {}).get("timestamp", "1970-01-01T00:00:00Z"),
            "tools": [
                {"vendor": "Open Android Intelligence", "name": "build-tsnet-aar.sh", "version": "1"},
                {"vendor": "golang.org/x/mobile", "name": "gomobile", "version": provenance.get("toolchain", {}).get("gomobile", {}).get("versionLine", "unknown")},
            ],
            "component": {
                "type": "library",
                "name": "tsnet-android",
                "version": "1.98.10",
                "properties": [
                    {"name": "source:commit", "value": provenance.get("source", {}).get("commit", "")},
                    {"name": "source:release", "value": provenance.get("source", {}).get("release", "")},
                    {"name": "aar:sha256", "value": provenance.get("artifact", {}).get("sha256", "")},
                ],
            },
        },
        "components": components,
    }

    pathlib.Path(args.output).write_text(json.dumps(sbom, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"sbom: {len(components)} components -> {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
