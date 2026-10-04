#!/usr/bin/env python3
"""Require app changes to update the README or user guide, or to say in the PR why they do not."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys

from release_notes import is_bot, markdown_lines, plain_content

DOCS = re.compile(r"(?:README\.md|docs/user-guide/.+)")
# Tests, generated profiles and other locales' strings never need user-facing docs.
APP = re.compile(r"app/src/(?!test/|androidTest/|[^/]+/generated/|[^/]+/res/values-[^/]+/).+")


def docs_section(body: str) -> str | None:
    lines = markdown_lines(body)
    start = next((index for index, (_, depth, title) in enumerate(lines)
                  if depth == 2 and re.fullmatch(r"(?:docs|documentation)\s*:?", title, re.IGNORECASE)), None)
    if start is None:
        return None
    level = lines[start][1]
    end = next((index for index in range(start + 1, len(lines))
                if lines[index][1] and lines[index][1] <= level), len(lines))
    return "\n".join(line for line, _, _ in lines[start + 1:end]).strip()


def is_bare(content: str) -> bool:
    return bool(re.fullmatch(
        r"(?:none|n/?a|not applicable|todo|tbd|no|-|"
        r"(?:docs? |documentation )?(?:not needed|not required|unchanged)|"
        r"no (?:docs?|documentation)(?: changes?| updates?)?(?: needed| required)?)", content))


def validate_docs(body: str, changed: list[str]) -> str:
    app = [path for path in changed if APP.fullmatch(path)]
    if not app:
        return "No app sources changed; no docs update required."
    if any(DOCS.fullmatch(path) for path in changed):
        return "README.md or docs/user-guide/ is updated."
    section = docs_section(body)
    if section is None:
        raise ValueError(
            "This PR changes app sources but not README.md or docs/user-guide/. Update the docs, "
            "or add a ## Docs section saying why no docs change is needed.")
    content = [plain_content(line) for line, depth, _ in markdown_lines(section) if not depth]
    content = [value for value in content if value]
    if re.search(r"\b(?:TODO|TBD)\b", section, re.IGNORECASE):
        raise ValueError("The ## Docs section must replace TODO/TBD placeholders with a reason.")
    if not content or all(is_bare(value) for value in content):
        raise ValueError("The ## Docs section must say why no docs change is needed, e.g. "
                         "\"Not needed: internal refactor, no visible change.\"")
    return "The ## Docs section explains why no docs change is needed."


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--event-file", type=Path, required=True)
    parser.add_argument("--changed-files", type=Path, required=True, help="One changed path per line")
    args = parser.parse_args()
    try:
        pull_request = json.loads(args.event_file.read_text(encoding="utf-8")).get("pull_request", {})
        changed = [line.strip() for line in args.changed_files.read_text(encoding="utf-8").splitlines() if line.strip()]
        if is_bot(pull_request):
            print("Bot PRs need no docs update.")
        else:
            print(validate_docs(str(pull_request.get("body") or ""), changed))
    except (ValueError, OSError) as error:
        print(f"Docs check: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
