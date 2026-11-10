"""为 Android 完成通知验收启动隔离的真实 Core 与受控 Hermes bridge。

只控制宿主任务何时结束；认证、原生会话、正文事件、持久游标与 HTTP 转发
全部复用生产实现。此进程不读取生产配置、不调用模型、不连接设备。
状态目录保留给人工复核，不自动删除。
"""
from __future__ import annotations

import argparse
import asyncio
from contextlib import AsyncExitStack, closing
import json
import os
from pathlib import Path
import signal
import sys

from aiohttp import web


# 从脚本所属 checkout 加载 Core 与会话适配器，避免误用另一工作区的安装。
repository = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(repository / "oai-core/src"))
sys.path.insert(0, str(repository / "integrations/hermes-session-adapter/src"))
if os.environ.get("HERMES_SDK_ROOT"):
    sys.path.insert(0, str(Path(os.environ["HERMES_SDK_ROOT"]).resolve()))
from communication_process_fixture import CONTRACT, OWNER, CommunicationProcess
from oai_core.server import create_core


def seed_conversations(directory):
    """仅在新的测试目录中创建两个真实会话，第二个用来验证通知导航。"""
    directory.mkdir()
    original = Path.cwd()
    try:
        os.chdir(directory)
        core = create_core({"storageRoot": "host-data", "masterKeyFile": "host-master",
                            "contractRoot": os.path.relpath(CONTRACT, directory)})
        conversations = []
        with closing(core.open_gateway_account(OWNER)) as account:
            for index in range(2):
                conversation = account.conversations.create(f"device-{index}", f"验收会话{index}",
                                                            f"device-{index}")["conversationId"]
                account.agent_sessions.record(conversation, "conversation-create")
                account.agent_sessions.attach(conversation, f"native-session-{index}", f"native-test-key-{index}")
                conversations.append(conversation)
        return conversations
    finally:
        os.chdir(original)


async def serve(arguments):
    directory = Path(arguments.state_dir).resolve()
    directory.mkdir(parents=True, exist_ok=True)
    if any(directory.iterdir()):
        raise ValueError("设备验收必须使用新的空状态目录")
    stopped = asyncio.Event()
    loop = asyncio.get_running_loop()
    for event in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(event, stopped.set)

    async with AsyncExitStack() as stack:
        conversation, other_conversation = seed_conversations(directory / "adapter")
        adapter = await CommunicationProcess("adapter", directory / "adapter", conversation=conversation).start()
        stack.push_async_callback(adapter.close)
        core = await CommunicationProcess("core", directory / "core", host_url=adapter.url).start()
        stack.push_async_callback(core.close)
        core_port = core.port
        command_lock = asyncio.Lock()

        async def state():
            async with command_lock:
                status = await adapter.command("status")
            starts = [item for item in adapter.observations() if item["event"] == "task.started"]
            return {"conversationId": adapter.ready["conversationId"], "otherConversationId": other_conversation,
                    "agentId": "hermes",
                    "generationId": starts[-1]["generationId"] if starts else None,
                    "startedCount": len(starts), **status}

        async def read_state(_request):
            return web.json_response(await state())

        async def release(_request):
            before = await state()
            if before["startedCount"] != 1 or before["liveTurns"] != 1:
                raise web.HTTPConflict(text="必须已经受理且只存在一轮尚未完成的测试回复")
            async with command_lock:
                await adapter.command("release")
            return web.json_response(await state())

        async def stop_core(_request):
            nonlocal core
            if core is None:
                raise web.HTTPConflict(text="测试 Core 已停止")
            await core.close()
            core = None
            return web.json_response({"stopped": True})

        async def start_core(_request):
            nonlocal core
            if core is not None:
                raise web.HTTPConflict(text="测试 Core 仍在运行")
            core = await CommunicationProcess("core", directory / "core", host_url=adapter.url,
                                              port=core_port).start()
            stack.push_async_callback(core.close)
            return web.json_response({"started": True})

        async def stop_fixture(_request):
            stopped.set()
            return web.json_response({"stopping": True})

        control = web.Application()
        control.router.add_get("/state", read_state)
        control.router.add_post("/release", release)
        control.router.add_post("/stop-core", stop_core)
        control.router.add_post("/start-core", start_core)
        control.router.add_post("/stop", stop_fixture)
        runner = web.AppRunner(control, access_log=None, shutdown_timeout=1)
        await runner.setup()
        stack.push_async_callback(runner.cleanup)
        site = web.TCPSite(runner, "127.0.0.1", arguments.port)
        await site.start()
        port = site._server.sockets[0].getsockname()[1]
        ready = {"coreUrl": core.url, "controlUrl": f"http://127.0.0.1:{port}",
                 "conversationId": adapter.ready["conversationId"], "agentId": "hermes",
                 "otherConversationId": other_conversation,
                 "corePid": core.process.pid, "adapterPid": adapter.process.pid}
        if arguments.ready_file:
            Path(arguments.ready_file).write_text(json.dumps(ready, ensure_ascii=False) + "\n", encoding="utf-8")
        print(json.dumps(ready, ensure_ascii=False), flush=True)
        await stopped.wait()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state-dir", required=True, help="新建的隔离空目录，完成后保留证据")
    parser.add_argument("--port", type=int, default=0, help="仅监听回环地址的测试控制口")
    parser.add_argument("--ready-file", help="可选的启动元数据 JSON 路径")
    arguments = parser.parse_args()
    if not 0 <= arguments.port <= 65535:
        parser.error("port 必须在 0 至 65535 之间")
    asyncio.run(serve(arguments))


if __name__ == "__main__":
    main()
