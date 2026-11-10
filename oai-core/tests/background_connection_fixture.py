"""Android 连接验收专用的隔离 Core 与回环控制器，不加载生产配置。"""
from __future__ import annotations

import argparse
import asyncio
from contextlib import closing, suppress
import json
import logging
import os
from pathlib import Path
import signal
import socket
import sys

from aiohttp import web

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from oai_core.server import create_app, create_core
from oai_core.sessions import AgentRegistry


ACCOUNT = "connection-e2e"
PASSWORD = "connection-fixture-only"
FIXTURE = "isolated-background-connection-v1"
PREFIX = "/open-android-intelligence/v2"


class BackgroundConnectionFixture:
    """只有测试控制器能重启自己创建的 Core；账号目录和端口始终不变。"""

    def __init__(self, directory: Path, contract: Path):
        self.directory = directory.resolve()
        self.directory.mkdir(parents=True, exist_ok=False)
        self.settings = {
            "storageRoot": os.path.relpath(self.directory / "accounts"),
            "masterKeyFile": os.path.relpath(self.directory / "secrets" / "master"),
            "contractRoot": os.path.relpath(contract.resolve()),
        }
        self.core = create_core(self.settings)
        with closing(self.core.open_gateway_account(ACCOUNT)) as account:
            account.credentials.create_password(PASSWORD)
        self.runner: web.AppRunner | None = None
        self.port = 0
        self.login_count = 0
        self.subscription_count = 0
        self.restart_count = 0
        self.restarting: asyncio.Task | None = None
        self.markers: list[str] = []
        self.stopped = asyncio.Event()

    async def start_core(self):
        @web.middleware
        async def observe(request, handler):
            if request.path == PREFIX + "/sessions/password":
                self.login_count += 1
            if request.path in {PREFIX + "/events", PREFIX + "/events/ws"}:
                self.subscription_count += 1
            return await handler(request)

        app = create_app(self.settings, registry=AgentRegistry(), core=self.core)
        app.middlewares.insert(0, observe)
        self.runner = web.AppRunner(app, access_log=None, shutdown_timeout=1)
        await self.runner.setup()
        listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind(("127.0.0.1", self.port))
        self.port = listener.getsockname()[1]
        await web.SockSite(self.runner, listener).start()

    def append(self, marker: str):
        with closing(self.core.open_gateway_account(ACCOUNT)) as account:
            account.events.append("gateway.notice", "cor_" + marker, {"noticeCode": marker})
        self.markers.append(marker)

    async def restart_after_outage(self):
        # 停止的是本夹具持有的服务端；在停机期写入相同持久事件库。
        await self.runner.cleanup()
        self.runner = None
        self.append("during_outage")
        await asyncio.sleep(0.8)
        self.core = create_core(self.settings)
        await self.start_core()
        self.restart_count += 1

    def status(self):
        return {"fixture": FIXTURE, "loginCount": self.login_count,
                "subscriptionCount": self.subscription_count,
                "restartCount": self.restart_count, "markers": self.markers}

    async def control(self, request):
        operation = request.match_info["operation"]
        if operation == "status" and request.method == "GET":
            return web.json_response(self.status())
        if request.method != "POST":
            raise web.HTTPMethodNotAllowed(request.method, ["POST"])
        if operation == "prepare" and not self.markers:
            self.append("before_outage")
        elif operation == "outage" and self.markers == ["before_outage"] and self.restarting is None:
            self.restarting = asyncio.create_task(self.restart_after_outage())
        elif operation == "finish" and self.restart_count == 1:
            self.append("after_recovery")
        elif operation == "stop":
            # 先发送控制响应，再结束本夹具持有的监听器。
            asyncio.get_running_loop().call_later(0.1, self.stopped.set)
        else:
            raise web.HTTPConflict(text="测试操作顺序无效")
        return web.json_response(self.status())

    async def close(self):
        if self.restarting is not None:
            await self.restarting
        if self.runner is not None:
            await self.runner.cleanup()


async def serve(directory: Path, contract: Path):
    fixture = BackgroundConnectionFixture(directory, contract)
    control = web.Application()
    control.router.add_route("*", "/{operation}", fixture.control)
    runner = web.AppRunner(control, access_log=None)
    stopped = fixture.stopped
    loop = asyncio.get_running_loop()
    for signum in (signal.SIGINT, signal.SIGTERM):
        with suppress(NotImplementedError):
            loop.add_signal_handler(signum, stopped.set)
    try:
        await fixture.start_core()
        await runner.setup()
        listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        listener.bind(("127.0.0.1", 0))
        control_port = listener.getsockname()[1]
        await web.SockSite(runner, listener).start()
        ready = {"fixture": FIXTURE, "corePort": fixture.port, "controlPort": control_port}
        (fixture.directory / "ready.json").write_text(json.dumps(ready), encoding="utf-8")
        print(json.dumps(ready), flush=True)
        await stopped.wait()
    finally:
        await fixture.close()
        await runner.cleanup()
        # 账号、密钥与日志保留在指定的独立目录，供人工复核。


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state-dir", type=Path, required=True, help="必须尚不存在的隔离状态目录")
    parser.add_argument("--contract-root", type=Path, default=Path(__file__).resolve().parents[2] / "gateway-contract")
    args = parser.parse_args()
    logging.basicConfig(level=logging.WARNING)
    asyncio.run(serve(args.state_dir, args.contract_root))


if __name__ == "__main__":
    main()
