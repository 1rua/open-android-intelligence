#!/usr/bin/env python3
"""
源码级硬编码中文扫描器 (Hardcoded Chinese Text Scanner)

功能:
  秒级扫描 Kotlin/Compose 源码中的硬编码中文，支持 4 级白名单/豁免体系:
    Level 1: 行内注释 // i18n-ignore 或 // i18n-exempt: <reason>
    Level 2: 注解 @file:Suppress("HardcodedChineseText") 或 @Suppress("HardcodedChineseText")
    Level 3: 语法模式自动豁免 (Log.*, GatewayLog.*, check, require, checkNotNull, throw, error)
    Level 4: 中央基线文件 (apps/android/ci/i18n-baseline.json)

退出码:
  0: 扫描通过 (无未豁免硬编码)
  1: 发现未豁免硬编码中文违规
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from collections import Counter
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SCAN_DIR = REPO_ROOT / "apps/android"
DEFAULT_BASELINE_PATH = REPO_ROOT / "apps/android/ci/i18n-baseline.json"

CHINESE_CHAR_PATTERN = re.compile(r"[\u4e00-\u9fa5]")

# Level 1: 行内/行首注释豁免标记
INLINE_EXEMPT_PATTERN = re.compile(r"//.*(?:i18n-ignore|i18n-exempt)")

# Level 2: 注解声明抑制标记
FILE_SUPPRESS_PATTERN = re.compile(r"@file:Suppress\([^)]*\"HardcodedChineseText\"[^)]*\)")
BLOCK_SUPPRESS_PATTERN = re.compile(r"@(?:kotlin\.)?Suppress\s*\(([^)]*\"HardcodedChineseText\"[^)]*)\)")

# Level 3: 语法模式自动豁免正则
SYNTAX_LOG_PATTERN = re.compile(r"\b(?:[A-Za-z0-9_]*Log|Timber)\.[a-zA-Z0-9_]+\s*\(")
SYNTAX_CHECK_REQUIRE_PATTERN = re.compile(r"\b(?:check|require|checkNotNull|requireNotNull|assert|error)\s*(?:\(|\{)")
SYNTAX_THROW_PATTERN = re.compile(r"\bthrow\s+[A-Za-z0-9_.]*(?:\(|\s+|$)")
SYNTAX_DEPRECATED_PATTERN = re.compile(r"@Deprecated\s*\(")

CONTINUATION_OPERATORS = (",", "=", "+", "-", "*", "/", "%", "&&", "||", "?:", "->", ".", "\\", "(")


def kotlin_non_code_spans(text: str) -> list[tuple[str, int, int]]:
    """记录注释和字面量区间，保留字符串模板中的真实 Kotlin 表达式。"""
    spans: list[tuple[str, int, int]] = []
    size = len(text)

    def scan_string(start: int) -> int:
        raw = text.startswith('"""', start)
        delimiter = '"""' if raw else '"'
        index = start + len(delimiter)
        chunk_start = start
        while index < size:
            if text.startswith(delimiter, index):
                end = index + len(delimiter)
                spans.append(("string", chunk_start, end))
                return end
            if not raw and text[index] == "\\":
                index += 2
            elif text.startswith("${", index):
                spans.append(("string", chunk_start, index + 2))
                index = scan_code(index + 2, in_template=True)
                if index > 0 and text[index - 1] == "}":
                    spans.append(("string", index - 1, index))
                chunk_start = index
            elif not raw and text[index] == "\n":
                break
            else:
                index += 1
        spans.append(("string", chunk_start, min(index, size)))
        return min(index, size)

    def scan_code(index: int, in_template: bool = False) -> int:
        brace_depth = 0
        while index < size:
            if text.startswith("//", index):
                end = text.find("\n", index)
                if end == -1:
                    end = size
                spans.append(("line_comment", index, end))
                index = end
            elif text.startswith("/*", index):
                start = index
                depth = 1
                index += 2
                while index < size and depth:
                    if text.startswith("/*", index):
                        depth += 1
                        index += 2
                    elif text.startswith("*/", index):
                        depth -= 1
                        index += 2
                    else:
                        index += 1
                spans.append(("block_comment", start, index))
            elif text[index] == '"':
                index = scan_string(index)
            elif text[index] == "'":
                start = index
                index += 1
                while index < size and text[index] != "\n":
                    if text[index] == "\\":
                        index += 2
                    elif text[index] == "'":
                        index += 1
                        break
                    else:
                        index += 1
                spans.append(("char", start, min(index, size)))
            elif in_template and text[index] == "}":
                index += 1
                if brace_depth == 0:
                    return index
                brace_depth -= 1
            else:
                if in_template and text[index] == "{":
                    brace_depth += 1
                index += 1
        return index

    scan_code(0)
    return spans


def mask_kotlin_non_code(text: str) -> str:
    """将非代码字符替换为空格，保留字符偏移和换行供其他源码门禁复用。"""
    masked = list(text)
    for _, start, end in kotlin_non_code_spans(text):
        for index in range(start, end):
            if masked[index] not in "\r\n":
                masked[index] = " "
    return "".join(masked)


class HardcodedFinding:
    def __init__(
        self,
        file_path: Path,
        line_number: int,
        content: str,
        is_exempt: bool = False,
        exempt_reason: str | None = None,
    ):
        self.file_path = file_path
        self.line_number = line_number
        self.content = content
        self.is_exempt = is_exempt
        self.exempt_reason = exempt_reason

    @property
    def relative_path(self) -> str:
        try:
            return str(self.file_path.relative_to(REPO_ROOT))
        except ValueError:
            return str(self.file_path)

    def __repr__(self) -> str:
        return f"[{self.relative_path}:{self.line_number}:{self.content}]"


def compute_suppression_spans(text: str) -> list[tuple[int, int]] | str:
    """按声明的字符区间计算注解抑制，避免把同行或下一行的其他声明一并豁免。"""
    code = mask_kotlin_non_code(text)
    for match in FILE_SUPPRESS_PATTERN.finditer(text):
        if code[match.start()] == "@":
            return "ALL"

    pairs = kotlin_delimiter_pairs(code)
    lexical_spans = {start: (kind, end) for kind, start, end in kotlin_non_code_spans(text)}
    declarations = re.compile(r"\b(?:val|var|fun|class|interface|object|typealias)\b")
    suppressed: list[tuple[int, int]] = []
    for annotation in BLOCK_SUPPRESS_PATTERN.finditer(text):
        if code[annotation.start()] != "@":
            continue
        declaration = declarations.search(code, annotation.end())
        if declaration is None or any(
            kind in {"string", "char"} and annotation.end() <= start < declaration.start()
            for start, (kind, _) in lexical_spans.items()
        ):
            continue
        index = declaration.start()
        tail = ""
        while index < len(code):
            if index in lexical_spans:
                kind, end = lexical_spans[index]
                if kind in {"string", "char"}:
                    tail += "字面量"
                index = end
                continue
            char = code[index]
            if char in ";}":
                break
            if char in "([{":
                if index not in pairs:
                    break
                end = pairs[index] + 1
                # 块函数和类的声明在主体结束时终止；表达式函数仍继续处理表达式。
                if char == "{" and declaration.group() in {"fun", "class", "interface", "object"} and "=" not in tail:
                    index = end
                    break
                tail += code[end - 1]
                index = end
                continue
            if char == "\n":
                next_code = code[index + 1 :].lstrip()
                if tail.rstrip().endswith(CONTINUATION_OPERATORS) or next_code.startswith(("{", ".", "?.")):
                    index += 1
                    continue
                break
            tail += char
            index += 1
        suppressed.append((annotation.start(), index))
    return suppressed


def kotlin_delimiter_pairs(code: str) -> dict[int, int]:
    """按词法视图匹配括号，记录真实调用和 lambda 的结束位置。"""
    stack: list[tuple[str, int]] = []
    pairs: dict[int, int] = {}
    closing_to_opening = {")": "(", "]": "[", "}": "{"}
    for index, char in enumerate(code):
        if char in "([{":
            stack.append((char, index))
        elif char in closing_to_opening and stack and stack[-1][0] == closing_to_opening[char]:
            _, opening = stack.pop()
            pairs[opening] = index
    return pairs


def syntax_exemption_spans(text: str) -> list[tuple[int, int, str]]:
    """只豁免实际调用参数或消息 lambda 内的文字，闭合后恢复界面文案检查。"""
    code = mask_kotlin_non_code(text)
    pairs = kotlin_delimiter_pairs(code)
    spans: list[tuple[int, int, str]] = []

    def add_span(opening: int, reason: str, content_start: int | None = None):
        if opening in pairs:
            spans.append((opening + 1 if content_start is None else content_start, pairs[opening], reason))

    for match in SYNTAX_LOG_PATTERN.finditer(code):
        add_span(match.end() - 1, "Log.*")

    for match in SYNTAX_CHECK_REQUIRE_PATTERN.finditer(code):
        opening = match.end() - 1
        if opening not in pairs:
            continue
        closing = pairs[opening]
        function_name = match.group(0).split("(", 1)[0].split("{", 1)[0].strip()
        if code[opening] == "{" or function_name == "error":
            add_span(opening, "check/require/assert/error")
        else:
            # 第一实参是业务值，只有之后的消息参数才属于自动豁免范围。
            index = opening + 1
            while index < closing:
                if code[index] == ",":
                    add_span(opening, "check/require/assert/error", index + 1)
                    break
                index = pairs[index] + 1 if index in pairs else index + 1

            trailing = closing + 1
            while trailing < len(code) and code[trailing].isspace():
                trailing += 1
            if trailing < len(code) and code[trailing] == "{":
                add_span(trailing, "check/require/assert/error")

    for pattern, reason in ((SYNTAX_THROW_PATTERN, "throw"), (SYNTAX_DEPRECATED_PATTERN, "@Deprecated")):
        for match in pattern.finditer(code):
            if code[match.end() - 1] == "(":
                add_span(match.end() - 1, reason)
    return spans


def extract_strings_from_kotlin_file(file_path: Path) -> list[HardcodedFinding]:
    """深度解析单个 Kotlin 文件的字符串字面量并标记豁免状态。"""
    try:
        text = file_path.read_text(encoding="utf-8")
    except Exception as e:
        print(f"警告: 无法读取文件 {file_path}: {e}", file=sys.stderr)
        return []

    lines = text.splitlines(keepends=True)
    clean_lines = [l.rstrip("\r\n") for l in lines]

    # Level 2 作用域预计算
    suppression = compute_suppression_spans(text)
    if suppression == "ALL":
        return []

    def is_suppressed_at(position: int) -> bool:
        return any(start <= position < end for start, end in suppression)

    lexical_spans = kotlin_non_code_spans(text)
    char_ends = {start: end for kind, start, end in lexical_spans if kind == "char"}
    syntax_spans = syntax_exemption_spans(text)

    def syntax_exemption_at(position: int) -> tuple[bool, str | None]:
        reason = next((reason for start, end, reason in syntax_spans if start <= position < end), None)
        return reason is not None, reason

    # Level 1 只识别真实单行注释，URL 或示例字符串中的标记不构成豁免。
    line_level1_exempt: dict[int, bool] = {}
    for kind, start, end in lexical_spans:
        if kind == "line_comment" and INLINE_EXEMPT_PATTERN.search(text[start:end]):
            idx = text.count("\n", 0, start) + 1
            line_level1_exempt[idx] = True
            # 若整行均为注释，同时豁免下一行
            if clean_lines[idx - 1].strip().startswith("//"):
                line_level1_exempt[idx + 1] = True

    findings: list[HardcodedFinding] = []
    i = 0
    n = len(text)
    line = 1

    in_block_comment = 0
    in_line_comment = False

    while i < n:
        ch = text[i]

        if ch == "\n":
            line += 1
            in_line_comment = False
            i += 1
            continue

        if in_line_comment:
            i += 1
            continue

        # 单行注释起止
        if in_block_comment == 0 and text[i : i + 2] == "//":
            in_line_comment = True
            i += 2
            continue

        # 多行块注释 (支持 Kotlin 嵌套块注释)
        if text[i : i + 2] == "/*":
            in_block_comment += 1
            i += 2
            continue
        if in_block_comment > 0:
            if text[i : i + 2] == "*/":
                in_block_comment -= 1
                i += 2
            else:
                i += 1
            continue

        if i in char_ends:
            i = char_ends[i]
            continue

        # 多行原始字符串: """..."""
        if text[i : i + 3] == '"""':
            str_start_line = line
            end_idx = text.find('"""', i + 3)
            if end_idx == -1:
                end_idx = n
            content = text[i + 3 : end_idx]

            if CHINESE_CHAR_PATTERN.search(content):
                is_suppressed = is_suppressed_at(i)
                is_l1 = any(line_level1_exempt.get(l, False) for l in range(str_start_line, line + content.count("\n") + 1))
                is_l3, l3_reason = syntax_exemption_at(i)

                is_exempt = is_suppressed or is_l1 or is_l3
                reason = "suppress" if is_suppressed else ("inline_comment" if is_l1 else (f"syntax_{l3_reason}" if is_l3 else None))

                clean_display = content.strip().replace("\n", " ")[:60]
                findings.append(HardcodedFinding(file_path, str_start_line, clean_display, is_exempt, reason))

            line += content.count("\n")
            i = end_idx + 3
            continue

        # 普通单行字符串: "..." (感知模板插值与内部嵌套字符串)
        if ch == '"':
            str_start_line = line
            j = i + 1
            outer_chunks = []
            curr_chunk = []
            expr_depth = 0
            nested_strings = []

            while j < n:
                if text[j] == "\n":
                    break
                if text[j] == "\\":
                    if j + 1 < n:
                        if expr_depth == 0:
                            curr_chunk.append(text[j : j + 2])
                        j += 2
                        continue
                if expr_depth == 0:
                    if text[j] == '"':
                        j += 1
                        break
                    elif text[j : j + 2] == "${":
                        outer_chunks.append("".join(curr_chunk))
                        curr_chunk = []
                        expr_depth = 1
                        j += 2
                        continue
                    else:
                        curr_chunk.append(text[j])
                        j += 1
                        continue
                else:
                    # Inside template expression ${...}
                    if text[j] == "{":
                        expr_depth += 1
                        j += 1
                    elif text[j] == "}":
                        expr_depth -= 1
                        j += 1
                    elif text[j] == '"':
                        nested_start_line = line
                        k = j + 1
                        nested_chars = []
                        while k < n and text[k] != "\n":
                            if text[k] == "\\":
                                if k + 1 < n:
                                    nested_chars.append(text[k : k + 2])
                                    k += 2
                                    continue
                            if text[k] == '"':
                                k += 1
                                break
                            nested_chars.append(text[k])
                            k += 1
                        nested_content = "".join(nested_chars)
                        if CHINESE_CHAR_PATTERN.search(nested_content):
                            nested_strings.append((nested_start_line, nested_content, j))
                        j = k
                    elif text[j] == "'":
                        k = j + 1
                        while k < n and text[k] != "\n":
                            if text[k] == "\\" and k + 1 < n:
                                k += 2
                                continue
                            if text[k] == "'":
                                k += 1
                                break
                            k += 1
                        j = k
                    else:
                        j += 1

            outer_chunks.append("".join(curr_chunk))
            full_outer = text[i + 1 : j - 1]
            outer_text_only = "".join(outer_chunks)

            # 1. 记录嵌套在模板内的中文字符串
            for nest_line, nest_text, nest_position in nested_strings:
                is_suppressed = is_suppressed_at(nest_position)
                is_l1 = line_level1_exempt.get(nest_line, False)
                is_l3, l3_reason = syntax_exemption_at(nest_position)
                is_exempt = is_suppressed or is_l1 or is_l3
                reason = "suppress" if is_suppressed else ("inline_comment" if is_l1 else (f"syntax_{l3_reason}" if is_l3 else None))
                findings.append(HardcodedFinding(file_path, nest_line, nest_text, is_exempt, reason))

            # 2. 若外层字符串字面量自身包含中文，记录外层字符串
            if CHINESE_CHAR_PATTERN.search(outer_text_only):
                is_suppressed = is_suppressed_at(i)
                is_l1 = line_level1_exempt.get(str_start_line, False)
                is_l3, l3_reason = syntax_exemption_at(i)
                is_exempt = is_suppressed or is_l1 or is_l3
                reason = "suppress" if is_suppressed else ("inline_comment" if is_l1 else (f"syntax_{l3_reason}" if is_l3 else None))
                findings.append(HardcodedFinding(file_path, str_start_line, full_outer, is_exempt, reason))

            i = j
            continue

        i += 1

    return findings


def load_baseline(baseline_file: Path) -> Counter[tuple[str, str]]:
    """加载 Level 4 基线并统计各 (file, text) 的已豁免出现次数配额。"""
    if not baseline_file.is_file():
        return Counter()

    try:
        data = json.loads(baseline_file.read_text(encoding="utf-8"))
        entries = data.get("entries", [])
        pool: Counter[tuple[str, str]] = Counter()
        for e in entries:
            f = e.get("file", "").strip()
            # 统一相对路径
            if f.startswith("./"):
                f = f[2:]
            t = e.get("text", "")
            if f and t:
                pool[(f, t)] += 1
        return pool
    except Exception as e:
        print(f"警告: 解析基线文件失败 ({e})，将不加载基线。", file=sys.stderr)
        return Counter()


def generate_baseline_file(findings: list[HardcodedFinding], target_file: Path):
    """基于当前未豁免的硬编码文案一键生成或更新中央基线文件。"""
    target_file.parent.mkdir(parents=True, exist_ok=True)
    entries = []
    for idx, f in enumerate(findings, 1):
        if not f.is_exempt:
            entries.append(
                {
                    "id": f"I18N-{idx:04d}",
                    "file": f.relative_path,
                    "line": f.line_number,
                    "text": f.content,
                    "action": "PENDING_MIGRATION",
                }
            )

    payload = {
        "$schema": "i18n-baseline-v1",
        "version": 1,
        "summary": {
            "total_entries": len(entries),
            "unique_texts": len(set(e["text"] for e in entries)),
        },
        "entries": entries,
    }
    target_file.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"✅ 已成功生成/更新基线文件: {target_file} (收录 {len(entries)} 条存量硬编码)")


def kotlin_source_files(paths: list[Path]) -> list[Path]:
    """枚举生产源码，并在进入目录前排除测试与派生产物。"""
    excluded = {"test", "androidTest", "build", ".gradle", "node_modules", "dist", ".git"}
    files: set[Path] = set()
    for path in paths:
        if path.is_file() and path.suffix == ".kt":
            files.add(path)
        elif path.is_dir():
            for directory, names, filenames in os.walk(path):
                names[:] = sorted(name for name in names if name not in excluded)
                files.update(Path(directory) / name for name in filenames if name.endswith(".kt"))
    return sorted(files)


def scan_codebase(
    target_paths: list[Path] | None = None,
    baseline_file: Path = DEFAULT_BASELINE_PATH,
    verbose: bool = False,
) -> tuple[list[HardcodedFinding], list[HardcodedFinding]]:
    """扫描指定路径或默认源码树，返回 (未豁免违规列表, 已豁免列表)。"""
    kt_files = kotlin_source_files(target_paths or [DEFAULT_SCAN_DIR])

    baseline_pool = load_baseline(baseline_file)
    violations: list[HardcodedFinding] = []
    exemptions: list[HardcodedFinding] = []

    for f_path in sorted(kt_files):
        file_findings = extract_strings_from_kotlin_file(f_path)
        for finding in file_findings:
            if finding.is_exempt:
                exemptions.append(finding)
                continue

            # 检查 Level 4: 基线池匹配
            key = (finding.relative_path, finding.content)
            if baseline_pool[key] > 0:
                baseline_pool[key] -= 1
                finding.is_exempt = True
                finding.exempt_reason = "baseline"
                exemptions.append(finding)
            else:
                violations.append(finding)

    return violations, exemptions


def main() -> int:
    parser = argparse.ArgumentParser(description="源码级硬编码中文扫描器与门禁工具")
    parser.add_argument("paths", nargs="*", help="指定扫描的文件或目录 (默认: apps/android)")
    parser.add_argument("--baseline", default=str(DEFAULT_BASELINE_PATH), help="指定中央基线文件路径")
    parser.add_argument("--generate-baseline", help="根据当前扫描结果直接输出到指定基线 JSON 文件")
    parser.add_argument("--verbose", "-v", action="store_true", help="输出详细豁免信息")
    args = parser.parse_args()

    targets = [Path(p).resolve() for p in args.paths] if args.paths else None
    baseline_path = Path(args.baseline).resolve()

    if args.generate_baseline:
        out_baseline = Path(args.generate_baseline).resolve()
        # 生成基线时忽略已有基线匹配，只应用 Level 1~3
        violations, _ = scan_codebase(targets, baseline_file=Path("/non_existent_baseline.json"))
        generate_baseline_file(violations, out_baseline)
        return 0

    violations, exemptions = scan_codebase(targets, baseline_file=baseline_path, verbose=args.verbose)

    if args.verbose:
        print(f"=== 硬编码中文豁免清单 (共 {len(exemptions)} 处) ===")
        for ex in exemptions[:30]:
            print(f"  [豁免: {ex.exempt_reason}] {ex.relative_path}:{ex.line_number}: {ex.content}")
        if len(exemptions) > 30:
            print(f"  ... 及其余 {len(exemptions) - 30} 处豁免")

    if violations:
        print(f"\n❌ [门禁阻断] 检出 {len(violations)} 处未豁免的硬编码中文违规:\n", file=sys.stderr)
        for v in violations:
            print(f"[{v.relative_path}:{v.line_number}:{v.content}]", file=sys.stderr)
        print(
            f"\n处置建议: 1. 迁移至 strings_*.xml; 2. 行内添加 // i18n-ignore; 3. 添加 @Suppress; 4. 登记基线。",
            file=sys.stderr,
        )
        return 1

    print(f"✅ 源码级硬编码中文扫描通过! (已豁免/基线承接: {len(exemptions)} 处, 新增违规: 0 处)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
