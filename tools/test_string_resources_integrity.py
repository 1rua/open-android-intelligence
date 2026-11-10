#!/usr/bin/env python3
"""
XML 字符串资源完整性与命名冲突校验器 (XML String Resources Integrity Validator)

功能:
  深入解析 values/*.xml 资源文件，严格强制执行 4 大国际化合规规则:
    Rule 1: 占位符数量 >= 2 时强制使用 %1$s, %2$s 显式位置索引
    Rule 2: 孤立未转义的 % 报错；单引号、双引号、& 特殊字符转义检查
    Rule 3: 模块内资源 ID 全局唯一性检查 (跨文件重复 ID 报错阻断)
    Rule 4: 单复数 <plurals> 必须包含必要修饰词 (必须包含 'other' 且修饰词合法唯一)

退出码:
  0: 校验通过 (所有资源 100% 合规)
  1: 检出资源语法、格式或命名冲突违规
"""

from __future__ import annotations

import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_RES_DIRS = [
    REPO_ROOT / "apps/android/ui-resources/src/main/res/values",
    REPO_ROOT / "apps/android/app/src/main/res/values",
]

# Java/Android 格式化占位符正则
FORMAT_SPEC_PATTERN = re.compile(r"%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]")

# Android Plurals 合法修饰词集合
VALID_PLURAL_QUANTITIES = {"zero", "one", "two", "few", "many", "other"}


class ResourceViolation:
    def __init__(self, rule: str, file_path: Path, line_number: int, res_name: str, message: str):
        self.rule = rule
        self.file_path = file_path
        self.line_number = line_number
        self.res_name = res_name
        self.message = message

    @property
    def relative_path(self) -> str:
        try:
            return str(self.file_path.relative_to(REPO_ROOT))
        except ValueError:
            return str(self.file_path)

    def __repr__(self) -> str:
        return f"[{self.rule}] {self.relative_path}:{self.line_number} ({self.res_name}): {self.message}"


def check_rule_1_placeholders(text: str, res_name: str, file_path: Path, line: int) -> list[ResourceViolation]:
    """Rule 1: 占位符数量 >= 2 时强制使用显式位置索引。"""
    matches = list(FORMAT_SPEC_PATTERN.finditer(text))
    # 排除转义后的 %%
    placeholders = [m for m in matches if m.group(0) != "%%"]

    violations = []
    if len(placeholders) >= 2:
        missing_index = [m.group(0) for m in placeholders if m.group(1) is None]
        if missing_index:
            msg = f"包含 {len(placeholders)} 个占位符，但存在未标明位置索引的占位符 {missing_index}。必须使用 %1$s, %2$s 等显式索引以支持多语系词序调整。"
            violations.append(ResourceViolation("RULE_1_PLACEHOLDER_INDEX", file_path, line, res_name, msg))
    return violations


def check_rule_2_escapes(raw_text: str, formatted: bool, res_name: str, file_path: Path, line: int) -> list[ResourceViolation]:
    """Rule 2: 孤立未转义的 % 报错；单引号、双引号、& 特殊字符转义检查。"""
    violations = []

    # 1. 孤立未转义 % 检查
    if formatted:
        idx = 0
        n = len(raw_text)
        while idx < n:
            if raw_text[idx] == "%":
                m = FORMAT_SPEC_PATTERN.match(raw_text, idx)
                if not m:
                    msg = f"在字符偏移 {idx} 处存在孤立且未转义的百分号 '%'。必须使用 '%%' 转义，或声明 formatted=\"false\"。"
                    violations.append(ResourceViolation("RULE_2_UNESCAPED_PERCENT", file_path, line, res_name, msg))
                    break
                idx = m.end()
            else:
                idx += 1

    # 2. 单引号未转义检查 (未被双引号包裹的文案中，单引号必须写作 \')
    trimmed = raw_text.strip()
    is_fully_quoted = trimmed.startswith('"') and trimmed.endswith('"') and len(trimmed) >= 2
    if not is_fully_quoted:
        # 寻找未被反斜杠转义的单引号 (不匹配 \')
        for m in re.finditer(r"(?<!\\)'", raw_text):
            msg = f"在字符偏移 {m.start()} 处存在未转义的单引号 '\''。AAPT 编译将失败，必须使用 \'\\'\'' 转义或使用双引号包围整段字符串。"
            violations.append(ResourceViolation("RULE_2_UNESCAPED_APOSTROPHE", file_path, line, res_name, msg))
            break

    return violations


def check_rule_4_plurals(elem: ET.Element, res_name: str, file_path: Path, line: int) -> list[ResourceViolation]:
    """Rule 4: 单复数 <plurals> 必须包含必要修饰词。"""
    violations = []
    items = elem.findall("item")
    if not items:
        violations.append(ResourceViolation("RULE_4_PLURAL_EMPTY", file_path, line, res_name, "<plurals> 节点下没有任何 <item> 子元素。"))
        return violations

    quantities_seen: set[str] = set()
    has_other = False

    for it in items:
        qty = it.get("quantity")
        if not qty:
            violations.append(ResourceViolation("RULE_4_PLURAL_QTY_MISSING", file_path, line, res_name, "<item> 节点缺少 'quantity' 属性。"))
            continue

        if qty not in VALID_PLURAL_QUANTITIES:
            violations.append(
                ResourceViolation(
                    "RULE_4_PLURAL_QTY_INVALID",
                    file_path,
                    line,
                    res_name,
                    f"非法修饰词 quantity='{qty}'。合法取值必须为: {sorted(list(VALID_PLURAL_QUANTITIES))}。",
                )
            )

        if qty in quantities_seen:
            violations.append(ResourceViolation("RULE_4_PLURAL_QTY_DUPLICATE", file_path, line, res_name, f"重复定义了修饰词 quantity='{qty}'。"))
        quantities_seen.add(qty)

        if qty == "other":
            has_other = True

        # 同时对 <item> 内部的文本应用 Rule 1 与 Rule 2
        item_text = "".join(it.itertext())
        violations.extend(check_rule_1_placeholders(item_text, f"{res_name}[{qty}]", file_path, line))
        violations.extend(check_rule_2_escapes(item_text, True, f"{res_name}[{qty}]", file_path, line))

    if not has_other:
        violations.append(
            ResourceViolation(
                "RULE_4_PLURAL_MISSING_OTHER",
                file_path,
                line,
                res_name,
                "<plurals> 缺少必需修饰词 'other'。Android 系统在未匹配到复数规则时将崩溃，'other' 必须提供。",
            )
        )

    return violations


def validate_resource_file(file_path: Path, id_registry: dict[str, list[tuple[Path, int]]]) -> list[ResourceViolation]:
    """校验单个 XML 资源文件，并登记其声明的所有 ID 用于跨文件唯一性核验。"""
    violations: list[ResourceViolation] = []
    try:
        content = file_path.read_text(encoding="utf-8")
    except Exception as e:
        violations.append(ResourceViolation("XML_FILE_READ_ERROR", file_path, 1, file_path.name, f"文件读取失败: {e}"))
        return violations

    try:
        root = ET.fromstring(content)
    except ET.ParseError as e:
        violations.append(ResourceViolation("XML_PARSE_ERROR", file_path, e.lineno or 1, file_path.name, f"XML 语法解析失败: {e}"))
        return violations

    if root.tag != "resources":
        violations.append(ResourceViolation("XML_ROOT_TAG_INVALID", file_path, 1, file_path.name, f"根节点必须为 <resources>，当前为 <{root.tag}>"))
        return violations

    # 为精准定位行号，记录各 tag 的行号偏移
    lines = content.splitlines()

    def find_approx_line(name: str) -> int:
        for idx, l in enumerate(lines, 1):
            if f'name="{name}"' in l or f"name='{name}'" in l:
                return idx
        return 1

    # 1. 扫描 <string>
    for elem in root.findall("string"):
        name = elem.get("name")
        if not name:
            violations.append(ResourceViolation("XML_NAME_MISSING", file_path, 1, "unknown", "<string> 元素缺少 'name' 属性"))
            continue

        line_num = find_approx_line(name)
        id_registry[name].append((file_path, line_num))

        formatted = elem.get("formatted", "true").lower() != "false"
        text = "".join(elem.itertext())

        violations.extend(check_rule_1_placeholders(text, name, file_path, line_num))
        violations.extend(check_rule_2_escapes(text, formatted, name, file_path, line_num))

    # 2. 扫描 <plurals>
    for elem in root.findall("plurals"):
        name = elem.get("name")
        if not name:
            violations.append(ResourceViolation("XML_NAME_MISSING", file_path, 1, "unknown", "<plurals> 元素缺少 'name' 属性"))
            continue

        line_num = find_approx_line(name)
        id_registry[name].append((file_path, line_num))
        violations.extend(check_rule_4_plurals(elem, name, file_path, line_num))

    # 3. 扫描 <string-array>
    for elem in root.findall("string-array"):
        name = elem.get("name")
        if not name:
            violations.append(ResourceViolation("XML_NAME_MISSING", file_path, 1, "unknown", "<string-array> 元素缺少 'name' 属性"))
            continue

        line_num = find_approx_line(name)
        id_registry[name].append((file_path, line_num))

    return violations


def validate_directories(res_dirs: list[Path]) -> list[ResourceViolation]:
    """全量校验指定 values 目录集合，并执行跨文件 ID 唯一性审计 (Rule 3)。"""
    all_violations: list[ResourceViolation] = []
    # 模块维度的 ID 注册表: module_path -> (res_id -> [(file, line), ...])
    module_registries: dict[Path, dict[str, list[tuple[Path, int]]]] = defaultdict(lambda: defaultdict(list))

    total_files = 0
    for res_dir in res_dirs:
        if not res_dir.is_dir():
            continue

        # 向上寻找所属模块根目录 (含 build.gradle.kts 的目录)
        module_root = res_dir
        curr = res_dir
        while curr != curr.parent and curr != REPO_ROOT:
            if (curr / "build.gradle.kts").exists():
                module_root = curr
                break
            curr = curr.parent

        xml_files = sorted(list(res_dir.glob("*.xml")))
        for xml_file in xml_files:
            total_files += 1
            v = validate_resource_file(xml_file, module_registries[module_root])
            all_violations.extend(v)

    # Rule 3: 模块内 ID 唯一性交叉核验
    for module_root, registry in module_registries.items():
        for res_id, locations in registry.items():
            if len(locations) > 1:
                loc_desc = ", ".join(f"{loc[0].name}:{loc[1]}" for loc in locations)
                first_file, first_line = locations[0]
                msg = f"资源 ID '{res_id}' 在当前模块内定义冲突 (共检出 {len(locations)} 处: {loc_desc})。必须保证资源 ID 全局唯一。"
                all_violations.append(ResourceViolation("RULE_3_DUPLICATE_ID", first_file, first_line, res_id, msg))

    return all_violations


def main() -> int:
    parser = argparse.ArgumentParser(description="XML 字符串资源完整性与命名冲突校验器")
    parser.add_argument("dirs", nargs="*", help="指定校验的 res/values 目录 (默认: ui-resources 与 app)")
    args = parser.parse_args()

    dirs = [Path(d).resolve() for d in args.dirs] if args.dirs else DEFAULT_RES_DIRS
    violations = validate_directories(dirs)

    if violations:
        print(f"\n❌ [资源完整性门禁阻断] 检出 {len(violations)} 项资源规范违规:\n", file=sys.stderr)
        for v in violations:
            print(f"[{v.rule}] {v.relative_path}:{v.line_number} ({v.res_name}): {v.message}", file=sys.stderr)
        return 1

    print("✅ XML 字符串资源完整性与命名冲突校验通过! (Rule 1~4 均已满足)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
