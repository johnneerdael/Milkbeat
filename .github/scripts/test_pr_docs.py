import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pr_docs  # noqa: E402

SCRIPT = Path(__file__).resolve().parent / "pr_docs.py"
SOURCE = "app/src/main/java/io/github/aedev/flow/ui/screens/settings/SettingsScreen.kt"


class DocsTests(unittest.TestCase):
    def test_changes_outside_user_facing_app_sources_need_no_docs(self):
        for changed in (
            [".github/workflows/build.yml"],
            ["app/src/test/java/io/github/aedev/flow/FooTest.kt"],
            ["app/src/androidTest/java/io/github/aedev/flow/FooTest.kt"],
            ["app/src/githubRelease/generated/baselineProfiles/baseline-prof.txt"],
            ["app/src/main/res/values-nl/strings.xml"],
        ):
            with self.subTest(changed=changed):
                self.assertIn("No app sources", pr_docs.validate_docs("", changed))

    def test_app_change_with_readme_or_user_guide_passes(self):
        for docs in ("README.md", "docs/user-guide/settings.md"):
            with self.subTest(docs=docs):
                self.assertIn("updated", pr_docs.validate_docs("", [SOURCE, docs]))

    def test_other_docs_do_not_count_as_user_docs(self):
        with self.assertRaisesRegex(ValueError, "## Docs"):
            pr_docs.validate_docs("## Summary\nRefactor.", [SOURCE, "docs/plugin-architecture.md"])

    def test_app_change_without_docs_needs_a_concrete_reason(self):
        for body in (
            "## Summary\nRefactor.",
            "## Docs\n<!-- Say why -->\n-",
            "## Docs\nN/A",
            "## Docs\n- Not needed",
            "## Docs\nNo docs changes needed.",
            "## Docs\nTODO",
            "```\n## Docs\nNot needed: refactor.\n```",
            "# Docs\nNot needed: refactor.",
            "### Docs\nNot needed: refactor.",
        ):
            with self.subTest(body=body):
                with self.assertRaises(ValueError):
                    pr_docs.validate_docs(body, [SOURCE])

    def test_concrete_reason_passes(self):
        for body in (
            "## Docs\nNot needed: internal refactor, no visible change.",
            "## Documentation\n- The setting already matches the user guide's description.",
        ):
            with self.subTest(body=body):
                self.assertIn("explains", pr_docs.validate_docs(body, [SOURCE]))

    def test_cli_skips_bots_and_reports_failures(self):
        with tempfile.TemporaryDirectory() as directory:
            event = Path(directory) / "event.json"
            changed = Path(directory) / "changed.txt"
            changed.write_text(SOURCE + "\n")
            for pull_request, expected in (
                ({"body": "## Summary\nRefactor."}, 1),
                ({"body": "## Summary\nBump.", "user": {"type": "Bot"}}, 0),
                ({"body": "## Docs\nNot needed: internal refactor."}, 0),
            ):
                event.write_text(json.dumps({"pull_request": pull_request}))
                result = subprocess.run([sys.executable, str(SCRIPT), "--event-file", str(event), "--changed-files", str(changed)],
                                        capture_output=True, text=True)
                self.assertEqual(result.returncode, expected, result.stderr)


if __name__ == "__main__":
    unittest.main()
