#!/usr/bin/env python3
"""Generate the Remote layout signatures from the released Remote Compose API.

The released `remote-creation-compose` sources are authoritative for the layout containers the
builder's `layout/*` nodes export as (`RemoteBox`, `RemoteColumn`, …). This reads every public
top-level overload of those functions from the sources jar of one release and writes each
parameter's name and whether it has a default, which is what the exporter needs to decide whether
an empty container may be written without a content lambda.

    python3 -I scripts/remote-vocabulary/generate_remote_layouts.py 1.0.0-alpha21 \
      > docs/design/fixtures/ui-builder/remote-layouts-v1.json

Re-run it for each new alpha, together with the modifier vocabulary and the component record
(docs/design/UI_BUILDER_REMOTE_RICHNESS.md, "Authoring target").
"""

import io
import json
import re
import sys
import urllib.request
import zipfile

GROUP = "androidx/compose/remote"
ARTIFACT = "remote-creation-compose"

# The containers `RemoteContentEmitter.container` writes.
LAYOUTS = {
    "RemoteBox",
    "RemoteColumn",
    "RemoteRow",
    "RemoteFitBox",
    "RemoteFlowRow",
    "RemoteCollapsibleColumn",
    "RemoteCollapsibleRow",
}

# Top-level only: an indented declaration is a member, not the call a generated file writes.
HEAD = re.compile(r"^public fun (\w+)\(", re.M)


def parameter_list(text: str, start: int) -> str:
    """The text between the `(` before [start] and its matching `)`."""
    depth, index = 1, start
    while depth:
        ch = text[index]
        if ch in "([{<":
            depth += 1
        elif ch in ")]}>":
            # `->` in a function type is not a closing bracket.
            if not (ch == ">" and text[index - 1] == "-"):
                depth -= 1
        index += 1
    return text[start : index - 1]


def split_parameters(text: str) -> list[str]:
    parts, depth, current = [], 0, ""
    for index, ch in enumerate(text):
        if ch in "(<[{":
            depth += 1
        elif ch in ")]}" or (ch == ">" and text[index - 1] != "-"):
            depth -= 1
        if ch == "," and depth == 0:
            parts.append(current)
            current = ""
        else:
            current += ch
    if current.strip():
        parts.append(current)
    return parts


def parameter(text: str):
    # Annotations go (`@Composable`, `@FloatRange(from = 0.0)`); the name is the first `name:`.
    text = re.sub(r"@\w+(\([^)]*\))?\s*", "", text.strip())
    match = re.match(r"(\w+)\s*:\s*(.+?)\s*(=\s*(.+))?$", text, re.S)
    if not match:
        return None
    raw_type = " ".join(match.group(2).split())
    return {
        "name": match.group(1),
        "type": raw_type,
        "optional": match.group(3) is not None,
        "composableSlot": raw_type.endswith("-> Unit"),
    }


def overloads(sources: zipfile.ZipFile):
    for entry in sorted(sources.namelist()):
        if not entry.endswith(".kt"):
            continue
        text = sources.read(entry).decode("utf-8")
        package = re.search(r"^package\s+([\w.]+)", text, re.M)
        for match in HEAD.finditer(text):
            if match.group(1) not in LAYOUTS:
                continue
            listing = re.sub(r"//[^\n]*", "", parameter_list(text, match.end()))
            params = [parameter(p) for p in split_parameters(listing)]
            if any(p is None for p in params):
                sys.exit(f"could not read a parameter of {match.group(1)} in {entry}")
            yield match.group(1), package.group(1) if package else "", params


def main(version: str) -> None:
    url = (
        f"https://dl.google.com/android/maven2/{GROUP}/{ARTIFACT}/{version}/"
        f"{ARTIFACT}-{version}-sources.jar"
    )
    with urllib.request.urlopen(url) as response:
        sources = zipfile.ZipFile(io.BytesIO(response.read()))
    layouts: dict[str, dict] = {}
    for name, package, params in overloads(sources):
        entry = layouts.setdefault(name, {"name": name, "package": package, "overloads": []})
        signature = {"parameters": params}
        if signature not in entry["overloads"]:
            entry["overloads"].append(signature)
    missing = LAYOUTS - layouts.keys()
    if missing:
        sys.exit(f"{version} has no public {', '.join(sorted(missing))}")
    json.dump(
        {
            "schema": "ui-builder-remote-layouts/v1",
            "source": {"group": GROUP.replace("/", "."), "artifact": ARTIFACT, "version": version},
            "layouts": sorted(layouts.values(), key=lambda m: m["name"]),
        },
        sys.stdout,
        indent=2,
    )
    sys.stdout.write("\n")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: generate_remote_layouts.py <remote-creation-compose version>")
    main(sys.argv[1])
