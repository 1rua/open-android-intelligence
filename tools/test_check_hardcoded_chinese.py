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


# ==============================================================================
# 对抗性回归测试套件 (Flaw 1, Flaw 2, Flaw 3 边界防线与防穿透验证)
# ==============================================================================

def test_level_3_url_slashes_in_strings_isolation():
    """验证字符串字面量中的 // (URL/路径/注释样字符串) 绝不破坏未闭合符号计数，严禁向后续行泄漏语法豁免。"""
    code = """
    package com.test
    fun testUrlIsolation() {
        // 1. Log.d 后紧跟 URL，下一行是面向用户的硬编码文案
        Log.d(TAG, "https://api.example.com//v1/endpoint")
        val userNotice = "URL泄漏用户文案"

        // 2. throw 后紧跟带 // 的 URL，下一行是用户通知
        throw RuntimeException("http://error.com//details")
        val throwNotice = "throw后URL泄漏文案"

        // 3. 多个 URL 与转义引号混合
        Log.i("Network", "Connected to \\\"https://gateway.internal//api\\\" and \\\"http://backup//v2\\\"")
        val prompt = "多URL未豁免提示"

        // 4. 带 URL 的字符串后紧跟真实的单行注释
        Log.w(TAG, "https://test.com//a") // 真实行尾注释
        val realCommentNotice = "真实注释后的用户文案"

        // 5. 合法语法豁免：Log/throw 自身内部包含中文与 URL
        Log.d(TAG, "请求地址: https://example.com//v1/query")
        throw IllegalArgumentException("请求失败: http://api.err//code")

        // 6. 多行 Log 内部拼接 URL 与合法中文，下一行是独立用户文案
        Log.d(
            TAG,
            "https://example.com//v1" +
            "合法跨行日志中文"
        )
        val separatedNotice = "独立用户文案"
    }
    """
    with tempfile.NamedTemporaryFile("w", suffix=".kt", encoding="utf-8", delete=False) as tf:
        tf.write(code)
        tf.flush()
        findings = extract_strings_from_kotlin_file(Path(tf.name))

    exempt_map = {f.content: (f.is_exempt, f.exempt_reason) for f in findings}

    # 漏报防范断言 (必须为未豁免违规)
    assert exempt_map["URL泄漏用户文案"] == (False, None)
    assert exempt_map["throw后URL泄漏文案"] == (False, None)
    assert exempt_map["多URL未豁免提示"] == (False, None)
    assert exempt_map["真实注释后的用户文案"] == (False, None)
    assert exempt_map["独立用户文案"] == (False, None)

    # 合法语法豁免保留断言 (必须正常豁免)
    assert exempt_map["请求地址: https://example.com//v1/query"] == (True, "syntax_Log.*")
    assert exempt_map["请求失败: http://api.err//code"] == (True, "syntax_throw")
    assert exempt_map["合法跨行日志中文"] == (True, "syntax_Log.*")


def test_level_3_inline_semicolon_isolation():
    """验证同行分号 ; 严格隔离独立语句，防止前序 throw/Log 等语法豁免泄漏至同行的后续文案。"""
    code = """
    package com.test
    fun testSemicolonIsolation() {
        // 1. 同行 throw 紧跟分号与用户文案
        throw RuntimeException("内部异常"); _notice = "同一行分号用户通知"

        // 2. 同行 Log.d 紧跟分号与按钮文本
        Log.d(TAG, "调试信息"); val btn = "同一行分号按钮"

        // 3. 同行 check 紧跟分号与校验提示
        check(x > 0); val prompt = "同一行分号校验提示"

        // 4. 字符串字面量内部包含分号，分号不应误截断字符串，外部独立语句仍需隔离
        Log.d(TAG, "参数1=值1;参数2=值2"); val separated = "分号在字符串内隔离文案"

        // 5. 同一行多个分号串联多条语句
        doInit(); throw IllegalStateException("多次分号异常"); cleanUp(); val title = "多次分号未豁免文案"

        // 6. 分号后的语句自身是合法 throw 语法豁免
        val prefix = 1; throw RuntimeException("分号后合法异常文案")
    }
    """
    with tempfile.NamedTemporaryFile("w", suffix=".kt", encoding="utf-8", delete=False) as tf:
        tf.write(code)
        tf.flush()
        findings = extract_strings_from_kotlin_file(Path(tf.name))

    exempt_map = {f.content: (f.is_exempt, f.exempt_reason) for f in findings}

    # 漏报防范断言：分号后非语法调用一律禁止豁免
    assert exempt_map["同一行分号用户通知"] == (False, None)
    assert exempt_map["同一行分号按钮"] == (False, None)
    assert exempt_map["同一行分号校验提示"] == (False, None)
    assert exempt_map["分号在字符串内隔离文案"] == (False, None)
    assert exempt_map["多次分号未豁免文案"] == (False, None)

    # 合法语法豁免保留断言
    assert exempt_map["内部异常"] == (True, "syntax_throw")
    assert exempt_map["调试信息"] == (True, "syntax_Log.*")
    assert exempt_map["参数1=值1;参数2=值2"] == (True, "syntax_Log.*")
    assert exempt_map["多次分号异常"] == (True, "syntax_throw")
    assert exempt_map["分号后合法异常文案"] == (True, "syntax_throw")


def test_level_3_multiline_trailing_lambda_error_messages():
    """验证 check/require/requireNotNull/checkNotNull 的多行尾随 lambda 错误消息正常获得语法豁免。"""
    code = """
    package com.test
    fun testTrailingLambdas() {
        // 1. requireNotNull 换行参数，闭合括号与大括号同行: ) {
        requireNotNull(
            account
        ) {
            "账户信息不能为空"
        }

        // 2. check 多行复杂复合条件
        check(
            user != null &&
            user.isActive
        ) {
            "用户必须处于激活状态"
        }

        // 3. checkNotNull 多行调用
        checkNotNull(
            sessionToken
        ) {
            "会话令牌无效或已过期"
        }

        // 4. require 多行调用
        require(
            amount > 0
        ) {
            "充值金额必须大于零"
        }

        // 5. 大括号独占一行换行: ) \n {
        requireNotNull(
            config
        )
        {
            "大括号独占一行配置错误"
        }

        // 6. 参数内含有嵌套函数调用
        check(
            verifySignature(
                token,
                timestamp
            )
        ) {
            "多层嵌套参数校验失败"
        }
    }
    """
    with tempfile.NamedTemporaryFile("w", suffix=".kt", encoding="utf-8", delete=False) as tf:
        tf.write(code)
        tf.flush()
        findings = extract_strings_from_kotlin_file(Path(tf.name))

    exempt_map = {f.content: (f.is_exempt, f.exempt_reason) for f in findings}

    # 误报消除断言 (所有合法的标准库前置断言懒加载提示均应正确豁免)
    assert exempt_map["账户信息不能为空"] == (True, "syntax_check/require/assert/error")
    assert exempt_map["用户必须处于激活状态"] == (True, "syntax_check/require/assert/error")
    assert exempt_map["会话令牌无效或已过期"] == (True, "syntax_check/require/assert/error")
    assert exempt_map["充值金额必须大于零"] == (True, "syntax_check/require/assert/error")
    assert exempt_map["大括号独占一行配置错误"] == (True, "syntax_check/require/assert/error")
    assert exempt_map["多层嵌套参数校验失败"] == (True, "syntax_check/require/assert/error")


def test_level_3_block_barrier_negative_isolation():
    """验证块屏障防线：普通 lambda、UI 容器、多行 if 条件等非断言尾随 lambda 严禁被误豁免。"""
    code = """
    package com.test
    fun testBlockBarriers() {
        // 1. 集合普通 lambda
        items.forEach {
            val itemLabel = "列表项普通文案"
        }
        items.map {
            val mapped = "映射普通文案"
        }

        // 2. UI 容器或组件尾随 lambda
        Button(
            onClick = { doSomething() }
        ) {
            Text("按钮文案")
        }
        Card(
            modifier = Modifier.padding(8)
        ) {
            Text("卡片文案")
        }

        // 3. Kotlin 作用域函数 let / run
        user?.let {
            val title = "let作用域文案"
        }
        settings.run {
            val desc = "run作用域文案"
        }

        // 4. 多行 if 判定内含 check 调用，但后续是 if 主体代码块，严禁将主体文案误判为 check 消息
        if (checkCondition(
            param
        )) {
            val ifBodyNotice = "if分支内文案"
        }

        // 5. 命名相似但非标准断言函数
        checkoutOrder(
            orderId
        ) {
            val checkoutPrompt = "结账普通文案"
        }
        requirePermissionAsync(
            perm
        ) {
            val callbackPrompt = "异步回调普通文案"
        }

        // 6. 前序为合法的多行 requireNotNull，后序为 catch 块中的兜底用户文案
        try {
            requireNotNull(
                account
            ) {
                "前置合法异常提示"
            }
        } catch (e: Exception) {
            val fallbackNotice = "后置兜底用户提示"
        }
    }
    """
    with tempfile.NamedTemporaryFile("w", suffix=".kt", encoding="utf-8", delete=False) as tf:
        tf.write(code)
        tf.flush()
        findings = extract_strings_from_kotlin_file(Path(tf.name))

    exempt_map = {f.content: (f.is_exempt, f.exempt_reason) for f in findings}

    # 负向严格断言：非断言 lambda 及后续控制块文案严禁豁免
    assert exempt_map["列表项普通文案"] == (False, None)
    assert exempt_map["映射普通文案"] == (False, None)
    assert exempt_map["按钮文案"] == (False, None)
    assert exempt_map["卡片文案"] == (False, None)
    assert exempt_map["let作用域文案"] == (False, None)
    assert exempt_map["run作用域文案"] == (False, None)
    assert exempt_map["if分支内文案"] == (False, None)
    assert exempt_map["结账普通文案"] == (False, None)
    assert exempt_map["异步回调普通文案"] == (False, None)
    assert exempt_map["后置兜底用户提示"] == (False, None)

    # 唯有合法的前置断言消息获得豁免
    assert exempt_map["前置合法异常提示"] == (True, "syntax_check/require/assert/error")

