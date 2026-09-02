#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("codex_event.py")
SPEC = importlib.util.spec_from_file_location("codex_event", SCRIPT)
assert SPEC and SPEC.loader
codex_event = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = codex_event
SPEC.loader.exec_module(codex_event)


def base_payload() -> dict:
    return {
        "repository": {
            "private": True,
            "full_name": "acme/widgets",
            "owner": {"login": "acme"},
            "default_branch": "main",
        },
        "sender": {"login": "alice", "type": "User"},
    }


class NormalizeEventTests(unittest.TestCase):
    def test_issue_body_mention(self) -> None:
        payload = base_payload()
        payload["issue"] = {
            "number": 7,
            "title": "Cache bug",
            "body": "@codex 修复缓存失效，并补测试",
            "html_url": "https://example.test/issues/7",
        }

        result = codex_event.normalize_event(payload, "issues")

        self.assertEqual(result.kind, "issue")
        self.assertEqual(result.number, 7)
        self.assertEqual(result.instruction, "修复缓存失效，并补测试")

    def test_issue_without_mention_is_rejected(self) -> None:
        payload = base_payload()
        payload["issue"] = {"number": 8, "title": "Question", "body": "请解释"}
        with self.assertRaises(codex_event.EventError):
            codex_event.normalize_event(payload, "issues")

    def test_mention_is_case_sensitive(self) -> None:
        payload = base_payload()
        payload["issue"] = {"number": 8, "title": "Question", "body": "@Codex 请解释"}
        with self.assertRaises(codex_event.EventError):
            codex_event.normalize_event(payload, "issues")

    def test_mention_must_not_be_part_of_a_longer_handle(self) -> None:
        payload = base_payload()
        payload["issue"] = {"number": 8, "title": "Question", "body": "@codex-team 请解释"}
        with self.assertRaises(codex_event.EventError):
            codex_event.normalize_event(payload, "issues")

    def test_issue_title_mention(self) -> None:
        payload = base_payload()
        payload["issue"] = {"number": 18, "title": "@codex explain", "body": "context"}

        result = codex_event.normalize_event(payload, "issues")

        self.assertEqual(result.event_name, "issues")
        self.assertTrue(result.instruction.startswith("explain"))

    def test_issue_comment_question(self) -> None:
        payload = base_payload()
        payload["issue"] = {
            "number": 9,
            "title": "Architecture",
            "body": "context",
        }
        payload["comment"] = {
            "body": "@codex 为什么这里使用队列？",
            "html_url": "https://example.test/issues/9#comment",
        }

        result = codex_event.normalize_event(payload, "issue_comment")

        self.assertEqual(result.kind, "issue")
        self.assertEqual(result.source, "issue comment")
        self.assertEqual(result.instruction, "为什么这里使用队列？")

    def test_pull_request_conversation_comment(self) -> None:
        payload = base_payload()
        payload["issue"] = {
            "number": 10,
            "title": "Improve retries",
            "body": "PR body",
            "pull_request": {"url": "https://api.example.test/pulls/10"},
        }
        payload["comment"] = {"body": "@codex 修复 CI", "html_url": "comment-url"}

        result = codex_event.normalize_event(payload, "issue_comment")

        self.assertEqual(result.kind, "pull_request")
        self.assertEqual(result.source, "pull request comment")

    def test_inline_review_location(self) -> None:
        payload = base_payload()
        payload["pull_request"] = {"number": 11, "title": "Parser", "body": "body"}
        payload["comment"] = {
            "body": "@codex 修复这个边界条件",
            "path": "parser.py",
            "line": 42,
            "diff_hunk": "@@ -40,2 +40,3 @@",
        }

        result = codex_event.normalize_event(payload, "pull_request_review_comment")

        self.assertEqual(result.inline_path, "parser.py")
        self.assertEqual(result.inline_line, "42")
        self.assertIn("边界条件", result.instruction)

    def test_review_body(self) -> None:
        payload = base_payload()
        payload["pull_request"] = {"number": 12, "title": "API", "body": "body"}
        payload["review"] = {"body": "@codex 根据这次 review 修改", "html_url": "review"}

        result = codex_event.normalize_event(payload, "pull_request_review")

        self.assertEqual(result.source, "pull request review")
        self.assertEqual(result.kind, "pull_request")

    def test_public_repository_rejects_unlisted_user(self) -> None:
        payload = base_payload()
        payload["repository"]["private"] = False
        payload["issue"] = {"number": 13, "body": "@codex fix"}
        with self.assertRaises(codex_event.EventError):
            codex_event.normalize_event(payload, "issues")

    def test_public_repository_allows_owner(self) -> None:
        payload = base_payload()
        payload["repository"]["private"] = False
        payload["sender"]["login"] = "Acme"
        payload["issue"] = {"number": 13, "body": "@codex fix"}

        result = codex_event.normalize_event(payload, "issues")

        self.assertEqual(result.actor, "Acme")

    def test_public_repository_allows_actual_repository_owner(self) -> None:
        payload = base_payload()
        payload["repository"].update(
            {
                "private": False,
                "full_name": "FireflyTang/codex-remote-android",
                "owner": {"login": "FireflyTang"},
            }
        )
        payload["sender"]["login"] = "fireflytang"
        payload["issue"] = {"number": 13, "body": "@codex fix"}

        result = codex_event.normalize_event(payload, "issues")

        self.assertEqual(result.actor, "fireflytang")

    def test_private_repository_allows_any_human(self) -> None:
        payload = base_payload()
        payload["sender"]["login"] = "unlisted-human"
        payload["issue"] = {"number": 19, "body": "@codex explain"}

        result = codex_event.normalize_event(payload, "issues")

        self.assertEqual(result.actor, "unlisted-human")

    def test_public_repository_allows_configured_user(self) -> None:
        payload = base_payload()
        payload["repository"]["private"] = False
        payload["issue"] = {"number": 13, "body": "@codex fix"}

        result = codex_event.normalize_event(payload, "issues", {"ALICE"})

        self.assertEqual(result.actor, "alice")

    def test_missing_private_flag_fails_closed(self) -> None:
        payload = base_payload()
        del payload["repository"]["private"]
        payload["issue"] = {"number": 13, "body": "@codex fix"}
        with self.assertRaises(codex_event.EventError):
            codex_event.normalize_event(payload, "issues")

    def test_public_allowlist_file(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "allowlist.txt"
            path.write_text("# teammates\n@Alice\nbob # release manager\n", encoding="utf-8")

            users = codex_event.load_public_allowlist(path)

        self.assertEqual(users, {"alice", "bob"})

    def test_pull_request_target_is_supported(self) -> None:
        payload = base_payload()
        payload["pull_request"] = {
            "number": 16,
            "title": "Fix",
            "body": "@codex review",
        }

        result = codex_event.normalize_event(payload, "pull_request_target")

        self.assertEqual(result.kind, "pull_request")

    def test_pull_request_target_captures_same_repository_refs(self) -> None:
        payload = base_payload()
        payload["pull_request"] = {
            "number": 20,
            "title": "@codex fix",
            "body": "details",
            "head": {"ref": "feature", "repo": {"full_name": "acme/widgets"}},
            "base": {"ref": "main"},
        }

        invocation = codex_event.normalize_event(payload, "pull_request_target")
        context = codex_event.fallback_context(invocation)

        self.assertEqual(codex_event.pr_refs("acme/widgets", context), ("feature", "acme/widgets", "main"))
        self.assertTrue(codex_event.publication_allowed("acme/widgets", invocation, context))

    def test_fork_pull_request_is_unsupported(self) -> None:
        payload = base_payload()
        payload["pull_request"] = {
            "number": 21,
            "title": "@codex fix",
            "body": "details",
            "head": {"ref": "feature", "repo": {"full_name": "outside/widgets"}},
            "base": {"ref": "main"},
        }
        invocation = codex_event.normalize_event(payload, "pull_request_target")
        context = codex_event.fallback_context(invocation)

        self.assertFalse(codex_event.publication_allowed("acme/widgets", invocation, context))
        self.assertTrue(codex_event.fork_pr_is_unsupported("acme/widgets", invocation, context))

    def test_same_repository_pull_request_is_supported(self) -> None:
        payload = base_payload()
        payload["pull_request"] = {
            "number": 23,
            "title": "@codex fix",
            "body": "details",
            "head": {"ref": "feature", "repo": {"full_name": "ACME/WIDGETS"}},
            "base": {"ref": "main"},
        }
        invocation = codex_event.normalize_event(payload, "pull_request_target")

        self.assertFalse(
            codex_event.fork_pr_is_unsupported(
                "acme/widgets", invocation, codex_event.fallback_context(invocation)
            )
        )

    def test_gate_only_reports_fork_as_unsupported(self) -> None:
        payload = base_payload()
        payload["pull_request"] = {
            "number": 24,
            "title": "@codex fix",
            "body": "details",
            "head": {"ref": "feature", "repo": {"full_name": "outside/widgets"}},
            "base": {"ref": "main"},
        }
        with tempfile.TemporaryDirectory() as directory:
            event = Path(directory) / "event.json"
            output = Path(directory) / "output.txt"
            event.write_text(json.dumps(payload), encoding="utf-8")
            completed = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT),
                    "--event", str(event),
                    "--event-name", "pull_request_target",
                    "--repository", "acme/widgets",
                    "--gate-only",
                    "--offline",
                    "--output", str(output),
                ],
                check=False,
                capture_output=True,
                text=True,
            )
            values = dict(line.split("=", 1) for line in output.read_text().splitlines())

        self.assertEqual(completed.returncode, 0)
        self.assertEqual(values["supported"], "false")
        self.assertEqual(values["fork_unsupported"], "true")
        self.assertEqual(values["number"], "24")

    def test_unknown_pr_head_repository_fails_closed_for_publication(self) -> None:
        payload = base_payload()
        payload["issue"] = {
            "number": 22,
            "title": "PR",
            "body": "context",
            "pull_request": {"url": "https://api.example.test/pulls/22"},
        }
        payload["comment"] = {"body": "@codex fix"}
        invocation = codex_event.normalize_event(payload, "issue_comment")
        context = codex_event.fallback_context(invocation)

        self.assertFalse(codex_event.publication_allowed("acme/widgets", invocation, context))

    def test_authorize_only_reports_public_denial_without_repo_checkout(self) -> None:
        payload = base_payload()
        payload["repository"]["private"] = False
        payload["issue"] = {"number": 17, "body": "@codex fix"}
        with tempfile.TemporaryDirectory() as directory:
            event = Path(directory) / "event.json"
            output = Path(directory) / "output.txt"
            event.write_text(json.dumps(payload), encoding="utf-8")

            completed = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT),
                    "--event",
                    str(event),
                    "--event-name",
                    "issues",
                    "--repository",
                    "acme/widgets",
                    "--authorize-only",
                    "--output",
                    str(output),
                ],
                check=False,
                capture_output=True,
                text=True,
            )
            values = dict(
                line.split("=", 1)
                for line in output.read_text(encoding="utf-8").splitlines()
            )

        self.assertEqual(completed.returncode, 0)
        self.assertEqual(values["recognized"], "true")
        self.assertEqual(values["authorized"], "false")
        self.assertEqual(values["kind"], "issue")
        self.assertEqual(values["number"], "17")
        self.assertEqual(values["actor"], "alice")

    def test_bot_is_rejected(self) -> None:
        payload = base_payload()
        payload["sender"] = {"login": "github-actions[bot]", "type": "Bot"}
        payload["issue"] = {"number": 14, "body": "@codex fix"}
        with self.assertRaises(codex_event.EventError):
            codex_event.normalize_event(payload, "issues")

    def test_prompt_marks_only_current_instruction_as_active(self) -> None:
        payload = base_payload()
        payload["issue"] = {"number": 15, "title": "Docs", "body": "old context"}
        payload["comment"] = {"body": "@codex 只解释，不改代码"}
        invocation = codex_event.normalize_event(payload, "issue_comment")
        context = codex_event.fallback_context(invocation)

        prompt = codex_event.build_prompt("acme/widgets", invocation, context, "abc123")

        self.assertIn("只解释，不改代码", prompt)
        self.assertIn("只把上面的“这一次的明确指令”当作当前任务", prompt)
        self.assertIn("不要修改文件", prompt)
        self.assertIn("本段只约束这一次 GitHub runner 执行", prompt)
        self.assertIn("不要拒绝", prompt)
        self.assertIn("把工作区交给 workflow 发布", prompt)


if __name__ == "__main__":
    unittest.main()
