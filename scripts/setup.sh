#!/usr/bin/env bash
# Open Android Intelligence (OAI) Core 交互式配置与管理向导
# curl -fsSL https://raw.githubusercontent.com/1rua/open-android-intelligence/dev/scripts/setup.sh | bash
set -euo pipefail

setup_colors() {
    if [ -z "${NO_COLOR:-}" ] && { [ -t 1 ] || [ -t 2 ]; }; then
        C_RESET=$'\033[0m'
        C_BOLD=$'\033[1m'
        C_DIM=$'\033[2m'
        C_CYAN=$'\033[36m'
        C_RED=$'\033[31m'
    else
        C_RESET='' C_BOLD='' C_DIM='' C_CYAN='' C_RED=''
    fi
}

print_banner() {
    setup_colors
    printf '\n%s╭──────────────────────────────────────────────────────────╮%s\n' "${C_CYAN}${C_BOLD}" "${C_RESET}" >&2
    printf '%s│%s   %sOpen Android Intelligence (OAI) · 设置与管理向导%s       %s│%s\n' "${C_CYAN}${C_BOLD}" "${C_RESET}" "${C_BOLD}" "${C_RESET}" "${C_CYAN}${C_BOLD}" "${C_RESET}" >&2
    printf '%s│%s   %s管理网络端口、登录账号、本地 AI 助手连接与后台服务%s     %s│%s\n' "${C_CYAN}${C_BOLD}" "${C_RESET}" "${C_DIM}" "${C_RESET}" "${C_CYAN}${C_BOLD}" "${C_RESET}" >&2
    printf '%s╰──────────────────────────────────────────────────────────╯%s\n\n' "${C_CYAN}${C_BOLD}" "${C_RESET}" >&2
}

main() {
    local script_dir='' target="${OAI_DIR:-${HOME}/.oai-core}" previous='' argument
    for argument in "$@"; do
        if [ "$previous" = --dir ]; then target="$argument"; fi
        case "$argument" in
            --dir=*) target="${argument#--dir=}" ;;
            -h|--help)
                printf '用法: %s [--dir <安装目录>]\n' "${0##*/}"
                printf '说明: 打开 OAI Core 交互式设置向导（默认目录: ~/.oai-core）。\n'
                return 0
                ;;
        esac
        previous="$argument"
    done
    if [ -n "${BASH_SOURCE[0]:-}" ] && [ -f "${BASH_SOURCE[0]}" ]; then
        script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
        if [ -f "$script_dir/../oai-core/pyproject.toml" ] && [ -z "${OAI_DIR:-}" ] && [ "$target" = "${HOME}/.oai-core" ]; then
            target="$(cd "$script_dir/.." && pwd)"
        fi
    fi
    if [ -L "$target" ] || [ ! -f "$target/scripts/installer.py" ]; then
        setup_colors
        printf '%s✗ [错误] 未找到可信的已安装管理入口（找不到已安装的 OAI Core），请先运行 scripts/install.sh 完成安装。%s\n' "${C_RED}${C_BOLD}" "${C_RESET}" >&2
        return 1
    fi
    if [ -t 1 ] || [ -t 2 ]; then
        print_banner
    fi
    local python=python3
    if [ -x "$target/.venv/bin/python" ]; then python="$target/.venv/bin/python"; fi
    exec "$python" -I "$target/scripts/installer.py" setup --dir "$target"
}
if [ "${BASH_SOURCE[0]:-}" = "$0" ] || [ ! -f "${BASH_SOURCE[0]:-}" ]; then main "$@"; fi
