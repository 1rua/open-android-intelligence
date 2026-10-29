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
    local allow_empty="${3:-0}"
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

    if command -v hermes >/dev/null 2>&1 || [ -f "${HOME}/.hermes/config.yaml" ] || [ -f "${HOME}/.hermes/.env" ] || [ -d "${HOME}/.hermes/hermes-agent" ]; then
        DETECTED_HERMES=1
        info "-> 发现 Hermes Agent"
    fi

    local dsh_home="${DSH_HOME:-${HOME}/.dsh-cline}"
    if command -v dsh >/dev/null 2>&1 || [ -f "${dsh_home}/settings.yaml" ] || [ -f "${dsh_home}/.env" ] || [ -d "${dsh_home}/runtime" ] || [ -f "${HOME}/.dsh/settings.yaml" ] || [ -f "${HOME}/.dsh/.env" ]; then
        DETECTED_DSH=1
        info "-> 发现 DSH (DeepSeek Harness) 环境"
    fi

    if command -v codex >/dev/null 2>&1 || [ -f "${HOME}/.codex/config.toml" ] || [ -f "${HOME}/.codex/auth.json" ]; then
        DETECTED_CODEX=1
        info "-> 发现 Codex 环境"
    fi
}

# 全局配置缓存变量
CFG_HOST=""
CFG_PORT=""
CFG_USERNAME=""
CFG_PASSWORD=""
CFG_PASSWORD_CONFIRM=""
INSTALL_HERMES=""
INSTALL_DSH=""
INSTALL_CODEX=""
HERMES_ACCOUNT=""

load_existing_config() {
    # 优先从 core.json 读取已有值填充默认参数
    if [ -f "${CONFIG_FILE}" ]; then
        if [ -z "${CFG_HOST}" ]; then
            CFG_HOST=$(OAI_CONFIG_PATH="${CONFIG_FILE}" "${VENV_PYTHON}" -c 'import json, os; cfg=json.load(open(os.environ["OAI_CONFIG_PATH"])); print(cfg.get("listen", {}).get("host", "0.0.0.0"))' 2>/dev/null || echo "0.0.0.0")
        fi
        if [ -z "${CFG_PORT}" ]; then
            CFG_PORT=$(OAI_CONFIG_PATH="${CONFIG_FILE}" "${VENV_PYTHON}" -c 'import json, os; cfg=json.load(open(os.environ["OAI_CONFIG_PATH"])); print(cfg.get("listen", {}).get("port", 11451))' 2>/dev/null || echo "11451")
        fi
        if [ -z "${INSTALL_DSH}" ]; then
            local has_dsh
            has_dsh=$(OAI_CONFIG_PATH="${CONFIG_FILE}" "${VENV_PYTHON}" -c 'import json, os; cfg=json.load(open(os.environ["OAI_CONFIG_PATH"])); print(1 if any(a.get("type")=="dsh" for a in cfg.get("agents",[])) else 0)' 2>/dev/null || echo "0")
            [ "$has_dsh" = "1" ] && INSTALL_DSH=1 || INSTALL_DSH=0
        fi
        if [ -z "${INSTALL_HERMES}" ]; then
            local has_hermes
            has_hermes=$(OAI_CONFIG_PATH="${CONFIG_FILE}" "${VENV_PYTHON}" -c 'import json, os; cfg=json.load(open(os.environ["OAI_CONFIG_PATH"])); print(1 if any(a.get("type")=="hermes" for a in cfg.get("agents",[])) else 0)' 2>/dev/null || echo "0")
            [ "$has_hermes" = "1" ] && INSTALL_HERMES=1 || INSTALL_HERMES=0
        fi
        if [ -z "${INSTALL_CODEX}" ]; then
            local has_codex
            has_codex=$(OAI_CONFIG_PATH="${CONFIG_FILE}" "${VENV_PYTHON}" -c 'import json, os; cfg=json.load(open(os.environ["OAI_CONFIG_PATH"])); print(1 if any(a.get("type")=="codex" for a in cfg.get("agents",[])) else 0)' 2>/dev/null || echo "0")
            [ "$has_codex" = "1" ] && INSTALL_CODEX=1 || INSTALL_CODEX=0
        fi
        if [ -z "${HERMES_ACCOUNT}" ]; then
            HERMES_ACCOUNT=$(OAI_CONFIG_PATH="${CONFIG_FILE}" "${VENV_PYTHON}" -c 'import json, os; cfg=json.load(open(os.environ["OAI_CONFIG_PATH"])); agents=[a for a in cfg.get("agents",[]) if a.get("type")=="hermes"]; print(list(agents[0].get("accounts",{}).values())[0] if agents and agents[0].get("accounts") else "")' 2>/dev/null || echo "")
        fi
    fi

    # 兜底默认值
    [ -n "${CFG_HOST}" ] || CFG_HOST="0.0.0.0"
    [ -n "${CFG_PORT}" ] || CFG_PORT="11451"
    [ -n "${CFG_USERNAME}" ] || CFG_USERNAME="alice"
    [ -n "${INSTALL_DSH}" ] || INSTALL_DSH=0
    [ -n "${INSTALL_HERMES}" ] || INSTALL_HERMES=0
    [ -n "${INSTALL_CODEX}" ] || INSTALL_CODEX=0
    [ -n "${HERMES_ACCOUNT}" ] || HERMES_ACCOUNT="${CFG_USERNAME}"
}

# 1. 网络与监听配置
configure_network() {
    load_existing_config
    printf "\n"
    info "${BOLD}=== 配置 OAI Core 监听主机与端口 ===${NC}"
    prompt_read "请输入 OAI Core 监听主机地址 (局域网共享使用 0.0.0.0，仅本机使用 127.0.0.1)" CFG_HOST "${CFG_HOST}"
    prompt_read "请输入 OAI Core 监听端口" CFG_PORT "${CFG_PORT}"
    success "网络监听参数已更新: ${CFG_HOST}:${CFG_PORT}"
}

# 2. 账号与密码配置
configure_account() {
    load_existing_config
    printf "\n"
    info "${BOLD}=== 配置管理员账号与密码 ===${NC}"
    prompt_read "请输入管理员用户名" CFG_USERNAME "${CFG_USERNAME}"

    local is_existing_account=0
    if [ -f "${CONFIG_FILE}" ]; then
        local check_res
        check_res=$(OAI_CONFIG_PATH="${CONFIG_FILE}" "${VENV_PYTHON}" -c "
import json, os, sqlite3, sys
cfg_path = os.environ['OAI_CONFIG_PATH']
cfg = json.load(open(cfg_path))
root = os.path.dirname(cfg_path)
storage = os.path.join(root, cfg.get('storageRoot', 'runtime/accounts'))
db = os.path.join(storage, sys.argv[1], 'gateway.sqlite')
print(1 if os.path.exists(db) else 0)
" "${CFG_USERNAME}" 2>/dev/null || echo "0")
        [ "$check_res" = "1" ] && is_existing_account=1
    fi

    if [ "$is_existing_account" -eq 1 ]; then
        info "检测到账号 [${CFG_USERNAME}] 已存在。"
        local change_pwd=""
        prompt_read "是否修改该账号密码？(y/n)" change_pwd "n"
        if [ "$change_pwd" != "y" ] && [ "$change_pwd" != "Y" ]; then
            CFG_PASSWORD=""
            info "保留账号 [${CFG_USERNAME}] 现有密码。"
            return 0
        fi
    fi

    while true; do
        prompt_password "请设置 ${CFG_USERNAME} 的密码（直接回车可取消改密）" CFG_PASSWORD 1
        if [ -z "$CFG_PASSWORD" ]; then
            if [ "$is_existing_account" -eq 1 ]; then
                info "未输入密码，保留原有密码。"
                break
            else
                warn "新建账号密码不能为空，请重新输入。"
                continue
            fi
        fi
        prompt_password "请再次输入密码以确认" CFG_PASSWORD_CONFIRM 1
        if [ "$CFG_PASSWORD" != "$CFG_PASSWORD_CONFIRM" ]; then
            error "两次输入的密码不一致，请重新输入！"
        else
            break
        fi
    done
    success "账号凭据参数设置完成。"
}

# 3. Agent 接入配置
configure_agent_integrations() {
    load_existing_config
    printf "\n"
    info "${BOLD}=== 配置 AI Agent 适配器接入 ===${NC}"
    local answer=""

    # Hermes
    local default_hermes="y"
    [ "${INSTALL_HERMES}" -eq 0 ] && default_hermes="n"
    if [ "$DETECTED_HERMES" -eq 1 ] || [ "${INSTALL_HERMES}" -eq 1 ]; then
        prompt_read "检测到 Hermes，是否一同安装并接入 Hermes 会话适配器插件？(y/n)" answer "${default_hermes}"
        if [ "$answer" = "y" ] || [ "$answer" = "Y" ]; then
            INSTALL_HERMES=1
            prompt_read "请输入与 Core 账号绑定的 Hermes 账号" HERMES_ACCOUNT "${HERMES_ACCOUNT:-$CFG_USERNAME}"
        else
            INSTALL_HERMES=0
        fi
    fi

    # DSH
    local default_dsh="y"
    [ "${INSTALL_DSH}" -eq 0 ] && default_dsh="n"
    if [ "$DETECTED_DSH" -eq 1 ] || [ "${INSTALL_DSH}" -eq 1 ]; then
        prompt_read "检测到 DSH 环境，是否一同配置并安装 OAI DSH Session Adapter？(y/n)" answer "${default_dsh}"
        [ "$answer" = "y" ] || [ "$answer" = "Y" ] && INSTALL_DSH=1 || INSTALL_DSH=0
    fi

    # Codex
    local default_codex="y"
    [ "${INSTALL_CODEX}" -eq 0 ] && default_codex="n"
    if [ "$DETECTED_CODEX" -eq 1 ] || [ "${INSTALL_CODEX}" -eq 1 ]; then
        prompt_read "检测到 Codex 环境，是否在 Core 配置中接入原生 Codex app-server？(y/n)" answer "${default_codex}"
        [ "$answer" = "y" ] || [ "$answer" = "Y" ] && INSTALL_CODEX=1 || INSTALL_CODEX=0
    fi

    if [ "$INSTALL_HERMES" -eq 1 ]; then
        info "已启用 Hermes 会话桥适配器。"
    fi
    if [ "$INSTALL_DSH" -eq 1 ]; then
        info "已启用 DSH 会话桥适配器。"
    fi
    if [ "$INSTALL_CODEX" -eq 1 ]; then
        info "已启用 Codex 会话桥适配器。"
    fi
    success "Agent 适配器配置完成。"
    return 0
}

# 兼容旧函数调用（线性全量流程）
configure_network_and_account() {
    configure_network
    configure_account
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
    local hermes_account="${HERMES_ACCOUNT:-}"
    if [ "$INSTALL_HERMES" -eq 1 ] && [ -z "$hermes_account" ]; then
        prompt_read "请输入与 Core 账号一对一绑定的既有 Hermes 账号" hermes_account "${CFG_USERNAME}"
        HERMES_ACCOUNT="$hermes_account"
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
    local save_status=0
    OAI_INIT_ADMIN_PASSWORD="${CFG_PASSWORD}" "${VENV_PYTHON}" "$helper" \
        --root "${TARGET_DIR}" --host "${CFG_HOST}" --port "${CFG_PORT}" \
        --username "${CFG_USERNAME}" --agents "$agents_csv" --hermes-account "$hermes_account" || save_status=$?
    if [ "$save_status" -ne 0 ] && [ "$save_status" -ne 2 ]; then
        error "配置未提交完成；请检查恢复 journal。"
        return "$save_status"
    fi
    if [ "${helper}" = "${TARGET_DIR}/.tmp_core_setup.py" ]; then
        # 兼容清理临时文件（遵循工作区安全删除规则，优先使用 trash-cli 或移至 /tmp）
        if command -v trash-put >/dev/null 2>&1; then
            trash-put "${helper}" 2>/dev/null || true
        else
            mkdir -p /tmp/oai-trash
            mv "${helper}" /tmp/oai-trash/ 2>/dev/null || true
        fi
    fi
    local plugin_status=0
    install_selected_agent_plugins || plugin_status=$?
    if [ "$save_status" -eq 2 ] || [ "$plugin_status" -ne 0 ]; then
        warn "部分完成：配置事务已提交，可选 skill 或插件安装尚未完成，请按提示重试。"
        return 2
    fi
    success "配置与启动脚本已保存；既有账号密码、密钥和存储路径已保留。"
    info "桥接凭据存于 runtime/secrets/adapter-tokens.env（0600），启动脚本不含凭据。"
}

install_selected_agent_plugins() {
    local partial=0
    # 1. Hermes 插件自动安装
    if [ "${INSTALL_HERMES:-0}" -eq 1 ]; then
        info "正在为 Hermes 环境安装 OAI 适配器插件..."
        local hermes_adapter_dir="${TARGET_DIR}/integrations/hermes-session-adapter"
        local oai_core_dir="${TARGET_DIR}/oai-core"
        if [ ! -d "${hermes_adapter_dir}" ] && [ -n "${OAI_SETUP_SCRIPT_DIR}" ]; then
            hermes_adapter_dir="$(cd "${OAI_SETUP_SCRIPT_DIR}/../integrations/hermes-session-adapter" 2>/dev/null && pwd || echo "")"
        fi
        if [ ! -d "${oai_core_dir}" ] && [ -n "${OAI_SETUP_SCRIPT_DIR}" ]; then
            oai_core_dir="$(cd "${OAI_SETUP_SCRIPT_DIR}/../oai-core" 2>/dev/null && pwd || echo "")"
        fi

        if command -v hermes >/dev/null 2>&1; then
            # 1.1 查找 Hermes 所在 Python 环境并预装本地依赖包
            local hermes_py=""
            local hermes_pip=""
            local hermes_bin
            hermes_bin="$(command -v hermes)"
            if [ -f "${hermes_bin}" ]; then
                local first_line
                first_line="$(head -n 1 "${hermes_bin}" 2>/dev/null || echo "")"
                if [[ "${first_line}" =~ ^#!(/.*python[0-9.]*) ]]; then
                    hermes_py="${BASH_REMATCH[1]}"
                    if [ -f "$(dirname "${hermes_py}")/pip" ]; then
                        hermes_pip="$(dirname "${hermes_py}")/pip"
                    fi
                fi
            fi

            if [ -n "${hermes_pip}" ] && [ -x "${hermes_pip}" ]; then
                info "正在将本地 OAI 依赖安装至 Hermes Python 环境 (${hermes_pip})..."
                if [ -d "${oai_core_dir}" ] && [ -d "${hermes_adapter_dir}" ]; then
                    "${hermes_pip}" install --no-deps "${oai_core_dir}" "${hermes_adapter_dir}" 2>&1 || {
                        warn "向 Hermes Python 环境安装本地依赖失败，将继续尝试常规安装流程..."
                        partial=2
                    }
                fi
            fi

            # 1.2 如果存在旧版 gateway 插件则先禁用
            hermes plugins disable open-android-intelligence-gateway 2>/dev/null || true

            # 1.3 优先通过官方仓库或本地以 --no-deps 安装，绕过未发布 PyPI 依赖解析阻断
            info "正在安装 Hermes 网关插件 (1rua/hermes-gateway-plugin)..."
            if hermes plugins install 1rua/hermes-gateway-plugin --force --no-deps --enable 2>&1; then
                success "Hermes 适配器插件安装成功并已自动启用！"
            elif [ -n "${hermes_adapter_dir}" ] && [ -d "${hermes_adapter_dir}" ] && hermes plugins install "${hermes_adapter_dir}" --force --enable 2>&1; then
                success "Hermes 适配器插件安装成功并已自动启用！"
            else
                warn "尝试使用备用源 1rua/hermes-android-gateway 安装..."
                hermes plugins install 1rua/hermes-android-gateway --force --no-deps --enable 2>&1 || {
                    warn "Hermes 插件自动安装失败，您可在 Hermes 环境手动执行: hermes plugins install 1rua/hermes-gateway-plugin --force --no-deps --enable"
                    partial=2
                }
            fi
        else
            partial=2
            warn "未检测到全局 hermes 命令；配置已保存，插件仍需手动安装。请在 Hermes 终端中运行:"
            warn "  hermes plugins install 1rua/hermes-gateway-plugin --force --no-deps --enable"
        fi
    fi

    # 2. DSH 适配器插件提示与引导
    if [ "${INSTALL_DSH:-0}" -eq 1 ]; then
        local dsh_adapter_dir="${TARGET_DIR}/integrations/dsh-session-adapter"
        if [ ! -d "${dsh_adapter_dir}" ] && [ -n "${OAI_SETUP_SCRIPT_DIR}" ]; then
            dsh_adapter_dir="$(cd "${OAI_SETUP_SCRIPT_DIR}/../integrations/dsh-session-adapter" 2>/dev/null && pwd || echo "")"
        fi
        info "Core 配置已提交；已发现的 DSH 环境文件已纳入配置事务。"
        if command -v plugin_manager >/dev/null 2>&1; then
            info "正在执行 DSH 插件注册: plugin_manager action=install_bundle target=${dsh_adapter_dir}"
            plugin_manager action=install_bundle target="${dsh_adapter_dir}" 2>/dev/null || partial=2
        else
            partial=2
            info "DSH 插件尚需手动安装；请在 DSH 会话内运行:"
            info "  plugin_manager action=install_bundle target=${dsh_adapter_dir}"
            info "安装后重启 DSH profile 即可自动建立连接。"
        fi
    fi

    # 3. Codex app-server 说明
    if [ "${INSTALL_CODEX:-0}" -eq 1 ]; then
        info "Codex app-server 配置已写入 core.json，Core 启动后将自动按需拉起并管理 Codex 进程。"
    fi
    return "$partial"
}

# 注入全局 CLI 命令至 ~/.local/bin 并确保 PATH 环境变量
install_cli_wrapper() {
    local bin_dir="${HOME}/.local/bin"
    mkdir -p "${bin_dir}"

    local wrapper="${bin_dir}/oai"
    info "正在配置 oai 全局快捷命令: ${wrapper}..."

    cat <<EOF > "${wrapper}"
#!/usr/bin/env bash
# Open Android Intelligence Core Launcher & CLI
set -euo pipefail

OAI_DIR="${TARGET_DIR}"
VENV_OAI="\${OAI_DIR}/.venv/bin/oai"

if [ \$# -eq 0 ]; then
    exec "\${OAI_DIR}/start-core.sh"
else
    exec "\${VENV_OAI}" "\$@"
fi
EOF
    chmod 0755 "${wrapper}"
    success "已生成快捷启动与 CLI 命令: ${wrapper}"

    # 检查并注入 PATH 环境变量到常用 shell 配置文件中
    local path_line='export PATH="$HOME/.local/bin:$PATH"'
    local fish_line='fish_add_path -m $HOME/.local/bin'
    local injected=0

    # 针对 Bash
    if [ -f "${HOME}/.bashrc" ] && ! grep -q '\.local/bin' "${HOME}/.bashrc" 2>/dev/null; then
        printf "\n# Added by OAI Core\n%s\n" "${path_line}" >> "${HOME}/.bashrc"
        injected=1
    fi

    # 针对 Zsh
    if [ -f "${HOME}/.zshrc" ] && ! grep -q '\.local/bin' "${HOME}/.zshrc" 2>/dev/null; then
        printf "\n# Added by OAI Core\n%s\n" "${path_line}" >> "${HOME}/.zshrc"
        injected=1
    fi

    # 针对 Fish Shell
    if [ -d "${HOME}/.config/fish" ]; then
        local fish_conf="${HOME}/.config/fish/config.fish"
        if [ -f "${fish_conf}" ] && ! grep -q '\.local/bin' "${fish_conf}" 2>/dev/null; then
            printf "\n# Added by OAI Core\n%s\n" "${fish_line}" >> "${fish_conf}"
            injected=1
        fi
    fi

    if [ "${injected}" -eq 1 ]; then
        info "已自动将 ~/.local/bin 写入您的 shell 环境变量配置中。"
    fi
}

show_menu() {
    printf "\n"
    printf "${CYAN}${BOLD}=== OAI Core 配置分类菜单 ===${NC}\n"
    printf "  ${GREEN}1.${NC} 配置监听主机与端口       [当前: ${CFG_HOST}:${CFG_PORT}]\n"
    printf "  ${GREEN}2.${NC} 配置管理员账号与密码     [当前账号: ${CFG_USERNAME}]\n"
    local hermes_status="未启用"
    local dsh_status="未启用"
    local codex_status="未启用"
    local existing_types=""
    if [ -f "${CONFIG_FILE}" ]; then
        existing_types=$(OAI_CONFIG_PATH="${CONFIG_FILE}" "${VENV_PYTHON}" -c 'import json,os; print(",".join(a.get("type", "") for a in json.load(open(os.environ["OAI_CONFIG_PATH"])).get("agents", [])))' 2>/dev/null || true)
    fi
    [[ ",$existing_types," == *,hermes,* ]] && hermes_status="保留现有"
    [[ ",$existing_types," == *,dsh,* ]] && dsh_status="保留现有"
    [[ ",$existing_types," == *,codex,* ]] && codex_status="保留现有"
    [ "${INSTALL_HERMES}" -eq 1 ] && hermes_status="已启用"
    [ "${INSTALL_DSH}" -eq 1 ] && dsh_status="已启用"
    [ "${INSTALL_CODEX}" -eq 1 ] && codex_status="已启用"
    printf "  ${GREEN}3.${NC} 配置 AI Agent 适配器接入  [Hermes: %s | DSH: %s | Codex: %s]\n" "$hermes_status" "$dsh_status" "$codex_status"
    printf "     选择否表示不新增接入；已配置的 Agent 保留。移除须显式编辑 core.json 的 agents。\n"
    printf "  ${GREEN}4.${NC} 保存当前配置并生成启动脚本\n"
    printf "  ${GREEN}5.${NC} 配置系统开机与桌面自启动\n"
    printf "  ${GREEN}6.${NC} 注册全局 CLI 命令 (oai)\n"
    printf "  ${GREEN}7.${NC} 运行完整配置向导 (依次执行上述所有流程)\n"
    printf "  ${YELLOW}0.${NC} 退出配置向导\n"
    printf "\n"
}

run_full_wizard() {
    info "开始运行完整配置向导..."
    configure_network || return $?
    configure_account || return $?
    configure_agent_integrations || return $?
    local save_status=0
    save_configuration_and_account || save_status=$?
    if [ "$save_status" -ne 0 ] && [ "$save_status" -ne 2 ]; then return "$save_status"; fi
    configure_autostart || { warn "部分完成：配置已保存，自启动设置未完成。"; return 2; }
    install_cli_wrapper || { warn "部分完成：配置已保存，CLI 注册未完成。"; return 2; }
    if [ "$save_status" -eq 2 ]; then
        warn "部分完成：配置已提交，可选安装仍需重试。"
        return 2
    fi
    printf "\n"
    success "完整配置向导已全部完成！"
    info "您可随时在终端直接运行 oai 或执行 ${TARGET_DIR}/start-core.sh 启动服务。"
    info "内置 oai-client skill 已同步部署，支持接入或未接入的 Agent 读取与操控连接的客户端。"
}

menu_loop() {
    load_existing_config

    while true; do
        show_menu
        local choice=""
        prompt_read "请选择需要配置的功能项 (0-7)" choice "7"
        case "$choice" in
            1)
                configure_network
                ;;
            2)
                configure_account
                ;;
            3)
                configure_agent_integrations
                ;;
            4)
                save_configuration_and_account || warn "操作未全部完成，请查看上方提示。"
                ;;
            5)
                configure_autostart
                ;;
            6)
                install_cli_wrapper
                ;;
            7)
                run_full_wizard || warn "操作未全部完成，请查看上方提示。"
                ;;
            0|q|Q)
                info "已退出配置向导。"
                break
                ;;
            *)
                warn "无效输入 [${choice}]，请输入 0-7 之间的数字。"
                ;;
        esac
    done
}

main() {
    banner
    resolve_paths
    detect_agents
    load_existing_config

    # 如果有任何来自外部的预置环境变量或非交互环境，支持直接运行全量向导
    if [ ! -t 0 ] && [ ! -e /dev/tty ]; then
        run_full_wizard
        return 0
    fi

    menu_loop
}

if [ "${BASH_SOURCE[0]:-}" = "$0" ] || [ -z "${BASH_SOURCE[0]:-}" ]; then
    main "$@"
fi
