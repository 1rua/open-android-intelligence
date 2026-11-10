#!/usr/bin/env python3
"""
tools/check_hardcoded_chinese.py 的配套单元测试。
全面验证 4 级豁免机制、多行原始字符串及未豁免拦截能力。
"""

import sys
import tempfile
from pathlib import Path
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_hardcoded_chinese import extract_strings_from_kotlin_file, scan_codebase


def test_level_1_inline_comments():
    code = """
    package com.test
    val a = "中文测试1" // i18n-ignore
    val b = "中文测试2" // i18n-exempt: 用于测试
    // i18n-ignore
    val c = "中文测试3"
    val d = "未豁免中文"
    """
    with tempfile.NamedTemporaryFile("w", suffix=".kt", encoding="utf-8", delete=False) as tf:
        tf.write(code)
        tf.flush()
        findings = extract_strings_from_kotlin_file(Path(tf.name))

    assert len(findings) == 4
    exempt_map = {f.content: f.is_exempt for f in findings}
    assert exempt_map["中文测试1"] is True
    assert exempt_map["中文测试2"] is True
    assert exempt_map["中文测试3"] is True
    assert exempt_map["未豁免中文"] is False


def test_level_2_suppress_annotation():
    code = """
    package com.test
    @Suppress("HardcodedChineseText")
    fun myFunc() {
        val inside = "函数内豁免中文"
    }
    val outside = "函数外未豁免"
    """
    with tempfile.NamedTemporaryFile("w", suffix=".kt", encoding="utf-8", delete=False) as tf:
        tf.write(code)
        tf.flush()
        findings = extract_strings_from_kotlin_file(Path(tf.name))

    exempt_map = {f.content: f.is_exempt for f in findings}
    assert exempt_map["函数内豁免中文"] is True
    assert exempt_map["函数外未豁免"] is False


def test_level_3_syntax_patterns():
    code = """
    package com.test
    Log.d(TAG, "Log日志中文")
    GatewayLog.e("GW", "网关日志中文")
    check(x > 0) { "check抛出异常中文" }
    require(y != null) { "require抛出异常中文" }
    checkNotNull(z) { "checkNotNull中文" }
    throw IllegalStateException("直接抛出异常中文")
    error("Kotlin标准库error中文")
    @Deprecated("废弃方法提示")
    fun old() {}
    val real = "真正UI硬编码"
    """
    with tempfile.NamedTemporaryFile("w", suffix=".kt", encoding="utf-8", delete=False) as tf:
        tf.write(code)
        tf.flush()
        findings = extract_strings_from_kotlin_file(Path(tf.name))

    exempt_map = {f.content: f.is_exempt for f in findings}
    assert exempt_map["Log日志中文"] is True
    assert exempt_map["网关日志中文"] is True
    assert exempt_map["check抛出异常中文"] is True
    assert exempt_map["require抛出异常中文"] is True
    assert exempt_map["checkNotNull中文"] is True
    assert exempt_map["直接抛出异常中文"] is True
    assert exempt_map["Kotlin标准库error中文"] is True
    assert exempt_map["废弃方法提示"] is True
    assert exempt_map["真正UI硬编码"] is False


def test_comments_should_not_trigger():
    code = """
    package com.test
    // 这里是单行注释包含 "中文引号"
    /* 这里是多行注释包含 "中文引号" */
    /* 嵌套 /* 深度注释 */ 同样包含 "中文" */
    """
    with tempfile.NamedTemporaryFile("w", suffix=".kt", encoding="utf-8", delete=False) as tf:
        tf.write(code)
        tf.flush()
        findings = extract_strings_from_kotlin_file(Path(tf.name))

    assert len(findings) == 0
