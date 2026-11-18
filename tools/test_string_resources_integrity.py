#!/usr/bin/env python3
"""兼容入口：实际实现已迁移至 tools/check_string_resources_integrity.py。"""

from __future__ import annotations

import sys
from pathlib import Path

_TOOLS_DIR = str(Path(__file__).resolve().parent)
if _TOOLS_DIR not in sys.path:
    sys.path.insert(0, _TOOLS_DIR)

from check_string_resources_integrity import (
    DEFAULT_RES_DIRS,
    DEFAULT_SCAN_DIR,
    FORMAT_SPEC_PATTERN,
    REPO_ROOT,
    RESOURCE_IMPORT_PATTERN,
    RESOURCE_REFERENCE_PATTERN,
    VALID_PLURAL_QUANTITIES,
    ResourceViolation,
    check_rule_1_placeholders,
    check_rule_2_escapes,
    check_rule_4_plurals,
    main,
    validate_directories,
    validate_resource_file,
    validate_resource_references,
)

__all__ = [
    "DEFAULT_RES_DIRS",
    "DEFAULT_SCAN_DIR",
    "FORMAT_SPEC_PATTERN",
    "REPO_ROOT",
    "RESOURCE_IMPORT_PATTERN",
    "RESOURCE_REFERENCE_PATTERN",
    "VALID_PLURAL_QUANTITIES",
    "ResourceViolation",
    "check_rule_1_placeholders",
    "check_rule_2_escapes",
    "check_rule_4_plurals",
    "main",
    "validate_directories",
    "validate_resource_file",
    "validate_resource_references",
]

if __name__ == "__main__":
    sys.exit(main())
