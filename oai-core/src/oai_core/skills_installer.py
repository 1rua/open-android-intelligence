"""Skill asset locator and host installer for connected and unconnected agents."""
from __future__ import annotations

import os
from pathlib import Path
import shutil
from typing import Sequence


def get_skill_source_dir() -> Path:
    """Find the canonical oai-client skill directory.

    Searches in:
    1. Installed package: oai_core/skills/oai-client
    2. Repository development tree: integrations/skills/oai-client
    """
    package_dir = Path(__file__).resolve().parent / "skills" / "oai-client"
    if (package_dir / "SKILL.md").is_file():
        return package_dir

    repo_dir = Path(__file__).resolve().parents[3] / "integrations" / "skills" / "oai-client"
    if (repo_dir / "SKILL.md").is_file():
        return repo_dir

    raise FileNotFoundError("Could not locate oai-client skill assets")


def get_agent_skill_destinations(home: Path | None = None) -> list[Path]:
    """Discover all common AI agent skill directories on the host."""
    user_home = home or Path.home()
    destinations: list[Path] = []

    # 1. Standard Agent frameworks / tools (connected or unconnected)
    candidates = [
        user_home / ".agents" / "skills",
        user_home / ".claude" / "skills",
        user_home / ".codex" / "skills",
        user_home / ".hermes" / "skills",
        user_home / ".codebuddy" / "skills",
        user_home / ".trae" / "skills",
    ]

    # 2. DSH (DeepSeek Harness) skills
    dsh_home_env = os.environ.get("DSH_HOME")
    if dsh_home_env:
        candidates.append(Path(dsh_home_env) / "skills")
    candidates.append(user_home / ".dsh-cline" / "skills")
    candidates.append(user_home / ".dsh" / "skills")

    seen = set()
    for candidate in candidates:
        resolved = candidate.resolve()
        if resolved not in seen:
            seen.add(resolved)
            destinations.append(resolved)

    return destinations


def install_skills_to_hosts(home: Path | None = None,
                            target_dirs: Sequence[Path] | None = None) -> list[Path]:
    """Copy the oai-client skill to all discovered or specified agent skill directories.

    Returns the list of paths where the skill was successfully installed.
    """
    source_dir = get_skill_source_dir()
    destinations = list(target_dirs) if target_dirs is not None else get_agent_skill_destinations(home)

    installed_paths: list[Path] = []
    for dest_dir in destinations:
        try:
            skill_target = dest_dir / "oai-client"
            skill_target.parent.mkdir(parents=True, exist_ok=True)
            # Safe replacement: remove existing target directory if present
            if skill_target.is_symlink() or skill_target.is_file():
                skill_target.unlink()
            elif skill_target.is_dir():
                shutil.rmtree(skill_target, ignore_errors=True)

            shutil.copytree(source_dir, skill_target)
            installed_paths.append(skill_target)
        except OSError:
            # Skip read-only or inaccessible locations gracefully
            continue

    return installed_paths
