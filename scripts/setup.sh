#!/usr/bin/env bash
#
# Open Android Intelligence (OAI) Core 独立配置脚本 (setup.sh / configure.sh)
#
# 用于在已安装 OAI Core 后独立运行配置向导：
# 1. 管理员账号与密码重设/新建；
# 2. 重新配置监听端口与主机地址；
# 3. 重新探测或调整 Agent 插件接入（Hermes、DSH、Codex）；
# 4. 重新生成 core.json 与 start-core.sh 快捷启动脚本。
#

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

info() {
    printf "${BLUE}[INFO]${NC} %s\n" "$*"
}

success() {
    printf "${GREEN}[SUCCESS]${NC} %s\n" "$*"
}

warn() {
    printf "${YELLOW}[WARN]${NC} %s\n" "$*"
}

error() {
    printf "${RED}[ERROR]${NC} %s\n" "$*" >&2
}

banner() {
    printf "${CYAN}${BOLD}"
    cat << 'EOF'
  ___    _    ___    ____                
 / _ \  / \  |_ _|  / ___|___  _ __ ___  
| | | |/ _ \  | |  | |   / _ \| '__/ _ \ 
| |_| / ___ \ | |  | |__| (_) | | |  __/ 
 \___/_/   \_\___|  \____\___/|_|  \___| 
EOF
    printf "${NC}\n"
    printf "${BOLD}Open Android Intelligence - Core 服务配置向导${NC}\n\n"
}

prompt_read() {
    local prompt_text="$1"
    local var_name="$2"
    local default_value="${3:-}"

    if [ -n "$default_value" ]; then
        prompt_text="${prompt_text} [默认: ${default_value}]: "
    else
        prompt_text="${prompt_text}: "
    fi

    local input=""
    if [ -e /dev/tty ] && true >/dev/tty 2>/dev/null; then
        printf "%b" "$prompt_text" > /dev/tty
        read -r input < /dev/tty || true
    elif [ -t 0 ]; then
        printf "%b" "$prompt_text"
        read -r input || true
    else
        input=""
    fi

    if [ -z "$input" ] && [ -n "$default_value" ]; then
        input="$default_value"
    fi
    eval "$var_name=\"\$input\""
}

prompt_password() {
    local prompt_text="$1"
    local var_name="$2"
    local input=""

    if [ -e /dev/tty ] && true >/dev/tty 2>/dev/null; then
        printf "%b: " "$prompt_text" > /dev/tty
        stty -echo < /dev/tty 2>/dev/null || true
        read -r input < /dev/tty || true
        stty echo < /dev/tty 2>/dev/null || true
        printf "\n" > /dev/tty
    elif [ -t 0 ]; then
        printf "%b: " "$prompt_text"
        stty -echo 2>/dev/null || true
        read -r input || true
        stty echo 2>/dev/null || true
        printf "\n"
    else
        input=""
    fi
    eval "$var_name=\"\$input\""
}

resolve_paths() {
    local script_dir=""
    if [ -n "${BASH_SOURCE[0]:-}" ]; then
        script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" 2>/dev/null && pwd || true)"
    fi

    if [ -n "$script_dir" ] && [ -f "${script_dir}/../oai-core/pyproject.toml" ]; then
        TARGET_DIR="$(cd "${script_dir}/.." && pwd)"
    elif [ -f "./oai-core/pyproject.toml" ]; then
        TARGET_DIR="$(pwd)"
    elif [ -d "${HOME}/.oai-core" ]; then
        TARGET_DIR="${HOME}/.oai-core"
    else
        TARGET_DIR="$(pwd)"
    fi

    CONFIG_FILE="${TARGET_DIR}/core.json"
    VENV_PYTHON="${TARGET_DIR}/.venv/bin/python"

    if [ ! -f "${VENV_PYTHON}" ]; then
        if command -v python3 >/dev/null 2>&1; then
            VENV_PYTHON="$(command -v python3)"
        else
            error "未找到可用的 Python 环境，请先运行 install.sh 安装 OAI Core。"
            exit 1
        fi
    fi
}

detect_agents() {
    info "正在探测当前宿主机上的 Agent..."

    DETECTED_HERMES=0
    DETECTED_DSH=0
    DETECTED_CODEX=0

    if command -v hermes >/dev/null 2>&1 || [ -d "${HOME}/.hermes" ]; then
        DETECTED_HERMES=1
        info "-> 发现 Hermes Agent"
    fi

    local dsh_home="${DSH_HOME:-${HOME}/.dsh-cline}"
    if [ -d "${dsh_home}" ] || [ -d "${HOME}/.dsh" ] || command -v dsh >/dev/null 2>&1; then
        DETECTED_DSH=1
        info "-> 发现 DSH (DeepSeek Harness) 环境"
    fi

    if command -v codex >/dev/null 2>&1 || [ -d "${HOME}/.codex" ]; then
        DETECTED_CODEX=1
        info "-> 发现 Codex 环境"
    fi
}

configure_network_and_account() {
    # 读取既有配置默认值（若存在）
    local default_host="127.0.0.1"
    local default_port="8045"
    if [ -f "${CONFIG_FILE}" ]; then
        local existing_host
        existing_host=$("${VENV_PYTHON}" -c "import json; cfg=json.load(open('${CONFIG_FILE}')); print(cfg.get('listen', {}).get('host', '127.0.0.1'))" 2>/dev/null || echo "127.0.0.1")
        local existing_port
        existing_port=$("${VENV_PYTHON}" -c "import json; cfg=json.load(open('${CONFIG_FILE}')); print(cfg.get('listen', {}).get('port', 8045))" 2>/dev/null || echo "8045")
        [ -n "$existing_host" ] && default_host="$existing_host"
        [ -n "$existing_port" ] && default_port="$existing_port"
    fi

    prompt_read "请输入 OAI Core 监听主机地址 (内网/本机使用 127.0.0.1，局域网共享使用 0.0.0.0)" CFG_HOST "${default_host}"
    prompt_read "请输入 OAI Core 监听端口" CFG_PORT "${default_port}"

    prompt_read "请输入需要配置或新建的管理员用户名" CFG_USERNAME "alice"

    while true; do
        prompt_password "请设置 ${CFG_USERNAME} 的密码" CFG_PASSWORD
        if [ -z "$CFG_PASSWORD" ]; then
            warn "密码不能为空，请重新输入。"
            continue
        fi
        prompt_password "请再次输入密码以确认" CFG_PASSWORD_CONFIRM
        if [ "$CFG_PASSWORD" != "$CFG_PASSWORD_CONFIRM" ]; then
            error "两次输入的密码不一致，请重新输入！"
        else
            break
        fi
    done
}

configure_agent_integrations() {
    local answer=""
    INSTALL_HERMES=0
    INSTALL_DSH=0
    INSTALL_CODEX=0

    if [ "$DETECTED_HERMES" -eq 1 ]; then
        prompt_read "是否启用 Hermes Agent 会话适配器接入？(y/n)" answer "y"
        [ "$answer" = "y" ] || [ "$answer" = "Y" ] && INSTALL_HERMES=1
    fi

    if [ "$DETECTED_DSH" -eq 1 ]; then
        prompt_read "是否启用 DSH (DeepSeek Harness) 会话适配器接入？(y/n)" answer "y"
        [ "$answer" = "y" ] || [ "$answer" = "Y" ] && INSTALL_DSH=1
    fi

    if [ "$DETECTED_CODEX" -eq 1 ]; then
        prompt_read "是否启用 Codex app-server 会话适配器接入？(y/n)" answer "y"
        [ "$answer" = "y" ] || [ "$answer" = "Y" ] && INSTALL_CODEX=1
    fi

    # 执行 Hermes 插件安装/启用
    if [ "$INSTALL_HERMES" -eq 1 ] && command -v hermes >/dev/null 2>&1; then
        info "正在检查并为 Hermes 启用原生会话适配器..."
        hermes plugins disable open-android-intelligence-gateway 2>/dev/null || true
        local hermes_path="${TARGET_DIR}/integrations/hermes-session-adapter"
        if [ -d "$hermes_path" ]; then
            hermes plugins install "$hermes_path" --enable || true
        fi
    fi
}

save_configuration_and_account() {
    local runtime_dir="${TARGET_DIR}/runtime"
    local accounts_dir="${runtime_dir}/accounts"
    local secrets_dir="${runtime_dir}/secrets"
    local master_key_file="${secrets_dir}/core-master-key"

    mkdir -p "${accounts_dir}" "${secrets_dir}"
    chmod 0700 "${secrets_dir}" "${accounts_dir}" 2>/dev/null || true

    if [ ! -f "${master_key_file}" ]; then
        info "正在生成 32 字节本地主加密密钥 (${master_key_file})..."
        if command -v openssl >/dev/null 2>&1; then
            openssl rand 32 > "${master_key_file}"
        else
            dd if=/dev/urandom of="${master_key_file}" bs=32 count=1 2>/dev/null
        fi
    fi
    chmod 0600 "${master_key_file}"

    local agents_array=()

    if [ "$INSTALL_DSH" -eq 1 ]; then
        local dsh_token
        dsh_token=$(openssl rand -hex 16 2>/dev/null || date +%s%N | sha256sum | head -c 32)
        export OAI_DSH_ADAPTER_TOKEN="${dsh_token}"
        agents_array+=("{\"id\":\"dsh\",\"name\":\"DSH\",\"type\":\"dsh\",\"url\":\"http://127.0.0.1:8791\",\"tokenEnv\":\"OAI_DSH_ADAPTER_TOKEN\"}")
    fi

    if [ "$INSTALL_HERMES" -eq 1 ]; then
        local hermes_token
        hermes_token=$(openssl rand -hex 16 2>/dev/null || date +%s%N | sha256sum | head -c 32)
        export OAI_HERMES_SESSION_TOKEN="${hermes_token}"
        agents_array+=("{\"id\":\"hermes\",\"name\":\"Hermes\",\"type\":\"hermes\",\"url\":\"http://127.0.0.1:8792\",\"tokenEnv\":\"OAI_HERMES_SESSION_TOKEN\",\"accounts\":{\"${CFG_USERNAME}\":\"${CFG_USERNAME}\"}}")
    fi

    if [ "$INSTALL_CODEX" -eq 1 ]; then
        agents_array+=("{\"id\":\"codex\",\"name\":\"Codex\",\"type\":\"codex\",\"command\":[\"codex\",\"app-server\"],\"codexHome\":\"runtime/codex\"}")
    fi

    local agents_json="[]"
    if [ ${#agents_array[@]} -gt 0 ]; then
        local joined
        joined=$(IFS=,; echo "${agents_array[*]}")
        agents_json="[${joined}]"
    fi

    info "正在保存配置文件至: ${CONFIG_FILE}..."
    cat > "${CONFIG_FILE}" << EOF
{
  "storageRoot": "runtime/accounts",
  "masterKeyFile": "runtime/secrets/core-master-key",
  "contractRoot": "gateway-contract",
  "listen": {
    "host": "${CFG_HOST}",
    "port": ${CFG_PORT}
  },
  "agents": ${agents_json}
}
EOF
    chmod 0600 "${CONFIG_FILE}" 2>/dev/null || true
    success "配置文件保存成功: ${CONFIG_FILE}"

    info "正在配置用户账号: ${CFG_USERNAME}..."
    OAI_INIT_CONFIG="${CONFIG_FILE}" \
    OAI_INIT_ADMIN_USERNAME="${CFG_USERNAME}" \
    OAI_INIT_ADMIN_PASSWORD="${CFG_PASSWORD}" \
    "${VENV_PYTHON}" -c '
import os
import sys
from pathlib import Path
from oai_core.server import load_config, create_core

cfg_path = Path(os.environ["OAI_INIT_CONFIG"])
username = os.environ["OAI_INIT_ADMIN_USERNAME"]
password = os.environ["OAI_INIT_ADMIN_PASSWORD"]

cfg = load_config(cfg_path)
core = create_core(cfg)

account = core.open_gateway_account(username)
try:
    account.credentials.create_password(password)
    print(f"[SUCCESS] 用户 {username} 凭据更新/创建成功！")
finally:
    account.close()
'

    # 重新生成 start-core.sh
    local start_script="${TARGET_DIR}/start-core.sh"
    local env_exports=""
    if [ -n "${OAI_DSH_ADAPTER_TOKEN:-}" ]; then
        env_exports="${env_exports}export OAI_DSH_ADAPTER_TOKEN=\"\${OAI_DSH_ADAPTER_TOKEN:-${OAI_DSH_ADAPTER_TOKEN}}\"\n"
    fi
    if [ -n "${OAI_HERMES_SESSION_TOKEN:-}" ]; then
        env_exports="${env_exports}export OAI_HERMES_SESSION_TOKEN=\"\${OAI_HERMES_SESSION_TOKEN:-${OAI_HERMES_SESSION_TOKEN}}\"\n"
    fi

    cat > "${start_script}" << EOF
#!/usr/bin/env bash
set -euo pipefail
DIR="\$(cd "\$(dirname "\${BASH_SOURCE[0]}")" && pwd)"
cd "\${DIR}"

if [ -f "\${DIR}/.venv/bin/activate" ]; then
    # shellcheck disable=SC1091
    source "\${DIR}/.venv/bin/activate"
fi

$(printf "%b" "${env_exports}")
echo "=========================================="
echo "正在启动 Open Android Intelligence Core..."
echo "配置文件: \${DIR}/core.json"
echo "=========================================="

exec oai serve --config "\${DIR}/core.json"
EOF
    chmod +x "${start_script}"
    success "快捷启动脚本已同步更新: ${start_script}"
}

main() {
    banner
    resolve_paths
    detect_agents
    configure_network_and_account
    configure_agent_integrations
    save_configuration_and_account
    printf "\n"
    success "OAI Core 配置流程已全部完成！"
    info "您可随时运行 ${TARGET_DIR}/start-core.sh 启动服务。"
}

if [ "${BASH_SOURCE[0]:-}" = "$0" ] || [ -z "${BASH_SOURCE[0]:-}" ]; then
    main "$@"
fi
