"""真实 Core 传输与内置 JavaScript 处理器之间的身份、恢复及取消契约。"""
import asyncio
import json
from pathlib import Path
import shutil

import pytest

from oai_core.adapters.dsh import DshSessionAdapter
from oai_core.auth import Principal


@pytest.mark.asyncio
async def test_builtin_dsh_identity_recovery_history_and_exact_terminal_cancel():
    node = shutil.which("node")
    if node is None:
        pytest.skip("本环境未安装 Node，跨语言桥契约未执行")
    fixture = Path(__file__).parents[2] / "integrations/dsh-session-adapter/core-contract-fixture.mjs"
    process = await asyncio.create_subprocess_exec(node, str(fixture), stdout=asyncio.subprocess.PIPE)
    try:
        ready = json.loads(await asyncio.wait_for(process.stdout.readline(), 5))
        adapter = DshSessionAdapter(ready["origin"], ready["token"])
        principal = Principal("synthetic-core-account")
        assert await adapter.status()
        assert adapter.capabilities["generationRecovery"] is True
        assert adapter.capabilities["cancel"] is True
        frames = [frame async for frame in adapter.send(principal, "native", "合成输入", client_message_id="stable-client")]
        generation_id = "dsh:native:message:native-user"
        for frame in frames:
            if frame["type"] == "generation" and "state" in frame:
                assert frame["state"] in {"queued", "running", "unknown"}
                assert type(frame.get("revision")) is int and frame["revision"] >= 0
        assert frames[-1]["generationId"] == generation_id
        assert frames[-1]["userMessageId"] == "native-user"
        receipt = await adapter.generation(principal, "native", client_message_id="stable-client", generation_id=generation_id)
        assert receipt["state"] == "completed"
        assert receipt["messageId"] == "native-answer"
        assert receipt["userMessageId"] == "native-user"
        assert await adapter.current_generation(principal, "native") is None
        history = await adapter.history(principal, "native")
        assert [message["id"] for message in history] == ["native-user", "native-answer"]
        assert all(message["clientMessageId"] == "stable-client" for message in history)
        assert all(message["generationId"] == generation_id for message in history)
        result = await adapter.manage(principal, "native", "cancel", {"generationId": generation_id})
        assert result["outcome"] == "already_completed"
        assert result["generationId"] == generation_id
        repeated = [frame async for frame in adapter.send(principal, "native", "合成输入", client_message_id="stable-client")]
        assert repeated[0]["generationId"] == generation_id
        assert "state" not in repeated[0]
        assert repeated[-1]["type"] == "done"
    finally:
        process.terminate()
        await asyncio.wait_for(process.wait(), 5)
