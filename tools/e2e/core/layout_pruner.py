"""Android 控件语义树解析、剪枝与定位匹配模块。"""

from __future__ import annotations

import json
import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from typing import Any, Sequence

# 匹配 uiautomator 输出的矩形坐标格式 "[x1,y1][x2,y2]"
_BOUNDS_PATTERN = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")
# 识别可编辑输入控件的类名关键字
_EDITABLE_CLASS_HINTS = ("EditText", "AutoCompleteTextView", "TextInputEditText")


def strip_resource_id_prefix(raw_id: str) -> str:
    """剥离 Android resource-id 中的包名前缀，保留 Compose testTag 原始标识。"""
    text = (raw_id or "").strip()
    if not text:
        return ""
    if ":id/" in text:
        return text.split(":id/", 1)[1]
    return text


def _parse_bounds(raw: Any) -> tuple[int, int, int, int]:
    """解析多种格式边界为 (x1, y1, x2, y2)。"""
    if isinstance(raw, (list, tuple)) and len(raw) == 4:
        return int(raw[0]), int(raw[1]), int(raw[2]), int(raw[3])
    if isinstance(raw, dict):
        return (
            int(raw.get("left", raw.get("x1", 0))),
            int(raw.get("top", raw.get("y1", 0))),
            int(raw.get("right", raw.get("x2", 0))),
            int(raw.get("bottom", raw.get("y2", 0))),
        )
    if isinstance(raw, str):
        match = _BOUNDS_PATTERN.search(raw)
        if match:
            return (
                int(match.group(1)),
                int(match.group(2)),
                int(match.group(3)),
                int(match.group(4)),
            )
    return (0, 0, 0, 0)


def _as_bool(value: Any, default: bool = False) -> bool:
    """将 XML 字符串或 JSON 值转换为布尔值。"""
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        lowered = value.strip().lower()
        if lowered == "true":
            return True
        if lowered == "false":
            return False
    return default


@dataclass(frozen=True)
class SemanticNode:
    """精简后的可访问性语义节点（不可变）。"""

    tag: str
    desc: str
    text: str
    cls: str
    bounds: tuple[int, int, int, int]
    center: tuple[int, int]
    clickable: bool = False
    editable: bool = False
    scrollable: bool = False
    focused: bool = False
    enabled: bool = True
    checked: bool = False
    selected: bool = False
    raw_id: str = ""
    children: tuple[SemanticNode, ...] = ()

    def to_dict(self) -> dict[str, Any]:
        """序列化为可落盘 JSON 的字典结构。"""
        data: dict[str, Any] = {
            "tag": self.tag,
            "desc": self.desc,
            "text": self.text,
            "cls": self.cls,
            "bounds": list(self.bounds),
            "center": list(self.center),
            "clickable": self.clickable,
            "editable": self.editable,
            "scrollable": self.scrollable,
            "focused": self.focused,
            "enabled": self.enabled,
            "checked": self.checked,
            "selected": self.selected,
            "raw_id": self.raw_id,
        }
        if self.children:
            data["children"] = [child.to_dict() for child in self.children]
        else:
            data["children"] = []
        return data

    def flatten(self) -> list[SemanticNode]:
        """按前序遍历返回当前节点及其全部后代节点。"""
        result: list[SemanticNode] = [self]
        for child in self.children:
            result.extend(child.flatten())
        return result

    def has_semantic_signal(self) -> bool:
        """判断节点本身是否携带有效语义或可交互特征。"""
        return bool(
            self.tag
            or self.raw_id
            or self.desc
            or self.text
            or self.clickable
            or self.editable
            or self.scrollable
            or self.focused
            or self.checked
            or self.selected
        )


@dataclass(frozen=True)
class BySemantics:
    """基于可访问性语义属性的控件定位器（禁止使用屏幕坐标）。"""

    tag: str | None = None
    tag_prefix: str | None = None
    desc: str | None = None
    desc_contains: str | None = None
    text: str | None = None
    text_contains: str | None = None
    clickable: bool | None = None
    editable: bool | None = None
    enabled: bool | None = None

    def matches(self, node: SemanticNode) -> bool:
        """校验给定节点是否满足全部已设置的定位条件。"""
        has_constraint = any(
            item is not None
            for item in (
                self.tag,
                self.tag_prefix,
                self.desc,
                self.desc_contains,
                self.text,
                self.text_contains,
                self.clickable,
                self.editable,
                self.enabled,
            )
        )
        if not has_constraint:
            return False
        if self.tag is not None and node.tag != self.tag:
            return False
        if self.tag_prefix is not None and not node.tag.startswith(self.tag_prefix):
            return False
        if self.desc is not None and node.desc != self.desc:
            return False
        if self.desc_contains is not None and self.desc_contains not in node.desc:
            return False
        if self.text is not None and node.text != self.text:
            return False
        if self.text_contains is not None and self.text_contains not in node.text:
            return False
        if self.clickable is not None and node.clickable != self.clickable:
            return False
        if self.editable is not None and node.editable != self.editable:
            return False
        if self.enabled is not None and node.enabled != self.enabled:
            return False
        return True

    def describe(self) -> str:
        """生成简洁可读的定位器描述文本，用于日志与视觉留痕报告。"""
        parts: list[str] = []
        for name in (
            "tag",
            "tag_prefix",
            "desc",
            "desc_contains",
            "text",
            "text_contains",
            "clickable",
            "editable",
            "enabled",
        ):
            value = getattr(self, name)
            if value is not None:
                parts.append(f"{name}={value!r}")
        return f"BySemantics({', '.join(parts)})"


def _prune_raw_node(
    *,
    raw_id: str,
    desc: str,
    text: str,
    cls: str,
    bounds: tuple[int, int, int, int],
    clickable: bool,
    editable: bool,
    scrollable: bool,
    focused: bool,
    enabled: bool,
    checked: bool,
    selected: bool,
    pruned_children: list[SemanticNode],
) -> list[SemanticNode]:
    """统一剪枝逻辑：保留语义节点，将纯容器的子节点上提以压缩树深度。"""
    x1, y1, x2, y2 = bounds
    has_area = (x2 > x1) and (y2 > y1)
    is_editable = editable or any(hint in cls for hint in _EDITABLE_CLASS_HINTS)
    tag = strip_resource_id_prefix(raw_id)
    center = ((x1 + x2) // 2, (y1 + y2) // 2)

    candidate = SemanticNode(
        tag=tag,
        desc=desc.strip(),
        text=text,
        cls=cls.strip(),
        bounds=bounds,
        center=center,
        clickable=clickable,
        editable=is_editable,
        scrollable=scrollable,
        focused=focused,
        enabled=enabled,
        checked=checked,
        selected=selected,
        raw_id=raw_id.strip(),
        children=tuple(pruned_children),
    )

    # 无任何自身语义的无意义容器：直接丢弃本身并上提有效子节点
    if not candidate.has_semantic_signal():
        return pruned_children

    # 零面积且无有效子节点的不可见节点忽略
    if not has_area and not pruned_children:
        return []

    return [candidate]


def _extract_xml_slice(xml_text: str) -> str:
    """剥离 uiautomator /dev/tty 输出尾部杂讯，截取完整 XML 片段。"""
    text = (xml_text or "").strip()
    if not text:
        return ""
    start = text.find("<?xml")
    if start < 0:
        start = text.find("<hierarchy")
    if start < 0:
        return ""
    end = text.rfind("</hierarchy>")
    if end >= 0:
        return text[start : end + len("</hierarchy>")]
    return text[start:]


def _walk_xml_node(elem: ET.Element) -> list[SemanticNode]:
    """递归解析并剪枝 uiautomator XML 节点。"""
    pruned_children: list[SemanticNode] = []
    for child in elem:
        pruned_children.extend(_walk_xml_node(child))

    if elem.tag == "hierarchy":
        return pruned_children

    attrib = elem.attrib
    raw_id = attrib.get("resource-id", "") or ""
    desc = attrib.get("content-desc", "") or ""
    text = attrib.get("text", "") or ""
    cls = attrib.get("class", "") or ""
    bounds = _parse_bounds(attrib.get("bounds", ""))
    clickable = _as_bool(attrib.get("clickable"), False)
    editable = _as_bool(attrib.get("editable"), False)
    scrollable = _as_bool(attrib.get("scrollable"), False)
    focused = _as_bool(attrib.get("focused"), False)
    enabled = _as_bool(attrib.get("enabled"), True)
    checked = _as_bool(attrib.get("checked"), False)
    selected = _as_bool(attrib.get("selected"), False)

    return _prune_raw_node(
        raw_id=raw_id,
        desc=desc,
        text=text,
        cls=cls,
        bounds=bounds,
        clickable=clickable,
        editable=editable,
        scrollable=scrollable,
        focused=focused,
        enabled=enabled,
        checked=checked,
        selected=selected,
        pruned_children=pruned_children,
    )


def prune_layout_xml(xml_text: str) -> list[SemanticNode]:
    """解析 `uiautomator dump` XML 文本并返回精简语义树节点列表。"""
    cleaned = _extract_xml_slice(xml_text)
    if not cleaned:
        return []
    try:
        root = ET.fromstring(cleaned)
    except ET.ParseError:
        return []
    return _walk_xml_node(root)


def _walk_json_node(node: Any) -> list[SemanticNode]:
    """递归解析并剪枝 `android layout` 返回的 JSON 节点。"""
    if isinstance(node, list):
        out: list[SemanticNode] = []
        for item in node:
            out.extend(_walk_json_node(item))
        return out
    if not isinstance(node, dict):
        return []

    raw_children = node.get("children") or node.get("nodes") or []
    pruned_children: list[SemanticNode] = []
    if isinstance(raw_children, list):
        for child in raw_children:
            pruned_children.extend(_walk_json_node(child))

    # 若是仅承载 root/nodes 的包装对象，直接透传子节点
    if (
        "nodes" in node
        and "bounds" not in node
        and "rect" not in node
        and "resourceId" not in node
        and "resource-id" not in node
    ):
        return pruned_children

    raw_id = str(
        node.get("resourceId")
        or node.get("resource-id")
        or node.get("testTag")
        or node.get("id")
        or ""
    )
    desc = str(
        node.get("contentDescription")
        or node.get("content-desc")
        or node.get("desc")
        or ""
    )
    text = str(node.get("text") or "")
    cls = str(node.get("className") or node.get("class") or node.get("cls") or "")
    bounds_raw = node.get("bounds") if "bounds" in node else node.get("rect")
    bounds = _parse_bounds(bounds_raw)

    clickable = _as_bool(node.get("isClickable", node.get("clickable")), False)
    editable = _as_bool(node.get("isEditable", node.get("editable")), False)
    scrollable = _as_bool(node.get("isScrollable", node.get("scrollable")), False)
    focused = _as_bool(node.get("isFocused", node.get("focused")), False)
    enabled = _as_bool(node.get("isEnabled", node.get("enabled")), True)
    checked = _as_bool(node.get("isChecked", node.get("checked")), False)
    selected = _as_bool(node.get("isSelected", node.get("selected")), False)

    return _prune_raw_node(
        raw_id=raw_id,
        desc=desc,
        text=text,
        cls=cls,
        bounds=bounds,
        clickable=clickable,
        editable=editable,
        scrollable=scrollable,
        focused=focused,
        enabled=enabled,
        checked=checked,
        selected=selected,
        pruned_children=pruned_children,
    )


def prune_android_cli_json(data: dict[str, Any] | list[Any] | str) -> list[SemanticNode]:
    """解析 `android layout` CLI 输出的 JSON 数据并剪枝。"""
    parsed: Any = data
    if isinstance(data, str):
        stripped = data.strip()
        if not stripped:
            return []
        try:
            parsed = json.loads(stripped)
        except json.JSONDecodeError:
            return []
    return _walk_json_node(parsed)


def find_nodes(
    roots: Sequence[SemanticNode], locator: BySemantics
) -> list[SemanticNode]:
    """在精简语义节点树中查找全部符合条件的节点（按前序顺序）。"""
    matched: list[SemanticNode] = []
    for root in roots:
        for node in root.flatten():
            if locator.matches(node):
                matched.append(node)
    return matched


def format_pruned_summary(
    roots: Sequence[SemanticNode], max_nodes: int = 60
) -> str:
    """生成紧凑的语义节点摘要，供超时报错与 HTML 折叠面板展示。"""
    flat: list[SemanticNode] = []
    for root in roots:
        flat.extend(root.flatten())

    if not flat:
        return "(无可见语义节点)"

    lines: list[str] = []
    shown = flat[: max(1, max_nodes)]
    for idx, node in enumerate(shown, start=1):
        attrs: list[str] = []
        if node.tag:
            attrs.append(f"tag={node.tag}")
        if node.text:
            snippet = node.text.replace("\n", " ")
            if len(snippet) > 48:
                snippet = snippet[:45] + "..."
            attrs.append(f"text={snippet!r}")
        if node.desc:
            snippet = node.desc.replace("\n", " ")
            if len(snippet) > 48:
                snippet = snippet[:45] + "..."
            attrs.append(f"desc={snippet!r}")
        flags: list[str] = []
        if node.clickable:
            flags.append("clickable")
        if node.editable:
            flags.append("editable")
        if node.scrollable:
            flags.append("scrollable")
        if node.focused:
            flags.append("focused")
        if not node.enabled:
            flags.append("disabled")
        if node.checked:
            flags.append("checked")
        if node.selected:
            flags.append("selected")
        flag_part = f" [{','.join(flags)}]" if flags else ""
        attr_part = " ".join(attrs) if attrs else (node.cls or "node")
        lines.append(
            f"{idx:02d}. {attr_part}{flag_part} center={node.center} bounds={list(node.bounds)}"
        )

    remaining = len(flat) - len(shown)
    if remaining > 0:
        lines.append(f"... 其余 {remaining} 个节点已省略")
    return "\n".join(lines)
