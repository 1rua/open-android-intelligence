#!/usr/bin/env python3
"""Independent verifier for the locked tsnet Android AAR.

Checks, using only its own ZIP/ELF parsers plus the system tools:
  - AAR zip structure: exactly jni/arm64-v8a/libgojni.so and jni/x86_64/libgojni.so
    native libraries, no other ABIs, no unsafe/duplicate entries
  - AndroidManifest minSdk == 34, no components/permissions (no VPN/TUN/proxy
    or listening surface declared)
  - every native ELF LOAD segment has p_align >= 0x4000 (16 KiB)
  - AAR size <= 80 MiB
  - sha256 sidecar matches the AAR
  - provenance/sbom/notices cross-links are consistent

Exit 0 prints TSNET_AAR_VERIFIED and the final SHA-256.
"""

import argparse
import hashlib
import json
import os
import pathlib
import re
import struct
import sys
import zipfile

MIN_ALIGN = 0x4000
MAX_AAR_BYTES = 80 * 1024 * 1024
EXPECTED_ABIS = {"arm64-v8a", "x86_64"}


def parse_elf_load_alignments(data: bytes) -> list:
    if data[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    is64 = data[4] == 2
    endian = "<" if data[5] == 1 else ">"
    if is64:
        phoff = struct.unpack_from(endian + "Q", data, 32)[0]
        phentsize = struct.unpack_from(endian + "H", data, 54)[0]
        phnum = struct.unpack_from(endian + "H", data, 56)[0]
        fmt = endian + "IIQQQQQQ"
    else:
        phoff = struct.unpack_from(endian + "I", data, 28)[0]
        phentsize = struct.unpack_from(endian + "H", data, 42)[0]
        phnum = struct.unpack_from(endian + "H", data, 44)[0]
        fmt = endian + "IIIIIIII"
    aligns = []
    for i in range(phnum):
        off = phoff + i * phentsize
        ph = struct.unpack_from(fmt, data, off)
        p_type = ph[0]
        if p_type == 1:  # PT_LOAD
            aligns.append(ph[-1])
    return aligns


def check_aar(aar_path: str, errors: list) -> dict:
    size = os.path.getsize(aar_path)
    if size > MAX_AAR_BYTES:
        errors.append(f"AAR size {size} exceeds {MAX_AAR_BYTES}")
    with zipfile.ZipFile(aar_path) as zf:
        infos = zf.infolist()
        names = [i.filename for i in infos]
        if len(set(names)) != len(names):
            errors.append("duplicate entries in AAR")
        for name in names:
            if name.startswith("/") or "\\" in name or ".." in name.split("/"):
                errors.append(f"unsafe entry name: {name!r}")
        if "AndroidManifest.xml" not in names:
            errors.append("AndroidManifest.xml missing")
        if "classes.jar" not in names:
            errors.append("classes.jar missing")
        if "R.txt" not in names:
            errors.append("R.txt missing")
        jni = sorted(n for n in names if n.startswith("jni/"))
        abis = set()
        for n in jni:
            parts = n.split("/")
            if len(parts) != 3 or parts[2] != "libgojni.so":
                errors.append(f"unexpected jni entry: {n!r}")
                continue
            abis.add(parts[1])
        if abis != EXPECTED_ABIS:
            errors.append(f"unexpected ABI set: {sorted(abis)}")
        manifest = zf.read("AndroidManifest.xml").decode("utf-8", "replace")
        m = re.search(r'minSdkVersion="(\d+)"', manifest)
        if not m or int(m.group(1)) != 34:
            errors.append(f"minSdkVersion is not 34: {manifest[:200]}")
        for tag in ("service", "receiver", "provider", "activity", "uses-permission"):
            if re.search(rf"<{tag}\b", manifest):
                errors.append(f"manifest declares <{tag}> (forbidden surface)")
        for name in ("libgojni",):
            for abi in sorted(abis):
                entry = f"jni/{abi}/{name}.so"
                data = zf.read(entry)
                try:
                    aligns = parse_elf_load_alignments(data)
                except ValueError as exc:
                    errors.append(f"{entry}: {exc}")
                    continue
                if not aligns:
                    errors.append(f"{entry}: no PT_LOAD segments")
                for a in aligns:
                    if a < MIN_ALIGN:
                        errors.append(f"{entry}: LOAD p_align {a:#x} < {MIN_ALIGN:#x}")
    return {"size": size, "abis": sorted(abis)}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lock", required=True)
    parser.add_argument("--aar", required=True)
    parser.add_argument("--provenance", required=True)
    parser.add_argument("--sbom", required=True)
    parser.add_argument("--notices", default="")
    args = parser.parse_args()

    errors = []
    aar_path = pathlib.Path(args.aar)
    sha256_sidecar = pathlib.Path(str(aar_path) + ".sha256")
    if not sha256_sidecar.exists():
        errors.append(f"sha256 sidecar missing: {sha256_sidecar}")

    facts = check_aar(str(aar_path), errors)

    if sha256_sidecar.exists():
        actual = hashlib.sha256(aar_path.read_bytes()).hexdigest()
        sidecar_line = sha256_sidecar.read_text().strip().splitlines()[0]
        sidecar_hash = sidecar_line.split()[0]
        if sidecar_hash != actual:
            errors.append(f"sha256 mismatch: sidecar {sidecar_hash} != actual {actual}")
        else:
            facts["sha256"] = actual

    prov = json.loads(pathlib.Path(args.provenance).read_text())
    if facts.get("sha256") and prov.get("artifact", {}).get("sha256") != facts["sha256"]:
        errors.append("provenance sha256 does not match AAR")
    sbom = json.loads(pathlib.Path(args.sbom).read_text())
    if sbom.get("bomFormat") != "CycloneDX":
        errors.append("sbom is not CycloneDX")
    if not sbom.get("components"):
        errors.append("sbom has no components")
    if args.notices and not pathlib.Path(args.notices).exists():
        errors.append(f"notices missing: {args.notices}")

    if errors:
        for e in errors:
            print(f"TSNET_AAR_REJECTED: {e}")
        return 1
    print(f"TSNET_AAR_VERIFIED sha256={facts.get('sha256', '?')} abis={','.join(facts['abis'])} size={facts['size']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
