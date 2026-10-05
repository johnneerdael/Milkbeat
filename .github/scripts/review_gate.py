#!/usr/bin/env python3
"""Trusted controller for reviewed PR validation; run only from main."""
import argparse
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import re
import subprocess

from pr_docs import validate_docs
from release_notes import is_bot, validate_release_notes

CONTEXT = "Reviewed PR builds"
BUILD_JOBS = {"android", "codeql", "docs", "metadata"}
WORKFLOW = "pr-builds.yml"
CODEX = "chatgpt-codex-connector[bot]"
TRUSTED = {"OWNER", "MEMBER", "COLLABORATOR"}


def eligibility(pr, reviews, comments, threads):
    if pr["state"] != "open" or pr["draft"] or pr["base"]["ref"] != "main":
        return False, "Waiting for an open, ready PR targeting main"
    if pr.get("requested_reviewers") or pr.get("requested_teams"):
        return False, "Waiting for requested reviews"
    if any(not thread["isResolved"] for thread in threads):
        return False, "Waiting for all review threads to be resolved"
    if any(review["state"] == "PENDING" for review in reviews):
        return False, "Waiting for a visible pending review"
    # A comment-only follow-up does not revoke a request for changes.
    decisions = {}
    for review in reviews:
        if review["state"] in {"APPROVED", "CHANGES_REQUESTED", "DISMISSED"}:
            decisions[review["user"]["login"]] = review["state"]
    if "CHANGES_REQUESTED" in decisions.values():
        return False, "Waiting for requested changes to be accepted"
    head = pr["head"]["sha"]
    completed = False
    requests, completions = {}, {}

    def completed_at(kind, value):
        if value:
            timestamp = datetime.fromisoformat(value.replace("Z", "+00:00"))
            completions[kind] = max(timestamp, completions.get(kind, timestamp))

    for comment in comments:
        user = comment["user"]["login"]
        trusted = user == pr["user"]["login"] or comment.get("author_association") in TRUSTED
        request = re.match(r"^@codex\s+(security\s+)?review\b", (comment.get("body") or "").strip(), re.I)
        requested_at = comment.get("updated_at") or comment.get("created_at")
        if trusted and request and requested_at:
            kind = "security" if request[1] else "code"
            timestamp = datetime.fromisoformat(requested_at.replace("Z", "+00:00"))
            requests[kind] = max(timestamp, requests.get(kind, timestamp))
    for comment in comments:
        if comment["user"]["login"] != CODEX or comment["user"].get("type") != "Bot":
            continue
        body = comment.get("body") or ""
        if "<!-- codex-pull-request-review-summary -->" in body:
            for row in body.splitlines():
                if "**Code Review**" not in row and "**Security Review**" not in row:
                    continue
                commit = re.search(r"`([0-9a-f]{7,40})`", row)
                if commit and "**Completed**" in row:
                    current = comment.get("_reviewed_commits", {}).get(commit[1], commit[1] if len(commit[1]) == 40 else None) == head
                    kind = "security" if "**Security Review**" in row else "code"
                    completed = completed or (current and kind == "code")
                    date = re.search(r'datetime="([^"]+)"', row)
                    if current:
                        completed_at(kind, date[1] if date else comment.get("updated_at"))
                elif commit and comment.get("_reviewed_commits", {}).get(commit[1], commit[1] if len(commit[1]) == 40 else None) == head:
                    return False, "Waiting for Codex review to finish"
        else:
            commit = re.search(r"\*\*Reviewed commit:\*\*\s*`([0-9a-f]{7,40})`", body)
            if commit and "Codex Review" in body:
                current = comment.get("_reviewed_commits", {}).get(commit[1], commit[1] if len(commit[1]) == 40 else None) == head
                kind = "security" if re.search(r"Codex Security Review", body, re.I) else "code"
                completed = completed or (current and kind == "code")
                if current:
                    completed_at(kind, comment.get("created_at"))
    for review in reviews:
        user = review["user"]
        report = (user["login"] == CODEX and user.get("type") == "Bot"
                  and re.search(r"Codex(?: Security)? Review", review.get("body") or "", re.I))
        marker = re.search(r"\*\*Reviewed commit:\*\*\s*`([0-9a-f]{7,40})`", review.get("body") or "")
        if marker:
            written = review.get("_reviewed_commits", {}).get(marker[1], marker[1] if len(marker[1]) == 40 else None)
            report = report and written == head
        if (report and review.get("submitted_at") and review["commit_id"] == head
                and review["state"] in {"APPROVED", "COMMENTED"}):
            kind = "security" if re.search(r"Codex Security Review", review.get("body") or "", re.I) else "code"
            completed_at(kind, review["submitted_at"])
            completed = completed or kind == "code"
    if any(kind not in completions or completions[kind] < timestamp for kind, timestamp in requests.items()):
        return False, "Waiting for requested Codex reviews to finish"
    if not completed:
        return False, "Waiting for a completed review of the latest commit"
    if pr.get("mergeable") is not True or not pr.get("merge_commit_sha"):
        return False, "Waiting for a mergeable PR and its test merge commit"
    return True, "Review complete; no outstanding findings"


def matches(pr, head, base, merge):
    return (pr["head"]["sha"], pr["base"]["sha"], pr.get("merge_commit_sha")) == (head, base, merge)


def builds_passed(results):
    return set(results) == BUILD_JOBS and all(value == "success" for value in results.values())


class GitHub:
    def __init__(self, repo):
        self.repo = repo

    def request(self, endpoint, data=None, paginate=False):
        command = ["gh", "api", endpoint]
        if data is not None:
            command += ["--method", "POST", "--input", "-"]
        if paginate:
            command += ["--paginate", "--slurp"]
        result = subprocess.run(command, input=json.dumps(data) if data is not None else None,
                                text=True, capture_output=True, check=True)
        return json.loads(result.stdout) if result.stdout.strip() else None

    def get(self, path):
        return self.request(f"repos/{self.repo}/{path}")

    def post(self, path, data):
        return self.request(f"repos/{self.repo}/{path}", data)

    def pages(self, path, key=None):
        pages = self.request(f"repos/{self.repo}/{path}", paginate=True)
        return [item for page in pages for item in (page[key] if key else page)]

    def snapshot(self, number):
        pr = self.get(f"pulls/{number}")
        reviews = self.pages(f"pulls/{number}/reviews?per_page=100")
        comments = self.pages(f"issues/{number}/comments?per_page=100")
        resolved = {}
        for comment in comments + reviews:
            if comment["user"]["login"] != CODEX or comment["user"].get("type") != "Bot":
                continue
            prefixes = [prefix for prefix in re.findall(r"`([0-9a-f]{7,40})`", comment.get("body") or "")
                        if pr["head"]["sha"].startswith(prefix)]
            for prefix in prefixes:
                if prefix not in resolved:
                    resolved[prefix] = self.get(f"commits/{prefix}")["sha"]
            comment["_reviewed_commits"] = {prefix: resolved[prefix] for prefix in prefixes}
        owner, name = self.repo.split("/")
        threads, cursor, seen = [], None, set()
        while True:
            data = self.request("graphql", dict(query="""
              query($owner:String!, $name:String!, $number:Int!, $cursor:String) {
                repository(owner:$owner,name:$name) {
                  pullRequest(number:$number) {
                    reviewThreads(first:100,after:$cursor) {
                      nodes { isResolved }
                      pageInfo { hasNextPage endCursor }
                    }
                  }
                }
              }""", variables=dict(owner=owner, name=name, number=number, cursor=cursor)))
            if data.get("errors"):
                raise RuntimeError(f"Cannot read review threads: {data['errors']}")
            connection = data["data"]["repository"]["pullRequest"]["reviewThreads"]
            threads.extend(connection["nodes"])
            if not connection["pageInfo"]["hasNextPage"]:
                break
            cursor = connection["pageInfo"]["endCursor"]
            if not cursor or cursor in seen:
                raise RuntimeError("Review thread pagination repeated a cursor")
            seen.add(cursor)
        # Re-read to reject a snapshot collected across a push or base change.
        current = self.get(f"pulls/{number}")
        if not matches(current, pr["head"]["sha"], pr["base"]["sha"], pr.get("merge_commit_sha")):
            return current, (False, "PR changed while reading reviews; recheck required")
        ready, reason = eligibility(current, reviews, comments, threads)
        if ready:
            merge = self.get(f"git/commits/{current['merge_commit_sha']}")
            parents = [parent["sha"] for parent in merge["parents"]]
            if merge["sha"] != current["merge_commit_sha"] or parents != [current["base"]["sha"], current["head"]["sha"]]:
                return current, (False, "Waiting for a test merge with the current base and reviewed head")
        return current, (ready, reason)

    def status(self, head, state, description, url=None):
        previous = next((s for s in self.pages(f"commits/{head}/statuses?per_page=100")
                         if s["context"] == CONTEXT), None)
        if previous and (previous["state"], previous["description"]) == (state, description):
            return
        payload = dict(state=state, context=CONTEXT, description=description[:140])
        if url:
            payload["target_url"] = url
        self.post(f"statuses/{head}", payload)


def title(pr):
    return f"Reviewed PR #{pr['number']} @ {pr['head']['sha']} + {pr['base']['sha']} = {pr['merge_commit_sha']}"


def passed_description(pr):
    return f"Passed builds: main {pr['base']['sha']} merge {pr['merge_commit_sha']}"


def validate_metadata(api, pr):
    if is_bot(pr):
        return
    body = pr.get("body") or ""
    validate_release_notes(body)
    changed = [item["filename"] for item in api.pages(f"pulls/{pr['number']}/files?per_page=100")]
    validate_docs(body, changed)


def reconcile(api, number, retry=False):
    pr, (ready, reason) = api.snapshot(number)
    all_runs = [run for run in api.pages(f"actions/workflows/{WORKFLOW}/runs?event=workflow_dispatch&per_page=100", "workflow_runs")
                if run["head_branch"] == "main" and run["display_title"].startswith(f"Reviewed PR #{number} @ ")]
    for run in all_runs:
        if run["status"] != "completed" and (run["display_title"] != title(pr) or pr["state"] != "open" or pr["base"]["ref"] != "main"):
            api.post(f"actions/runs/{run['id']}/cancel", {})
    if pr["state"] != "open" or pr["base"]["ref"] != "main":
        return
    head = pr["head"]["sha"]
    runs = [run for run in all_runs if run["display_title"] == title(pr)]
    runs.sort(key=lambda run: run["id"], reverse=True)
    active = [run for run in runs if run["status"] != "completed"]
    if not ready:
        api.status(head, "pending", reason)
        for run in active:
            api.post(f"actions/runs/{run['id']}/cancel", {})
        return
    try:
        validate_metadata(api, pr)
    except ValueError as error:
        api.status(head, "failure", f"PR metadata: {error}")
        for run in active:
            api.post(f"actions/runs/{run['id']}/cancel", {})
        return
    if active:
        api.status(head, "pending", "Reviewed; full validation is running", active[0]["html_url"])
        return
    statuses = [status for status in api.pages(f"commits/{head}/statuses?per_page=100") if status["context"] == CONTEXT]
    previous = next(iter(statuses), None)
    if runs and not retry and runs[0]["conclusion"] not in {"success", "cancelled", "skipped"}:
        api.status(head, "failure", "Validation did not pass; fix the PR or rerun validation", runs[0]["html_url"])
        return
    passed = passed_description(pr)
    retrying_failure = retry and runs and runs[0]["conclusion"] != "success"
    if not retrying_failure and any(status["state"] == "success" and status["description"] == passed for status in statuses):
        api.status(head, "success", passed)
        return
    # Dispatch reservations prevent duplicate runs while GitHub queues a new workflow.
    reservation = f"Queued builds against main {pr['base']['sha']}"
    if previous and previous["state"] == "pending" and previous["description"] == reservation:
        created = datetime.fromisoformat(previous["created_at"].replace("Z", "+00:00"))
        if datetime.now(timezone.utc) - created < timedelta(minutes=20):
            return
    api.status(head, "pending", reservation)
    api.post(f"actions/workflows/{WORKFLOW}/dispatches", dict(ref="main", inputs=dict(
        pr_number=str(number), head_sha=head, base_sha=pr["base"]["sha"], merge_sha=pr["merge_commit_sha"])))


def snapshot_safely(api, number):
    try:
        return api.snapshot(number)
    except (RuntimeError, subprocess.CalledProcessError, KeyError, ValueError, TypeError):
        pr = api.get(f"pulls/{number}")
        if pr["state"] == "open" and pr["base"]["ref"] == "main":
            api.status(pr["head"]["sha"], "pending", "Unable to verify review state; recheck required")
        raise


def reconcile_safely(api, numbers, retry=False):
    errors = []
    for number in numbers:
        try:
            reconcile(api, number, retry)
        except (RuntimeError, subprocess.CalledProcessError, KeyError, ValueError, TypeError) as error:
            # Fail closed if review reads fail after a previously successful build.
            try:
                pr = api.get(f"pulls/{number}")
                if pr["state"] == "open" and pr["base"]["ref"] == "main":
                    api.status(pr["head"]["sha"], "pending", "Unable to verify review state; recheck required")
            except (RuntimeError, subprocess.CalledProcessError, KeyError, ValueError, TypeError) as status_error:
                errors.append(f"PR #{number}: cannot invalidate status: {status_error}")
            errors.append(f"PR #{number}: {error}")
    if errors:
        raise RuntimeError("; ".join(errors))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["reconcile", "preflight", "finish", "inspect"])
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY"), required=not os.environ.get("GITHUB_REPOSITORY"))
    parser.add_argument("--pr", type=int, default=0)
    parser.add_argument("--retry", action="store_true")
    args = parser.parse_args()
    api = GitHub(args.repo)
    if args.mode == "inspect":
        if args.pr <= 0:
            raise ValueError("A positive PR number is required")
        pr, (ready, reason) = api.snapshot(args.pr)
        print(json.dumps(dict(pr=args.pr, head=pr["head"]["sha"], base=pr["base"]["sha"],
                              merge=pr.get("merge_commit_sha"), eligible=ready, reason=reason)))
        return
    if args.mode == "reconcile":
        numbers = [args.pr] if args.pr else [pr["number"] for pr in api.pages("pulls?state=open&per_page=100")]
        reconcile_safely(api, numbers, args.retry)
        return
    head, base, merge = (os.environ[name] for name in ["PR_HEAD", "PR_BASE", "PR_MERGE"])
    if any(not re.fullmatch(r"[0-9a-f]{40}", sha) for sha in [head, base, merge]):
        raise ValueError("Full commit SHAs are required")
    pr, (ready, reason) = snapshot_safely(api, args.pr)
    ready = ready and matches(pr, head, base, merge)
    if args.mode == "preflight":
        if not ready:
            raise RuntimeError(f"PR is not eligible or its revision changed: {reason}")
        with Path(os.environ["GITHUB_OUTPUT"]).open("a") as output:
            output.write(f"merge_sha={merge}\n")
        return
    results = json.loads(os.environ["BUILD_RESULTS"])
    # Never mutate the new head's status from an old validation run.
    if pr["head"]["sha"] != head:
        return
    url = f"https://github.com/{args.repo}/actions/runs/{os.environ['GITHUB_RUN_ID']}"
    if not ready:
        api.status(head, "pending", "Review or base changed; fresh validation required", url)
    elif builds_passed(results):
        try:
            validate_metadata(api, pr)
        except ValueError as error:
            api.status(head, "failure", f"PR metadata: {error}", url)
            raise
        api.status(head, "success", passed_description(pr), url)
    else:
        api.status(head, "failure", "Validation failed, was cancelled, or skipped a required build", url)
        raise RuntimeError(f"Required builds did not pass: {results}")


if __name__ == "__main__":
    main()
