#!/usr/bin/env python3
"""Verify a published experimental catalog generation and install its paired executable ZIP."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import zipfile


def install(download, output, source, runtime):
    manifest = json.loads((download / "bundle.json").read_text())
    if manifest.get("schema") != "compose-ui-builder-executable-bundle/v1-experimental":
        raise ValueError("unsupported executable catalog manifest")
    if not re.fullmatch(r"[0-9a-f]{40}", source) or manifest["source"]["commit"] != source:
        raise ValueError("published source does not match the requested immutable commit")
    if manifest["runtime"] != runtime:
        raise ValueError("published bundle targets a different host runtime")
    archive = manifest["archive"]
    if Path(archive).name != archive or not archive.endswith(".zip"):
        raise ValueError("invalid archive name")
    required = {archive, "components.json", "ui-builder.json"}
    if set(manifest["sha256"]) != required:
        raise ValueError("manifest must cover the archive and both standard catalog files")
    for name, digest in manifest["sha256"].items():
        if not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise ValueError("invalid SHA-256 digest")
        with (download / name).open("rb") as content:
            actual = hashlib.file_digest(content, "sha256").hexdigest()
        if actual != digest:
            raise ValueError(f"checksum mismatch for {name}")
    if output.exists():
        raise ValueError("installation destination must not already exist")
    with zipfile.ZipFile(download / archive) as bundle:
        names = bundle.namelist()
        if len(names) != len(set(names)):
            raise ValueError("duplicate bundle paths")
        for entry in bundle.infolist():
            path = PurePosixPath(entry.filename)
            if path.is_absolute() or ".." in path.parts or "\\" in entry.filename:
                raise ValueError("invalid bundle path")
            if stat.S_ISLNK(entry.external_attr >> 16):
                raise ValueError("bundle links are unsupported")
        for name in ["components.json", "ui-builder.json"]:
            if bundle.read(name) != (download / name).read_bytes():
                raise ValueError("published catalog pair differs from executable bundle")
        output.mkdir(parents=True)
        try:
            bundle.extractall(output)
            (output / "bundle.json").write_text(json.dumps(manifest, indent=2) + "\n")
        except BaseException:
            shutil.rmtree(output)
            raise
    print(f"Installed {manifest['catalogId']} from {source} for {runtime} at {output}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("download", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("source", help="expected full source commit")
    parser.add_argument("runtime", choices=["android", "jvm-linux-x64"])
    args = parser.parse_args()
    install(args.download, args.output, args.source, args.runtime)
