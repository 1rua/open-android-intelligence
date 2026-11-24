"""自包含视觉留痕 HTML/JSON 报告生成器（内建 15MB 体积上限二次压缩守卫）。"""

from __future__ import annotations

import copy
import datetime
import html
import json
from pathlib import Path
from typing import Any, Sequence

from .evidence import (
    MASKED_SECRET,
    StepEvidence,
    reencode_b64_image,
)

# 报告总大小硬上限：15 MiB
MAX_REPORT_BYTES = 15 * 1024 * 1024

# 多级压缩降采样阶梯：(最大宽像素, WebP 质量)
_COMPRESSION_PROFILES: tuple[tuple[int, int], ...] = (
    (440, 62),
    (360, 48),
    (280, 35),
    (200, 22),
)


def _redact_metadata(
    metadata: dict[str, Any] | None,
    secrets: Sequence[str],
) -> dict[str, Any]:
    """深度脱敏元数据中可能残留的 token / password 类字段与密文值。"""
    if not metadata:
        return {}
    sensitive_keys = ("token", "password", "secret", "credential", "key")

    def _scrub(obj: Any, parent_key: str = "") -> Any:
        if isinstance(obj, dict):
            return {
                str(k): (
                    MASKED_SECRET
                    if any(sk in str(k).lower() for sk in sensitive_keys)
                    and isinstance(v, str)
                    and v
                    else _scrub(v, str(k))
                )
                for k, v in obj.items()
            }
        if isinstance(obj, list):
            return [_scrub(item, parent_key) for item in obj]
        if isinstance(obj, str):
            out = obj
            for s in secrets:
                if s:
                    out = out.replace(s, MASKED_SECRET)
            return out
        return obj

    return _scrub(metadata)


def _img_tag(b64: str | None, mime: str, alt: str) -> str:
    """渲染内联 Base64 图片标签。"""
    if not b64:
        return '<div class="img-empty">无截图</div>'
    safe_alt = html.escape(alt, quote=True)
    safe_mime = html.escape(mime or "image/webp", quote=True)
    return f'<img src="data:{safe_mime};base64,{b64}" alt="{safe_alt}" loading="lazy" />'


def _render_failure_banner(failed_steps: list[StepEvidence]) -> str:
    """生成置顶标红的失败高亮卡片。"""
    if not failed_steps:
        return ""
    cards: list[str] = []
    for step in failed_steps:
        img_html = _img_tag(
            step.after_image_b64 or step.before_image_b64,
            step.after_mime if step.after_image_b64 else step.before_mime,
            f"失败现场 #{step.index}",
        )
        layout_html = (
            f'<details class="layout-box" open><summary>失败时精简语义树 (Pruned Layout)</summary>'
            f"<pre>{html.escape(step.pruned_layout)}</pre></details>"
            if step.pruned_layout
            else ""
        )
        tb_html = (
            f'<details class="tb-box" open><summary>精简异常堆栈</summary>'
            f"<pre>{html.escape(step.traceback_text)}</pre></details>"
            if step.traceback_text
            else ""
        )
        detail_html = (
            f"<pre class=\"detail-pre\">{html.escape(step.detail)}</pre>"
            if step.detail
            else ""
        )
        cards.append(
            f"""
            <article class="failure-card">
              <div class="failure-header">
                <span class="badge badge-fail">失败步骤 #{step.index}</span>
                <h3>{html.escape(step.name)}</h3>
              </div>
              <p class="failure-target">目标语义: <code>{html.escape(step.target_desc or '-')}</code></p>
              <div class="failure-error">{html.escape(step.error_summary or '执行失败')}</div>
              <div class="failure-body">
                <div class="failure-shot">{img_html}</div>
                <div class="failure-diag">
                  {tb_html}
                  {detail_html}
                  {layout_html}
                </div>
              </div>
            </article>
            """
        )
    return f'<section class="failure-zone"><h2>失败诊断置顶</h2>{"".join(cards)}</section>'


def _render_step_card(step: StepEvidence) -> str:
    """生成单个时间线步骤展示卡片。"""
    is_fail = step.status != "PASS"
    badge_cls = "badge-fail" if is_fail else "badge-pass"
    card_cls = "step-card step-fail" if is_fail else "step-card"

    meta_items: list[str] = [
        f"<span>类型: {html.escape(step.kind)}</span>",
        f"<span>耗时: {step.duration_ms:.0f} ms</span>",
    ]
    if step.target_desc:
        meta_items.append(f"<span>目标: <code>{html.escape(step.target_desc)}</code></span>")
    if step.tap_point:
        meta_items.append(f"<span>落点: {step.tap_point}</span>")
    if step.input_value is not None:
        meta_items.append(f"<span>输入: <code>{html.escape(step.input_value)}</code></span>")

    before_html = _img_tag(step.before_image_b64, step.before_mime, f"Step {step.index} Before")
    after_html = _img_tag(step.after_image_b64, step.after_mime, f"Step {step.index} After")

    layout_html = (
        f'<details class="layout-box"><summary>展开 Pruned Layout 节点摘要</summary>'
        f"<pre>{html.escape(step.pruned_layout)}</pre></details>"
        if step.pruned_layout
        else ""
    )
    err_html = (
        f'<div class="step-err">{html.escape(step.error_summary)}</div>'
        if step.error_summary
        else ""
    )

    return f"""
    <article class="{card_cls}" id="step-{step.index}">
      <div class="step-head">
        <div>
          <span class="badge {badge_cls}">{html.escape(step.status)}</span>
          <strong>#{step.index} {html.escape(step.name)}</strong>
        </div>
        <div class="step-meta">{"".join(meta_items)}</div>
      </div>
      {err_html}
      <div class="shots-grid">
        <div class="shot-col">
          <div class="shot-label">Before（红框 / 中心红点 #FF1744）</div>
          {before_html}
        </div>
        <div class="shot-col">
          <div class="shot-label">After（对比参考环）</div>
          {after_html}
        </div>
      </div>
      {layout_html}
    </article>
    """


def _build_html_document(
    scenario_name: str,
    passed: bool,
    steps: Sequence[StepEvidence],
    metadata: dict[str, Any],
    compression_note: str = "",
) -> str:
    """拼装完整的自包含单文件 HTML 报告文本。"""
    status_text = "PASS" if passed else "FAIL"
    status_cls = "badge-pass" if passed else "badge-fail"
    total_ms = sum(s.duration_ms for s in steps)
    failed_steps = [s for s in steps if s.status != "PASS"]

    meta_rows = "".join(
        f"<tr><th>{html.escape(str(k))}</th><td><code>{html.escape(str(v))}</code></td></tr>"
        for k, v in metadata.items()
    )
    notice_html = (
        f'<div class="compress-note">{html.escape(compression_note)}</div>'
        if compression_note
        else ""
    )
    failure_html = _render_failure_banner(failed_steps)
    steps_html = "".join(_render_step_card(s) for s in steps)

    return f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>OAI E2E 视觉报告 - {html.escape(scenario_name)} [{status_text}]</title>
<style>
  :root {{
    --bg: #0f1117;
    --card: #181b24;
    --border: #2a3040;
    --text: #e6ebf5;
    --muted: #98a2b3;
    --pass: #12b76a;
    --fail: #ff1744;
    --fail-bg: rgba(255, 23, 68, 0.12);
  }}
  * {{ box-sizing: border-box; }}
  body {{
    margin: 0; padding: 24px; background: var(--bg); color: var(--text);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
    line-height: 1.5;
  }}
  .container {{ max-width: 1120px; margin: 0 auto; }}
  .summary-bar {{
    background: var(--card); border: 1px solid var(--border); border-radius: 12px;
    padding: 18px 22px; margin-bottom: 20px;
  }}
  .summary-top {{ display: flex; flex-wrap: wrap; align-items: center; gap: 12px; justify-content: space-between; }}
  .summary-stats {{ color: var(--muted); font-size: 14px; display: flex; gap: 16px; flex-wrap: wrap; }}
  .badge {{
    display: inline-block; padding: 2px 10px; border-radius: 999px;
    font-size: 12px; font-weight: 700; letter-spacing: 0.4px;
  }}
  .badge-pass {{ background: rgba(18, 183, 106, 0.18); color: #32d583; border: 1px solid #12b76a; }}
  .badge-fail {{ background: rgba(255, 23, 68, 0.22); color: #ff6b81; border: 1px solid #ff1744; }}
  .meta-table {{ width: 100%; border-collapse: collapse; margin-top: 12px; font-size: 13px; }}
  .meta-table th {{ text-align: left; color: var(--muted); width: 180px; padding: 4px 8px; border-top: 1px solid var(--border); }}
  .meta-table td {{ padding: 4px 8px; border-top: 1px solid var(--border); word-break: break-all; }}
  .compress-note {{
    margin-bottom: 16px; padding: 10px 14px; border-radius: 8px;
    background: rgba(247, 144, 9, 0.15); border: 1px solid #f79009; font-size: 13px;
  }}
  .failure-zone {{ margin-bottom: 26px; }}
  .failure-zone h2 {{ color: #ff6b81; margin: 0 0 12px 0; }}
  .failure-card {{
    background: var(--fail-bg); border: 2px solid var(--fail); border-radius: 12px; padding: 16px; margin-bottom: 14px;
  }}
  .failure-header {{ display: flex; align-items: center; gap: 10px; }}
  .failure-header h3 {{ margin: 0; color: #ff8a9e; }}
  .failure-error {{
    margin: 10px 0; padding: 10px 12px; background: rgba(0, 0, 0, 0.35);
    border-left: 4px solid var(--fail); font-family: monospace; font-size: 13px; color: #ffd4dc;
  }}
  .failure-body {{ display: grid; grid-template-columns: minmax(220px, 340px) 1fr; gap: 16px; align-items: start; }}
  .failure-shot img, .shot-col img {{ max-width: 100%; border-radius: 8px; border: 1px solid var(--border); display: block; }}
  .step-card {{
    background: var(--card); border: 1px solid var(--border); border-radius: 12px;
    padding: 16px; margin-bottom: 16px;
  }}
  .step-fail {{ border-color: var(--fail); background: rgba(255, 23, 68, 0.06); }}
  .step-head {{ display: flex; flex-wrap: wrap; justify-content: space-between; gap: 8px; margin-bottom: 12px; }}
  .step-meta {{ color: var(--muted); font-size: 13px; display: flex; flex-wrap: wrap; gap: 12px; }}
  .step-err {{ color: #ff8a9e; margin-bottom: 10px; font-family: monospace; font-size: 13px; }}
  .shots-grid {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 14px; }}
  .shot-label {{ font-size: 12px; color: var(--muted); margin-bottom: 6px; }}
  .img-empty {{
    padding: 28px; text-align: center; color: var(--muted);
    border: 1px dashed var(--border); border-radius: 8px; font-size: 13px;
  }}
  details.layout-box, details.tb-box {{ margin-top: 10px; background: rgba(0,0,0,0.28); border-radius: 8px; padding: 8px 12px; }}
  summary {{ cursor: pointer; font-size: 13px; color: #b2c2e0; }}
  pre {{ margin: 8px 0 0 0; font-size: 12px; overflow-x: auto; white-space: pre-wrap; word-break: break-word; }}
  code {{ font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }}
  @media (max-width: 720px) {{ .failure-body {{ grid-template-columns: 1fr; }} }}
</style>
</head>
<body>
<div class="container">
  <header class="summary-bar">
    <div class="summary-top">
      <div>
        <span class="badge {status_cls}">{status_text}</span>
        <strong style="font-size:18px;margin-left:8px;">场景: {html.escape(scenario_name)}</strong>
      </div>
      <div class="summary-stats">
        <span>步骤数: {len(steps)}</span>
        <span>失败数: {len(failed_steps)}</span>
        <span>步骤累计耗时: {total_ms / 1000.0:.2f}s</span>
        <span>生成时间: {datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}</span>
      </div>
    </div>
    <table class="meta-table">{meta_rows}</table>
  </header>
  {notice_html}
  {failure_html}
  <section>
    <h2>执行步骤时间线</h2>
    {steps_html}
  </section>
</div>
</body>
</html>
"""


def _recompress_steps(
    steps: list[StepEvidence], max_width: int, quality: int
) -> list[StepEvidence]:
    """对步骤列表副本的所有嵌入截图按指定宽度与质量降采样重编码。"""
    compressed: list[StepEvidence] = []
    for step in steps:
        clone = copy.copy(step)
        if clone.before_image_b64:
            b64, mime = reencode_b64_image(
                clone.before_image_b64, max_width=max_width, quality=quality
            )
            clone.before_image_b64 = b64 or None
            clone.before_mime = mime
        if clone.after_image_b64:
            b64, mime = reencode_b64_image(
                clone.after_image_b64, max_width=max_width, quality=quality
            )
            clone.after_image_b64 = b64 or None
            clone.after_mime = mime
        compressed.append(clone)
    return compressed


def _trim_middle_screenshots(steps: list[StepEvidence]) -> list[StepEvidence]:
    """极限兜底：当全量降采样后仍超 15MB 时，优先保留失败及首尾步骤图，渐进裁剪中间图。"""
    trimmed = [copy.copy(s) for s in steps]
    total = len(trimmed)
    # 优先清理中间成功步骤的 before 图，再清理 after 图
    for attr in ("before_image_b64", "after_image_b64"):
        for idx, step in enumerate(trimmed):
            if step.status != "PASS" or idx == 0 or idx == total - 1:
                continue
            setattr(step, attr, None)
    return trimmed


def write_html_report(
    report_dir: Path | str,
    scenario_name: str,
    passed: bool,
    steps: Sequence[StepEvidence],
    metadata: dict[str, Any] | None = None,
) -> Path:
    """写入自包含 `report.html`（确保 <= 15MB）与 `report.json`，返回 `report.html` 路径。"""
    out_dir = Path(report_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    safe_meta = _redact_metadata(metadata, [])
    step_list = list(steps)

    json_payload = {
        "scenario": scenario_name,
        "passed": bool(passed),
        "generated_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "step_count": len(step_list),
        "failed_count": sum(1 for s in step_list if s.status != "PASS"),
        "metadata": safe_meta,
        "steps": [s.to_json_dict() for s in step_list],
    }
    json_path = out_dir / "report.json"
    json_path.write_text(
        json.dumps(json_payload, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    working_steps = step_list
    note = ""
    html_text = _build_html_document(scenario_name, passed, working_steps, safe_meta)
    html_bytes = html_text.encode("utf-8")

    # 若超出 15MB 硬上限，自动逐级降采样重编码
    if len(html_bytes) > MAX_REPORT_BYTES:
        for width, quality in _COMPRESSION_PROFILES:
            working_steps = _recompress_steps(step_list, max_width=width, quality=quality)
            note = f"报告触发 15MB 体积预算守卫，已自动二次降采样重编码（max_width={width}px, quality={quality}）"
            html_text = _build_html_document(
                scenario_name, passed, working_steps, safe_meta, compression_note=note
            )
            html_bytes = html_text.encode("utf-8")
            if len(html_bytes) <= MAX_REPORT_BYTES:
                break

    # 极端场景兜底：裁剪中间成功步骤截图确保绝对不突破 15MB
    if len(html_bytes) > MAX_REPORT_BYTES:
        working_steps = _trim_middle_screenshots(working_steps)
        note = "报告触发 15MB 极限守卫，已二次压缩并裁剪部分中间通过步骤截图（完整保留失败与首尾现场）"
        html_text = _build_html_document(
            scenario_name, passed, working_steps, safe_meta, compression_note=note
        )
        html_bytes = html_text.encode("utf-8")

    if len(html_bytes) > MAX_REPORT_BYTES:
        # 最终字节截断兜底（剥离所有截图后写纯文本诊断）
        for s in working_steps:
            s.before_image_b64 = None
            s.after_image_b64 = None
        note = "报告超过 15MB 硬上限，已保留结构化步骤与诊断日志"
        html_bytes = _build_html_document(
            scenario_name, passed, working_steps, safe_meta, compression_note=note
        ).encode("utf-8")

    html_path = out_dir / "report.html"
    html_path.write_bytes(html_bytes)
    return html_path
