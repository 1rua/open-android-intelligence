#!/usr/bin/env bash
#
# Open Android Intelligence (OAI) Core 一键安装 / 更新与配置脚本
#
# 支持直接通过 curl 运行：
#   curl -fsSL https://raw.githubusercontent.com/1rua/open-android-intelligence/dev/scripts/install.sh | bash
#
# 特性：
# 1. 自动检测环境依赖（Python 3.11+、git、curl 等）；
# 2. 安装或就地更新 OAI Core 源码与 Python 虚拟环境；
# 3. 自动探测当前宿主机上存在的 Agent（Hermes、DSH、Codex 等）；
# 4. 交互式询问并一键安装/启用其 Agent 端适配器插件；
# 5. 引导用户完成初始化配置（账号密码创建、监听端口及地址配置、生成配置文件）；
# 6. 支持非交互式无人值守模式（通过环境变量或 CLI 参数控制）。
#

set -euo pipefail

OAI_SETUP_SCRIPT_DIR=""
if [ -n "${BASH_SOURCE[0]:-}" ]; then
    OAI_SETUP_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fi

# 颜色与显示定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m' # No Color

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
    printf "${BOLD}Open Android Intelligence - Core 一键安装与配置程序${NC}\n\n"
}

# 默认参数与路径（遵循相对路径规范与用户目录规范）
REPO_URL="${OAI_REPO_URL:-https://github.com/1rua/open-android-intelligence.git}"
DEFAULT_INSTALL_DIR="${OAI_DIR:-${HOME}/.oai-core}"
BRANCH="${OAI_BRANCH:-dev}"
PIP_INDEX_URL="${PIP_INDEX_URL:-https://pypi.tuna.tsinghua.edu.cn/simple}"

# 探测是否具有可用的交互式 tty
is_interactive() {
    [ -t 0 ] || [ -c /dev/tty ]
}

# 提示输入辅助函数（支持 curl | bash 模式下从 /dev/tty 读取）
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
        # 非交互式
        input=""
    fi

    if [ -z "$input" ] && [ -n "$default_value" ]; then
        input="$default_value"
    fi
    printf -v "$var_name" '%s' "$input"
}

# 提示输入密码（不回显）
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

# 检查基础依赖
check_prerequisites() {
    info "正在检查系统前置环境与依赖..."

    # 1. 检查 Python 3
    local python_bin=""
    if command -v python3 >/dev/null 2>&1; then
        python_bin="python3"
    elif command -v python >/dev/null 2>&1; then
        python_bin="python"
    else
        error "未找到 Python。OAI Core 需要 Python 3.11 或更高版本，请先安装 Python。"
        exit 1
    fi

    local py_version
    py_version=$("$python_bin" -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")')
    local py_major
    py_major=$(echo "$py_version" | cut -d. -f1)
    local py_minor
    py_minor=$(echo "$py_version" | cut -d. -f2)

    if [ "$py_major" -lt 3 ] || { [ "$py_major" -eq 3 ] && [ "$py_minor" -lt 11 ]; }; then
        error "当前 Python 版本为 ${py_version}，OAI Core 要求 Python >= 3.11。"
        exit 1
    fi
    info "检测到符合要求的 Python 环境: ${python_bin} (v${py_version})"

    # 2. 检查 python venv 模块
    if ! "$python_bin" -m venv --help >/dev/null 2>&1; then
        error "Python venv 模块不可用，请安装 python3-venv（例如: apt install python3-venv）。"
        exit 1
    fi

    # 3. 检查 git
    if ! command -v git >/dev/null 2>&1; then
        error "未找到 git，请先安装 git。"
        exit 1
    fi

    # 4. 检查 openssl
    if ! command -v openssl >/dev/null 2>&1; then
        warn "未找到 openssl 命令，后续若需计算 TLS SPKI 指纹可能受限。"
    fi
}

# 探测本地或仓库根目录
resolve_repo_root() {
    # 如果脚本在已有仓库内部执行
    local script_dir=""
    if [ -n "${BASH_SOURCE[0]:-}" ]; then
        script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" 2>/dev/null && pwd || true)"
    fi
    if [ -n "$script_dir" ] && [ -f "${script_dir}/../oai-core/pyproject.toml" ]; then
        TARGET_DIR="$(cd "${script_dir}/.." && pwd)"
        IS_LOCAL_REPO=1
        info "检测到脚本在 OAI 仓库内运行: ${TARGET_DIR}"
    elif [ -f "./oai-core/pyproject.toml" ]; then
        TARGET_DIR="$(pwd)"
        IS_LOCAL_REPO=1
        info "检测到当前目录即为 OAI 仓库根目录: ${TARGET_DIR}"
    else
        TARGET_DIR="${DEFAULT_INSTALL_DIR}"
        IS_LOCAL_REPO=0
    fi
}

# 克隆或更新仓库
prepare_source() {
    if [ "$IS_LOCAL_REPO" -eq 1 ]; then
        info "使用当前仓库源码，跳过 git clone 步骤。"
        cd "${TARGET_DIR}"
        return 0
    fi

    if [ -d "${TARGET_DIR}/.git" ]; then
        info "检测到已有 OAI Core 安装目录: ${TARGET_DIR}，正在执行更新 (git pull)..."
        cd "${TARGET_DIR}"
        git fetch origin "${BRANCH}" || warn "获取远程更新失败，将尝试使用本地现有代码"
        git checkout "${BRANCH}" || true
        git pull origin "${BRANCH}" || warn "git pull 未完全成功，将继续使用当前代码安装"
    else
        info "正在将 OAI 源码克隆至 ${TARGET_DIR} (分支: ${BRANCH})..."
        mkdir -p "${TARGET_DIR}"
        git clone -b "${BRANCH}" --depth 1 "${REPO_URL}" "${TARGET_DIR}"
        cd "${TARGET_DIR}"
    fi
}

# 搭建 Python 虚拟环境并安装 oai-core
install_python_env() {
    local venv_dir="${TARGET_DIR}/.venv"
    info "正在配置 Python 虚拟环境: ${venv_dir}..."

    if [ ! -f "${venv_dir}/bin/python" ]; then
        python3 -m venv "${venv_dir}"
    fi

    local pip_bin="${venv_dir}/bin/pip"

    info "正在安装/更新 build 依赖 (setuptools, wheel)..."
    "${pip_bin}" install --timeout 15 -i "${PIP_INDEX_URL}" --upgrade pip setuptools wheel || {
        warn "通过镜像源更新 pip/setuptools 失败，尝试默认源..."
        "${pip_bin}" install --timeout 15 --upgrade pip setuptools wheel || true
    }

    info "正在安装独立 OAI Core 与随包协议契约（不安装 Hermes）..."
    "${pip_bin}" install --timeout 15 -i "${PIP_INDEX_URL}" -e ./oai-core || {
        warn "通过镜像源安装 oai-core 失败，尝试默认官方源安装..."
        "${pip_bin}" install --timeout 15 -e ./oai-core
    }
    "${venv_dir}/bin/python" -m pip check

    if [ -f "${venv_dir}/bin/oai" ]; then
        success "OAI Core CLI (oai) 安装成功: ${venv_dir}/bin/oai"
    else
        error "oai 可执行文件未在虚拟环境中生成，请检查安装日志。"
        exit 1
    fi
}

# 探测环境中的主流 Agent
detect_agents() {
    info "正在探测当前宿主机上支持 Gateway/Adapter 插件的 Agent..."

    DETECTED_HERMES=0
    DETECTED_DSH=0
    DETECTED_CODEX=0

    # 1. 探测 Hermes Agent
    if command -v hermes >/dev/null 2>&1 || [ -d "${HOME}/.hermes" ]; then
        DETECTED_HERMES=1
        info "-> 发现 Hermes Agent (CLI: $(command -v hermes 2>/dev/null || echo '未加入PATH'), 配置目录: ${HOME}/.hermes)"
    fi

    # 2. 探测 DSH (DeepSeek Harness)
    local dsh_home="${DSH_HOME:-${HOME}/.dsh-cline}"
    if [ -d "${dsh_home}" ] || [ -d "${HOME}/.dsh" ] || command -v dsh >/dev/null 2>&1; then
        DETECTED_DSH=1
        info "-> 发现 DeepSeek Harness (DSH) 环境 (配置目录: ${dsh_home})"
    fi

    # 3. 探测 Codex
    if command -v codex >/dev/null 2>&1 || [ -d "${HOME}/.codex" ]; then
        DETECTED_CODEX=1
        info "-> 发现 Codex 环境 (CLI: $(command -v codex 2>/dev/null || echo '未加入PATH'), 目录: ${HOME}/.codex)"
    fi

    if [ "$DETECTED_HERMES" -eq 0 ] && [ "$DETECTED_DSH" -eq 0 ] && [ "$DETECTED_CODEX" -eq 0 ]; then
        info "未在常见路径中探测到已安装的 Agent。稍后您可以手动在配置文件中添加 Agent。"
    fi
}

# 询问并安装 Agent 插件
configure_agent_plugins() {
    INSTALL_HERMES_PLUGIN=0
    INSTALL_DSH_PLUGIN=0
    INSTALL_CODEX_CONFIG=0

    local answer=""

    # 1. Hermes 适配器引导
    if [ "$DETECTED_HERMES" -eq 1 ]; then
        prompt_read "检测到 Hermes，是否配置可选会话桥接入？(y/n)" answer "n"
        case "$answer" in
            [Yy]* )
                INSTALL_HERMES_PLUGIN=1
                ;;
            * )
                INSTALL_HERMES_PLUGIN=0
                ;;
        esac
    fi

    # 2. DSH 适配器引导
    if [ "$DETECTED_DSH" -eq 1 ]; then
        prompt_read "检测到 DSH 环境，是否配置接入 OAI DSH Session Adapter？(y/n)" answer "y"
        case "$answer" in
            [Yy]* )
                INSTALL_DSH_PLUGIN=1
                ;;
            * )
                INSTALL_DSH_PLUGIN=0
                ;;
        esac
    fi

    # 3. Codex 适配器引导
    if [ "$DETECTED_CODEX" -eq 1 ]; then
        prompt_read "检测到 Codex 环境，是否在 Core 配置中接入原生 Codex app-server？(y/n)" answer "y"
        case "$answer" in
            [Yy]* )
                INSTALL_CODEX_CONFIG=1
                ;;
            * )
                INSTALL_CODEX_CONFIG=0
                ;;
        esac
    fi

    # 宿主安装与停用旧 Gateway 是部署切换步骤，不在 Core 安装时执行。
    if [ "$INSTALL_HERMES_PLUGIN" -eq 1 ]; then
        info "已为您启用 Core 的 Hermes 会话桥配置。"
        info "配置向导已自动将生成的凭据与账号配置同步至 Hermes 环境（若检测到 ~/.hermes）。"
        info "请确保在 Hermes 环境安装适配器并启用插件："
        info "  hermes plugins enable open-android-intelligence-gateway"
        info "  hermes gateway restart"
    fi

    # DSH 插件说明
    if [ "$INSTALL_DSH_PLUGIN" -eq 1 ]; then
        info "已为您启用 DSH 会话接入配置。"
        info "如需在 DSH 会话中生效，可在 DSH 会话内运行:"
        info "  plugin_manager action=install_bundle target=${TARGET_DIR}/integrations/dsh-session-adapter"
    fi
}

# 引导用户完成初始化配置（账号、密码、端口、生成 core.json）
guide_initial_configuration() {
    printf "\n"
    info "${BOLD}=== 开始 OAI Core 初始化配置向导 ===${NC}"
    printf "\n"

    # 1. 监听端口与地址
    local default_host="${CFG_HOST:-0.0.0.0}"
    local default_port="${CFG_PORT:-11451}"
    local cfg_host=""
    local cfg_port=""

    prompt_read "请输入 OAI Core 监听主机地址 (局域网共享使用 0.0.0.0，仅本机使用 127.0.0.1)" cfg_host "${default_host}"
    prompt_read "请输入 OAI Core 监听端口" cfg_port "${default_port}"

    # 2. 账号与密码设置
    local cfg_username=""
    prompt_read "请设置初始管理员用户名" cfg_username "alice"

    local cfg_password=""
    local cfg_password_confirm=""
    while true; do
        prompt_password "请设置 ${cfg_username} 的登录密码" cfg_password
        if [ -z "$cfg_password" ]; then
            warn "密码不能为空，请重新输入。"
            continue
        fi
        prompt_password "请再次输入密码以确认" cfg_password_confirm
        if [ "$cfg_password" != "$cfg_password_confirm" ]; then
            error "两次输入的密码不一致，请重新输入！"
        else
            break
        fi
    done

    local selected_agents=()
    [ "${INSTALL_DSH_PLUGIN:-0}" -eq 0 ] || selected_agents+=(dsh)
    [ "${INSTALL_HERMES_PLUGIN:-0}" -eq 0 ] || selected_agents+=(hermes)
    [ "${INSTALL_CODEX_CONFIG:-0}" -eq 0 ] || selected_agents+=(codex)
    local hermes_account=""
    if [ "${INSTALL_HERMES_PLUGIN:-0}" -eq 1 ]; then
        prompt_read "请输入与 Core 账号一对一绑定的既有 Hermes 账号" hermes_account "${cfg_username}"
    fi
    local agents_csv
    agents_csv=$(IFS=,; printf '%s' "${selected_agents[*]}")
    local helper="${TARGET_DIR}/scripts/core_setup.py"
    [ -f "$helper" ] || helper="${OAI_SETUP_SCRIPT_DIR}/core_setup.py"
    OAI_INIT_ADMIN_PASSWORD="${cfg_password}" "${TARGET_DIR}/.venv/bin/python" "$helper" \
        --root "${TARGET_DIR}" --host "${cfg_host}" --port "${cfg_port}" \
        --username "${cfg_username}" --agents "$agents_csv" --hermes-account "$hermes_account"
    success "配置与启动脚本已保存；既有账号、主密钥和路径保持可用。"
    info "桥接凭据仅存于 runtime/secrets/adapter-tokens.env（0600），启动脚本不含凭据。"
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

# 打印最终完成信息
show_completion_summary() {
    printf "\n"
    printf "${GREEN}${BOLD}======================================================${NC}\n"
    printf "${GREEN}${BOLD}   OAI Core 安装与初始化配置已顺利完成！${NC}\n"
    printf "${GREEN}${BOLD}======================================================${NC}\n\n"

    printf "${BOLD}服务启动方式：${NC}\n"
    printf "  1. 直接使用快捷脚本：\n"
    printf "     ${CYAN}%s/start-core.sh${NC}\n\n" "${TARGET_DIR}"
    printf "  2. 或通过命令行启动：\n"
    printf "     ${CYAN}cd %s${NC}\n" "${TARGET_DIR}"
    printf "     ${CYAN}source .venv/bin/activate${NC}\n"
    printf "     ${CYAN}oai serve --config core.json${NC}\n\n"

    printf "${BOLD}手机连接说明：${NC}\n"
    printf "  - 在 Android App 登录页中填入服务端地址，如：${CYAN}http://<本机IP>:11451${NC}\n"
    printf "  - 使用向导中配置的账号与密码登录并完成配对。\n\n"

    printf "${BOLD}日常管理命令：${NC}\n"
    printf "  - 添加新账号: ${CYAN}.venv/bin/oai account create --config core.json --username <用户名>${NC}\n"
    printf "  - 查看设备列表: ${CYAN}.venv/bin/oai devices${NC}\n"
    printf "  - 启动 MCP 服务: ${CYAN}.venv/bin/oai mcp${NC}\n\n"
}

main() {
    banner
    check_prerequisites
    resolve_repo_root
    prepare_source
    install_python_env
    detect_agents
    configure_agent_plugins
    guide_initial_configuration
    configure_autostart
    show_completion_summary
}

# 当作为独立脚本或通过管道执行时调用 main
if [ "${BASH_SOURCE[0]:-}" = "$0" ] || [ -z "${BASH_SOURCE[0]:-}" ]; then
    main "$@"
fi
