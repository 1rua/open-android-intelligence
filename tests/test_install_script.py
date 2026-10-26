import subprocess
import sys
from pathlib import Path

def test_install_script_syntax():
    script = Path(__file__).resolve().parent.parent / "scripts" / "install.sh"
    assert script.exists(), f"{script} must exist"
    result = subprocess.run(["bash", "-n", str(script)], capture_output=True, text=True)
    assert result.returncode == 0, f"bash -n failed: {result.stderr}"

def test_install_script_functions():
    script = Path(__file__).resolve().parent.parent / "scripts" / "install.sh"
    # Source the script in bash and test individual functions
    test_bash_code = f"""
    source "{script}"
    resolve_repo_root
    echo "TARGET_DIR=$TARGET_DIR"
    echo "IS_LOCAL_REPO=$IS_LOCAL_REPO"
    detect_agents
    echo "HERMES=$DETECTED_HERMES"
    echo "DSH=$DETECTED_DSH"
    echo "CODEX=$DETECTED_CODEX"
    """
    result = subprocess.run(["bash", "-c", test_bash_code], capture_output=True, text=True)
    assert result.returncode == 0, f"function test failed: {result.stderr}"
    output = result.stdout
    assert "IS_LOCAL_REPO=1" in output
    assert "HERMES=1" in output
    assert "DSH=1" in output

def test_config_generation_and_account_creation(tmp_path):
    script = Path(__file__).resolve().parent.parent / "scripts" / "install.sh"
    repo_root = Path(__file__).resolve().parent.parent

    # Run guide_initial_configuration in a simulated environment
    test_bash_code = f"""
    set -euo pipefail
    source "{script}"
    TARGET_DIR="{tmp_path}"
    mkdir -p "$TARGET_DIR/gateway-contract"
    cp -r "{repo_root}/gateway-contract"/* "$TARGET_DIR/gateway-contract/"
    
    # Mock prompt functions
    prompt_read() {{
        local var_name="$2"
        local default_val="${{3:-}}"
        eval "$var_name=\\"$default_val\\""
    }}
    prompt_password() {{
        local var_name="$2"
        eval "$var_name=\\"testpassword123\\""
    }}
    
    # Mock venv python that includes oai-core and gateway contract in PYTHONPATH
    mkdir -p "$TARGET_DIR/.venv/bin"
    cat > "$TARGET_DIR/.venv/bin/python" << 'EOF'
#!/usr/bin/env bash
PYTHONPATH="{repo_root}/oai-core/src:/home/djbd/.hermes/plugins/open-android-intelligence-gateway:{repo_root}/gateway-contract" /home/djbd/.hermes/hermes-agent/venv/bin/python "$@"
EOF
    chmod +x "$TARGET_DIR/.venv/bin/python"

    INSTALL_HERMES_PLUGIN=1
    INSTALL_DSH_PLUGIN=1
    INSTALL_CODEX_CONFIG=1
    DETECTED_HERMES=1
    DETECTED_DSH=1
    DETECTED_CODEX=1

    guide_initial_configuration
    generate_convenience_scripts
    """
    result = subprocess.run(["bash", "-c", test_bash_code], capture_output=True, text=True)
    assert result.returncode == 0, f"execution failed: {result.stderr}\nstdout: {result.stdout}"

    config_file = tmp_path / "core.json"
    assert config_file.exists()
    
    import json
    cfg = json.loads(config_file.read_text())
    assert cfg["listen"]["port"] == 8045
    assert len(cfg["agents"]) >= 3
    assert any(a["type"] == "hermes" for a in cfg["agents"])
    assert any(a["type"] == "dsh" for a in cfg["agents"])
    assert any(a["type"] == "codex" for a in cfg["agents"])

    start_script = tmp_path / "start-core.sh"
    assert start_script.exists()
    assert "oai serve --config" in start_script.read_text()

def test_install_script_offline_and_update(tmp_path):
    script = Path(__file__).resolve().parent.parent / "scripts" / "install.sh"
    repo_root = Path(__file__).resolve().parent.parent

    # Test update mode when git repo already exists
    test_bash_code = f"""
    set -euo pipefail
    source "{script}"
    TARGET_DIR="{tmp_path}/installed_app"
    mkdir -p "$TARGET_DIR/.git"
    IS_LOCAL_REPO=0
    BRANCH="dev"
    
    # Mock git
    git() {{
        echo "mock git $*"
        return 0
    }}
    prepare_source
    """
    result = subprocess.run(["bash", "-c", test_bash_code], capture_output=True, text=True)
    assert result.returncode == 0
    assert "mock git fetch origin dev" in result.stdout
    assert "mock git pull origin dev" in result.stdout


