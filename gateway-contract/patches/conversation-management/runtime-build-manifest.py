#!/usr/bin/env python3
"""Record/check exact runtime build bytes after a reviewed patch has been applied."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


def runtime_manifest(root: Path) -> dict[str, str]:
    if not root.is_dir() or root.is_symlink():
        raise ValueError("runtime root must be an existing regular directory")
    result = {}
    for path in sorted(root.rglob("*")):
        if path.is_symlink():
            raise ValueError(f"runtime symlink is not supported: {path}")
        if path.is_file():
            result[path.relative_to(root).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    if not result:
        raise ValueError("runtime manifest cannot be empty")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime-root", required=True, type=Path)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", type=Path)
    mode.add_argument("--compare", type=Path)
    args = parser.parse_args()
    actual = runtime_manifest(args.runtime_root)
    if args.write:
        args.write.write_text(json.dumps(actual, indent=2) + "\n")
    else:
        expected = json.loads(args.compare.read_text())
        changed = sorted(name for name in set(actual) | set(expected) if actual.get(name) != expected.get(name))
        if changed:
            raise SystemExit("Runtime build differs from reviewed artifacts: " + ", ".join(changed))
        print(f"Runtime build matches all {len(actual)} reviewed files.")


if __name__ == "__main__":
    main()
