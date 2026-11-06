#!/usr/bin/env bash
# Open Android Intelligence (OAI) Core 交互式安装与更新向导
# curl -fsSL https://raw.githubusercontent.com/1rua/open-android-intelligence/dev/scripts/install.sh | bash
set -euo pipefail

setup_colors() {
    if [ -z "${NO_COLOR:-}" ] && { [ -t 1 ] || [ -t 2 ] || [ -c /dev/tty ]; }; then
        C_RESET=$'\033[0m'
        C_BOLD=$'\033[1m'
        C_DIM=$'\033[2m'
        C_CYAN=$'\033[36m'
        C_GREEN=$'\033[32m'
        C_YELLOW=$'\033[33m'
        C_RED=$'\033[31m'
    else
        C_RESET='' C_BOLD='' C_DIM='' C_CYAN='' C_GREEN='' C_YELLOW='' C_RED=''
    fi
}

print_banner() {
    setup_colors
    printf '\n%s╭──────────────────────────────────────────────────────────╮%s\n' "${C_CYAN}${C_BOLD}" "${C_RESET}" >&2
    printf '%s│%s   %sOpen Android Intelligence (OAI) · 安装与更新向导%s       %s│%s\n' "${C_CYAN}${C_BOLD}" "${C_RESET}" "${C_BOLD}" "${C_RESET}" "${C_CYAN}${C_BOLD}" "${C_RESET}" >&2
    printf '%s│%s   %s一步完成服务端部署、管理员设置与本地 AI 助手连接%s       %s│%s\n' "${C_CYAN}${C_BOLD}" "${C_RESET}" "${C_DIM}" "${C_RESET}" "${C_CYAN}${C_BOLD}" "${C_RESET}" >&2
    printf '%s╰──────────────────────────────────────────────────────────╯%s\n\n' "${C_CYAN}${C_BOLD}" "${C_RESET}" >&2
}

ui_step() {
    setup_colors
    printf '%s▸ %s%s\n' "${C_CYAN}${C_BOLD}" "$*" "${C_RESET}" >&2
}

ui_ok() {
    setup_colors
    printf '%s✓ %s%s\n' "${C_GREEN}" "$*" "${C_RESET}" >&2
}

ui_warn() {
    setup_colors
    printf '%s! [提示] %s%s\n' "${C_YELLOW}" "$*" "${C_RESET}" >&2
}

ui_error() {
    setup_colors
    printf '%s✗ [错误] %s%s\n' "${C_RED}${C_BOLD}" "$*" "${C_RESET}" >&2
}

read_terminal() {
    local prompt="$1" answer='' idx=0 key='' seq=''
    setup_colors
    if ! { : >/dev/tty; : </dev/tty; } 2>/dev/null; then
        ui_error '安装需要可交互的终端窗口；当前未检测到终端，未执行任何安装操作。'
        return 1
    fi
    render_yes_no() {
        if [ "$idx" -eq 0 ]; then
            printf '\r\033[2K  %s❯ 1. 是，继续执行 (Yes)%s\n' "${C_CYAN}${C_BOLD}" "${C_RESET}" >/dev/tty
            printf '\r\033[2K    2. 否，取消退出 (No)\n' >/dev/tty
        else
            printf '\r\033[2K    1. 是，继续执行 (Yes)\n' >/dev/tty
            printf '\r\033[2K  %s❯ 2. 否，取消退出 (No)%s\n' "${C_CYAN}${C_BOLD}" "${C_RESET}" >/dev/tty
        fi
        printf '\r\033[2K%s? %s%s%s' "${C_CYAN}${C_BOLD}" "$prompt" "${C_RESET}" "$answer" >/dev/tty
    }
    render_yes_no
    while true; do
        if ! IFS= read -r -s -n 1 key </dev/tty; then
            printf '\n' >/dev/tty
            return 1
        fi
        if [ -z "$key" ] || [ "$key" = $'\r' ]; then
            printf '\n' >/dev/tty
            break
        fi
        if [ "$key" = $'\x1b' ]; then
            IFS= read -r -s -n 2 -t 0.05 seq </dev/tty || true
            case "$seq" in
                '[A'|'OA'|'[D'|'OD'|'[B'|'OB'|'[C'|'OC')
                    idx=$((1 - idx))
                    answer=''
                    printf '\r\033[2A' >/dev/tty
                    render_yes_no
                    ;;
            esac
            continue
        fi
        if [ "$key" = $'\x7f' ] || [ "$key" = $'\x08' ]; then
            if [ -n "$answer" ]; then
                answer="${answer%?}"
                printf '\b \b' >/dev/tty
            fi
            continue
        fi
        answer="${answer}${key}"
        printf '%s' "$key" >/dev/tty
    done
    if [ -z "$answer" ]; then
        [ "$idx" -eq 0 ] && return 0 || return 1
    fi
    local normalized
    normalized="$(printf '%s' "$answer" | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]')"
    case "$normalized" in
        y|yes|1) return 0 ;;
        *) return 1 ;;
    esac
}

select_python() {
    local candidate
    for candidate in python3 python; do
        if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -I -c 'import sys,venv; raise SystemExit(sys.version_info < (3,11))' >/dev/null 2>&1; then
            PYTHON_BIN="$candidate"
            return 0
        fi
    done
    return 1
}

install_new_dependencies() {
    # 仅在确认属于全新安装时提示自动安装基础依赖。
    ui_warn '检测到电脑尚未安装 Python 3.11+（含 venv）或 Git。'
    if ! read_terminal '是否自动为您安装这些基础运行组件？(y/n) [y]: '; then
        ui_warn '已取消自动安装依赖。请手动安装 Python 3.11+ 和 Git 后重试。'
        return 1
    fi
    ui_step '正在通过系统包管理器安装基础组件（可能需要输入系统密码）...'
    local elevate=()
    if [ "$(id -u)" -ne 0 ]; then elevate=(sudo); fi
    if command -v apt-get >/dev/null 2>&1; then
        "${elevate[@]}" apt-get update
        "${elevate[@]}" apt-get install -y python3 python3-venv git
    elif command -v dnf >/dev/null 2>&1; then
        "${elevate[@]}" dnf install -y python3 python3-pip git
    elif command -v pacman >/dev/null 2>&1; then
        "${elevate[@]}" pacman -S --needed --noconfirm python python-pip git
    elif command -v brew >/dev/null 2>&1; then
        brew install python@3.12 git
    else
        ui_error '暂不支持在当前系统自动安装组件，请先手动安装 Python 3.11+、venv 和 Git 后重试。'
        return 1
    fi
    select_python && command -v git >/dev/null 2>&1
    ui_ok '系统基础组件安装完成。'
}

main() {
    local script_dir='' recovery_source='' target="${OAI_DIR:-${HOME}/.oai-core}" argument previous=''
    # --dir 仅用于指定安装目录；密码等敏感信息绝不通过命令行或环境变量传递。
    for argument in "$@"; do
        if [ "$previous" = --dir ]; then target="$argument"; fi
        case "$argument" in
            --dir=*) target="${argument#--dir=}" ;;
            -h|--help)
                printf '用法: %s [--dir <安装目录>]\n' "${0##*/}"
                printf '说明: 交互式安装或更新 OAI Core 服务端（默认目录: ~/.oai-core）。\n'
                return 0
                ;;
        esac
        previous="$argument"
    done
    if [ -n "${BASH_SOURCE[0]:-}" ] && [ -f "${BASH_SOURCE[0]}" ]; then
        script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
        if [ -f "${script_dir}/../oai-core/pyproject.toml" ] && [ -z "${OAI_DIR:-}" ] && [ "$target" = "${HOME}/.oai-core" ]; then
            target="$(cd "${script_dir}/.." && pwd)"
        fi
    fi
    if [ -L "$target" ]; then
        ui_error '安装目录不能是符号链接（快捷方式），请指定真实文件夹路径。'
        return 1
    fi
    if [ -t 1 ] || [ -t 2 ]; then
        print_banner
    fi
    if [ -f "$target/core.json" ] || [ -e "$target/runtime" ] || [ -f "$target/start-core.sh" ]; then
        if [ -x "$target/.venv/bin/python" ]; then PYTHON_BIN="$target/.venv/bin/python"; else select_python; fi
        if [ ! -f "$target/oai-core/pyproject.toml" ]; then
            if [ -n "$script_dir" ] && [ -f "$script_dir/../oai-core/pyproject.toml" ] && [ -f "$script_dir/installer.py" ]; then
                recovery_source="$(cd "$script_dir/.." && pwd)"
            else
                # 先在独立临时目录下载程序文件，验证原管理员密码前不改动原有数据。
                if ! command -v git >/dev/null 2>&1; then
                    ui_error '恢复缺失的程序文件需要先安装 git；您原有的配置和账号数据完好未动。'
                    return 1
                fi
                ui_step '检测到已有的账号与配置数据，正在下载程序文件（完成身份验证后将自动恢复，原有数据不会丢失）...'
                recovery_source="$(mktemp -d "${target}.download.XXXXXXXX")"
                git clone --single-branch --branch "${OAI_BRANCH:-dev}" https://github.com/1rua/open-android-intelligence.git "$recovery_source"
            fi
            exec "$PYTHON_BIN" -I "$recovery_source/scripts/installer.py" install --dir "$target"
        fi
    else
        if ! select_python || ! command -v git >/dev/null 2>&1; then install_new_dependencies; fi
    fi
    if [ ! -f "$target/oai-core/pyproject.toml" ]; then
        if [ -e "$target" ]; then
            ui_error '目标目录已经存在其他文件且不是 OAI Core 安装目录，为避免误删未覆盖。'
            return 1
        fi
        ui_step "准备将 OAI Core 安装到: ${target}"
        if ! read_terminal '即将从官方仓库下载并安装 OAI Core，是否继续？(y/n) [y]: '; then
            ui_warn '已取消安装。'
            return 1
        fi
        local staging
        staging="$(mktemp -d "${target}.download.XXXXXXXX")"
        ui_step "正在下载 OAI Core 源码（分支: ${OAI_BRANCH:-dev}）..."
        git clone --single-branch --branch "${OAI_BRANCH:-dev}" https://github.com/1rua/open-android-intelligence.git "$staging"
        "$PYTHON_BIN" -I -c 'import os,sys; source,target=sys.argv[1:]; assert not os.path.lexists(target), "目标目录已存在"; os.rename(source,target)' "$staging" "$target"
        ui_ok "源码已就绪: ${target}"
    fi
    local launcher="$target/scripts/installer.py"
    if [ -n "$script_dir" ] && [ -f "$script_dir/installer.py" ]; then launcher="$script_dir/installer.py"; fi
    if [ ! -f "$launcher" ]; then
        ui_error '下载的版本缺少安全安装入口；已保留下载目录供人工检查。'
        return 1
    fi
    exec "$PYTHON_BIN" -I "$launcher" install --dir "$target"
}

if [ "${BASH_SOURCE[0]:-}" = "$0" ] || [ ! -f "${BASH_SOURCE[0]:-}" ]; then main "$@"; fi
