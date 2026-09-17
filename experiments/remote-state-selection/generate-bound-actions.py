#!/usr/bin/env python3
"""Generate the isolated value-aware callback proof; no production route calls this script."""
import importlib.util
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("bound_actions", HERE / "bound-action-generator.py")
generator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(generator)
document = json.loads((HERE / "bound-actions.document.json").read_text())
for remote in (False, True):
    directory = HERE / "build/bound-action-source" / ("remote" if remote else "compose")
    directory.mkdir(parents=True, exist_ok=True)
    file = directory / ("RemoteRepeatedContent.kt" if remote else "ComposeRepeatedContent.kt")
    file.write_text(generator.generate(document, remote, "boundactions"))
    print(file)
