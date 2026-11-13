"""Tests for oai-client skill packaging, CLI integration and automatic installation."""
from pathlib import Path
import subprocess
import sys
import tempfile

import pytest


REPOSITORY = Path(__file__).resolve().parents[1]


def test_oai_client_skill_file_structure_and_frontmatter():
    skill_dir = REPOSITORY / "integrations" / "skills" / "oai-client"
    skill_file = skill_dir / "SKILL.md"
    assert skill_file.is_file(), "SKILL.md must exist in integrations/skills/oai-client"

    content = skill_file.read_text(encoding="utf-8")
    assert content.startswith("---\n"), "SKILL.md must start with YAML frontmatter"
    assert "name: oai-client" in content
    assert "description:" in content
    assert "oai capabilities" in content
    assert "oai invoke" in content
    assert "oai mcp" in content

    # Check references directory
    ref_file = skill_dir / "references" / "capabilities.md"
    assert ref_file.is_file(), "references/capabilities.md must exist"
    ref_content = ref_file.read_text(encoding="utf-8")
    assert "org.openandroidintelligence.notifications.query" in ref_content
    assert "org.openandroidintelligence.sms.query" in ref_content


def test_skills_installer_copies_to_mocked_home():
    sys.path.insert(0, str(REPOSITORY / "oai-core" / "src"))
    from oai_core.skills_installer import get_agent_skill_destinations, get_skill_source_dir, install_skills_to_hosts

    src = get_skill_source_dir()
    assert (src / "SKILL.md").is_file()

    with tempfile.TemporaryDirectory() as tmp_home_str:
        tmp_home = Path(tmp_home_str)
        # Create some simulated agent directories (both connected and unconnected)
        (tmp_home / ".agents" / "skills").mkdir(parents=True)
        (tmp_home / ".claude" / "skills").mkdir(parents=True)
        (tmp_home / ".codex" / "skills").mkdir(parents=True)
        (tmp_home / ".hermes" / "skills").mkdir(parents=True)

        destinations = get_agent_skill_destinations(tmp_home)
        installed = install_skills_to_hosts(home=tmp_home)

        assert len(installed) >= 4
        for installed_dir in installed:
            assert installed_dir.is_dir()
            assert (installed_dir / "SKILL.md").is_file()
            assert (installed_dir / "references" / "capabilities.md").is_file()


def test_oai_cli_skill_install_subcommand():
    with tempfile.TemporaryDirectory() as target_dir_str:
        target_dir = Path(target_dir_str) / "custom-agent-skills"
        result = subprocess.run(
            [sys.executable, "-m", "oai_core.cli", "skill", "install", "--dest", str(target_dir)],
            cwd=REPOSITORY / "oai-core" / "src",
            capture_output=True,
            text=True,
        )
        assert result.returncode == 0, result.stderr
        assert '"skill": "oai-client"' in result.stdout
        assert (target_dir / "oai-client" / "SKILL.md").is_file()


def test_setup_deploys_skills_to_agents(monkeypatch):
    import runpy
    helper = runpy.run_path(str(REPOSITORY / "scripts" / "core_setup.py"))
    deploy = helper["deploy_skills_to_agents"]

    with tempfile.TemporaryDirectory() as fake_home_str:
        fake_home = Path(fake_home_str)
        monkeypatch.setattr(Path, "home", lambda: fake_home)
        (fake_home / ".claude" / "skills").mkdir(parents=True)
        (fake_home / ".dsh-cline" / "skills").mkdir(parents=True)

        deploy(REPOSITORY)

        assert (fake_home / ".claude" / "skills" / "oai-client" / "SKILL.md").is_file()
        assert (fake_home / ".dsh-cline" / "skills" / "oai-client" / "SKILL.md").is_file()
