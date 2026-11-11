#!/usr/bin/env python3
"""
tools/test_string_resources_integrity.py 的配套单元测试。
全面覆盖 Rule 1~4 核心规则及异常边界。
"""

import sys
import subprocess
import tempfile
from pathlib import Path
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import test_string_resources_integrity as validator
from test_string_resources_integrity import (
    check_rule_1_placeholders,
    check_rule_2_escapes,
    validate_directories,
)


@pytest.fixture
def resource_values_dir():
    """保留测试数据供复核，避免退出上下文时永久清除临时目录。"""
    module_dir = Path(tempfile.mkdtemp(prefix="oai-resource-gate-"))
    (module_dir / "build.gradle.kts").write_text('android { namespace = "com.example.ui" }\n', encoding="utf-8")
    values_dir = module_dir / "src/main/res/values"
    values_dir.mkdir(parents=True)
    return values_dir


def test_rule_1_placeholders():
    fp = Path("fake.xml")
    # 单占位符无需索引
    assert len(check_rule_1_placeholders("已处理 %s 条消息", "msg", fp, 1)) == 0

    # 双占位符未标索引 -> 违规
    v1 = check_rule_1_placeholders("用户 %s 在 %s 登录", "login", fp, 1)
    assert len(v1) == 1
    assert "RULE_1_PLACEHOLDER_INDEX" in v1[0].rule

    # 双占位符已标索引 -> 合规
    v2 = check_rule_1_placeholders("用户 %1$s 在 %2$s 登录", "login_ok", fp, 1)
    assert len(v2) == 0


def test_rule_2_escapes():
    fp = Path("fake.xml")
    # 孤立百分号 -> 违规
    v1 = check_rule_2_escapes("当前完成度为 90%", True, "p1", fp, 1)
    assert len(v1) == 1
    assert "RULE_2_UNESCAPED_PERCENT" in v1[0].rule

    # 转义百分号 %% -> 合规
    v2 = check_rule_2_escapes("当前完成度为 90%%", True, "p2", fp, 1)
    assert len(v2) == 0

    # 未转义单引号 -> 违规
    v3 = check_rule_2_escapes("It's a test", True, "q1", fp, 1)
    assert len(v3) == 1
    assert "RULE_2_UNESCAPED_APOSTROPHE" in v3[0].rule

    # 转义单引号 \' 或整段双引号 -> 合规
    assert len(check_rule_2_escapes(r"It\'s a test", True, "q2", fp, 1)) == 0
    assert len(check_rule_2_escapes('"It\'s a test"', True, "q3", fp, 1)) == 0


def test_rule_3_duplicate_id_in_module(resource_values_dir):
    f1 = resource_values_dir / "strings_1.xml"
    f1.write_text('<resources><string name="action_confirm">确认</string></resources>', encoding="utf-8")

    f2 = resource_values_dir / "strings_2.xml"
    f2.write_text('<resources><string name="action_confirm">确定</string></resources>', encoding="utf-8")

    violations = validate_directories([resource_values_dir])
    dup_v = [v for v in violations if v.rule == "RULE_3_DUPLICATE_ID"]
    assert len(dup_v) == 1
    assert "action_confirm" in dup_v[0].res_name


def test_rule_4_plurals_must_have_other(resource_values_dir):
    # 缺少 'other'
    f1 = resource_values_dir / "plurals_bad.xml"
    f1.write_text(
        '<resources><plurals name="messages"><item quantity="one">%1$d message</item></plurals></resources>',
        encoding="utf-8",
    )

    violations = validate_directories([resource_values_dir])
    pl_v = [v for v in violations if v.rule == "RULE_4_PLURAL_MISSING_OTHER"]
    assert len(pl_v) == 1


def test_missing_resource_reference_ignores_non_code_and_checks_templates(resource_values_dir):
    """真实引用及模板表达式必须有定义，注释、字面量和 Android 系统资源不参与。"""
    (resource_values_dir / "strings.xml").write_text(
        '<resources><string name="existing">已有文案</string></resources>', encoding="utf-8"
    )
    source = resource_values_dir.parent / "References.kt"
    source.write_text('''
import com.example.ui.R as UiR
import android.R as AndroidR
// UiR.string.missing_in_comment
/* UiR.string.missing_in_block /* R.string.missing_in_nested */ */
val example = "UiR.string.missing_in_string"
val raw = """UiR.string.missing_in_raw"""
val quote = '"'
val existing = UiR.string.existing
val system = AndroidR.string.ok
val systemQualified = android.R.string.cancel
val missing = UiR.string.missing_direct
val qualified = com.example.ui.R.string.missing_qualified
val template = "状态：${getString(UiR.string.missing_template)}"
''', encoding="utf-8")

    violations = validator.validate_resource_references([source], [resource_values_dir])

    assert [(violation.rule, violation.res_name) for violation in violations] == [
        ("RULE_5_MISSING_RESOURCE", "string.missing_direct"),
        ("RULE_5_MISSING_RESOURCE", "string.missing_qualified"),
        ("RULE_5_MISSING_RESOURCE", "string.missing_template"),
    ]
    assert [violation.line_number for violation in violations] == [12, 13, 14]


@pytest.mark.parametrize("code", [
    'val reference = R.string.missing',
    'import com.example.ui.R as Resources\nval reference = Resources.string.missing',
    'import com.example.ui.R\nval reference = """${getString(R.string.missing)}"""',
])
def test_missing_reference_supports_default_alias_and_raw_template(resource_values_dir, code):
    source = resource_values_dir.parent / "Missing.kt"
    source.write_text(code, encoding="utf-8")

    violations = validator.validate_resource_references([source], [resource_values_dir])

    assert [violation.res_name for violation in violations] == ["string.missing"]


@pytest.mark.parametrize("code", [
    'import android.R\nval reference = R.string.ok',
    'import androidx.example.R\nval reference = R.string.external',
    'import androidx.example.R as LibraryR\nval reference = LibraryR.string.external',
    'import com.example.ui.R\nval example = "${\"R.string.not_a_reference\"}"',
])
def test_external_resources_and_nested_literal_examples_are_ignored(resource_values_dir, code):
    source = resource_values_dir.parent / "Examples.kt"
    source.write_text(code, encoding="utf-8")

    assert validator.validate_resource_references([source], [resource_values_dir]) == []


def test_reference_must_match_the_resource_type(resource_values_dir):
    (resource_values_dir / "strings.xml").write_text('''<resources>
<string-array name="labels"><item>标签</item></string-array>
<plurals name="count"><item quantity="other">%1$d 条</item></plurals>
<item name="alias" type="string">已有别名</item>
</resources>''', encoding="utf-8")
    source = resource_values_dir.parent / "Types.kt"
    source.write_text('''import com.example.ui.R
val labels = R.array.labels
val count = R.plurals.count
val alias = R.string.alias
val wrongType = R.string.labels
''', encoding="utf-8")

    violations = validator.validate_resource_references([source], [resource_values_dir])

    assert [violation.res_name for violation in violations] == ["string.labels"]


def test_same_name_in_another_namespace_does_not_satisfy_a_reference(resource_values_dir):
    other_module = Path(tempfile.mkdtemp(prefix="oai-other-resource-gate-"))
    (other_module / "build.gradle.kts").write_text('android { namespace = "com.example.other" }', encoding="utf-8")
    other_values = other_module / "src/main/res/values"
    other_values.mkdir(parents=True)
    (other_values / "strings.xml").write_text(
        '<resources><string name="only_in_other_module">其他模块文案</string></resources>', encoding="utf-8"
    )
    source = resource_values_dir.parent / "Namespace.kt"
    source.write_text('import com.example.ui.R\nval reference = R.string.only_in_other_module', encoding="utf-8")

    violations = validator.validate_resource_references([source], [resource_values_dir, other_values])

    assert [violation.res_name for violation in violations] == ["string.only_in_other_module"]


@pytest.mark.parametrize("resource_name, expected_exit", [("existing", 0), ("missing", 1)])
def test_resource_gate_cli_propagates_missing_reference_failure(resource_values_dir, resource_name, expected_exit):
    """CI 使用的真实 CLI 必须因缺失引用失败，并在补上定义后恢复通过。"""
    (resource_values_dir / "strings.xml").write_text(
        '<resources><string name="existing">已有文案</string></resources>', encoding="utf-8"
    )
    source = resource_values_dir.parent / "Cli.kt"
    source.write_text(f'import com.example.ui.R\nval reference = R.string.{resource_name}', encoding="utf-8")

    result = subprocess.run(
        [sys.executable, str(Path(validator.__file__)), str(resource_values_dir), "--sources", str(source)],
        capture_output=True, text=True,
    )

    assert result.returncode == expected_exit, result.stderr
    assert ("RULE_5_MISSING_RESOURCE" in result.stderr) == bool(expected_exit)
