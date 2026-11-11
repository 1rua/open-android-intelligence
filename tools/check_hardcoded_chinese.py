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

CHECK_REQUIRE_FUNC_NAMES = {
    "check",
    "require",
    "checkNotNull",
    "requireNotNull",
    "assert",
    "error",
}

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


def strip_string_literals_and_comments(s: str) -> str:
    """去除行内的双引号字符串字面量、三引号多行字符串、单引号字符字面量与注释，保留纯语法骨架。"""
    out = []
    i = 0
    n = len(s)
    block_comment = 0

    while i < n:
        if block_comment > 0:
            if s[i : i + 2] == "*/":
                block_comment -= 1
                i += 2
            elif s[i : i + 2] == "/*":
                block_comment += 1
                i += 2
            else:
                i += 1
            continue

        # 块注释起止 (支持嵌套)
        if s[i : i + 2] == "/*":
            block_comment += 1
            i += 2
            continue

        # 单行注释起止 (仅在字符串及字符字面量外部有效)
        if s[i : i + 2] == "//":
            break

        # 三引号原始字符串: """..."""
        if s[i : i + 3] == '"""':
            end_idx = s.find('"""', i + 3)
            out.append('""')
            if end_idx == -1:
                break
            i = end_idx + 3
            continue

        # 普通单行字符串: "..." (感知模板插值与内部嵌套字符串)
        if s[i] == '"':
            j = i + 1
            expr_depth = 0
            while j < n:
                if s[j] == "\n":
                    break
                if s[j] == "\\":
                    j += 2
                    continue
                if expr_depth == 0:
                    if s[j] == '"':
                        j += 1
                        break
                    elif s[j : j + 2] == "${":
                        expr_depth = 1
                        j += 2
                        continue
                    else:
                        j += 1
                        continue
                else:
                    # 处于 ${ ... } 模板表达式内部
                    if s[j] == "{":
                        expr_depth += 1
                        j += 1
                    elif s[j] == "}":
                        expr_depth -= 1
                        j += 1
                    elif s[j] == '"':
                        k = j + 1
                        while k < n and s[k] != "\n":
                            if s[k] == "\\":
                                k += 2
                            elif s[k] == '"':
                                k += 1
                                break
                            else:
                                k += 1
                        j = k
                    elif s[j] == "'":
                        k = j + 1
                        while k < n and s[k] != "\n":
                            if s[k] == "\\":
                                k += 2
                            elif s[k] == "'":
                                k += 1
                                break
                            else:
                                k += 1
                        j = k
                    else:
                        j += 1
            out.append('""')
            i = j
            continue

        # 字符字面量 (如 '\'' 或 '\"')
        if s[i] == "'":
            j = i + 1
            escape = False
            while j < n:
                if s[j] == "\n":
                    break
                if escape:
                    escape = False
                    j += 1
                elif s[j] == "\\":
                    escape = True
                    j += 1
                elif s[j] == "'":
                    j += 1
                    break
                else:
                    j += 1
            out.append("''")
            i = j
            continue

        out.append(s[i])
        i += 1

    return "".join(out)


def strip_outer_closed_blocks(prefix: str) -> str:
    """若前缀在顶层（圆括号深度为 0）包含闭合花括号 '}'，截断丢弃最后一个闭合代码块之前的内容。"""
    paren_depth = 0
    last_block_close = -1
    for i, ch in enumerate(prefix):
        if ch == "(":
            paren_depth += 1
        elif ch == ")":
            if paren_depth > 0:
                paren_depth -= 1
        elif ch == "}" and paren_depth == 0:
            last_block_close = i
    if last_block_close != -1:
        return prefix[last_block_close + 1 :]
    return prefix


def is_brace_check_require_lambda(lines: list[str], brace_line_idx: int, prefix_before_brace: str) -> bool:
    """判断给定 '{' 是否属于 check/require/checkNotNull/requireNotNull/assert/error 的尾随 lambda 或参数 lambda。
    向上回溯匹配闭合的圆括号链与函数调用标识符，严格阻断 catch/try/if/while/for 等控制块。
    """
    chars_rev = list(reversed(prefix_before_brace))
    line_idx = brace_line_idx

    def get_next_char() -> str | None:
        nonlocal line_idx, chars_rev
        while True:
            if chars_rev:
                return chars_rev.pop(0)
            line_idx -= 1
            if line_idx >= 0 and line_idx >= max(-1, brace_line_idx - 15):
                clean_line = strip_string_literals_and_comments(lines[line_idx])
                chars_rev = list(reversed(clean_line))
            else:
                return None

    ch = get_next_char()
    while ch is not None and ch.isspace():
        ch = get_next_char()

    if ch is None:
        return False

    if ch == ")":
        paren_depth = 1
        brace_depth = 0
        while True:
            ch = get_next_char()
            if ch is None:
                return False
            if ch == "}":
                brace_depth += 1
            elif ch == "{":
                if brace_depth > 0:
                    brace_depth -= 1
            elif brace_depth == 0:
                if ch == ")":
                    paren_depth += 1
                elif ch == "(":
                    paren_depth -= 1
                    if paren_depth == 0:
                        break
                elif ch in (";", "}"):
                    return False

        ch = get_next_char()
        while ch is not None and ch.isspace():
            ch = get_next_char()

        ident_chars = []
        while ch is not None and (ch.isalnum() or ch in ("_", ".")):
            ident_chars.append(ch)
            ch = get_next_char()

        func_name = "".join(reversed(ident_chars)).split(".")[-1]
        if func_name not in CHECK_REQUIRE_FUNC_NAMES:
            return False

        # 检查标识符前是否有控制流关键字 (如 if (checkNotNull(x)) {)
        while ch is not None and ch.isspace():
            ch = get_next_char()
        prefix_chars = []
        while ch is not None and ch.isalnum():
            prefix_chars.append(ch)
            ch = get_next_char()
        prefix_keyword = "".join(reversed(prefix_chars))
        if prefix_keyword in ("if", "while", "for", "when", "catch"):
            return False

        return True

    elif ch.isalnum() or ch == "_":
        ident_chars = [ch]
        ch = get_next_char()
        while ch is not None and (ch.isalnum() or ch in ("_", ".")):
            ident_chars.append(ch)
            ch = get_next_char()

        func_name = "".join(reversed(ident_chars)).split(".")[-1]
        return func_name in CHECK_REQUIRE_FUNC_NAMES

    elif ch == ",":
        paren_depth = 0
        while True:
            ch = get_next_char()
            if ch is None:
                return False
            if ch == ")":
                paren_depth += 1
            elif ch == "(":
                if paren_depth > 0:
                    paren_depth -= 1
                else:
                    break
            elif ch in (";", "}") and paren_depth == 0:
                return False

        ch = get_next_char()
        while ch is not None and ch.isspace():
            ch = get_next_char()

        ident_chars = []
        while ch is not None and (ch.isalnum() or ch in ("_", ".")):
            ident_chars.append(ch)
            ch = get_next_char()

        func_name = "".join(reversed(ident_chars)).split(".")[-1]
        return func_name in CHECK_REQUIRE_FUNC_NAMES

    return False


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


def compute_suppression_lines(lines: list[str]) -> set[int] | str:
    """计算 Level 2 @Suppress 所覆盖的代码行号集合。若全文件抑制则返回 'ALL'。"""
    code_lines = mask_kotlin_non_code("\n".join(lines)).split("\n")

    def has_annotation(pattern: re.Pattern, index: int) -> bool:
        return any(code_lines[index][match.start()] == "@" for match in pattern.finditer(lines[index]))

    # 检查文件级 @file:Suppress
    for index in range(min(50, len(lines))):
        if has_annotation(FILE_SUPPRESS_PATTERN, index):
            return "ALL"

    suppressed_lines: set[int] = set()
    for idx in range(len(lines)):
        if has_annotation(BLOCK_SUPPRESS_PATTERN, idx):
            brace_count = 0
            found_first_brace = False
            for j in range(idx, len(lines)):
                code_part = strip_string_literals_and_comments(lines[j])
                suppressed_lines.add(j + 1)
                for ch in code_part:
                    if ch == "{":
                        brace_count += 1
                        found_first_brace = True
                    elif ch == "}":
                        brace_count -= 1
                if found_first_brace and brace_count == 0:
                    break
                # 单行或无花括号声明语句 (如 val x = "...")
                if not found_first_brace and j > idx:
                    stripped = code_part.strip()
                    if not stripped.endswith(("=", "+", "-", "&&", "||", ",", "\\")):
                        break
    return suppressed_lines


def count_unclosed_delimiters(syntax_str: str) -> tuple[int, int]:
    """计算未闭合的圆括号 ( 与方括号 [ 数量。"""
    parens = 0
    brackets = 0
    for ch in syntax_str:
        if ch == "(":
            parens += 1
        elif ch == ")":
            parens = max(0, parens - 1)
        elif ch == "[":
            brackets += 1
        elif ch == "]":
            brackets = max(0, brackets - 1)
    return parens, brackets


def check_syntax_patterns(stmt: str) -> tuple[bool, str | None]:
    """检查语句是否匹配 4 类语法豁免正则。"""
    if SYNTAX_LOG_PATTERN.search(stmt):
        return True, "Log.*"
    for match in SYNTAX_CHECK_REQUIRE_PATTERN.finditer(stmt):
        # 断言的首个参数是普通业务值；只有消息参数、尾随 lambda 和 error 消息可豁免。
        opening = stmt[match.end() - 1]
        closing = ")" if opening == "(" else "}"
        depth = 0
        brackets = 0
        braces = 0
        argument_index = 0
        for char in stmt[match.end() - 1 :]:
            if char == opening:
                depth += 1
            elif char == closing:
                depth -= 1
                if depth == 0:
                    break
            elif char == "[":
                brackets += 1
            elif char == "]":
                brackets -= 1
            elif char == "{":
                braces += 1
            elif char == "}":
                braces -= 1
            elif char == "," and depth == 1 and brackets == 0 and braces == 0:
                argument_index += 1
        function_name = match.group(0).split("(", 1)[0].split("{", 1)[0].strip()
        if depth > 0 and (opening == "{" or function_name == "error" or argument_index > 0):
            return True, "check/require/assert/error"
    if SYNTAX_THROW_PATTERN.search(stmt):
        return True, "throw"
    if SYNTAX_DEPRECATED_PATTERN.search(stmt):
        return True, "@Deprecated"
    return False, None


def is_syntax_pattern_exempt(lines: list[str], line_idx: int, prefix_on_line: str | None = None) -> tuple[bool, str | None]:
    """计算 Level 3 语法模式自动豁免 (严格阻断跨代码块与跨控制流语句的向上回溯)。"""
    raw_prefix = prefix_on_line if prefix_on_line is not None else lines[line_idx]
    clean_prefix = strip_string_literals_and_comments(raw_prefix).rstrip()

    # 1. 顶层块闭合截断：丢弃顶层 '}' 之前的一切内容（保留参数内 lambda）
    clean_prefix = strip_outer_closed_blocks(clean_prefix)

    # 2. 块开启截断：当前行前缀若包含 '{'，需判断是否属于 check/require 等合法尾随 lambda
    can_backtrack = True
    if "{" in clean_prefix:
        before_brace, after_brace = clean_prefix.rsplit("{", 1)
        if is_brace_check_require_lambda(lines, line_idx, before_brace):
            return True, "check/require/assert/error"
        # 否则 '{' 属于控制块 (catch/try/if/lambda/函数体)，截断且严禁向上跨越该作用域边界
        curr_prefix = after_brace
        can_backtrack = False
    else:
        curr_prefix = clean_prefix

    # 3. 同行分号隔离：若当前语句前存在分号 ';'，截断丢弃分号前的前序语句，并禁止向上回溯跨越语句边界
    if ";" in curr_prefix:
        curr_prefix = curr_prefix.rsplit(";", 1)[1]
        can_backtrack = False

    # 4. 控制流关键字边界：catch, finally, try 等关键字禁止向上穿透
    if re.search(r"\b(?:catch|finally|try)\b", curr_prefix):
        can_backtrack = False

    # 5. 当前行局部检查
    is_ex, reason = check_syntax_patterns(curr_prefix.strip())
    if is_ex:
        return True, reason

    if not can_backtrack:
        return False, None

    # 6. 向上回溯当前语句的前缀 (多行语句场景)
    stmt_parts = [curr_prefix.strip()]
    curr_unclosed_parens, curr_unclosed_brackets = count_unclosed_delimiters(curr_prefix)

    for i in range(line_idx - 1, max(-1, line_idx - 15), -1):
        clean = strip_string_literals_and_comments(lines[i]).strip()
        if not clean:
            continue

        # 前序行若包含闭合大括号 '}'（顶层代码块），严禁向上跨越代码块屏障
        if strip_outer_closed_blocks(clean) != clean:
            break

        # 前序行分号截断
        has_semicolon = ";" in clean
        if has_semicolon:
            clean = clean.rsplit(";", 1)[1].strip()
            if not clean:
                break

        # 遇到开启块 '{'
        if "{" in clean:
            b_before, b_after = clean.rsplit("{", 1)
            if is_brace_check_require_lambda(lines, i, b_before):
                return True, "check/require/assert/error"
            break

        # 遇到声明或控制流关键字，且非跨行表达式延续，终止向上回溯
        if re.search(r"^\s*(?:val|var|fun|class|interface|object|return|if|for|while|when|catch|finally|try)\b", clean):
            p_parens, p_brackets = count_unclosed_delimiters(clean)
            if p_parens <= 0 and p_brackets <= 0 and not clean.endswith(CONTINUATION_OPERATORS):
                break

        p_parens, p_brackets = count_unclosed_delimiters(clean)
        prev_is_continuation = (
            p_parens > 0
            or p_brackets > 0
            or clean.endswith(CONTINUATION_OPERATORS)
            or curr_prefix.strip().startswith((".", "->"))
            or curr_unclosed_parens < 0
        )

        if not prev_is_continuation:
            break

        stmt_parts.append(clean)
        combined = " ".join(reversed(stmt_parts))
        is_ex, reason = check_syntax_patterns(combined)
        if is_ex:
            return True, reason

        if has_semicolon:
            break

    return False, None

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
    suppression = compute_suppression_lines(clean_lines)
    if suppression == "ALL":
        return []

    lexical_spans = kotlin_non_code_spans(text)
    char_ends = {start: end for kind, start, end in lexical_spans if kind == "char"}

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
                is_suppressed = str_start_line in suppression
                is_l1 = any(line_level1_exempt.get(l, False) for l in range(str_start_line, line + content.count("\n") + 1))
                last_nl = text.rfind("\n", 0, i)
                prefix_on_line = text[last_nl + 1 : i] if last_nl != -1 else text[:i]
                is_l3, l3_reason = is_syntax_pattern_exempt(clean_lines, str_start_line - 1, prefix_on_line)

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
                            nested_strings.append((nested_start_line, nested_content))
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
            for nest_line, nest_text in nested_strings:
                is_suppressed = nest_line in suppression
                is_l1 = line_level1_exempt.get(nest_line, False)
                last_nl = text.rfind("\n", 0, i)
                prefix_on_line = text[last_nl + 1 : i] if last_nl != -1 else text[:i]
                is_l3, l3_reason = is_syntax_pattern_exempt(clean_lines, nest_line - 1, prefix_on_line)
                is_exempt = is_suppressed or is_l1 or is_l3
                reason = "suppress" if is_suppressed else ("inline_comment" if is_l1 else (f"syntax_{l3_reason}" if is_l3 else None))
                findings.append(HardcodedFinding(file_path, nest_line, nest_text, is_exempt, reason))

            # 2. 若外层字符串字面量自身包含中文，记录外层字符串
            if CHINESE_CHAR_PATTERN.search(outer_text_only):
                is_suppressed = str_start_line in suppression
                is_l1 = line_level1_exempt.get(str_start_line, False)
                last_nl = text.rfind("\n", 0, i)
                prefix_on_line = text[last_nl + 1 : i] if last_nl != -1 else text[:i]
                is_l3, l3_reason = is_syntax_pattern_exempt(clean_lines, str_start_line - 1, prefix_on_line)
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
