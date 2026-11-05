#!/usr/bin/env bash
# OAI Core 安装 / 更新；已有部署的写操作由 Python 管理层验证管理员。
# curl -fsSL https://raw.githubusercontent.com/1rua/open-android-intelligence/dev/scripts/install.sh | bash
set -euo pipefail

read_terminal() {
    local prompt="$1" answer=''
    if ! { printf '%s' "$prompt" >/dev/tty; IFS= read -r answer </dev/tty; }; then
        printf '%s\n' '[错误] 安装需要交互终端；未执行安装。' >&2
        return 1
    fi
    local normalized
    normalized="$(printf '%s' "$answer" | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]')"
    case "$normalized" in
        ''|y|yes) return 0 ;;
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
    # Only called after proving that no existing Core deployment is present.
    read_terminal '缺少 Python 3.11+ / venv 或 git。是否安装系统依赖？(y/n) [y]: ' || return 1
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
        printf '%s\n' '[错误] 请安装 Python 3.11+、venv 和 git 后重试；当前平台不自动更改。' >&2
        return 1
    fi
    select_python && command -v git >/dev/null 2>&1
}

main() {
    local script_dir='' target="${OAI_DIR:-${HOME}/.oai-core}" argument previous=''
    # --dir is a non-secret installation selector; credentials never enter Shell.
    for argument in "$@"; do
        if [ "$previous" = --dir ]; then target="$argument"; fi
        case "$argument" in --dir=*) target="${argument#--dir=}" ;; esac
        previous="$argument"
    done
    if [ -n "${BASH_SOURCE[0]:-}" ] && [ -f "${BASH_SOURCE[0]}" ]; then
        script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
        if [ -f "${script_dir}/../oai-core/pyproject.toml" ] && [ -z "${OAI_DIR:-}" ] && [ "$target" = "${HOME}/.oai-core" ]; then
            target="$(cd "${script_dir}/.." && pwd)"
        fi
    fi
    if [ -L "$target" ]; then printf '%s\n' '[错误] 安装目录不能是符号链接。' >&2; return 1; fi
    if [ -f "$target/core.json" ] || [ -e "$target/runtime" ] || [ -f "$target/start-core.sh" ]; then
        # Never install dependencies, download a helper or update code first.
        if [ ! -f "$target/scripts/installer.py" ] && [ ! -f "${script_dir}/installer.py" ]; then
            printf '%s\n' '[错误] 旧部署尚无安全迁移入口；请从可信检出运行 scripts/install.sh --dir <旧目录>。未更新旧部署。' >&2
            return 1
        fi
        if [ -x "$target/.venv/bin/python" ]; then PYTHON_BIN="$target/.venv/bin/python"; else select_python; fi
    else
        if ! select_python || ! command -v git >/dev/null 2>&1; then install_new_dependencies; fi
    fi
    if [ ! -f "$target/oai-core/pyproject.toml" ]; then
        if [ -e "$target" ]; then printf '%s\n' '[错误] 目标目录已经存在且不是 OAI 源码，未覆盖。' >&2; return 1; fi
        read_terminal '将从项目仓库安装 OAI Core，是否继续？(y/n) [y]: ' || return 1
        local staging
        staging="$(mktemp -d "${target}.download.XXXXXXXX")"
        git clone --single-branch --branch "${OAI_BRANCH:-dev}" https://github.com/1rua/open-android-intelligence.git "$staging"
        # Do not execute a downloader's current directory or overwrite a race winner.
        "$PYTHON_BIN" -I -c 'import os,sys; source,target=sys.argv[1:]; assert not os.path.lexists(target), "目标目录已存在"; os.rename(source,target)' "$staging" "$target"
    fi
    local launcher="$target/scripts/installer.py"
    if [ -n "$script_dir" ] && [ -f "$script_dir/installer.py" ]; then launcher="$script_dir/installer.py"; fi
    if [ ! -f "$launcher" ]; then
        printf '%s\n' '[错误] 该版本没有安全安装入口；保留下载目录供复核。' >&2; return 1
    fi
    exec "$PYTHON_BIN" -I "$launcher" install --dir "$target"
}

if [ "${BASH_SOURCE[0]:-}" = "$0" ] || [ ! -f "${BASH_SOURCE[0]:-}" ]; then main "$@"; fi
