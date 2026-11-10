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
    # 检查文件级 @file:Suppress
    for line in lines[:50]:
        if FILE_SUPPRESS_PATTERN.search(line):
            return "ALL"

    suppressed_lines: set[int] = set()
    for idx, line in enumerate(lines):
        if BLOCK_SUPPRESS_PATTERN.search(line):
            brace_count = 0
            found_first_brace = False
            for j in range(idx, len(lines)):
                code_part = lines[j].split("//")[0]
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


def is_syntax_pattern_exempt(lines: list[str], line_idx: int) -> tuple[bool, str | None]:
    """计算 Level 3 语法模式自动豁免 (向上回溯当前语句的前缀)。"""
    stmt_lines = []
    for i in range(line_idx, -1, -1):
        clean = lines[i].split("//")[0].strip()
        stmt_lines.append(clean)
        if any(clean.startswith(k) for k in ("fun ", "class ", "interface ", "object ", "val ", "var ", "@")):
            break
        if clean.endswith(";") or (i < line_idx and (clean.endswith("}") or clean == "")):
            break
        if len(stmt_lines) >= 15:
            break

    stmt = " ".join(reversed(stmt_lines))
    if SYNTAX_LOG_PATTERN.search(stmt):
        return True, "Log.*"
    if SYNTAX_CHECK_REQUIRE_PATTERN.search(stmt):
        return True, "check/require/assert/error"
    if SYNTAX_THROW_PATTERN.search(stmt):
        return True, "throw"
    if SYNTAX_DEPRECATED_PATTERN.search(stmt):
        return True, "@Deprecated"
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

    # Level 1 行内/前置注释预计算
    line_level1_exempt: dict[int, bool] = {}
    for idx, line in enumerate(clean_lines, 1):
        if INLINE_EXEMPT_PATTERN.search(line):
            line_level1_exempt[idx] = True
            # 若整行均为注释，同时豁免下一行
            if line.strip().startswith("//"):
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
                is_l3, l3_reason = is_syntax_pattern_exempt(clean_lines, str_start_line - 1)

                is_exempt = is_suppressed or is_l1 or is_l3
                reason = "suppress" if is_suppressed else ("inline_comment" if is_l1 else (f"syntax_{l3_reason}" if is_l3 else None))

                clean_display = content.strip().replace("\n", " ")[:60]
                findings.append(HardcodedFinding(file_path, str_start_line, clean_display, is_exempt, reason))

            line += content.count("\n")
            i = end_idx + 3
            continue

        # 普通单行字符串: "..."
        if ch == '"':
            str_start_line = line
            j = i + 1
            content_chars = []
            while j < n:
                if text[j] == "\n":
                    break  # 未闭合字符串保护
                if text[j] == "\\":
                    if j + 1 < n:
                        content_chars.append(text[j : j + 2])
                        j += 2
                        continue
                if text[j] == '"':
                    break
                content_chars.append(text[j])
                j += 1

            content = "".join(content_chars)
            if CHINESE_CHAR_PATTERN.search(content):
                is_suppressed = str_start_line in suppression
                is_l1 = line_level1_exempt.get(str_start_line, False)
                is_l3, l3_reason = is_syntax_pattern_exempt(clean_lines, str_start_line - 1)

                is_exempt = is_suppressed or is_l1 or is_l3
                reason = "suppress" if is_suppressed else ("inline_comment" if is_l1 else (f"syntax_{l3_reason}" if is_l3 else None))

                findings.append(HardcodedFinding(file_path, str_start_line, content, is_exempt, reason))

            i = j + 1
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


def scan_codebase(
    target_paths: list[Path] | None = None,
    baseline_file: Path = DEFAULT_BASELINE_PATH,
    verbose: bool = False,
) -> tuple[list[HardcodedFinding], list[HardcodedFinding]]:
    """扫描指定路径或默认源码树，返回 (未豁免违规列表, 已豁免列表)。"""
    if target_paths:
        kt_files = []
        for p in target_paths:
            if p.is_file() and p.suffix == ".kt":
                kt_files.append(p)
            elif p.is_dir():
                kt_files.extend(
                    [
                        f
                        for f in p.glob("**/*.kt")
                        if not any(x in f.parts for x in ("test", "androidTest", "build", ".gradle"))
                    ]
                )
    else:
        kt_files = [
            f
            for f in DEFAULT_SCAN_DIR.glob("**/*.kt")
            if not any(x in f.parts for x in ("test", "androidTest", "build", ".gradle"))
        ]

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
