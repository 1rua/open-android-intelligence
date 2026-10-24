#!/usr/bin/env python3
"""Deterministic AAR normalizer for the locked tsnet Android artifact.

Normalizes, in order, the inner classes.jar and then the outer AAR:
  - bytewise entry ordering (POSIX byte order of entry names)
  - exactly one entry per name (duplicates rejected)
  - fixed DOS timestamp derived from SOURCE_DATE_EPOCH (UTC)
  - fixed file modes (directories 0755, files 0644)
  - no comments, no extra fields, no absolute paths, no zip-slip paths
  - stable DEFLATE compression, non-zip64 output
  - atomic output: writes to a temporary sibling and renames over the target
  - never edits the raw file in place

Usage:
  normalize-aar.py --source-date-epoch <epoch> --input <raw.aar> --output <normalized.aar>
"""

import argparse
import datetime
import io
import os
import re
import shutil
import sys
import tempfile
import zipfile

PATH_RE = re.compile(r"^(?!.*(?:^|/)\.\.(?:/|$))[^/][^/\\]*$", re.MULTILINE)


def dos_timestamp(epoch: int) -> tuple:
    dt = datetime.datetime.fromtimestamp(epoch, datetime.timezone.utc)
    if dt.year < 1980:
        raise ValueError(f"SOURCE_DATE_EPOCH {epoch} predates the ZIP epoch (1980)")
    return (dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second)


def normalize_zip_bytes(data: bytes, stamp: tuple) -> bytes:
    """Re-pack a ZIP from raw bytes into the canonical normalized form."""
    out = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(data), "r") as src:
        infos = src.infolist()
        names = [i.filename for i in infos]
        if len(set(names)) != len(names):
            raise ValueError("duplicate entries in zip")
        for name in names:
            if name.startswith("/") or "\\" in name or ".." in name.split("/"):
                raise ValueError(f"unsafe entry name: {name!r}")
            if name.endswith("/"):
                continue
        with zipfile.ZipFile(
            out, "w", compression=zipfile.ZIP_DEFLATED, allowZip64=False
        ) as dst:
            for info in sorted(infos, key=lambda i: os.fsencode(i.filename)):
                is_dir = info.filename.endswith("/")
                zi = zipfile.ZipInfo(info.filename, date_time=stamp)
                zi.compress_type = zipfile.ZIP_DEFLATED
                zi.external_attr = (0o40755 if is_dir else 0o100644) << 16
                zi.flag_bits = 0
                zi.internal_attr = 0
                zi.extra = b""
                zi.comment = b""
                zi.create_system = 3  # Unix
                if is_dir:
                    dst.writestr(zi, b"")
                else:
                    dst.writestr(zi, src.read(info.filename))
    return out.getvalue()


def normalize_aar(raw_path: str, out_path: str, epoch: int) -> None:
    stamp = dos_timestamp(epoch)
    raw = open(raw_path, "rb").read()

    # Normalize the inner classes.jar first (if present).
    with zipfile.ZipFile(io.BytesIO(raw), "r") as src:
        names = src.namelist()
        if "classes.jar" in names:
            inner = src.read("classes.jar")
        else:
            inner = None

    entries = {}
    with zipfile.ZipFile(io.BytesIO(raw), "r") as src:
        for info in src.infolist():
            name = info.filename
            if name.startswith("/") or "\\" in name or ".." in name.split("/"):
                raise ValueError(f"unsafe entry name: {name!r}")
            if name in entries:
                raise ValueError(f"duplicate entry: {name!r}")
            entries[name] = src.read(name)

    if inner is not None:
        entries["classes.jar"] = normalize_zip_bytes(inner, stamp)

    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED, allowZip64=False) as dst:
        for name in sorted(entries, key=os.fsencode):
            is_dir = name.endswith("/")
            zi = zipfile.ZipInfo(name, date_time=stamp)
            zi.compress_type = zipfile.ZIP_DEFLATED
            zi.external_attr = (0o40755 if is_dir else 0o100644) << 16
            zi.flag_bits = 0
            zi.internal_attr = 0
            zi.extra = b""
            zi.comment = b""
            zi.create_system = 3
            dst.writestr(zi, entries[name])

    # Atomic replace of the target, never in place.
    directory = os.path.dirname(os.path.abspath(out_path)) or "."
    fd, tmp_path = tempfile.mkstemp(prefix=".normalize-aar.", dir=directory)
    try:
        with os.fdopen(fd, "wb") as fh:
            fh.write(out.getvalue())
        os.chmod(tmp_path, 0o644)
        os.replace(tmp_path, out_path)
    except BaseException:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass
        raise
    print(f"normalized {raw_path} -> {out_path} ({os.path.getsize(out_path)} bytes)")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-date-epoch", required=True, type=int)
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    if not os.path.isfile(args.input):
        print(f"raw input missing: {args.input}", file=sys.stderr)
        return 2
    normalize_aar(args.input, args.output, args.source_date_epoch)
    return 0


if __name__ == "__main__":
    sys.exit(main())
