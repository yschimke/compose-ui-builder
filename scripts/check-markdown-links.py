#!/usr/bin/env python3
"""Check repository-relative links and Markdown heading fragments."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from collections import defaultdict
from pathlib import Path
from urllib.parse import unquote, urlsplit


INLINE_LINK = re.compile(r"!?\[[^\]]*\]\(\s*(<[^>]+>|[^\s)]+)")
REFERENCE_LINK = re.compile(r"^\s*\[[^\]]+\]:\s*(<[^>]+>|\S+)", re.MULTILINE)
HTML_LINK = re.compile(r"<(?:a|img)\b[^>]*?\b(?:href|src)=[\"']([^\"']+)[\"']", re.IGNORECASE)
HEADING = re.compile(r"^\s{0,3}#{1,6}\s+(.+?)\s*#*\s*$")
EXPLICIT_ANCHOR = re.compile(r"<(?:a\s+(?:name|id)|[^>]+\sid)=[\"']([^\"']+)[\"']", re.IGNORECASE)


def tracked_markdown(root: Path) -> list[Path]:
    result = subprocess.run(
        ["git", "ls-files", "-z", "--", "*.md"],
        cwd=root,
        check=True,
        capture_output=True,
    )
    return [root / path.decode() for path in result.stdout.split(b"\0") if path]


def without_fenced_code(text: str) -> str:
    output: list[str] = []
    fence: str | None = None
    for line in text.splitlines(keepends=True):
        match = re.match(r"^\s*(`{3,}|~{3,})", line)
        if match and fence is None:
            fence = match.group(1)[0]
            output.append("\n" if line.endswith("\n") else "")
        elif match and fence == match.group(1)[0]:
            fence = None
            output.append("\n" if line.endswith("\n") else "")
        elif fence is None:
            output.append(line)
        else:
            output.append("\n" if line.endswith("\n") else "")
    return "".join(output)


def github_slug(text: str) -> str:
    text = re.sub(r"<[^>]+>", "", text)
    text = re.sub(r"!?\[([^\]]*)\]\([^)]*\)", r"\1", text)
    text = re.sub(r"[`*_~]", "", text).strip().lower()
    text = re.sub(r"[^\w\- ]", "", text, flags=re.UNICODE)
    return re.sub(r"\s", "-", text)


def anchors(markdown: Path) -> set[str]:
    text = without_fenced_code(markdown.read_text(encoding="utf-8"))
    found = {unquote(anchor) for anchor in EXPLICIT_ANCHOR.findall(text)}
    occurrences: defaultdict[str, int] = defaultdict(int)
    for line in text.splitlines():
        match = HEADING.match(line)
        if not match:
            continue
        base = github_slug(match.group(1))
        suffix = occurrences[base]
        occurrences[base] += 1
        found.add(base if suffix == 0 else f"{base}-{suffix}")
    return found


def links(text: str) -> list[tuple[int, str]]:
    visible = without_fenced_code(text)
    matches = [*INLINE_LINK.finditer(visible), *REFERENCE_LINK.finditer(visible), *HTML_LINK.finditer(visible)]
    return sorted((visible.count("\n", 0, match.start()) + 1, match.group(1).strip("<>")) for match in matches)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--allow",
        action="append",
        default=[],
        help="repository-relative target to allow (repeatable)",
    )
    args = parser.parse_args()

    root = Path(subprocess.run(["git", "rev-parse", "--show-toplevel"], check=True, capture_output=True, text=True).stdout.strip())
    allowed = set(args.allow)
    failures: list[str] = []
    anchor_cache: dict[Path, set[str]] = {}

    for markdown in tracked_markdown(root):
        text = markdown.read_text(encoding="utf-8")
        for line, raw_target in links(text):
            parsed = urlsplit(raw_target)
            if parsed.scheme or raw_target.startswith("//"):
                continue
            path_text = unquote(parsed.path)
            target = markdown if not path_text else (markdown.parent / path_text).resolve()
            try:
                relative = target.relative_to(root).as_posix()
            except ValueError:
                relative = str(target)
            if relative in allowed or raw_target in allowed:
                continue
            location = f"{markdown.relative_to(root)}:{line}"
            if not target.exists():
                failures.append(f"{location}: missing target `{raw_target}`")
                continue
            if parsed.fragment and target.is_file() and target.suffix.lower() == ".md":
                target_anchors = anchor_cache.setdefault(target, anchors(target))
                fragment = unquote(parsed.fragment)
                if fragment not in target_anchors:
                    failures.append(f"{location}: missing anchor `#{fragment}` in `{relative}`")

    if failures:
        print("Broken repository-relative Markdown links:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1
    print("All repository-relative Markdown links and anchors resolve.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
