#!/usr/bin/env python3
"""Generate the Remote modifier vocabulary from the released Remote Compose API.

The released `remote-creation-compose` sources are authoritative for which `RemoteModifier` calls
exist and what they take. This reads every public `RemoteModifier.<name>(...)` extension from the
sources jar of one release and writes the overloads a document can author: those whose required
parameters are all plain values (numbers, dp, colours, booleans, ints, strings). Parameters a
document cannot hold (lambdas, transitions, scroll state, actions) are dropped when optional, and
the overload is dropped when one is required. Interaction stays out: it is event bindings.

    python3 -I scripts/remote-vocabulary/generate_remote_modifiers.py 1.0.0-alpha20 \
      > docs/design/fixtures/ui-builder/remote-modifiers-v1.json

Re-run it for each new alpha, together with the component record, so the builder authors against
the latest released API (docs/design/UI_BUILDER_REMOTE_RICHNESS.md, "Authoring target").
"""

import io
import json
import re
import sys
import urllib.request
import zipfile

GROUP = "androidx/compose/remote"
ARTIFACT = "remote-creation-compose"

# Parameter types a document value can be written as, and the value kinds each accepts.
VALUE_TYPES = {
    "RemoteFloat": "float",
    "Float": "float",
    "RemoteDp": "dp",
    "RemoteInt": "int",
    "Int": "int",
    "RemoteBoolean": "bool",
    "Boolean": "bool",
    "RemoteColor": "color",
    "RemoteString": "string",
    "String": "string",
}

# Top-level extensions only: an indented one is a member of a row, column or list scope
# (`weight`, `alignByBaseline`, …), which the builder writes from the node's position instead.
SIGNATURE = re.compile(
    r"^public fun RemoteModifier\.(\w+)\((.*?)\)\s*:\s*RemoteModifier", re.S | re.M
)

# Behaviour, not appearance: a node's interactions are its event bindings, which the reducer
# validates against declared state. A second way to say "tappable" is how the two disagree.
INTERACTION = {
    "clickable",
    "combinedClickable",
    "onClick",
    "onTouchDown",
    "onTouchUp",
    "onTouchCancel",
    "semantics",
    "clearAndSetSemantics",
}


def split_parameters(text: str) -> list[str]:
    parts, depth, current = [], 0, ""
    for ch in text:
        if ch in "(<[{":
            depth += 1
        elif ch in ")>]}":
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
    text = re.sub(r"@\w+(\([^)]*\))?\s*", "", text.strip())
    match = re.match(r"(\w+)\s*:\s*([^=]+?)\s*(=\s*(.+))?$", text, re.S)
    if not match:
        return None
    raw_type = match.group(2).strip()
    nullable = raw_type.endswith("?")
    return {
        "name": match.group(1),
        "type": raw_type.rstrip("?"),
        "nullable": nullable,
        "optional": match.group(3) is not None,
    }


def overloads(sources: zipfile.ZipFile):
    for entry in sorted(sources.namelist()):
        if not entry.endswith(".kt"):
            continue
        text = sources.read(entry).decode("utf-8")
        package = re.search(r"^package\s+([\w.]+)", text, re.M)
        for match in SIGNATURE.finditer(text):
            if match.group(1) in INTERACTION:
                continue
            params = [parameter(p) for p in split_parameters(match.group(2))]
            if any(p is None for p in params):
                continue
            kept = []
            for p in params:
                if p["type"] in VALUE_TYPES:
                    kept.append({**p, "kind": VALUE_TYPES[p["type"]]})
                elif not p["optional"]:
                    break  # a required parameter no document value can be
            else:
                yield match.group(1), package.group(1) if package else "", kept


def main(version: str) -> None:
    url = (
        f"https://dl.google.com/android/maven2/{GROUP}/{ARTIFACT}/{version}/"
        f"{ARTIFACT}-{version}-sources.jar"
    )
    with urllib.request.urlopen(url) as response:
        sources = zipfile.ZipFile(io.BytesIO(response.read()))
    modifiers: dict[str, dict] = {}
    for name, package, params in overloads(sources):
        entry = modifiers.setdefault(name, {"name": name, "package": package, "overloads": []})
        signature = {"parameters": params}
        if signature not in entry["overloads"]:
            entry["overloads"].append(signature)
    json.dump(
        {
            "schema": "ui-builder-remote-modifiers/v1",
            "source": {"group": GROUP.replace("/", "."), "artifact": ARTIFACT, "version": version},
            "modifiers": sorted(modifiers.values(), key=lambda m: m["name"]),
        },
        sys.stdout,
        indent=2,
    )
    sys.stdout.write("\n")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: generate_remote_modifiers.py <remote-creation-compose version>")
    main(sys.argv[1])
