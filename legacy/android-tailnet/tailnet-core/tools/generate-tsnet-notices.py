#!/usr/bin/env python3
"""Generate THIRD_PARTY_NOTICES.md for the locked tsnet Android AAR.

Enumerates every Go module that is actually linked into the artifact (the
`go list -deps` build closure of the wrapper package), detects the license from
the module cache (LICENSE/COPYING/LICENCE files), records the SPDX identifier
and license-file SHA-256. When the extracted module directory is absent it
falls back to reading license files from the cached module ZIP. Unknown or
incompatible licenses are flagged for controller review.
"""

import argparse
import hashlib
import io
import json
import pathlib
import re
import sys
import zipfile

SPDX_RULES = [
    (re.compile(r"Apache License\s+Version 2\.0|Apache-2\.0|apache 2\.0", re.I), "Apache-2.0"),
    (re.compile(r"MIT License|Permission is hereby granted, free of charge", re.I), "MIT"),
    (re.compile(r"BSD 3-Clause|Redistribution and use in source and binary forms", re.I), "BSD-3-Clause"),
    (re.compile(r"BSD 2-Clause", re.I), "BSD-2-Clause"),
    (re.compile(r"ISC License|Permission to use, copy, modify, and/or distribute this software", re.I), "ISC"),
    (re.compile(r"Mozilla Public License Version 2\.0|MPL-2\.0", re.I), "MPL-2.0"),
    (re.compile(r"GNU LESSER GENERAL PUBLIC LICENSE", re.I), "LGPL-3.0-only"),
    (re.compile(r"GNU GENERAL PUBLIC LICENSE", re.I), "GPL-3.0-only"),
    (re.compile(r"Unlicense|This is free and unencumbered software released into the public domain", re.I), "Unlicense"),
    (re.compile(r"Blue Oak Model License", re.I), "BlueOak-1.0.0"),
    (re.compile(r"CC0|Creative Commons Legal Code", re.I), "CC0-1.0"),
    (re.compile(r"Zlib License|zlib/libpng", re.I), "Zlib"),
]

LICENSE_NAMES = (
    "LICENSE", "LICENSE.md", "LICENSE.txt", "LICENCE", "LICENCE.md", "LICENCE.txt",
    "COPYING", "COPYING.md", "COPYING.txt", "LICENSE-APACHE", "LICENSE-MIT",
)


def detect_spdx(text: str) -> str:
    for pattern, spdx in SPDX_RULES:
        if pattern.search(text):
            return spdx
    return "UNKNOWN"


def module_dir(cache: pathlib.Path, path: str, version: str) -> pathlib.Path:
    return cache / f"{path}@{version}"


def module_zip(cache: pathlib.Path, path: str, version: str) -> pathlib.Path:
    return cache / "cache/download" / path / "@v" / f"{version}.zip"


def license_from_dir(module_dir_path: pathlib.Path):
    if not module_dir_path.is_dir():
        return None
    for name in LICENSE_NAMES:
        candidate = module_dir_path / name
        if candidate.is_file():
            return candidate.read_bytes(), name
    matches = sorted(module_dir_path.glob("LICENSE*")) + sorted(module_dir_path.glob("COPYING*"))
    if matches:
        return matches[0].read_bytes(), matches[0].name
    return None


def license_from_zip(zip_path: pathlib.Path):
    if not (zip_path.is_file() or zip_path.is_symlink()):
        return None
    try:
        with zipfile.ZipFile(zip_path) as zf:
            for name in zf.namelist():
                rel = name.split("/", 2)
                if len(rel) < 3:
                    continue
                rel = rel[2]
                if any(rel == lic or rel.startswith(lic + "/") for lic in LICENSE_NAMES):
                    data = zf.read(name)
                    if len(data) <= 4 * 1024 * 1024:
                        return data, rel.split("/")[-1]
    except (zipfile.BadZipFile, KeyError, OSError):
        return None
    return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--modules", required=True)
    parser.add_argument("--gomodcache", required=True)
    parser.add_argument("--staged-source", default="")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    text = pathlib.Path(args.modules).read_text()
    try:
        parsed = json.loads(text)
        modules = parsed if isinstance(parsed, list) else [parsed]
    except json.JSONDecodeError:
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
    cache = pathlib.Path(args.gomodcache)
    staged = pathlib.Path(args.staged_source) if args.staged_source else None

    rows = []
    unknown = []
    for mod in modules:
        path = mod.get("Path") or mod.get("path", "")
        version = mod.get("Version") or mod.get("version", "")
        if not path or not version:
            continue
        if path == "tailscale.com" and staged is not None:
            license_data = (staged / "LICENSE").read_bytes()
            license_name = "LICENSE"
        else:
            found = license_from_dir(module_dir(cache, path, version))
            if found is None:
                found = license_from_zip(module_zip(cache, path, version))
            license_data, license_name = (None, None) if found is None else found
        if license_data is None:
            spdx = "UNKNOWN (no license file)"
            digest = ""
        else:
            spdx = detect_spdx(license_data.decode("utf-8", "replace"))
            digest = hashlib.sha256(license_data).hexdigest()
            if spdx == "UNKNOWN":
                unknown.append(path)
        rows.append((path, version, spdx, digest))

    lines = [
        "# THIRD PARTY NOTICES",
        "",
        "This artifact embeds a Go binding of Tailscale `v1.98.10` built with",
        "gomobile. The following Go modules are linked into the native",
        "libraries (the `go list -deps` build closure of the wrapper package)",
        "and their licenses are enumerated below. License texts are the files",
        "shipped in the pinned Go module cache.",
        "",
        "| Module | Version | SPDX | License file SHA-256 |",
        "| --- | --- | --- | --- |",
    ]
    for path, version, spdx, digest in rows:
        lines.append(f"| `{path}` | {version} | {spdx} | {digest} |")
    if unknown:
        lines.append("")
        lines.append("## Controller review required")
        lines.append("")
        lines.append("License could not be classified for:")
        for path in unknown:
            lines.append(f"- `{path}`")
    lines.append("")
    pathlib.Path(args.output).write_text("\n".join(lines), encoding="utf-8")
    print(f"notices: {len(rows)} shipped modules, {len(unknown)} unclassified -> {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
