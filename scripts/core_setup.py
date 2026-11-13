#!/usr/bin/env python3
"""Compatibility entry point for the installed configuration module.

All configuration writes, including recovery, are subject to administrator
verification in :mod:`oai_core.configuration`.  Secrets are never accepted
through command-line arguments or ordinary environment variables.
"""
from __future__ import annotations

from pathlib import Path
import sys

# Source checkouts and standalone installations both resolve this relative to
# the script, never to an untrusted current working directory.
_source = Path(__file__).resolve().parent.parent / "oai-core" / "src"
if (_source / "oai_core" / "configuration.py").is_file():
    sys.path.insert(0, str(_source))

from oai_core.configuration import *  # noqa: F401,F403
from oai_core.configuration import main


if __name__ == "__main__":
    main()
