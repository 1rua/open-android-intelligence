"""操作视觉留痕、截图缩放标注（#FF1744 红框红点 / 对比圆环）、凭据脱敏与失败现场采集模块。"""

from __future__ import annotations

import base64
import io
import time
import traceback
from dataclasses import dataclass, field
from typing import Any

from .layout_pruner import SemanticNode, format_pruned_summary

# 视觉留痕高亮色值与默认压缩参数
HIGHLIGHT_HEX = "#FF1744"
HIGHLIGHT_RGBA_STROKE = (255, 23, 68, 240)
HIGHLIGHT_RGBA_FILL = (255, 23, 68, 48)
RING_WHITE_RGBA = (255, 255, 255, 230)
DEFAULT_MAX_WIDTH = 540
DEFAULT_WEBP_QUALITY = 78
MASKED_SECRET = "******"

try:
    from PIL import Image, ImageDraw, features as pil_features

    _HAS_PIL = True
    _HAS_WEBP = bool(pil_features.check("webp"))
except ImportError:  # pragma: no cover - 本机已安装 Pillow，此分支仅作极限兜底
    Image = None  # type: ignore[assignment]
    ImageDraw = None  # type: ignore[assignment]
    _HAS_PIL = False
    _HAS_WEBP = False


@dataclass
class StepEvidence:
    """单个测试步骤的视觉留痕与语义快照证据。"""

    index: int
    name: str
    kind: str  # "action" | "assertion" | "failure"
    status: str  # "PASS" | "FAIL"
    timestamp: float
    duration_ms: float = 0.0
    target_desc: str = ""
    target_bounds: tuple[int, int, int, int] | None = None
    tap_point: tuple[int, int] | None = None
    input_value: str | None = None
    detail: str = ""
    error_summary: str = ""
    traceback_text: str = ""
    pruned_layout: str = ""
    before_image_b64: str | None = None
    before_mime: str = "image/webp"
    after_image_b64: str | None = None
    after_mime: str = "image/webp"

    def to_json_dict(self) -> dict[str, Any]:
        """转换为适合写入 report.json 的结构化字典（不内联大图 Base64 避免 JSON 膨胀）。"""
        return {
            "index": self.index,
            "name": self.name,
            "kind": self.kind,
            "status": self.status,
            "timestamp": self.timestamp,
            "duration_ms": round(self.duration_ms, 2),
            "target_desc": self.target_desc,
            "target_bounds": list(self.target_bounds) if self.target_bounds else None,
            "tap_point": list(self.tap_point) if self.tap_point else None,
            "input_value": self.input_value,
            "detail": self.detail,
            "error_summary": self.error_summary,
            "traceback_text": self.traceback_text,
            "pruned_layout": self.pruned_layout,
            "has_before_image": self.before_image_b64 is not None,
            "before_mime": self.before_mime if self.before_image_b64 else None,
            "before_b64_bytes": len(self.before_image_b64) if self.before_image_b64 else 0,
            "has_after_image": self.after_image_b64 is not None,
            "after_mime": self.after_mime if self.after_image_b64 else None,
            "after_b64_bytes": len(self.after_image_b64) if self.after_image_b64 else 0,
        }


def _encode_pil_image(img: Any, quality: int) -> tuple[bytes, str]:
    """将 Pillow Image 对象编码为轻量 WebP（不支持时回退 PNG）。"""
    buf = io.BytesIO()
    rgb_img = img.convert("RGB")
    if _HAS_WEBP:
        rgb_img.save(buf, format="WEBP", quality=max(10, min(95, int(quality))), method=4)
        return buf.getvalue(), "image/webp"
    rgb_img.save(buf, format="PNG", optimize=True)
    return buf.getvalue(), "image/png"


def render_annotated_image_b64(
    raw_bytes: bytes,
    *,
    mode: str,
    target_bounds: tuple[int, int, int, int] | None = None,
    tap_point: tuple[int, int] | None = None,
    max_width: int = DEFAULT_MAX_WIDTH,
    quality: int = DEFAULT_WEBP_QUALITY,
) -> tuple[str, str]:
    """缩放截图并绘制视觉标注，返回 `(base64字符串, mime类型)`。

    - `mode="before"`: 绘制目标半透明红框及中心高亮红点（`#FF1744`）。
    - `mode="after"`: 绘制对比参考空心圆环，便于前后比对位移与状态。
    - `mode="plain"` / `"failure"`: 可选绘制目标参考框，缩放并压缩为 WebP。
    """
    if not raw_bytes:
        return "", "image/webp"

    if not _HAS_PIL or Image is None or ImageDraw is None:
        # 极限无 Pillow 环境直接回退保留原始 PNG
        return base64.b64encode(raw_bytes).decode("ascii"), "image/png"

    with Image.open(io.BytesIO(raw_bytes)) as src:
        orig_w, orig_h = src.size
        if orig_w <= 0 or orig_h <= 0:
            return base64.b64encode(raw_bytes).decode("ascii"), "image/png"

        scale = min(1.0, float(max_width) / float(orig_w)) if max_width > 0 else 1.0
        target_w = max(1, int(round(orig_w * scale)))
        target_h = max(1, int(round(orig_h * scale)))
        if scale < 1.0:
            resized = src.convert("RGBA").resize(
                (target_w, target_h), Image.Resampling.LANCZOS
            )
        else:
            resized = src.convert("RGBA")

        overlay = Image.new("RGBA", resized.size, (0, 0, 0, 0))
        draw = ImageDraw.Draw(overlay, "RGBA")

        if target_bounds is not None:
            x1, y1, x2, y2 = target_bounds
            sx1 = max(0, min(target_w - 1, int(round(x1 * scale))))
            sy1 = max(0, min(target_h - 1, int(round(y1 * scale))))
            sx2 = max(sx1 + 1, min(target_w - 1, int(round(x2 * scale))))
            sy2 = max(sy1 + 1, min(target_h - 1, int(round(y2 * scale))))
            if mode == "before":
                draw.rectangle(
                    [sx1, sy1, sx2, sy2],
                    fill=HIGHLIGHT_RGBA_FILL,
                    outline=HIGHLIGHT_RGBA_STROKE,
                    width=3,
                )
            else:
                draw.rectangle(
                    [sx1, sy1, sx2, sy2],
                    outline=HIGHLIGHT_RGBA_STROKE,
                    width=2,
                )

        point = tap_point
        if point is None and target_bounds is not None:
            point = (
                (target_bounds[0] + target_bounds[2]) // 2,
                (target_bounds[1] + target_bounds[3]) // 2,
            )

        if point is not None:
            cx = max(0, min(target_w - 1, int(round(point[0] * scale))))
            cy = max(0, min(target_h - 1, int(round(point[1] * scale))))
            if mode == "before":
                # 白色外圈增强深色界面对比度，中心为 #FF1744 实心红点
                r_outer = 9
                r_inner = 6
                draw.ellipse(
                    [cx - r_outer, cy - r_outer, cx + r_outer, cy + r_outer],
                    fill=RING_WHITE_RGBA,
                )
                draw.ellipse(
                    [cx - r_inner, cy - r_inner, cx + r_inner, cy + r_inner],
                    fill=HIGHLIGHT_RGBA_STROKE,
                )
            elif mode == "after":
                # 对比参考空心圆环 + 十字准星标记原操作落点
                r_ring = 11
                draw.ellipse(
                    [cx - r_ring - 1, cy - r_ring - 1, cx + r_ring + 1, cy + r_ring + 1],
                    outline=RING_WHITE_RGBA,
                    width=4,
                )
                draw.ellipse(
                    [cx - r_ring, cy - r_ring, cx + r_ring, cy + r_ring],
                    outline=HIGHLIGHT_RGBA_STROKE,
                    width=2,
                )
                draw.line([cx - 5, cy, cx + 5, cy], fill=HIGHLIGHT_RGBA_STROKE, width=2)
                draw.line([cx, cy - 5, cx, cy + 5], fill=HIGHLIGHT_RGBA_STROKE, width=2)

        composed = Image.alpha_composite(resized, overlay)
        encoded_bytes, mime = _encode_pil_image(composed, quality=quality)
        return base64.b64encode(encoded_bytes).decode("ascii"), mime


def reencode_b64_image(
    image_b64: str,
    *,
    max_width: int,
    quality: int,
) -> tuple[str, str]:
    """按更低宽度与压缩质量二次重编码已有 Base64 图片，供 15MB 体积守卫调用。"""
    if not image_b64:
        return "", "image/webp"
    if not _HAS_PIL or Image is None:
        return image_b64, "image/png"
    raw = base64.b64decode(image_b64)
    with Image.open(io.BytesIO(raw)) as src:
        orig_w, orig_h = src.size
        if orig_w <= 0 or orig_h <= 0:
            return image_b64, "image/webp"
        scale = min(1.0, float(max_width) / float(orig_w)) if max_width > 0 else 1.0
        target_w = max(1, int(round(orig_w * scale)))
        target_h = max(1, int(round(orig_h * scale)))
        resized = (
            src.resize((target_w, target_h), Image.Resampling.LANCZOS)
            if scale < 1.0
            else src
        )
        encoded_bytes, mime = _encode_pil_image(resized, quality=quality)
        return base64.b64encode(encoded_bytes).decode("ascii"), mime


def _compact_traceback(error: BaseException, max_lines: int = 16) -> str:
    """提取精简可读的异常堆栈，保留最核心末尾调用帧。"""
    lines = traceback.format_exception(type(error), error, error.__traceback__)
    flat_lines: list[str] = []
    for chunk in lines:
        flat_lines.extend(chunk.splitlines())
    if len(flat_lines) <= max_lines:
        return "\n".join(flat_lines)
    head = flat_lines[:2]
    tail = flat_lines[-(max_lines - 2) :]
    omitted = len(flat_lines) - len(head) - len(tail)
    return "\n".join([*head, f"  ... ({omitted} 行外层调用帧已折叠) ...", *tail])


class EvidenceRecorder:
    """E2E 步骤证据采集与脱敏记录器。"""

    def __init__(
        self,
        driver: Any = None,
        max_width: int = DEFAULT_MAX_WIDTH,
        webp_quality: int = DEFAULT_WEBP_QUALITY,
    ) -> None:
        self.driver = driver
        self.max_width = int(max_width)
        self.webp_quality = int(webp_quality)
        self.steps: list[StepEvidence] = []
        self._secrets: list[str] = []

    def add_secret(self, value: str) -> None:
        """登记需在报告和日志中强制替换为 `******` 的敏感字符串。"""
        if value and len(value) >= 3 and value not in self._secrets:
            self._secrets.append(value)

    def redact_text(self, text: str) -> str:
        """将文本中出现的全部已登记凭据脱敏替换为 `******`。"""
        if not text:
            return text
        masked = text
        for secret_val in sorted(self._secrets, key=len, reverse=True):
            masked = masked.replace(secret_val, MASKED_SECRET)
        return masked

    def _safe_capture_png(self) -> bytes | None:
        """容错抓取当前设备截图，任何异常均静默返回 None。"""
        if self.driver is None or not hasattr(self.driver, "capture_screenshot_bytes"):
            return None
        try:
            data = self.driver.capture_screenshot_bytes()
            return data if isinstance(data, (bytes, bytearray)) and len(data) > 0 else None
        except Exception:
            return None

    def _safe_dump_summary(self) -> str:
        """容错抓取当前设备精简语义树摘要，任何异常均不向外抛出。"""
        if self.driver is None or not hasattr(self.driver, "dump_semantics"):
            return ""
        try:
            roots = self.driver.dump_semantics()
            return format_pruned_summary(roots)
        except Exception:
            return ""

    def _encode_safe(
        self,
        raw_png: bytes | None,
        *,
        mode: str,
        target_bounds: tuple[int, int, int, int] | None,
        tap_point: tuple[int, int] | None,
    ) -> tuple[str | None, str]:
        """安全渲染单张证据图，避免编码失败影响主测试流程。"""
        if not raw_png:
            return None, "image/webp"
        try:
            b64, mime = render_annotated_image_b64(
                raw_png,
                mode=mode,
                target_bounds=target_bounds,
                tap_point=tap_point,
                max_width=self.max_width,
                quality=self.webp_quality,
            )
            return (b64 or None), mime
        except Exception:
            return None, "image/webp"

    def record_action(
        self,
        step_name: str,
        *,
        before_png: bytes | None = None,
        after_png: bytes | None = None,
        target: SemanticNode | None = None,
        tap_point: tuple[int, int] | None = None,
        target_desc: str = "",
        input_value: str | None = None,
        secret: bool = False,
        detail: str = "",
        duration_ms: float = 0.0,
        layout_summary: str = "",
    ) -> StepEvidence:
        """记录一次带前后视觉对比及目标红框红点标注的 UI 交互动作。"""
        bounds = target.bounds if target is not None else None
        effective_tap = tap_point or (target.center if target is not None else None)
        if not target_desc and target is not None:
            target_desc = (
                f"tag={target.tag}" if target.tag else f"text={target.text!r}"
            )

        stored_input = input_value
        if input_value is not None and secret:
            self.add_secret(input_value)
            stored_input = MASKED_SECRET
        elif stored_input is not None:
            stored_input = self.redact_text(stored_input)

        before_b64, before_mime = self._encode_safe(
            before_png,
            mode="before",
            target_bounds=bounds,
            tap_point=effective_tap,
        )
        after_b64, after_mime = self._encode_safe(
            after_png,
            mode="after",
            target_bounds=bounds,
            tap_point=effective_tap,
        )

        ev = StepEvidence(
            index=len(self.steps) + 1,
            name=step_name,
            kind="action",
            status="PASS",
            timestamp=time.time(),
            duration_ms=float(duration_ms),
            target_desc=self.redact_text(target_desc),
            target_bounds=bounds,
            tap_point=effective_tap,
            input_value=stored_input,
            detail=self.redact_text(detail),
            pruned_layout=self.redact_text(layout_summary),
            before_image_b64=before_b64,
            before_mime=before_mime,
            after_image_b64=after_b64,
            after_mime=after_mime,
        )
        self.steps.append(ev)
        return ev

    def record_assertion(
        self,
        step_name: str,
        *,
        passed: bool = True,
        screenshot_png: bytes | None = None,
        target: SemanticNode | None = None,
        target_desc: str = "",
        detail: str = "",
        duration_ms: float = 0.0,
        layout_summary: str = "",
    ) -> StepEvidence:
        """记录一次语义断言及其现场截图证据。"""
        bounds = target.bounds if target is not None else None
        center = target.center if target is not None else None
        if not target_desc and target is not None:
            target_desc = (
                f"tag={target.tag}" if target.tag else f"text={target.text!r}"
            )

        raw = screenshot_png if screenshot_png is not None else self._safe_capture_png()
        after_b64, after_mime = self._encode_safe(
            raw,
            mode="before" if bounds is not None else "plain",
            target_bounds=bounds,
            tap_point=center,
        )

        ev = StepEvidence(
            index=len(self.steps) + 1,
            name=step_name,
            kind="assertion",
            status="PASS" if passed else "FAIL",
            timestamp=time.time(),
            duration_ms=float(duration_ms),
            target_desc=self.redact_text(target_desc),
            target_bounds=bounds,
            tap_point=center,
            detail=self.redact_text(detail),
            pruned_layout=self.redact_text(layout_summary),
            after_image_b64=after_b64,
            after_mime=after_mime,
        )
        self.steps.append(ev)
        return ev

    def capture_failure_step(
        self,
        step_name: str,
        error: BaseException,
        *,
        target_desc: str = "",
        layout_summary: str = "",
        before_png: bytes | None = None,
    ) -> StepEvidence:
        """捕获异常瞬间的现场截图、精简堆栈与布局树，保证绝对不二次抛异常。"""
        try:
            raw = self._safe_capture_png() or before_png
            after_b64, after_mime = self._encode_safe(
                raw,
                mode="failure",
                target_bounds=None,
                tap_point=None,
            )
            effective_layout = layout_summary
            if not effective_layout:
                effective_layout = str(getattr(error, "last_layout_summary", "") or "")
            if not effective_layout:
                effective_layout = self._safe_dump_summary()

            crash_excerpt = ""
            if self.driver is not None and hasattr(self.driver, "recent_crash_log"):
                try:
                    crash_excerpt = str(self.driver.recent_crash_log() or "")[:1500]
                except Exception:
                    crash_excerpt = ""

            err_msg = self.redact_text(f"{type(error).__name__}: {error}")
            tb_text = self.redact_text(_compact_traceback(error))
            detail_parts = []
            if crash_excerpt:
                detail_parts.append(f"[logcat crash]\n{self.redact_text(crash_excerpt)}")

            ev = StepEvidence(
                index=len(self.steps) + 1,
                name=step_name or "异常中断",
                kind="failure",
                status="FAIL",
                timestamp=time.time(),
                target_desc=self.redact_text(target_desc),
                detail="\n".join(detail_parts),
                error_summary=err_msg,
                traceback_text=tb_text,
                pruned_layout=self.redact_text(effective_layout),
                after_image_b64=after_b64,
                after_mime=after_mime,
            )
            self.steps.append(ev)
            return ev
        except Exception as fallback_exc:
            ev = StepEvidence(
                index=len(self.steps) + 1,
                name=step_name or "异常中断",
                kind="failure",
                status="FAIL",
                timestamp=time.time(),
                error_summary=self.redact_text(
                    f"{type(error).__name__}: {error} (采集兜底: {fallback_exc})"
                ),
            )
            self.steps.append(ev)
            return ev
