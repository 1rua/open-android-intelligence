#!/usr/bin/env python3
"""
Unit tests for the E2E Multi-Agent Orchestrator (e2e/android-cli/run-e2e-orchestrator.py)
"""

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

ROOT_DIR = Path(__file__).resolve().parents[2]
ORCHESTRATOR_PATH = ROOT_DIR / "e2e" / "android-cli"
if str(ORCHESTRATOR_PATH) not in sys.path:
    sys.path.insert(0, str(ORCHESTRATOR_PATH))

import importlib.util
spec = importlib.util.spec_from_file_location("run_e2e_orchestrator", ORCHESTRATOR_PATH / "run-e2e-orchestrator.py")
orchestrator_module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = orchestrator_module
spec.loader.exec_module(orchestrator_module)

from run_e2e_orchestrator import (
    AgentRole,
    AndroidCliBridge,
    CommandRunner,
    DiagnosticTicket,
    E2EOrchestrator,
    E2EStage,
    MultiAgentWorktreeManager,
    StageResult,
    TestStatus,
    WorktreePriority,
    detect_gateway_port,
    is_port_listening,
    module_to_priority,
)


class TestE2EOrchestrator(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.temp_storage = Path(self.temp_dir.name) / "storage"
        self.orchestrator = E2EOrchestrator(
            batch_id="TEST_BATCH_001",
            dry_run=True,
            storage_root=self.temp_storage,
        )

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_orchestrator_initialization(self):
        self.assertEqual(self.orchestrator.batch_id, "TEST_BATCH_001")
        self.assertTrue(self.orchestrator.dry_run)
        self.assertEqual(self.orchestrator.runner.env.get("HERMES_STORAGE_ROOT"), str(self.temp_storage))
        self.assertTrue(self.orchestrator.screenshots_dir.exists())
        self.assertTrue(self.orchestrator.logs_dir.exists())
        self.assertTrue(self.orchestrator.results_dir.exists())

    def test_stage_1_plugin_install(self):
        res = self.orchestrator.run_stage_1()
        self.assertEqual(res.stage, E2EStage.STAGE_1_PLUGIN_INSTALL)
        self.assertEqual(res.status, TestStatus.PASSED)
        self.assertTrue(res.metrics.get("conformancePassed"))
        self.assertEqual(res.metrics.get("conformanceMatches"), 24)
        self.assertTrue(res.metrics.get("adminServiceReady"))

    def test_stage_2_account_provision(self):
        res = self.orchestrator.run_stage_2(username="unit_test_user")
        self.assertEqual(res.stage, E2EStage.STAGE_2_ACCOUNT_PROVISION)
        self.assertEqual(res.status, TestStatus.PASSED)
        self.assertEqual(res.details.get("account"), "unit_test_user")
        self.assertTrue(res.metrics.get("passwordDigestVerification"))
        self.assertTrue(res.metrics.get("sandboxPermission0700"))
        self.assertTrue(res.metrics.get("accountStoragePasswordVerified"))
        self.assertTrue(res.metrics.get("pairingHandshakeVerifierSeamless"))

    def test_detect_gateway_port_logic(self):
        # 1. 显式指定端口
        p, active = detect_gateway_port(explicit_port=9999)
        self.assertEqual(p, 9999)

        # 2. 模拟某端口监听
        with patch("run_e2e_orchestrator.is_port_listening") as mock_listen:
            # 模拟 11451 处于监听状态
            mock_listen.side_effect = lambda port, host="127.0.0.1", timeout=0.3: port == 11451
            p, active = detect_gateway_port(candidate_ports=[11451, 8045])
            self.assertEqual(p, 11451)
            self.assertTrue(active)

            # 模拟 8045 处于监听状态
            mock_listen.side_effect = lambda port, host="127.0.0.1", timeout=0.3: port == 8045
            p, active = detect_gateway_port(candidate_ports=[11451, 8045])
            self.assertEqual(p, 8045)
            self.assertTrue(active)

            # 模拟均未在监听，回退到默认 8045
            mock_listen.side_effect = lambda port, host="127.0.0.1", timeout=0.3: False
            p, active = detect_gateway_port(candidate_ports=[11451, 8045])
            self.assertEqual(p, 8045)
            self.assertFalse(active)

    def test_stage_3_pairing_handshake_dry_run(self):
        res = self.orchestrator.run_stage_3(
            gateway_url="http://127.0.0.1:8045",
            username="unit_test_user",
        )
        self.assertEqual(res.stage, E2EStage.STAGE_3_PAIRING_HANDSHAKE)
        self.assertEqual(res.status, TestStatus.PASSED)
        self.assertIn("handshakeLatencyMs", res.metrics)
        self.assertTrue(res.metrics.get("workbenchReached"))
        self.assertEqual(res.metrics.get("gatewayPort"), 8045)

    def test_stage_3_pairing_handshake_dynamic_port_11451(self):
        res = self.orchestrator.run_stage_3(
            port=11451,
            username="unit_test_user",
        )
        self.assertEqual(res.stage, E2EStage.STAGE_3_PAIRING_HANDSHAKE)
        self.assertEqual(res.status, TestStatus.PASSED)
        self.assertEqual(res.metrics.get("gatewayPort"), 11451)
        self.assertEqual(res.details.get("port"), 11451)
        self.assertIn("http://127.0.0.1:11451", res.details.get("gatewayUrl"))

    def test_stage_4_bidirectional_msg_dry_run(self):
        res = self.orchestrator.run_stage_4(message_text="Test Message 123")
        self.assertEqual(res.stage, E2EStage.STAGE_4_BIDIRECTIONAL_MSG)
        self.assertEqual(res.status, TestStatus.PASSED)
        self.assertTrue(res.metrics.get("messageDelivered"))
        self.assertIn("outboundAckLatencyMs", res.metrics)
        self.assertIn("sseTimeToFirstTokenMs", res.metrics)

    def test_diagnostic_ticket_and_reporting(self):
        # Run all stages
        self.orchestrator.run_stage_1()
        self.orchestrator.run_stage_2()
        self.orchestrator.run_stage_3()
        self.orchestrator.run_stage_4()

        report_path = self.orchestrator.generate_final_report()
        self.assertTrue(report_path.exists())

        # Verify JSON summary was written
        json_path = self.orchestrator.results_dir / "e2e_summary.json"
        self.assertTrue(json_path.exists())

        with open(json_path, "r", encoding="utf-8") as f:
            data = json.load(f)

        self.assertEqual(data["batchId"], "TEST_BATCH_001")
        self.assertEqual(data["passed"], 4)
        self.assertEqual(data["failed"], 0)
        self.assertEqual(data["passRatePercent"], 100.0)

        # Verify issue resolution matrix was written (per spec section 7.3)
        matrix_path = self.orchestrator.results_dir / "issue_resolution_matrix.json"
        self.assertTrue(matrix_path.exists())
        with open(matrix_path, "r", encoding="utf-8") as f:
            matrix_data = json.load(f)
        self.assertEqual(matrix_data["batchId"], "TEST_BATCH_001")
        self.assertIn("resolved", matrix_data)
        self.assertIn("conflicts", matrix_data)
        self.assertIn("tickets", matrix_data)

    def test_android_cli_bridge_search_and_center(self):
        bridge = AndroidCliBridge(CommandRunner())
        mock_tree = [
            {
                "class": "android.widget.TextView",
                "text": "连接你的 Agent Gateway",
                "bounds": "[100,200][300,400]",
                "center": "[200,300]",
                "children": [
                    {
                        "class": "android.widget.EditText",
                        "content-desc": "网关地址输入",
                        "bounds": "[100,500][400,600]",
                    }
                ]
            }
        ]

        # Search by text
        found_title = bridge.find_nodes_by_text(mock_tree, "连接你的")
        self.assertEqual(len(found_title), 1)
        self.assertEqual(bridge.get_node_center(found_title[0]), (200, 300))

        # Search by class
        found_edits = bridge.find_nodes_by_class(mock_tree, "android.widget.EditText")
        self.assertEqual(len(found_edits), 1)

        # Search by content-desc
        found_input = bridge.find_nodes_by_text(mock_tree, "网关地址")
        self.assertEqual(len(found_input), 1)
        self.assertEqual(bridge.get_node_center(found_input[0]), (250, 550))

    def test_module_to_priority(self):
        self.assertEqual(module_to_priority("gateway-contract/schemas"), WorktreePriority.CONTRACT.value)
        self.assertEqual(module_to_priority("integrations/hermes"), WorktreePriority.GATEWAY.value)
        self.assertEqual(module_to_priority("apps/android/gateway-client"), WorktreePriority.CLIENT.value)
        self.assertEqual(module_to_priority("apps/android/journeys"), WorktreePriority.TESTS.value)

    def test_multi_agent_worktree_repair_and_topological_merge(self):
        tickets = [
            DiagnosticTicket(
                ticket_id="TICKET-T1",
                stage=E2EStage.STAGE_4_BIDIRECTIONAL_MSG,
                severity="P2",
                error_summary="Test layer issue",
                root_cause_module="apps/android/journeys",
                evidence_paths=[],
                suggested_fix="Fix test manifest",
                assigned_agent="Agent-Fix",
            ),
            DiagnosticTicket(
                ticket_id="TICKET-C1",
                stage=E2EStage.STAGE_1_PLUGIN_INSTALL,
                severity="P0",
                error_summary="Contract schema issue",
                root_cause_module="gateway-contract/schemas",
                evidence_paths=[],
                suggested_fix="Fix contract schema",
                assigned_agent="Agent-Fix",
            ),
            DiagnosticTicket(
                ticket_id="TICKET-G1",
                stage=E2EStage.STAGE_2_ACCOUNT_PROVISION,
                severity="P1",
                error_summary="Gateway adapter issue",
                root_cause_module="integrations/hermes",
                evidence_paths=[],
                suggested_fix="Fix adapter storage",
                assigned_agent="Agent-Fix",
            ),
            DiagnosticTicket(
                ticket_id="TICKET-A1",
                stage=E2EStage.STAGE_3_PAIRING_HANDSHAKE,
                severity="P0",
                error_summary="Client UI state issue",
                root_cause_module="apps/android/app",
                evidence_paths=[],
                suggested_fix="Fix login state",
                assigned_agent="Agent-Fix",
            ),
        ]

        # Execute multi-worktree parallel repair pipeline
        results = self.orchestrator.run_multi_agent_worktree_repair_flow(tickets)
        self.assertEqual(len(results), 4)

        # Confirm topological ordering during merge: CONTRACT (1) -> GATEWAY (2) -> CLIENT (3) -> TESTS (4)
        priorities = [t.priority for t in results]
        self.assertEqual(priorities, [1, 2, 3, 4])
        self.assertEqual(results[0].ticket_id, "TICKET-C1")
        self.assertEqual(results[1].ticket_id, "TICKET-G1")
        self.assertEqual(results[2].ticket_id, "TICKET-A1")
        self.assertEqual(results[3].ticket_id, "TICKET-T1")

        for t in results:
            self.assertEqual(t.resolution_status, "VERIFIED")

    def test_stage_2_account_provision_custom_password(self):
        res = self.orchestrator.run_stage_2(username="custom_user", password="CustomSecretPassword123!")
        self.assertEqual(res.stage, E2EStage.STAGE_2_ACCOUNT_PROVISION)
        self.assertEqual(res.status, TestStatus.PASSED)
        self.assertEqual(res.details.get("account"), "custom_user")
        self.assertTrue(res.metrics.get("passwordDigestVerification"))

    def test_worktree_manager_real_git_workflow(self):
        """测试真实 Git Worktree 分配、补丁提交、拓扑合并与冲突安全回滚（涵盖无 mock 的真实 Git 场景）"""
        repo_dir = Path(self.temp_dir.name) / "git_test_repo"
        repo_dir.mkdir(parents=True, exist_ok=True)

        runner = CommandRunner(cwd=repo_dir)
        runner.run("git init -b main")
        runner.run('git config user.email "test@example.com"')
        runner.run('git config user.name "Test Runner"')

        # 创建初始提交
        readme = repo_dir / "README.md"
        readme.write_text("# Initial Repo\n", encoding="utf-8")
        runner.run("git add -A")
        runner.run('git commit -m "初始: 初始化仓库"')

        mgr = MultiAgentWorktreeManager(runner=runner, root_dir=repo_dir, mock_mode=False)

        # 1. 正常分支：创建独立文件，无冲突
        ticket_ok = DiagnosticTicket(
            ticket_id="TICKET-REAL-1",
            stage=E2EStage.STAGE_1_PLUGIN_INSTALL,
            severity="P0",
            error_summary="修复契约",
            root_cause_module="gateway-contract",
            evidence_paths=[],
            suggested_fix="修改 schema",
            assigned_agent="Agent-Fix",
            priority=1,
        )

        wt_dir, branch = mgr.allocate_worktree(ticket_ok)
        self.assertTrue(wt_dir.exists())
        self.assertEqual(branch, "fix/e2e_ticket_real_1")

        ok_committed = mgr.commit_fix(
            wt_dir,
            "修复: 针对 TICKET-REAL-1 提交修复",
            patch_content="schema update\n",
            patch_rel_path="contract.txt",
        )
        self.assertTrue(ok_committed)

        # 2. 冲突分支：修改 README.md 第一行
        ticket_conflict = DiagnosticTicket(
            ticket_id="TICKET-REAL-2",
            stage=E2EStage.STAGE_2_ACCOUNT_PROVISION,
            severity="P1",
            error_summary="冲突修改",
            root_cause_module="integrations/hermes",
            evidence_paths=[],
            suggested_fix="冲突修复",
            assigned_agent="Agent-Fix",
            priority=2,
        )

        wt_conflict_dir, conflict_branch = mgr.allocate_worktree(ticket_conflict)
        conflict_committed = mgr.commit_fix(
            wt_conflict_dir,
            "修复: 分支修改 README 产生冲突",
            patch_content="# Branch Conflicting Content\n",
            patch_rel_path="README.md",
        )
        self.assertTrue(conflict_committed)

        # 在主干也修改 README.md 造成与分支的物理代码冲突
        readme.write_text("# Main Conflicting Content\n", encoding="utf-8")
        runner.run("git add -A")
        runner.run('git commit -m "提交: 主干并发修改产生冲突"')

        # 3. 执行拓扑合并
        merged = mgr.merge_in_topological_order([ticket_ok, ticket_conflict])
        self.assertEqual(len(merged), 2)

        # ticket_ok 应成功合入并状态为 VERIFIED
        self.assertEqual(merged[0].ticket_id, "TICKET-REAL-1")
        self.assertEqual(merged[0].resolution_status, "VERIFIED")

        # ticket_conflict 应被判定为 CONFLICT，且主干安全回滚（未被卡在 MERGE 冲突状态中）
        self.assertEqual(merged[1].ticket_id, "TICKET-REAL-2")
        self.assertEqual(merged[1].resolution_status, "CONFLICT")

        # 验证主干仓库保持干净可用
        rc_st, out_st, _ = runner.run("git status")
        self.assertEqual(rc_st, 0)
        self.assertNotIn("You have unmerged paths", out_st)
        self.assertNotIn("MERGE_HEAD", out_st)

        # 验证发生冲突的 worktree 目录被妥善保留以供问题排查分析
        self.assertTrue(wt_conflict_dir.exists())

    def test_stage_2_account_provision_invalid_user_fails(self):
        """测试阶段 2 输入非法用户名格式时能诚实失败并生成诊断工单，而非被 dry_run 隐瞒"""
        res = self.orchestrator.run_stage_2(username="invalid user with spaces!")
        self.assertEqual(res.stage, E2EStage.STAGE_2_ACCOUNT_PROVISION)
        self.assertEqual(res.status, TestStatus.FAILED)
        self.assertIsNotNone(res.ticket)
        self.assertEqual(res.ticket.priority, WorktreePriority.GATEWAY.value)
        self.assertEqual(res.ticket.stage, E2EStage.STAGE_2_ACCOUNT_PROVISION)

    def test_stage_3_and_4_no_device_connected_failure(self):
        """测试在无物理/模拟设备连接且非 dry_run 状态下，阶段 3 与阶段 4 能够安全拦截并产出标准诊断工单"""
        live_orchestrator = E2EOrchestrator(
            batch_id="TEST_NO_DEVICE_BATCH",
            dry_run=False,
            storage_root=self.temp_storage,
        )
        # 确保模拟无设备在线
        live_orchestrator.android_cli.is_device_connected = MagicMock(return_value=False)

        res_stage3 = live_orchestrator.run_stage_3()
        self.assertEqual(res_stage3.status, TestStatus.FAILED)
        self.assertEqual(res_stage3.metrics.get("error"), "NO_DEVICE_CONNECTED")
        self.assertIsNotNone(res_stage3.ticket)
        self.assertEqual(res_stage3.ticket.stage, E2EStage.STAGE_3_PAIRING_HANDSHAKE)

        res_stage4 = live_orchestrator.run_stage_4(message_text="Custom E2E Message")
        self.assertEqual(res_stage4.status, TestStatus.FAILED)
        self.assertEqual(res_stage4.metrics.get("error"), "NO_DEVICE_CONNECTED")
        self.assertIsNotNone(res_stage4.ticket)
        self.assertEqual(res_stage4.ticket.stage, E2EStage.STAGE_4_BIDIRECTIONAL_MSG)

    def test_android_cli_input_escaping(self):
        """测试 AndroidCliBridge.input_text 对特殊字符与单双引号的安全转义"""
        mock_runner = MagicMock()
        mock_runner.run.return_value = (0, "", "")
        bridge = AndroidCliBridge(mock_runner)

        success = bridge.input_text("Hello World! It's me & you")
        self.assertTrue(success)
        mock_runner.run.assert_called_once()
        cmd = mock_runner.run.call_args[0][0]
        # 验证关键字符转义
        self.assertIn("Hello%sWorld\\!%sIt\\'s%sme%s\\&%syou", cmd)


if __name__ == "__main__":
    unittest.main()
