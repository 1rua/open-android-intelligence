#!/usr/bin/env python3
"""
Hermes Gateway Account Management CLI
用于快速注册、管理和查看 Hermes Gateway 本地账号
"""

import sys
import os
from pathlib import Path

# 自动定位项目路径
REPO_ROOT = Path(__file__).resolve().parent
HERMES_INTEGRATION = REPO_ROOT / "integrations" / "hermes"
if str(HERMES_INTEGRATION) not in sys.path:
    sys.path.insert(0, str(HERMES_INTEGRATION))

from open_android_intelligence_gateway.admin import (
    run_admin_command,
    create_admin_service,
    HostApiCompatibility,
)

def main():
    if len(sys.argv) < 2:
        print("用法:")
        print("  ./hermes-account.py create <用户名>      # 注册新账号")
        print("  ./hermes-account.py delete <用户名>      # 删除账号")
        print("  ./hermes-account.py status              # 查看网关管理状态")
        print("\n示例:")
        print("  ./hermes-account.py create djbd")
        sys.exit(1)

    cmd = sys.argv[1]

    # 默认存储目录（可按需通过 HERMES_STORAGE_ROOT 环境变量指定）
    storage_root = os.environ.get(
        "HERMES_STORAGE_ROOT",
        str(Path.home() / ".hermes" / "open_android_intelligence_storage")
    )

    admin = create_admin_service(
        storage_root=storage_root,
        host_version="2.0.0",
        host_api=HostApiCompatibility("1.0.0", "3.0.0", "0" * 40),
    )

    if cmd in ("create", "add"):
        if len(sys.argv) < 3:
            print("错误: 请提供要创建的用户名，例如: ./hermes-account.py create djbd")
            sys.exit(1)
        account_id = sys.argv[2]
        remaining = sys.argv[3:]
        password = None
        if "--password" in remaining:
            idx = remaining.index("--password")
            if idx + 1 < len(remaining):
                password = remaining[idx + 1]
        if not password:
            for arg in remaining:
                if not arg.startswith("--"):
                    password = arg
                    break
        if not password:
            password = os.environ.get("OPEN_ANDROID_PASSWORD", "GatewaySecretPass2026!")

        result = run_admin_command(["account", "create", account_id, "--password", password, "--confirm-local"], admin)
    elif cmd in ("delete", "remove", "rm"):
        if len(sys.argv) < 3:
            print("错误: 请提供要删除的用户名，例如: ./hermes-account.py delete djbd")
            sys.exit(1)
        account_id = sys.argv[2]
        result = run_admin_command(["account", "delete", account_id, "--confirm-local"], admin)
    elif cmd == "status":
        result = run_admin_command(["status"], admin)
    else:
        result = run_admin_command(sys.argv[1:] + ["--confirm-local"], admin)

    if result.get("ok"):
        print(f"✅ 操作成功: {result}")
    else:
        print(f"❌ 操作失败: {result}")
        sys.exit(1)

if __name__ == "__main__":
    main()
