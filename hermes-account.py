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
from open_android_intelligence_gateway.account_paths import default_hermes_gateway_root
from open_android_intelligence_gateway.core import create_gateway_core
from open_android_intelligence_gateway.local_keys import (
    MASTER_KEY_FILE_ENV,
    MASTER_KEY_FILE_ENV_ALIAS,
    MasterKeyUnavailable,
    create_master_key_file,
    resolve_local_master_key_store,
)

def main():
    if len(sys.argv) < 2:
        print("用法:")
        print("  ./hermes-account.py create <用户名>      # 注册新账号")
        print("  ./hermes-account.py delete <用户名>      # 删除账号")
        print("  ./hermes-account.py status              # 查看网关管理状态")
        print("  ./hermes-account.py init-key [路径]     # 生成 0600 受限主密钥文件（ADR 0023）")
        print("\n示例:")
        print("  ./hermes-account.py create djbd")
        sys.exit(1)

    cmd = sys.argv[1]

    if cmd in ("init-key", "init_key", "key"):
        # ADR 0023：宿主没有 Secret Store 时必须由部署者提供受限密钥文件。
        # 该文件不能与数据库、备份或普通配置放在一起，所以默认写在项目外部。
        default_path = Path.home() / ".open-android-intelligence" / "gateway-master-key"
        target = Path(sys.argv[2]).expanduser() if len(sys.argv) > 2 else default_path
        try:
            created = create_master_key_file(target)
        except FileExistsError:
            print(f"❌ 密钥文件已存在，未覆盖: {target}")
            sys.exit(1)
        except OSError as exc:
            print(f"❌ 无法创建密钥文件 {target}: {exc}")
            sys.exit(1)
        print(f"✅ 已生成受限主密钥文件: {created}")
        print("请在 ~/.hermes/.env 中配置：")
        print(f"  {MASTER_KEY_FILE_ENV}={created}")
        print(f"（兼容别名也接受：{MASTER_KEY_FILE_ENV_ALIAS}）")
        print("配置后重启网关；缺少主密钥时网关会拒绝启动。")
        return

    # 默认存储目录：与 Hermes 插件运行时的 default_hermes_gateway_root() 保持严格对齐
    storage_root = os.environ.get("HERMES_STORAGE_ROOT") or str(default_hermes_gateway_root())

    # 命令行必须与插件使用同一个主密钥来源，否则这里创建的账号会缺少主密钥，
    # 表现为"登录成功但所有业务请求 400"。
    try:
        secret_store = resolve_local_master_key_store()
    except MasterKeyUnavailable as exc:
        print(f"❌ 主密钥不可用: {exc}")
        print("   可执行 ./hermes-account.py init-key 生成受限密钥文件后重试。")
        sys.exit(1)

    core = create_gateway_core(storage_root=storage_root, secret_store=secret_store)
    admin = create_admin_service(
        core=core,
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
