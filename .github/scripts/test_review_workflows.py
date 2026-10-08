"""Behavioral boundaries of trusted and untrusted validation jobs."""
from pathlib import Path
import unittest

import yaml

WORKFLOWS = Path(__file__).resolve().parent.parent / "workflows"


def workflow(name):
    return yaml.load((WORKFLOWS / name).read_text(), Loader=yaml.BaseLoader)


class WorkflowPolicyTests(unittest.TestCase):
    def test_reusable_jobs_cannot_request_permissions_missing_from_the_caller(self):
        levels = {"none": 0, "read": 1, "write": 2}
        for caller_name in ["build.yml", "pr-builds.yml"]:
            caller = workflow(caller_name)
            for name, call in caller["jobs"].items():
                reference = call.get("uses", "")
                if not reference.startswith("./.github/workflows/"):
                    continue
                allowed = call.get("permissions", caller.get("permissions", {}))
                callee = workflow(reference.rsplit("/", 1)[-1])
                for nested_name, nested in callee["jobs"].items():
                    requested = nested.get("permissions", callee.get("permissions", {}))
                    for permission, level in requested.items():
                        with self.subTest(caller=caller_name, job=name, nested=nested_name, permission=permission):
                            self.assertLessEqual(levels[level], levels[allowed.get(permission, "none")])

    def test_guide_build_code_has_only_read_permissions(self):
        docs = workflow("docs.yml")
        build = docs["jobs"]["build"]
        permissions = build.get("permissions", docs["permissions"])
        self.assertEqual(permissions.get("contents"), "read")
        self.assertFalse(any(level == "write" for level in permissions.values()))

    def test_only_main_pushes_start_full_publication(self):
        main = workflow("build.yml")
        self.assertEqual(main["on"]["push"]["branches"], ["main"])
        self.assertNotIn("pull_request", main["on"])
        self.assertEqual(set(main["jobs"]["publish-main"]["needs"]), {"build", "codeql", "metadata", "docs"})
        for job in ["build", "codeql", "metadata", "docs"]:
            self.assertIn("refs/heads/main", main["jobs"][job]["if"])

    def test_pr_validation_pins_an_immutable_merge_and_requires_every_suite(self):
        pr = workflow("pr-builds.yml")
        self.assertEqual(set(pr["on"]), {"workflow_dispatch"})
        self.assertEqual(pr["cache-mode"], "none")
        suites = {"android", "codeql", "metadata", "docs"}
        self.assertEqual(set(pr["jobs"]["result"]["needs"]), suites | {"preflight"})
        for job in suites:
            self.assertEqual(pr["jobs"][job]["needs"], "preflight")
            self.assertIn("merge_sha", pr["jobs"][job]["with"]["source_ref"])
            self.assertNotIn("secrets", pr["jobs"][job])
        self.assertEqual(pr["jobs"]["android"]["with"]["pr_build"], "true")
        self.assertEqual(pr["jobs"]["docs"]["with"]["pr_build"], "true")

    def test_write_capable_controller_and_result_never_checkout_pr_code(self):
        gate = workflow("review-gate.yml")
        checkout = gate["jobs"]["review-gate"]["steps"][0]
        self.assertEqual(checkout["with"]["ref"], "main")
        self.assertEqual(checkout["with"]["persist-credentials"], "false")
        result = workflow("pr-builds.yml")["jobs"]["result"]
        self.assertEqual(result["permissions"]["statuses"], "write")
        self.assertEqual(result["steps"][0]["with"].get("ref", "main"), "main")
        self.assertNotIn("download-artifact", str(result))

    def test_review_relay_is_unprivileged_and_build_free(self):
        relay = workflow("review-signal.yml")
        self.assertEqual(relay["permissions"], {})
        self.assertNotIn("checkout", str(relay["jobs"]))
        self.assertNotIn("gradlew", str(relay["jobs"]))

    def test_pr_android_build_has_no_signing_secrets_or_cache_access(self):
        android = workflow("android-build.yml")
        steps = android["jobs"]["build"]["steps"]
        key = next(step for step in steps if step.get("name") == "Decode Keystore")
        self.assertIn("!inputs.pr_build", key["if"])
        apk = next(step for step in steps if step.get("name") == "Build TV release APKs")
        for expression in apk["env"].values():
            self.assertIn("!inputs.pr_build", expression)
        gradle = next(step for step in steps if step.get("name") == "Set up Gradle")
        self.assertIn("inputs.pr_build", gradle["with"]["cache-disabled"])
        self.assertFalse(any("secrets" in str(step) for step in steps if step.get("name") not in {"Decode Keystore", "Build TV release APKs"}))

    def test_source_codeql_job_is_read_only_and_trusted_upload_pins_results(self):
        codeql = workflow("codeql.yml")
        analyze = codeql["jobs"]["analyze"]
        self.assertNotIn("write", analyze["permissions"].values())
        query = next(step for step in analyze["steps"] if step.get("name") == "Perform CodeQL analysis")
        self.assertEqual(query["with"]["upload"], "never")
        upload = codeql["jobs"]["upload"]
        steps = upload["steps"]
        self.assertEqual(steps[0]["with"]["ref"], "main")
        download = next(step for step in steps if step.get("uses", "").startswith("actions/download-artifact"))["with"]
        self.assertIn("sarif_artifact", download["artifact-ids"])
        publish_index, publish_step = next(
            (index, step) for index, step in enumerate(steps) if step.get("uses", "").startswith("github/codeql-action/upload-sarif")
        )
        publish = publish_step["with"]
        self.assertIn("refs/pull", publish["ref"])
        self.assertIn("source_ref", publish["sha"])
        # upload-sarif reports the HEAD of checkout_path, so it must be a checkout of the analyzed revision.
        analyzed = next(
            step
            for step in steps[:publish_index]
            if step.get("uses", "").startswith("actions/checkout") and step["with"].get("path")
        )["with"]
        self.assertIn("source_ref", analyzed["ref"])
        self.assertEqual(analyzed["persist-credentials"], "false")
        self.assertTrue(publish["checkout_path"].endswith("/" + analyzed["path"]))

    def test_pages_deployments_share_a_lock_and_reject_stale_main(self):
        docs = workflow("docs.yml")
        deploy = docs["jobs"]["deploy"]
        self.assertIn("!inputs.pr_build", deploy["if"])
        self.assertEqual(deploy["concurrency"]["group"], "milkbeat-pages-deployment")
        self.assertEqual(deploy["concurrency"]["cancel-in-progress"], "false")
        self.assertEqual(deploy["permissions"]["contents"], "read")
        freshness = next(step for step in deploy["steps"] if step.get("id") == "freshness")
        self.assertIn("heads/main", freshness["with"]["script"])
        self.assertIn("SOURCE_SHA", freshness["with"]["script"])
        publish = next(step for step in deploy["steps"] if step.get("id") == "deployment")
        self.assertEqual(publish["if"], "steps.freshness.outputs.current == 'true'")

    def test_manual_provider_maintenance_is_main_only(self):
        self.assertIn("refs/heads/main", workflow("spotify-totp.yml")["jobs"]["extract"]["if"])


if __name__ == "__main__":
    unittest.main()
