#!/usr/bin/env python3
"""
tools/verify_pr_source.py 的单元测试。
覆盖合法的 dev->main、非法的 feature->main、非法的 fork->main、缺少参数等各场景。
"""
import unittest
from tools.verify_pr_source import validate_pr_source


class TestVerifyPrSource(unittest.TestCase):
    def test_valid_dev_to_main(self):
        """合法场景：本仓库 dev -> main"""
        ok, msg = validate_pr_source(
            base_ref="main",
            head_ref="dev",
            base_repo="1rua/open-android-intelligence",
            head_repo="1rua/open-android-intelligence",
            base_repo_id="123456",
            head_repo_id="123456",
            is_fork=False,
        )
        self.assertTrue(ok)
        self.assertIn("校验通过", msg)

    def test_invalid_feature_branch_to_main(self):
        """非法场景：feature 分支向 main 提 PR 应被拒绝"""
        ok, msg = validate_pr_source(
            base_ref="main",
            head_ref="feature/login-fix",
            base_repo="1rua/open-android-intelligence",
            head_repo="1rua/open-android-intelligence",
            is_fork=False,
        )
        self.assertFalse(ok)
        self.assertIn("非法来源分支", msg)
        self.assertIn("feature/login-fix", msg)

    def test_invalid_base_branch(self):
        """非法场景：非 main 的目标分支传入（针对 main 校验器）"""
        ok, msg = validate_pr_source(
            base_ref="dev",
            head_ref="feature/test",
            base_repo="1rua/open-android-intelligence",
            head_repo="1rua/open-android-intelligence",
        )
        self.assertFalse(ok)
        self.assertIn("非法目标分支", msg)

    def test_invalid_fork_repo(self):
        """非法场景：外部 Fork 仓库试图向 main 发起 PR"""
        ok, msg = validate_pr_source(
            base_ref="main",
            head_ref="dev",
            base_repo="1rua/open-android-intelligence",
            head_repo="attacker/open-android-intelligence",
            is_fork=True,
        )
        self.assertFalse(ok)
        self.assertIn("非法来源仓库", msg)

    def test_invalid_fork_flag_even_if_name_matches(self):
        """非法场景：即使仓库名匹配但标为 fork"""
        ok, msg = validate_pr_source(
            base_ref="main",
            head_ref="dev",
            base_repo="1rua/open-android-intelligence",
            head_repo="1rua/open-android-intelligence",
            is_fork=True,
        )
        self.assertFalse(ok)
        self.assertIn("拒绝来自 Fork 仓库的合并请求", msg)

    def test_invalid_repo_id_mismatch(self):
        """非法场景：仓库 ID 不匹配"""
        ok, msg = validate_pr_source(
            base_ref="main",
            head_ref="dev",
            base_repo="1rua/open-android-intelligence",
            head_repo="1rua/open-android-intelligence",
            base_repo_id="111",
            head_repo_id="222",
            is_fork=False,
        )
        self.assertFalse(ok)
        self.assertIn("仓库 ID 不匹配", msg)

    def test_missing_required_arguments(self):
        """边界场景：缺少参数"""
        ok, msg = validate_pr_source(
            base_ref="",
            head_ref="dev",
            base_repo="1rua/open-android-intelligence",
            head_repo="1rua/open-android-intelligence",
        )
        self.assertFalse(ok)
        self.assertIn("缺少必要分支信息", msg)

        ok, msg = validate_pr_source(
            base_ref="main",
            head_ref="dev",
            base_repo="",
            head_repo="1rua/open-android-intelligence",
        )
        self.assertFalse(ok)
        self.assertIn("缺少必要仓库信息", msg)


if __name__ == "__main__":
    unittest.main()
