#!/usr/bin/env python3
"""Check recovered signed plugin identities, signatures and payload digests."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
from pathlib import Path, PurePosixPath
import stat
import sys
import zipfile

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey


ROOT = Path(__file__).resolve().parents[3]
EXPECTED = {
    "notifications": "notifications.wasm",
    "sms": "sms.wasm",
    "call-log": "call_log.wasm",
    "screen-reader": "screen_reader.wasm",
    "screen-capture": "screen_capture.wasm",
    "screen-control": "screen_control.wasm",
}
DOMAIN = b"OPEN-ANDROID-INTELLIGENCE-PLUGIN-PACKAGE-V1\n"


def decode(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def verify(directory: Path) -> None:
    if {p.name for p in directory.glob("*.alp")} != {name + ".alp" for name in EXPECTED}:
        raise ValueError("expected exactly the six official package filenames")
    authors = set()
    warnings = []
    for name, wasm in EXPECTED.items():
        package = directory / f"{name}.alp"
        with zipfile.ZipFile(package) as archive:
            entries = archive.infolist()
            names = [entry.filename for entry in entries]
            if len(names) != len(set(names)) or len(names) > 64 or names != sorted(names, key=lambda n: n.encode()):
                raise ValueError(f"{name}: duplicate, unsorted or excessive entries")
            if any(entry.is_dir() or entry.file_size > 8 * 1024 * 1024 or
                   stat.S_ISLNK(entry.external_attr >> 16) or
                   PurePosixPath(entry.filename).is_absolute() or
                   any(part in (".", "..") for part in PurePosixPath(entry.filename).parts) for entry in entries):
                raise ValueError(f"{name}: unsafe archive entry")
            if sum(entry.file_size for entry in entries) > 32 * 1024 * 1024:
                raise ValueError(f"{name}: package exceeds size limit")
            manifest_bytes = archive.read("manifest.json")
            index_bytes = archive.read("files.json")
            manifest = json.loads(manifest_bytes)
            index = json.loads(index_bytes)
            if manifest["plugin"]["id"] != f"org.openandroidintelligence.{name}":
                raise ValueError(f"{name}: unexpected plugin identity")
            if manifest["author"]["algorithm"] != "Ed25519":
                raise ValueError(f"{name}: unexpected signing algorithm")
            public_key = decode(manifest["author"]["publicKey"])
            Ed25519PublicKey.from_public_bytes(public_key).verify(
                decode(archive.read("signature.ed25519").decode().strip()), DOMAIN + manifest_bytes + b"\n" + index_bytes,
            )
            authors.add(hashlib.sha256(public_key).hexdigest())
            indexed = {item["path"]: item for item in index}
            if len(indexed) != len(index) or set(indexed) != set(names) - {"manifest.json", "files.json", "signature.ed25519"}:
                raise ValueError(f"{name}: archive and file index differ")
            for entry, detail in indexed.items():
                payload = archive.read(entry)
                if len(payload) != detail["size"] or hashlib.sha256(payload).hexdigest() != detail["sha256"]:
                    raise ValueError(f"{name}: payload digest mismatch")
                if entry.startswith("schemas/"):
                    current = ROOT / "plugins" / entry
                    if not current.is_file() or current.read_bytes() != payload:
                        warnings.append(f"{name}: {entry} differs from current worktree")
            template = json.loads((ROOT / "plugins" / name / "manifest.json").read_text())
            if {k: v for k, v in manifest.items() if k != "author"} != {k: v for k, v in template.items() if k != "author"}:
                warnings.append(f"{name}: manifest differs from current worktree")
            local_wasm = ROOT / "plugins/target/wasm32-unknown-unknown/release" / wasm
            if local_wasm.is_file() and archive.read("payload/" + wasm) != local_wasm.read_bytes():
                warnings.append(f"{name}: packaged WASM differs from local build output")
        print(f"PASS {name} sha256={hashlib.sha256(package.read_bytes()).hexdigest()}")
    if len(authors) != 1:
        raise ValueError("packages do not share the same author")
    print("AUTHOR sha256=" + next(iter(authors)))
    for warning in warnings:
        print("REVIEW " + warning)
    print("Signed artifact provenance and Android runtime compatibility must be checked separately.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    try:
        verify(parser.parse_args().directory)
    except (OSError, ValueError, KeyError, InvalidSignature, zipfile.BadZipFile) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
