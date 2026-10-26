#!/usr/bin/env bash
#
# Open Android Intelligence (OAI) Core 独立配置脚本 (setup.sh / configure.sh)
#
# 支持直接通过 curl 运行：
#   curl -fsSL https://raw.githubusercontent.com/1rua/open-android-intelligence/dev/scripts/setup.sh | bash
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
        info "本向导已配置 Core 会话桥，并自动将适配器环境同步至 Hermes 环境（若检测到 ~/.hermes）。"
        info "请确保在 Hermes 宿主侧启用插件并重启服务："
        info "  hermes plugins enable open-android-intelligence-gateway"
        info "  hermes gateway restart"
    fi
    return 0
}

configure_autostart() {
    local answer=""
    prompt_read "是否配置开机自启服务？(y/n)" answer "y"
    if [ "$answer" != "y" ] && [ "$answer" != "Y" ]; then
        return 0
    fi

    local os_type
    os_type="$(uname -s 2>/dev/null || echo "Unknown")"

    case "${os_type}" in
        Darwin)
            # macOS: 使用 LaunchAgents (用户级后台自启服务)
            local launch_agents_dir="${HOME}/Library/LaunchAgents"
            mkdir -p "${launch_agents_dir}"
            local plist_file="${launch_agents_dir}/com.openandroidintelligence.core.plist"
            cat <<EOF > "${plist_file}"
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.openandroidintelligence.core</string>
    <key>ProgramArguments</key>
    <array>
        <string>${TARGET_DIR}/start-core.sh</string>
    </array>
    <key>WorkingDirectory</key>
    <string>${TARGET_DIR}</string>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardOutPath</key>
    <string>${TARGET_DIR}/runtime/core.log</string>
    <key>StandardErrorPath</key>
    <string>${TARGET_DIR}/runtime/core.log</string>
</dict>
</plist>
EOF
            if command -v launchctl >/dev/null 2>&1; then
                launchctl unload "${plist_file}" 2>/dev/null || true
                launchctl load -w "${plist_file}" 2>/dev/null || true
                success "已配置并启用 macOS LaunchAgent 自启项: ${plist_file}"
            else
                info "已生成 macOS LaunchAgent 配置文件: ${plist_file}"
            fi
            ;;

        MINGW*|MSYS*|CYGWIN*)
            # Windows (Git Bash / MSYS / Cygwin)
            local startup_dir="${APPDATA}/Microsoft/Windows/Start Menu/Programs/Startup"
            if [ -d "${startup_dir}" ]; then
                local vbs_script="${startup_dir}/oai-core.vbs"
                cat <<EOF > "${vbs_script}"
Set WshShell = CreateObject("WScript.Shell")
WshShell.Run chr(34) & "${TARGET_DIR}/start-core.sh" & Chr(34), 0
Set WshShell = Nothing
EOF
                success "已添加 Windows 开机启动项: ${vbs_script}"
            else
                info "未检测到 Windows 启动目录，请手动添加 ${TARGET_DIR}/start-core.sh 到开机启动"
            fi
            ;;

        *)
            # Linux 及其他类 Unix 系统
            local configured=0

            # 1. systemd 用户级服务（适用于大部分现代 Linux 发行版，支持进程守护与崩溃重启）
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
            if command -v systemctl >/dev/null 2>&1; then
                systemctl --user daemon-reload 2>/dev/null || true
                systemctl --user enable oai-core.service 2>/dev/null || true
                systemctl --user restart oai-core.service 2>/dev/null || true
                configured=1
            fi

            # 2. XDG 桌面环境登录自启（兼容 GNOME、KDE Plasma、XFCE、LXQt、Cinnamon、MATE、DWM、Niri、Hyprland 等桌面/窗口管理器）
            local autostart_dir="${HOME}/.config/autostart"
            mkdir -p "${autostart_dir}"
            local desktop_file="${autostart_dir}/oai-core.desktop"
            cat <<EOF > "${desktop_file}"
[Desktop Entry]
Type=Application
Name=OAI Core
Exec=${TARGET_DIR}/start-core.sh
Terminal=false
Hidden=false
NoDisplay=false
X-GNOME-Autostart-enabled=true
X-KDE-autostart-after=panel
Comment=Open Android Intelligence Core Service
EOF
            configured=1

            # 3. 如果系统没有 systemctl，尝试补充 cron @reboot 兜底机制
            if ! command -v systemctl >/dev/null 2>&1 && command -v crontab >/dev/null 2>&1; then
                local cron_cmd="@reboot ${TARGET_DIR}/start-core.sh >/dev/null 2>&1 &"
                (crontab -l 2>/dev/null | grep -Fv "start-core.sh" ; echo "${cron_cmd}") | crontab - 2>/dev/null || true
            fi

            success "已配置开机与桌面会话自启服务（systemd user + XDG autostart）"
            ;;
    esac
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
    if [ ! -f "$helper" ] && [ -n "${OAI_SETUP_SCRIPT_DIR}" ] && [ -f "${OAI_SETUP_SCRIPT_DIR}/core_setup.py" ]; then
        helper="${OAI_SETUP_SCRIPT_DIR}/core_setup.py"
    fi
    if [ ! -f "$helper" ]; then
        # 当通过 curl | bash 远程运行时，若本地没有 core_setup.py 则尝试下载到临时路径
        local tmp_helper="${TARGET_DIR}/.tmp_core_setup.py"
        local branch="${OAI_BRANCH:-dev}"
        local helper_url="https://raw.githubusercontent.com/1rua/open-android-intelligence/${branch}/scripts/core_setup.py"
        if command -v curl >/dev/null 2>&1; then
            curl -fsSL "${helper_url}" -o "${tmp_helper}" 2>/dev/null || true
        elif command -v wget >/dev/null 2>&1; then
            wget -qO "${tmp_helper}" "${helper_url}" 2>/dev/null || true
        fi
        if [ -f "${tmp_helper}" ]; then
            helper="${tmp_helper}"
        fi
    fi
    if [ ! -f "$helper" ]; then
        error "未找到配置辅助脚本 core_setup.py，请确保已安装 OAI Core 或网络连通。"
        exit 1
    fi
    OAI_INIT_ADMIN_PASSWORD="${CFG_PASSWORD}" "${VENV_PYTHON}" "$helper" \
        --root "${TARGET_DIR}" --host "${CFG_HOST}" --port "${CFG_PORT}" \
        --username "${CFG_USERNAME}" --agents "$agents_csv" --hermes-account "$hermes_account"
    if [ "${helper}" = "${TARGET_DIR}/.tmp_core_setup.py" ]; then
        # 兼容清理临时文件（遵循工作区安全删除规则，优先使用 trash-cli 或移至 /tmp）
        if command -v trash-put >/dev/null 2>&1; then
            trash-put "${helper}" 2>/dev/null || true
        else
            mkdir -p /tmp/oai-trash
            mv "${helper}" /tmp/oai-trash/ 2>/dev/null || true
        fi
    fi
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
