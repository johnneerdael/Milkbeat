# Review-gated Milkbeat CI Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan inline, preserving the repository's autonomous PR/review/merge workflow.

**Goal:** Run Milkbeat PR validation only after a completed Codex review of its exact head, with no unresolved review threads; retain full main CI/CD.

**Architecture:** Add a tested GitHub API review policy and a small metadata dispatcher. Keep existing validation workflows, accepting an immutable reviewed head/base/test-merge snapshot through manual dispatch while retaining main triggers. Each workflow rechecks eligibility before checkout, pins the reviewed head and reports the result to that head. Main publishing remains independent of PR inputs.

**Tech stack:** GitHub Actions, actions/github-script and its Octokit client, Python unittest, the maintained gh CLI, existing Kotlin/Python/Node validators.

**Spec:** The user's situation table and explicit choices: minimum one completed Codex review (not formal APPROVED), no unresolved findings, Milkbeat only. Original playback PR #37 remains separately preserved.

## Global constraints

- Skip feature-branch pushes and PRs targeting branches other than main.
- Require current-head Codex evidence from the actual Codex bot; human comments cannot impersonate it.
- Block drafts, active current-head reviews, unresolved threads and active changes requests.
- Fail closed on API failures and revalidate the current PR head before dispatch and result publication.
- Pin PR code to the reviewed SHA; PR validation receives no release signing secrets and never publishes releases.
- Keep existing main APK publication and ProjectM core-release rebuild/update behavior. Do not modify ProjectM-TV.
- Keep zero formal approving reviews because Codex submits COMMENTED reviews or no-findings comments; enforce the Codex gate through required statuses after deployment.
- Preserve unrelated rules and bypass actors; never use the owner's bypass to merge unreviewed or unvalidated code.

## Review focus

- A new commit after review must not inherit review eligibility or green results.
- Threads after the first 100 must be inspected; pagination cannot hide a finding.
- A stale no-findings comment during a new review must not start builds.
- Main-context dispatch of PR validation must not expose signing secrets or publish SARIF against main.
- Resolving a thread without another review/comment must eventually refresh eligibility without repeated expensive builds.

## Task 1: Review policy and metadata loading

Create `.github/scripts/review_gate.py` and `.github/scripts/test_review_gate.py`.

- [ ] Write failing tests for absent/stale/spoofed Codex review, active review, unresolved paginated threads, changes requests, valid submitted review and valid no-findings comment.
- [ ] Implement `GitHub.snapshot(number)` and `eligibility(pr, reviews, comments, threads)` with full pagination and exact head matching. Resolve shortened bot commit references through GitHub's commit API.
- [ ] Run `python3 -m unittest discover -s .github/scripts -p test_review_gate.py -v`; require zero failures.
- [ ] Commit and push the policy checkpoint.

## Task 2: Gate dispatch and result reporting

Extend the tested Python controller; add `.github/workflows/review-gate.yml`, `review-signal.yml` and `pr-builds.yml`.

- [ ] Test event routing, dispatch pinning, same-head deduplication, stale result rejection and main/fork eligibility.
- [ ] Trigger metadata checks on PR changes, Codex comment changes and scheduled/manual rechecks. Do not execute PR code in the privileged metadata dispatcher.
- [ ] Dispatch a trusted-main reusable validation orchestrator with validated PR number/head/base/test-merge SHAs; serialize metadata dispatch and deduplicate by current-head result/run markers.
- [ ] Refresh review pending/success status on the PR head and cancel only this PR's superseded validation runs when its head or eligibility changes.
- [ ] Keep result reporting in a separate trusted job without PR checkout; require successful results plus current eligibility before posting success.
- [ ] Run dispatcher tests and commit/push the checkpoint.

## Task 3: Gate existing validation and publishing workflows

Modify `build.yml`, `codeql.yml`, `docs.yml`, `pr-docs.yml`, `release-notes.yml`; add shared gate/result actions if they reduce real duplication.

- [ ] Preserve main push, applicable main scheduled/manual and core-release events. Add reviewed PR dispatch inputs and guard every expensive job on live review eligibility.
- [ ] Keep this bootstrap PR under the existing trusted workflows until installation; post-deployment dispatch handles review completion and thread-resolution rechecks.
- [ ] Pin PR checkout to the validated SHA with no persisted credentials. Restrict signing secrets and publishing to trusted main builds.
- [ ] Pass explicit PR ref/SHA to CodeQL's supported `ref` and `sha` inputs.
- [ ] Fetch PR body/changed paths from GitHub for metadata validators when the event is dispatch, preserving their existing validation contracts.
- [ ] Remove main path filters that would skip validation under the user's full-main policy. Keep labels as metadata work, and provider maintenance restricted to main.
- [ ] Validate YAML and Actions expressions with actionlint; test trigger/guard/secret/reporting invariants and run existing script tests.
- [ ] Commit/push workflow checkpoint.

## Task 4: Documentation, review and activation

- [ ] Evaluate README, contribution instructions, AGENTS and guide releases/site publishing documentation. Update affected CI guidance and publishing facts without app/version/schema changes.
- [ ] Run documentation build, Python/Node script suites, actionlint, and graphify update.
- [ ] Open a ready PR to main, attach it, obtain current-head Codex review, dispose every finding and rerun validation/review after corrections.
- [ ] Run reviewed bootstrap CI; do not treat skipped jobs as passing validation. Merge only when required gates pass.
- [ ] After workflow deployment, add required current-head Codex and reviewed validation statuses to the main ruleset, preserving other fields. Verify activation on eligible and ineligible PR states.
- [ ] Resume PR #37's final CI/merge once GitHub runner allocation recovers; preserve both worktrees if the outage prevents completion.
