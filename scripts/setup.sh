#!/usr/bin/env bash
# OAI Core 配置入口：先验证管理员，再进入同一 Python 管理会话。
# curl -fsSL https://raw.githubusercontent.com/1rua/open-android-intelligence/dev/scripts/setup.sh | bash
set -euo pipefail
main() {
    local script_dir='' target="${OAI_DIR:-${HOME}/.oai-core}" previous='' argument
    for argument in "$@"; do
        if [ "$previous" = --dir ]; then target="$argument"; fi
        case "$argument" in --dir=*) target="${argument#--dir=}" ;; esac
        previous="$argument"
    done
    if [ -n "${BASH_SOURCE[0]:-}" ] && [ -f "${BASH_SOURCE[0]}" ]; then
        script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
        if [ -f "$script_dir/../oai-core/pyproject.toml" ] && [ -z "${OAI_DIR:-}" ] && [ "$target" = "${HOME}/.oai-core" ]; then
            target="$(cd "$script_dir/.." && pwd)"
        fi
    fi
    if [ -L "$target" ] || [ ! -f "$target/scripts/installer.py" ]; then
        printf '%s\n' '[错误] 未找到可信的已安装管理入口，请先完成安装；未下载或执行配置 helper。' >&2
        return 1
    fi
    local python=python3
    if [ -x "$target/.venv/bin/python" ]; then python="$target/.venv/bin/python"; fi
    exec "$python" -I "$target/scripts/installer.py" setup --dir "$target"
}
if [ "${BASH_SOURCE[0]:-}" = "$0" ] || [ ! -f "${BASH_SOURCE[0]:-}" ]; then main "$@"; fi
