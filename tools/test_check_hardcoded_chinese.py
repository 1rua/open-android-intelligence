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

def test_level_3_consecutive_catch_blocks():
    """验证多重连续 catch 块中，前序 catch 内的 throw 严禁穿透作用域边界豁免后序 catch 内的用户通知。"""
    code = """
    package com.test
    fun handle1() {
        try {
            doWork()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            _operationNotice.value = "换行多重catch用户通知"
        }
    }
    fun handle2() {
        try { doWork() } catch (c: CancellationException) { throw c } catch (e: Exception) { _operationNotice.value = "单行多重catch用户通知" }
    }
    """
    with tempfile.NamedTemporaryFile("w", suffix=".kt", encoding="utf-8", delete=False) as tf:
        tf.write(code)
        tf.flush()
        findings = extract_strings_from_kotlin_file(Path(tf.name))

    exempt_map = {f.content: f.is_exempt for f in findings}
    assert exempt_map["换行多重catch用户通知"] is False
    assert exempt_map["单行多重catch用户通知"] is False


def test_level_3_nested_try_catch():
    """验证嵌套 try/catch 块中，内层异常抛出严禁穿透截断影响外层正常业务及外层 catch。"""
    code = """
    package com.test
    fun nested() {
        try {
            try {
                throw IllegalStateException("内层底层异常")
            } catch (e: Exception) {
            }
            _uiState.value = "外层正常展示文案"
        } catch (e: Exception) {
            val toast = "外层兜底提示"
        }
    }
    """
    with tempfile.NamedTemporaryFile("w", suffix=".kt", encoding="utf-8", delete=False) as tf:
        tf.write(code)
        tf.flush()
        findings = extract_strings_from_kotlin_file(Path(tf.name))

    exempt_map = {f.content: (f.is_exempt, f.exempt_reason) for f in findings}
    assert exempt_map["内层底层异常"] == (True, "syntax_throw")
    assert exempt_map["外层正常展示文案"] == (False, None)
    assert exempt_map["外层兜底提示"] == (False, None)


def test_level_3_lambda_and_anonymous_blocks():
    """验证 lambda、协程作用域或匿名对象内的 throw 严禁污染外部语句。"""
    code = """
    package com.test
    fun asyncWorkflow() {
        scope.launch {
            throw CancellationException("协程内部异常")
        }
        val outsideNotice = "协程外部提示"

        executor.submit {
            throw RuntimeException("线程池内部异常")
        }
        val uiNotice = "前台展示提示"
    }
    """
    with tempfile.NamedTemporaryFile("w", suffix=".kt", encoding="utf-8", delete=False) as tf:
        tf.write(code)
        tf.flush()
        findings = extract_strings_from_kotlin_file(Path(tf.name))

    exempt_map = {f.content: (f.is_exempt, f.exempt_reason) for f in findings}
    assert exempt_map["协程内部异常"] == (True, "syntax_throw")
    assert exempt_map["协程外部提示"] == (False, None)
    assert exempt_map["线程池内部异常"] == (True, "syntax_throw")
    assert exempt_map["前台展示提示"] == (False, None)


def test_level_3_multiline_syntax_patterns():
    """验证跨多行的合法语法模式 (Log, throw, check, require, @Deprecated) 仍被正常识别豁免。"""
    code = """
    package com.test
    Log.d(
        TAG,
        "多行日志中文"
    )
    throw IllegalStateException(
        "多行抛出异常中文"
    )
    check(x > 0) {
        "多行check中文"
    }
    require(y != null) {
        "多行require中文"
    }
    @Deprecated(
        "多行废弃提示"
    )
    fun deprecatedFunc() {}
    """
    with tempfile.NamedTemporaryFile("w", suffix=".kt", encoding="utf-8", delete=False) as tf:
        tf.write(code)
        tf.flush()
        findings = extract_strings_from_kotlin_file(Path(tf.name))

    exempt_map = {f.content: (f.is_exempt, f.exempt_reason) for f in findings}
    assert exempt_map["多行日志中文"] == (True, "syntax_Log.*")
    assert exempt_map["多行抛出异常中文"] == (True, "syntax_throw")
    assert exempt_map["多行check中文"] == (True, "syntax_check/require/assert/error")
    assert exempt_map["多行require中文"] == (True, "syntax_check/require/assert/error")
    assert exempt_map["多行废弃提示"] == (True, "syntax_@Deprecated")

