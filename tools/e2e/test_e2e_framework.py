"""Android E2E 测试框架核心底座单元测试套件。"""

from __future__ import annotations

import base64
import io
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Any
from unittest.mock import patch

import pytest
from PIL import Image

# 确保直接调用 `pytest tools/e2e/test_e2e_framework.py` 时也能按相对路径找到仓库根目录
_REPO_ROOT = Path(__file__).resolve().parents[2]
if str(_REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT))

from tools.e2e.core import (
    MAX_REPORT_BYTES,
    AdbAndroidDriver,
    AuthEnvironmentManager,
    BySemantics,
    E2EAuthEnvironmentError,
    E2EDeviceError,
    E2ERuntimeContext,
    EvidenceRecorder,
    IsolatedAuthEndpoint,
    SemanticNode,
    SmartWait,
    SmartWaitTimeoutError,
    find_nodes,
    format_pruned_summary,
    prune_android_cli_json,
    prune_layout_xml,
    resolve_repo_root,
    resolve_toolchains_root,
    strip_resource_id_prefix,
    write_html_report,
)


def _make_png_bytes(
    width: int = 1080,
    height: int = 1920,
    color: tuple[int, int, int] = (24, 28, 36),
    noisy: bool = False,
) -> bytes:
    """生成测试用 PNG 字节流；noisy=True 时生成高熵噪点图用于压测报告体积守卫。"""
    if noisy:
        raw = os.urandom(width * height * 3)
        img = Image.frombytes("RGB", (width, height), raw)
    else:
        img = Image.new("RGB", (width, height), color)
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return buf.getvalue()


# ---------------------------------------------------------------------------
# 1. layout_pruner 测试
# ---------------------------------------------------------------------------


def test_strip_resource_id_prefix_preserves_colon_tags() -> None:
    """验证包名前缀剥离不会误切自身包含冒号的 Compose testTag。"""
    assert strip_resource_id_prefix("com.openandroidintelligence.mobile:id/foo") == "foo"
    assert strip_resource_id_prefix("remote-agent-item:harness") == "remote-agent-item:harness"
    assert strip_resource_id_prefix("remote-host-item:host-123") == "remote-host-item:host-123"
    assert strip_resource_id_prefix("") == ""


def test_prune_layout_xml_handles_tty_noise_and_hoists_containers() -> None:
    """验证 XML 杂讯截取、空容器上提剪枝与语义查找。"""
    xml_sample = """
    UI hierarchy dumped to: /dev/tty
    <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
    <hierarchy rotation="0">
      <node index="0" text="" resource-id="" class="android.widget.FrameLayout" bounds="[0,0][1080,2400]" clickable="false" enabled="true">
        <node index="0" text="" resource-id="" class="android.view.ViewGroup" bounds="[0,0][1080,2400]" clickable="false" enabled="true">
          <node index="0" text="" resource-id="com.openandroidintelligence.mobile:id/remote-login-root" class="android.view.View" bounds="[0,0][1080,2400]" clickable="false" enabled="true">
            <node index="0" text="登录" resource-id="remote-login-submit" content-desc="提交登录" class="android.widget.Button" bounds="[100,800][980,940]" clickable="true" enabled="true" />
            <node index="1" text="" resource-id="remote-login-origin-input" class="android.widget.EditText" bounds="[100,400][980,520]" clickable="true" editable="false" enabled="true" />
          </node>
          <node index="1" text="" resource-id="" class="android.view.View" bounds="[0,0][0,0]" clickable="false" />
        </node>
      </node>
    </hierarchy>
    trailing noise
    """
    roots = prune_layout_xml(xml_sample)
    # 最外层两个无语义容器应被剪掉，直接提升到 remote-login-root
    assert len(roots) == 1
    root = roots[0]
    assert root.tag == "remote-login-root"
    assert len(root.children) == 2

    submit_nodes = find_nodes(roots, BySemantics(tag="remote-login-submit", clickable=True))
    assert len(submit_nodes) == 1
    assert submit_nodes[0].center == (540, 870)
    assert submit_nodes[0].desc == "提交登录"

    # EditText 即使 editable="false" 也应自动推断为 editable=True
    origin_nodes = find_nodes(roots, BySemantics(tag_prefix="remote-login-origin", editable=True))
    assert len(origin_nodes) == 1
    assert origin_nodes[0].editable is True

    # dict 序列化与 summary 格式化
    payload = root.to_dict()
    assert payload["tag"] == "remote-login-root"
    assert len(payload["children"]) == 2
    summary = format_pruned_summary(roots, max_nodes=2)
    assert "remote-login-root" in summary
    assert "其余 1 个节点已省略" in summary


def test_prune_android_cli_json_and_by_semantics_filters() -> None:
    """验证 android layout JSON 兼容解析及 BySemantics 多维度匹配。"""
    json_data = {
        "nodes": [
            {
                "className": "android.widget.LinearLayout",
                "bounds": {"left": 0, "top": 0, "right": 1080, "bottom": 1920},
                "children": [
                    {
                        "resourceId": "com.openandroidintelligence.mobile:id/remote-host-item:h1",
                        "text": "E2E Sim Host",
                        "contentDescription": "在线主机设备",
                        "bounds": [40, 200, 1040, 320],
                        "isClickable": True,
                        "isEnabled": True,
                    }
                ],
            }
        ]
    }
    roots = prune_android_cli_json(json_data)
    assert len(roots) == 1
    node = roots[0]
    assert node.tag == "remote-host-item:h1"
    assert node.center == (540, 260)

    assert BySemantics(tag_prefix="remote-host-item:", text_contains="Sim Host").matches(node)
    assert BySemantics(desc="在线主机设备", desc_contains="主机", enabled=True).matches(node)
    assert not BySemantics(text="Other").matches(node)
    assert not BySemantics().matches(node)
    assert "BySemantics(" in BySemantics(tag="foo", clickable=True).describe()


# ---------------------------------------------------------------------------
# 2. device_driver 测试
# ---------------------------------------------------------------------------


def test_resolve_roots_and_driver_fallbacks(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """验证相对路径工具链解析、adb 超时、语义树多级回退及安全输入转义。"""
    repo_root = resolve_repo_root()
    assert (repo_root / "apps/android").is_dir()

    custom_tc = tmp_path / "custom_tc"
    custom_tc.mkdir()
    monkeypatch.setenv("OAI_TOOLCHAINS_ROOT", str(custom_tc))
    assert resolve_toolchains_root(repo_root) == custom_tc
    monkeypatch.delenv("OAI_TOOLCHAINS_ROOT", raising=False)
    tc_resolved = resolve_toolchains_root(repo_root)
    assert tc_resolved.name == ".toolchains"

    driver = AdbAndroidDriver(serial="emulator-5554", repo_root=repo_root, command_timeout=3.0)
    calls: list[list[str]] = []

    def fake_run(cmd: list[str], **kwargs: Any) -> subprocess.CompletedProcess[Any]:
        calls.append(cmd)
        assert "timeout" in kwargs and kwargs["timeout"] > 0
        if "devices" in cmd:
            return subprocess.CompletedProcess(
                cmd, 0, stdout="List of devices attached\nemulator-5554\tdevice\n", stderr=""
            )
        if "/dev/tty" in cmd:
            # 模拟 /dev/tty 失败
            return subprocess.CompletedProcess(cmd, 1, stdout="", stderr="permission denied")
        if "/sdcard/window_dump.xml" in cmd and "dump" in cmd:
            return subprocess.CompletedProcess(cmd, 0, stdout="UI dumped", stderr="")
        if "cat" in cmd and "/sdcard/window_dump.xml" in cmd:
            return subprocess.CompletedProcess(
                cmd,
                0,
                stdout='<hierarchy><node text="FallbackOk" resource-id="fb-tag" bounds="[0,0][100,100]" clickable="true" enabled="true"/></hierarchy>',
                stderr="",
            )
        if "dumpsys" in cmd and "input_method" in cmd:
            return subprocess.CompletedProcess(cmd, 0, stdout="mInputShown=false\n", stderr="")
        return subprocess.CompletedProcess(cmd, 0, stdout="", stderr="")

    with patch("subprocess.run", side_effect=fake_run):
        nodes = driver.dump_semantics()
        assert len(nodes) == 1
        assert nodes[0].tag == "fb-tag"

        # 键盘未弹出时 hide_keyboard 不应误发送 KEYCODE_BACK
        assert driver.hide_keyboard() is False
        assert not any("keyevent" in c and "4" in c for c in calls)

        # 验证 input_text 会转义空格为 %s 且加单引号保护特殊字符
        driver.input_text("http://127.0.0.1:8080/a b&c")
        shell_cmds = [c[-1] for c in calls if "shell" in c and "input text" in c[-1]]
        assert shell_cmds
        assert "%s" in shell_cmds[-1]
        assert "'" in shell_cmds[-1]

    # 验证命令超时抛出 E2EDeviceError
    def timeout_run(cmd: list[str], **kwargs: Any) -> subprocess.CompletedProcess[Any]:
        if "devices" in cmd:
            return subprocess.CompletedProcess(
                cmd, 0, stdout="List of devices attached\nemulator-5554\tdevice\n", stderr=""
            )
        raise subprocess.TimeoutExpired(cmd=cmd, timeout=kwargs["timeout"])

    with patch("subprocess.run", side_effect=timeout_run):
        with pytest.raises(E2EDeviceError, match="超时"):
            driver.tap_point(10, 20)


# ---------------------------------------------------------------------------
# 3. SmartWait 防抖与超时测试
# ---------------------------------------------------------------------------


class _FakeWaitDriver:
    """可编程模拟语义树序列的测试桩驱动。"""

    def __init__(self, frames: list[list[SemanticNode]]) -> None:
        self._frames = frames
        self._idx = 0

    def dump_semantics(self) -> list[SemanticNode]:
        if self._idx < len(self._frames):
            cur = self._frames[self._idx]
            self._idx += 1
            return cur
        return self._frames[-1]


def _btn(tag: str, cx: int, cy: int) -> SemanticNode:
    return SemanticNode(
        tag=tag,
        desc="",
        text=tag,
        cls="android.widget.Button",
        bounds=(cx - 50, cy - 20, cx + 50, cy + 20),
        center=(cx, cy),
        clickable=True,
    )


def test_smart_wait_debounce_and_timeout() -> None:
    """验证 SmartWait 位移防抖收敛、wait_for_any 返回元组及超时诊断摘要。"""
    # 前两帧在动画漂移 (100,100) -> (100,140)，第三/四帧稳定在 (100,200) -> (100,201)
    frames = [
        [_btn("anim-btn", 100, 100)],
        [_btn("anim-btn", 100, 140)],
        [_btn("anim-btn", 100, 200)],
        [_btn("anim-btn", 100, 201)],
    ]
    fake_driver = _FakeWaitDriver(frames)
    waiter = SmartWait(fake_driver, default_timeout=2.0, poll_interval=0.01, stability_px=2)

    node = waiter.wait_for(BySemantics(tag="anim-btn"), require_stable=True)
    assert node.center == (100, 201)
    assert fake_driver._idx == 4

    # wait_for_any 返回 (locator, node)
    any_driver = _FakeWaitDriver([[_btn("target-b", 300, 400)], [_btn("target-b", 300, 400)]])
    any_waiter = SmartWait(any_driver, default_timeout=1.0, poll_interval=0.01, stability_px=2)
    loc_a = BySemantics(tag="target-a")
    loc_b = BySemantics(tag="target-b")
    matched_loc, matched_node = any_waiter.wait_for_any([loc_a, loc_b])
    assert matched_loc == loc_b
    assert matched_node.tag == "target-b"

    # wait_until_gone
    gone_driver = _FakeWaitDriver([[_btn("loading", 50, 50)], []])
    SmartWait(gone_driver, default_timeout=1.0, poll_interval=0.01).wait_until_gone(
        BySemantics(tag="loading")
    )

    # wait_for_predicate
    pred_driver = _FakeWaitDriver([[], [_btn("ready", 10, 10)]])
    res = SmartWait(pred_driver, default_timeout=1.0, poll_interval=0.01).wait_for_predicate(
        "检查 ready 节点", lambda roots: roots[0] if roots else None
    )
    assert res.tag == "ready"

    # 超时应携带 locator_desc / timeout / last_layout_summary
    timeout_driver = _FakeWaitDriver([[_btn("other-node", 50, 50)]])
    with pytest.raises(SmartWaitTimeoutError) as exc_info:
        SmartWait(timeout_driver, default_timeout=0.05, poll_interval=0.01).wait_for(
            BySemantics(tag="missing-node")
        )
    err = exc_info.value
    assert "missing-node" in err.locator_desc
    assert err.timeout == pytest.approx(0.05)
    assert "other-node" in err.last_layout_summary


# ---------------------------------------------------------------------------
# 4. EvidenceRecorder 视觉标注与脱敏测试
# ---------------------------------------------------------------------------


def test_evidence_red_dot_annotation_redaction_and_safe_failure() -> None:
    """验证 before 红点标注、WebP 缩放、secret 脱敏与失败现场安全捕获。"""

    class _BrokenDriver:
        def capture_screenshot_bytes(self) -> bytes:
            raise RuntimeError("device disconnected")

        def dump_semantics(self) -> list[SemanticNode]:
            raise RuntimeError("uiautomator died")

        def recent_crash_log(self) -> str:
            return "FATAL secret-token-xyz"

    recorder = EvidenceRecorder(driver=_BrokenDriver(), max_width=540, webp_quality=78)
    raw_before = _make_png_bytes(1080, 1920, (20, 20, 25))
    raw_after = _make_png_bytes(1080, 1920, (40, 45, 55))
    target = _btn("remote-login-password-input", 540, 960)

    ev = recorder.record_action(
        "输入密码",
        before_png=raw_before,
        after_png=raw_after,
        target=target,
        input_value="super-secret-token-12345",
        secret=True,
    )
    assert ev.input_value == "******"
    assert ev.before_image_b64 is not None
    assert ev.after_image_b64 is not None

    # 检验缩放宽度 <= 540 且中心红点确实被绘制在图片正中央 (#FF1744 附近高亮红)
    before_bytes = base64.b64decode(ev.before_image_b64)
    with Image.open(io.BytesIO(before_bytes)) as img:
        assert img.width == 540
        assert img.height == 960
        r, g, b = img.convert("RGB").getpixel((270, 480))
        assert r > 200 and g < 80 and b < 110  # 中心应为高亮红色 #FF1744

    # 验证故障捕获在驱动全面异常下也绝不二次抛异常，且敏感口令已被脱敏
    fail_ev = recorder.capture_failure_step(
        "故意失败",
        RuntimeError("连接失败 super-secret-token-12345"),
        before_png=raw_before,
    )
    assert fail_ev.status == "FAIL"
    assert "super-secret-token-12345" not in fail_ev.error_summary
    assert "******" in fail_ev.error_summary


# ---------------------------------------------------------------------------
# 5. Reporter HTML 置顶标红与 15MB 硬上限压缩测试
# ---------------------------------------------------------------------------


def test_reporter_failure_highlight_and_15mb_guard(tmp_path: Path) -> None:
    """验证 report.html 失败置顶标红以及超过 15MB 时的自动压缩守卫。"""
    # 先测试失败报告置顶与 JSON 输出
    rec = EvidenceRecorder()
    png = _make_png_bytes(800, 1200)
    rec.record_action("点击入口", before_png=png, after_png=png, target=_btn("entry", 400, 600))
    rec.capture_failure_step(
        "缺失节点演练",
        SmartWaitTimeoutError("BySemantics(tag='missing')", 2.0, "01. tag=entry center=(400, 600)"),
        before_png=png,
    )
    report_dir = tmp_path / "fail_report"
    html_path = write_html_report(
        report_dir,
        "failure_drill",
        passed=False,
        steps=rec.steps,
        metadata={"mode": "local-sim", "token": "should-be-masked"},
    )
    assert html_path.is_file()
    json_path = report_dir / "report.json"
    assert json_path.is_file()
    html_content = html_path.read_text(encoding="utf-8")
    assert "failure-zone" in html_content
    assert "failure-card" in html_content
    assert "缺失节点演练" in html_content
    assert "should-be-masked" not in html_content
    assert "******" in html_content

    # 再构造巨型噪点图（超过 15MB 原始 Base64 负荷），验证 15MB 压缩守卫自动生效
    huge_rec = EvidenceRecorder(max_width=1440, webp_quality=95)
    # 生成多张大图，确保未压缩前 > 15MB
    fake_steps = []
    for i in range(12):
        huge_png = _make_png_bytes(1440, 2880, noisy=True)
        # 直接构造超高分 PNG Base64 模拟超大步骤载荷（每张 ~1.8MB -> 12*2*1.8MB > 30MB）
        huge_b64 = base64.b64encode(huge_png).decode("ascii")
        from tools.e2e.core.evidence import StepEvidence

        fake_steps.append(
            StepEvidence(
                index=i + 1,
                name=f"压测步骤 {i + 1}",
                kind="action",
                status="PASS" if i < 11 else "FAIL",
                timestamp=time.time(),
                before_image_b64=huge_b64,
                before_mime="image/png",
                after_image_b64=huge_b64,
                after_mime="image/png",
            )
        )

    huge_dir = tmp_path / "huge_report"
    huge_html = write_html_report(huge_dir, "size_guard", passed=False, steps=fake_steps)
    assert huge_html.stat().st_size <= MAX_REPORT_BYTES
    assert "15MB" in huge_html.read_text(encoding="utf-8")

    # 遵循无永久删除铁律，将测试临时报告目录移入工作区 .worktrees/trash
    trash_dir = resolve_repo_root() / ".worktrees" / "trash"
    trash_dir.mkdir(parents=True, exist_ok=True)
    shutil.move(str(report_dir), str(trash_dir / f"ut-fail-report-{int(time.time() * 1000)}"))
    shutil.move(str(huge_dir), str(trash_dir / f"ut-huge-report-{int(time.time() * 1000)}"))


# ---------------------------------------------------------------------------
# 6. AuthEnvironmentManager + fixed-server.mjs + sim_remote_host.mjs 实进程集成测试
# ---------------------------------------------------------------------------


def test_auth_env_local_sim_end_to_end_protocol_and_clean_exit() -> None:
    """实测启动 fixed-server.mjs + sim_remote_host.mjs，完成真实客户端握手、发消息与优雅退出。"""
    repo_root = resolve_repo_root()

    # 未连接模拟器时 snapshot 模式应清晰报错
    with pytest.raises(E2EAuthEnvironmentError):
        AuthEnvironmentManager(mode="snapshot", driver=None, repo_root=repo_root).start()

    mgr = AuthEnvironmentManager(
        mode="local-sim",
        driver=None,
        repo_root=repo_root,
        startup_timeout=20.0,
    )
    endpoint = mgr.start()
    try:
        assert isinstance(endpoint, IsolatedAuthEndpoint)
        assert endpoint.mode == "local-sim"
        assert endpoint.origin.startswith("http://127.0.0.1:")
        assert endpoint.fixture_account == "fixture@example.test"
        assert endpoint.host_name == "E2E Sim Host"
        assert endpoint.host_id

        # 启动隔离 Node 客户端进程：完整跑一遍登录、注册、WS 连接、Noise IK 握手、
        # describe/workspace/create/follow/prompt，并验证收到 "E2E_PONG: Hello OAI E2E"
        from tools.e2e.core.auth_env import _discover_fallback_node_modules, _resolve_node_binary

        node_bin = _resolve_node_binary(repo_root)
        fallback_dirs = _discover_fallback_node_modules(repo_root)
        env = os.environ.copy()
        if fallback_dirs:
            env["OAI_NODE_FALLBACK_MODULES"] = ":".join(str(p) for p in fallback_dirs)

        probe_script = r"""
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import {
  loadRuntimeDependencies,
  toBase64Url,
  fromBase64Url,
} from './tools/e2e/core/sim_remote_host.mjs';

const [origin, account, password, hostId] = process.argv.slice(1);
const deps = await loadRuntimeDependencies();
const { WebSocket, NoiseIkSession, createNoisePrologue, SecureMessageCodec } = deps;

async function post(path, body, bearer) {
  const res = await fetch(origin + path, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(bearer ? { Authorization: `Bearer ${bearer}` } : {}),
    },
    body: JSON.stringify(body),
  });
  assert.equal(res.status, 200, `${path} status=${res.status}`);
  return res.json();
}
async function get(path, bearer) {
  const res = await fetch(origin + path, {
    headers: { Authorization: `Bearer ${bearer}` },
  });
  assert.equal(res.status, 200, `${path} status=${res.status}`);
  return res.json();
}

const login = await post('/api/v1/auth/login', { email: account, password });
const clientKeys = deps.generateKeyPair();
const clientDeviceId = randomUUID();
const tokens = await post('/api/v1/devices/register', {
  v: 1,
  device: {
    deviceId: clientDeviceId,
    name: 'E2E Probe Client',
    role: 'client',
    platform: 'android',
    identityKey: clientKeys.publicKey,
    clientVersion: '0.6.5+oai.android',
  },
}, login.token);

const hostDesc = await get(`/api/v1/devices/${hostId}`, tokens.accessToken);
assert.equal(hostDesc.name, 'E2E Sim Host');

const ws = new WebSocket(origin.replace(/^http/, 'ws') + '/ws/v1/connect');
const incomingQueue = [];
const waiters = [];
function pushFrame(raw) {
  const frame = JSON.parse(raw);
  if (waiters.length > 0) waiters.shift()(frame);
  else incomingQueue.push(frame);
}
if (typeof ws.on === 'function') {
  ws.on('message', (b) => pushFrame(b.toString('utf8')));
} else {
  ws.onmessage = (e) => pushFrame(String(e.data));
}
async function nextFrame() {
  if (incomingQueue.length > 0) return incomingQueue.shift();
  return new Promise((resolve) => waiters.push(resolve));
}
await new Promise((resolve) => {
  if (typeof ws.once === 'function') ws.once('open', resolve);
  else ws.onopen = resolve;
});

function sendCtrl(type, payload) {
  ws.send(JSON.stringify({ v: 1, id: randomUUID(), type, timestamp: Date.now(), payload }));
}

sendCtrl('hello', {
  role: 'client',
  deviceId: clientDeviceId,
  accessToken: tokens.accessToken,
  protocols: [1],
  capabilities: ['transport.relay'],
  clientVersion: '0.6.5+oai.android',
});

let frame = await nextFrame();
assert.equal(frame.type, 'hello.ack');

sendCtrl('connect.request', { hostDeviceId: hostId, preferredTransports: ['relay'] });
frame = await nextFrame();
assert.equal(frame.type, 'connect.accepted');
const connectionId = frame.payload.connectionId;

sendCtrl('transport.selected', { connectionId, targetDeviceId: hostId, transport: 'relay' });

const noise = new NoiseIkSession({
  role: 'initiator',
  localPrivateKey: clientKeys.privateKey,
  localPublicKey: clientKeys.publicKey,
  remotePublicKey: hostDesc.identityKey,
  prologue: createNoisePrologue(connectionId, hostId, clientDeviceId),
});

sendCtrl('secure.handshake', {
  connectionId,
  targetDeviceId: hostId,
  step: 1,
  data: toBase64Url(noise.writeHandshake(new Uint8Array(0))),
});

frame = await nextFrame();
assert.equal(frame.type, 'secure.handshake');
assert.equal(frame.payload.step, 2);
noise.readHandshake(fromBase64Url(frame.payload.data));
assert.equal(noise.complete, true);

const outCodec = new SecureMessageCodec();
const inCodec = new SecureMessageCodec();
let outCounter = 0;
let inCounter = 0;
const events = [];
const rpcWaiters = new Map();

function sendMsg(type, payload, id = randomUUID()) {
  const bytes = new TextEncoder().encode(JSON.stringify({ v: 1, id, type, timestamp: Date.now(), payload }));
  for (const chunk of outCodec.encode(bytes)) {
    sendCtrl('relay', {
      connectionId,
      targetDeviceId: hostId,
      counter: outCounter++,
      ciphertext: toBase64Url(noise.encrypt(chunk)),
    });
  }
}

async function pump() {
  while (true) {
    const f = await nextFrame();
    if (f.type === 'ping') { sendCtrl('pong', f.payload); continue; }
    if (f.type !== 'relay') continue;
    assert.equal(f.payload.counter, inCounter++);
    const pt = noise.decrypt(fromBase64Url(f.payload.ciphertext));
    const rawMsg = inCodec.decode(pt);
    if (!rawMsg) continue;
    const msg = JSON.parse(new TextDecoder().decode(rawMsg));
    if (msg.type === 'rpc.response') {
      rpcWaiters.get(msg.payload.requestId)?.(msg.payload.result);
    } else if (msg.type === 'event') {
      events.push(msg.payload);
    }
  }
}
const pumpTask = pump().catch(() => {});

async function rpc(method, params) {
  const id = randomUUID();
  const p = new Promise((resolve) => rpcWaiters.set(id, resolve));
  sendMsg('rpc.request', { method, params }, id);
  return p;
}

const desc = await rpc('harness.transport.describe', {});
assert.ok(desc.capabilities.includes('harness.remote.v3'));

await rpc('harness.remote.stream.open', {
  streamId: 'ws-stream-1',
  endpoint: 'workspace/follow',
  payload: { args: {} },
});
const created = await rpc('harness.remote.call', {
  endpoint: 'session/create',
  payload: { args: { request: { workspaceId: 'e2e-sim-ws' } } },
});
const sid = created.value.sessionId;
assert.ok(sid);

await rpc('harness.remote.stream.open', {
  streamId: 'sess-stream-1',
  endpoint: 'session/follow',
  payload: { args: { request: { address: { kind: 'session', sessionId: sid }, assistantStream: true } } },
});

const models = await rpc('harness.remote.call', {
  endpoint: 'session/models',
  payload: { args: { request: { sessionId: sid } } },
});
assert.equal(models.ok, true);
assert.ok(models.value.groups.length > 0);

const cmds = await rpc('harness.remote.call', {
  endpoint: 'commands/list',
  payload: { args: { agentId: sid } },
});
assert.equal(cmds.ok, true);

const pageRes = await rpc('harness.remote.call', {
  endpoint: 'session/page',
  payload: { args: { request: { address: { kind: 'session', sessionId: sid }, maxMessages: 20 } } },
});
assert.equal(pageRes.ok, true);

const renameRes = await rpc('harness.remote.call', {
  endpoint: 'session/rename',
  payload: { args: { request: { sessionId: sid, title: 'Renamed E2E Session' } } },
});
assert.equal(renameRes.value.title, 'Renamed E2E Session');

await rpc('harness.remote.stream.open', {
  streamId: 'ev-stream-1',
  endpoint: '$events',
  payload: { args: {} },
});

const promptRes = await rpc('harness.remote.call', {
  endpoint: 'session/prompt',
  payload: { args: { request: { sessionId: sid, requestId: 'req-e2e-1', mode: 'queue', content: [{ type: 'text', text: 'Hello OAI E2E' }] } } },
});
assert.equal(promptRes.value.accepted, true);

// 等待推送包含 E2E_PONG: Hello OAI E2E 与 turn/end
const deadline = Date.now() + 5000;
while (Date.now() < deadline) {
  const hasTurnEnd = events.some((e) => e.data?.value?.event?.type === 'turn/end');
  if (hasTurnEnd) break;
  await new Promise((r) => setTimeout(r, 30));
}
const assistant = events.find((e) => e.data?.value?.event?.type === 'assistant/message');
assert.ok(assistant, '缺少 assistant/message 流事件');
assert.equal(assistant.data.value.event.data.message.content[0].text, 'E2E_PONG: Hello OAI E2E');

// 验证 session/page 历史中也记录了本轮对话（seq 连续 1..4）
const afterPage = await rpc('harness.remote.call', {
  endpoint: 'session/page',
  payload: { args: { request: { address: { kind: 'session', sessionId: sid }, maxMessages: 20 } } },
});
assert.equal(afterPage.value.records.length, 4);
assert.deepEqual(
  afterPage.value.records.map((r) => r.event.seq),
  [1, 2, 3, 4],
);

// 校验大于 48KB 的 DSHF 分片加密通信正常重组
const bigPayload = 'X'.repeat(60_000);
const bigRes = await rpc('harness.remote.call', {
  endpoint: 'session/rename',
  payload: { args: { request: { sessionId: sid, title: bigPayload } } },
});
assert.equal(bigRes.value.title.length, 60_000);

ws.close();
console.log('PROBE_OK');
process.exit(0);
"""
        proc = subprocess.run(
            [
                node_bin,
                "--experimental-vm-modules",
                "--input-type=module",
                "-e",
                probe_script,
                endpoint.origin,
                endpoint.fixture_account,
                endpoint.fixture_token,
                endpoint.host_id,
            ],
            cwd=str(repo_root),
            env=env,
            capture_output=True,
            text=True,
            timeout=15.0,
            check=False,
        )
        assert proc.returncode == 0, f"stdout={proc.stdout}\nstderr={proc.stderr}"
        assert "PROBE_OK" in proc.stdout
    finally:
        fixed_proc = mgr._fixed_server_proc
        sim_proc = mgr._sim_host_proc
        mgr.close()
        if fixed_proc is not None:
            assert fixed_proc.poll() is not None
        if sim_proc is not None:
            assert sim_proc.poll() is not None


def test_runtime_context_construction() -> None:
    """验证 E2ERuntimeContext 可直接装配所有核心组件。"""
    repo_root = resolve_repo_root()
    driver = AdbAndroidDriver(serial="emu-test", repo_root=repo_root)
    wait = SmartWait(driver)
    evidence = EvidenceRecorder(driver)
    auth = IsolatedAuthEndpoint(
        mode="local-sim",
        origin="http://127.0.0.1:18080",
        fixture_account="fixture@example.test",
        fixture_token="fixture-local-password",
    )
    ctx = E2ERuntimeContext(driver=driver, wait=wait, evidence=evidence, auth=auth)
    assert ctx.expect_reply == "E2E_PONG"
    assert ctx.report_dir.name == "e2e"
