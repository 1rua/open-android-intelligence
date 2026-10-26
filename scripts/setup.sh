#!/usr/bin/env bash
#
# Open Android Intelligence (OAI) Core 独立配置脚本 (setup.sh / configure.sh)
#
# 用于在已安装 OAI Core 后独立运行配置向导：
# 1. 保留既有账号凭据，仅为新账号设置密码；
# 2. 重新配置监听端口与主机地址；
# 3. 重新探测或调整 Agent 插件接入（Hermes、DSH、Codex）；
# 4. 重新生成 core.json 与 start-core.sh 快捷启动脚本。
#

set -euo pipefail

OAI_SETUP_SCRIPT_DIR=""
if [ -n "${BASH_SOURCE[0]:-}" ]; then
    OAI_SETUP_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fi

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
    printf -v "$var_name" '%s' "$input"
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
    printf -v "$var_name" '%s' "$input"
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
    local default_host="${CFG_HOST:-0.0.0.0}"
    local default_port="${CFG_PORT:-11451}"
    if [ -f "${CONFIG_FILE}" ]; then
        local existing_host
        existing_host=$(OAI_CONFIG_PATH="${CONFIG_FILE}" "${VENV_PYTHON}" -c 'import json, os; cfg=json.load(open(os.environ["OAI_CONFIG_PATH"])); print(cfg.get("listen", {}).get("host", "0.0.0.0"))' 2>/dev/null || echo "0.0.0.0")
        local existing_port
        existing_port=$(OAI_CONFIG_PATH="${CONFIG_FILE}" "${VENV_PYTHON}" -c 'import json, os; cfg=json.load(open(os.environ["OAI_CONFIG_PATH"])); print(cfg.get("listen", {}).get("port", 11451))' 2>/dev/null || echo "11451")
        [ -n "$existing_host" ] && default_host="$existing_host"
        [ -n "$existing_port" ] && default_port="$existing_port"
    fi

    prompt_read "请输入 OAI Core 监听主机地址 (局域网共享使用 0.0.0.0，仅本机使用 127.0.0.1)" CFG_HOST "${default_host}"
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
        prompt_read "是否配置可选 Hermes 会话桥接入？(y/n)" answer "n"
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

    if [ "$INSTALL_HERMES" -eq 1 ]; then
        info "本向导仅配置 Core 会话桥，不停用或重启既有 Hermes Gateway。"
        info "宿主侧请在其 Python 环境安装 ./integrations/hermes-session-adapter，并按部署文档切换。"
    fi
    return 0
}

configure_autostart() {
    local answer=""
    prompt_read "是否配置开机自启 systemd user 服务？(y/n)" answer "y"
    if [ "$answer" = "y" ] || [ "$answer" = "Y" ]; then
        # 写入 systemd user service
        local user_service_dir="${HOME}/.config/systemd/user"
        mkdir -p "${user_service_dir}"
        local service_file="${user_service_dir}/oai-core.service"
        cat <<EOF > "${service_file}"
[Unit]
Description=Open Android Intelligence Core Service
After=network.target

[Service]
Type=simple
WorkingDirectory=${TARGET_DIR}
ExecStart=${TARGET_DIR}/start-core.sh
Restart=always
RestartSec=3

[Install]
WantedBy=default.target
EOF

        # 写入 XDG 桌面会话自启项，兼顾所有桌面环境登录自启
        local autostart_dir="${HOME}/.config/autostart"
        mkdir -p "${autostart_dir}"
        cat <<EOF > "${autostart_dir}/oai-core.desktop"
[Desktop Entry]
Type=Application
Name=OAI Core
Exec=${TARGET_DIR}/start-core.sh
Terminal=false
Hidden=false
NoDisplay=false
X-GNOME-Autostart-enabled=true
Comment=Open Android Intelligence Core Service
EOF
        if command -v systemctl >/dev/null 2>&1; then
            systemctl --user daemon-reload 2>/dev/null || true
            systemctl --user enable oai-core.service 2>/dev/null || true
            systemctl --user restart oai-core.service 2>/dev/null || true
            success "已配置并启用 systemd user 自启服务: oai-core.service"
        else
            info "已生成服务单元 ${service_file}（未检测到可用 systemctl 命令）"
        fi
    fi
}

save_configuration_and_account() {
    local selected_agents=()
    [ "$INSTALL_DSH" -eq 0 ] || selected_agents+=(dsh)
    [ "$INSTALL_HERMES" -eq 0 ] || selected_agents+=(hermes)
    [ "$INSTALL_CODEX" -eq 0 ] || selected_agents+=(codex)
    local hermes_account=""
    if [ "$INSTALL_HERMES" -eq 1 ]; then
        prompt_read "请输入与 Core 账号一对一绑定的既有 Hermes 账号" hermes_account "${CFG_USERNAME}"
    fi
    local agents_csv
    agents_csv=$(IFS=,; printf '%s' "${selected_agents[*]}")
    local helper="${TARGET_DIR}/scripts/core_setup.py"
    [ -f "$helper" ] || helper="${OAI_SETUP_SCRIPT_DIR}/core_setup.py"
    OAI_INIT_ADMIN_PASSWORD="${CFG_PASSWORD}" "${VENV_PYTHON}" "$helper" \
        --root "${TARGET_DIR}" --host "${CFG_HOST}" --port "${CFG_PORT}" \
        --username "${CFG_USERNAME}" --agents "$agents_csv" --hermes-account "$hermes_account"
    success "配置与启动脚本已保存；既有账号密码、密钥和存储路径已保留。"
    info "桥接凭据存于 runtime/secrets/adapter-tokens.env（0600），启动脚本不含凭据。"
}

main() {
    banner
    resolve_paths
    detect_agents
    configure_network_and_account
    configure_agent_integrations
    save_configuration_and_account
    configure_autostart
    printf "\n"
    success "OAI Core 配置流程已全部完成！"
    info "您可随时运行 ${TARGET_DIR}/start-core.sh 启动服务。"
}

if [ "${BASH_SOURCE[0]:-}" = "$0" ] || [ -z "${BASH_SOURCE[0]:-}" ]; then
    main "$@"
fi
