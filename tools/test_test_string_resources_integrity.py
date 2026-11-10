#!/usr/bin/env python3
"""
tools/test_string_resources_integrity.py 的配套单元测试。
全面覆盖 Rule 1~4 核心规则及异常边界。
"""

import sys
import tempfile
from pathlib import Path
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_string_resources_integrity import (
    check_rule_1_placeholders,
    check_rule_2_escapes,
    validate_directories,
)


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


def test_rule_3_duplicate_id_in_module():
    with tempfile.TemporaryDirectory() as td:
        mod_dir = Path(td)
        (mod_dir / "build.gradle.kts").touch()
        values_dir = mod_dir / "src/main/res/values"
        values_dir.mkdir(parents=True)

        f1 = values_dir / "strings_1.xml"
        f1.write_text('<resources><string name="action_confirm">确认</string></resources>', encoding="utf-8")

        f2 = values_dir / "strings_2.xml"
        f2.write_text('<resources><string name="action_confirm">确定</string></resources>', encoding="utf-8")

        violations = validate_directories([values_dir])
        dup_v = [v for v in violations if v.rule == "RULE_3_DUPLICATE_ID"]
        assert len(dup_v) == 1
        assert "action_confirm" in dup_v[0].res_name


def test_rule_4_plurals_must_have_other():
    with tempfile.TemporaryDirectory() as td:
        mod_dir = Path(td)
        (mod_dir / "build.gradle.kts").touch()
        values_dir = mod_dir / "src/main/res/values"
        values_dir.mkdir(parents=True)

        # 缺少 'other'
        f1 = values_dir / "plurals_bad.xml"
        f1.write_text(
            '<resources><plurals name="messages"><item quantity="one">%1$d message</item></plurals></resources>',
            encoding="utf-8",
        )

        violations = validate_directories([values_dir])
        pl_v = [v for v in violations if v.rule == "RULE_4_PLURAL_MISSING_OTHER"]
        assert len(pl_v) == 1
