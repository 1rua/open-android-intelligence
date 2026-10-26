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
    eval "$var_name=\"\$input\""
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
    eval "$var_name=\"\$input\""
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

    info "正在安装 OAI Core 及网关契约包..."
    "${pip_bin}" install --timeout 15 -i "${PIP_INDEX_URL}" -e ./oai-core || {
        warn "通过镜像源安装 oai-core 失败，尝试默认官方源安装..."
        "${pip_bin}" install --timeout 15 -e ./oai-core
    }

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
        prompt_read "检测到 Hermes Agent，是否安装/配置 OAI Hermes Session Adapter 插件？(y/n)" answer "y"
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

    # 执行 Hermes 插件安装操作（若选中）
    if [ "$INSTALL_HERMES_PLUGIN" -eq 1 ]; then
        info "正在为 Hermes 配置会话插件..."
        if command -v hermes >/dev/null 2>&1; then
            info "检查并停用旧版 Hermes Gateway 插件（若已安装）..."
            hermes plugins disable open-android-intelligence-gateway 2>/dev/null || true
            local hermes_adapter_path="integrations/hermes-session-adapter"
            if [ -d "${TARGET_DIR}/${hermes_adapter_path}" ]; then
                info "通过 hermes CLI 安装本地原生会话适配器: ${hermes_adapter_path}..."
                hermes plugins install "${TARGET_DIR}/${hermes_adapter_path}" --enable || {
                    warn "自动安装插件失败，您可以稍后在终端手动运行: hermes plugins install ${TARGET_DIR}/${hermes_adapter_path} --enable"
                }
            fi
        else
            warn "未在 PATH 中找到 hermes 可执行命令，请在 Hermes 运行环境中手动安装: ${TARGET_DIR}/integrations/hermes-session-adapter"
        fi
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
    local default_host="127.0.0.1"
    local default_port="8045"
    local cfg_host=""
    local cfg_port=""

    prompt_read "请输入 OAI Core 监听主机地址 (内网/本机使用 127.0.0.1，局域网共享使用 0.0.0.0)" cfg_host "${default_host}"
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

    # 3. 配置文件生成路径
    local runtime_dir="${TARGET_DIR}/runtime"
    local accounts_dir="${runtime_dir}/accounts"
    local secrets_dir="${runtime_dir}/secrets"
    local master_key_file="${secrets_dir}/core-master-key"
    local config_file="${TARGET_DIR}/core.json"

    mkdir -p "${accounts_dir}" "${secrets_dir}"
    chmod 0700 "${secrets_dir}" "${accounts_dir}" 2>/dev/null || true

    # 生成安全随机主密钥（若不存在）
    if [ ! -f "${master_key_file}" ]; then
        info "正在生成 32 字节本地主加密密钥 (${master_key_file})..."
        if command -v openssl >/dev/null 2>&1; then
            openssl rand 32 > "${master_key_file}"
        else
            dd if=/dev/urandom of="${master_key_file}" bs=32 count=1 2>/dev/null
        fi
    fi
    chmod 0600 "${master_key_file}"

    # 4. 生成 Agents 配置项
    local agents_json="[]"
    local agents_array=()

    # DSH 配置项
    if [ "${INSTALL_DSH_PLUGIN:-0}" -eq 1 ] || [ "${DETECTED_DSH:-0}" -eq 1 ]; then
        local dsh_token
        dsh_token=$(openssl rand -hex 16 2>/dev/null || date +%s%N | sha256sum | head -c 32)
        export OAI_DSH_ADAPTER_TOKEN="${dsh_token}"
        agents_array+=("{\"id\":\"dsh\",\"name\":\"DSH\",\"type\":\"dsh\",\"url\":\"http://127.0.0.1:8791\",\"tokenEnv\":\"OAI_DSH_ADAPTER_TOKEN\"}")
        info "已为 DSH Session Adapter 配置鉴权环境变量 OAI_DSH_ADAPTER_TOKEN"
    fi

    # Hermes 配置项
    if [ "${INSTALL_HERMES_PLUGIN:-0}" -eq 1 ] || [ "${DETECTED_HERMES:-0}" -eq 1 ]; then
        local hermes_token
        hermes_token=$(openssl rand -hex 16 2>/dev/null || date +%s%N | sha256sum | head -c 32)
        export OAI_HERMES_SESSION_TOKEN="${hermes_token}"
        agents_array+=("{\"id\":\"hermes\",\"name\":\"Hermes\",\"type\":\"hermes\",\"url\":\"http://127.0.0.1:8792\",\"tokenEnv\":\"OAI_HERMES_SESSION_TOKEN\",\"accounts\":{\"${cfg_username}\":\"${cfg_username}\"}}")
        info "已为 Hermes Session Adapter 配置一对一账号映射与鉴权环境变量 OAI_HERMES_SESSION_TOKEN"
    fi

    # Codex 配置项
    if [ "${INSTALL_CODEX_CONFIG:-0}" -eq 1 ]; then
        agents_array+=("{\"id\":\"codex\",\"name\":\"Codex\",\"type\":\"codex\",\"command\":[\"codex\",\"app-server\"],\"codexHome\":\"runtime/codex\"}")
    fi

    # 拼装 agents json 列表
    if [ ${#agents_array[@]} -gt 0 ]; then
        local joined
        joined=$(IFS=,; echo "${agents_array[*]}")
        agents_json="[${joined}]"
    fi

    # 相对路径规范：相对于 config_file 所在目录
    info "正在生成服务端配置文件: ${config_file}..."
    cat > "${config_file}" << EOF
{
  "storageRoot": "runtime/accounts",
  "masterKeyFile": "runtime/secrets/core-master-key",
  "contractRoot": "gateway-contract",
  "listen": {
    "host": "${cfg_host}",
    "port": ${cfg_port}
  },
  "agents": ${agents_json}
}
EOF
    chmod 0600 "${config_file}" 2>/dev/null || true
    success "配置文件生成完毕: ${config_file}"

    # 5. 调用 oai account create 自动创建管理员账号
    info "正在创建服务端本地用户: ${cfg_username}..."

    OAI_INIT_CONFIG="${config_file}" \
    OAI_INIT_ADMIN_USERNAME="${cfg_username}" \
    OAI_INIT_ADMIN_PASSWORD="${cfg_password}" \
    "${TARGET_DIR}/.venv/bin/python" -c '
import os
import sys
from pathlib import Path
from oai_core.server import load_config, create_core

cfg_path = Path(os.environ["OAI_INIT_CONFIG"])
username = os.environ["OAI_INIT_ADMIN_USERNAME"]
password = os.environ["OAI_INIT_ADMIN_PASSWORD"]

cfg = load_config(cfg_path)
core = create_core(cfg)

if core.account_exists(username):
    print(f"[INFO] 用户 {username} 已存在，跳过新建。")
else:
    account = core.open_gateway_account(username)
    try:
        account.credentials.create_password(password)
        print(f"[SUCCESS] 用户 {username} 创建成功！")
    finally:
        account.close()
'
}

# 快捷启动包装与环境变量脚本
generate_convenience_scripts() {
    local start_script="${TARGET_DIR}/start-core.sh"
    info "正在生成快捷启动脚本: ${start_script}..."

    # 导出各 Agent 会话适配器可能使用的随机 Token，若环境变量未设置则提供默认回退
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
    success "快捷启动脚本已生成: ${start_script}"
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
    printf "  - 在 Android App 登录页中填入服务端地址，如：${CYAN}http://<本机IP>:8045${NC}\n"
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
    generate_convenience_scripts
    show_completion_summary
}

# 当作为独立脚本或通过管道执行时调用 main
if [ "${BASH_SOURCE[0]:-}" = "$0" ] || [ -z "${BASH_SOURCE[0]:-}" ]; then
    main "$@"
fi
