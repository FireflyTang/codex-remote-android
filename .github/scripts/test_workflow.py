#!/usr/bin/env python3
from __future__ import annotations

import unittest
from pathlib import Path

import yaml


WORKFLOW = Path(__file__).parents[1] / "workflows" / "codex-mention.yml"


class WorkflowTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.text = WORKFLOW.read_text(encoding="utf-8")
        cls.document = yaml.load(cls.text, Loader=yaml.BaseLoader)

    def test_yaml_and_supported_new_content_events(self) -> None:
        self.assertIsInstance(self.document, dict)
        triggers = self.document["on"]
        self.assertEqual(triggers["issues"]["types"], ["opened"])
        self.assertEqual(triggers["issue_comment"]["types"], ["created"])
        self.assertEqual(triggers["pull_request_target"]["types"], ["opened"])
        self.assertEqual(triggers["pull_request_review"]["types"], ["submitted"])
        self.assertEqual(triggers["pull_request_review_comment"]["types"], ["created"])

    def test_denial_precedes_target_checkout_and_codex(self) -> None:
        authorize = self.text.index("- name: Authorize this mention")
        denial = self.text.index("- name: Explain public-repository permission denial")
        target_checkout = self.text.index("- name: Check out the current issue or pull request code")
        codex = self.text.index("- name: Run the repository's persistent Codex session")

        self.assertLess(authorize, denial)
        self.assertLess(denial, target_checkout)
        self.assertLess(target_checkout, codex)
        before_denial = self.text[:denial]
        self.assertNotIn("path: worktree", before_denial)
        target_block = self.text[target_checkout:codex]
        self.assertIn("if: steps.target.outputs.supported == 'true'", target_block)

    def test_fork_gate_replies_before_checkout_and_skips_all_target_steps(self) -> None:
        resolve = self.text.index("- name: Resolve whether the authorized target is supported")
        reply = self.text.index("- name: Explain unsupported fork pull request")
        checkout = self.text.index("- name: Check out the current issue or pull request code")
        codex = self.text.index("- name: Run the repository's persistent Codex session")

        self.assertLess(resolve, reply)
        self.assertLess(reply, checkout)
        self.assertLess(checkout, codex)
        self.assertNotIn("path: worktree", self.text[:reply])
        self.assertIn("steps.target.outputs.fork_unsupported == 'true'", self.text[reply:checkout])
        self.assertIn("暂不支持 fork PR 的 @codex 分析或修改，请在同仓分支/Issue中请求。", self.text[reply:checkout])

        target_steps = self.text[checkout:]
        self.assertNotIn("if: steps.authorize.outputs.authorized == 'true'", target_steps)
        self.assertEqual(target_steps.count("steps.target.outputs.supported == 'true'"), 9)

    def test_repository_runner_and_tool_variables_are_explicit(self) -> None:
        self.assertIn(
            "runs-on: [self-hosted, linux, x64, codex-remote-android]", self.text
        )
        self.assertIn("CODEX_HOME: ${{ vars.CODEX_HOME }}", self.text)
        expected_tools = {
            "TOOLS_DIR": "${{ vars.ANDROID_TOOLS_DIR }}",
            "ANDROID_WORK_DIR": "${{ vars.ANDROID_WORK_DIR }}",
            "GO": "${{ vars.ANDROID_TOOLS_DIR }}/go/bin/go",
        }
        steps = self.document["jobs"]["codex"]["steps"]
        for name in (
            "Run the repository's persistent Codex session",
            "Verify changed code",
        ):
            step = next(step for step in steps if step.get("name") == name)
            self.assertEqual(
                {key: step["env"].get(key) for key in expected_tools},
                expected_tools,
            )

    def test_headless_verification_and_publication_routes(self) -> None:
        self.assertIn("source scripts/android/env.sh", self.text)
        self.assertIn("scripts/android/test.sh", self.text)
        self.assertIn('"$GOROOT/bin/go" -C mobilecore test ./...', self.text)
        self.assertNotIn("emulator-start", self.text)
        self.assertIn("--draft", self.text)
        self.assertNotIn("gh pr merge", self.text)
        self.assertIn("steps.prepare.outputs.publish_allowed == 'true'", self.text)
        self.assertNotIn("这是来自 fork 的 PR；本次只允许分析", self.text)

    def test_automation_is_always_from_default_branch(self) -> None:
        trusted_checkout = self.text.index(
            "- name: Check out trusted automation from the default branch"
        )
        authorization = self.text.index("- name: Authorize this mention")
        block = self.text[trusted_checkout:authorization]
        self.assertIn("ref: ${{ github.event.repository.default_branch }}", block)
        self.assertIn("path: .codex-automation", block)
        self.assertIn("persist-credentials: false", block)


if __name__ == "__main__":
    unittest.main()
