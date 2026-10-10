#!/usr/bin/env bash
# Explicit opt-in: these archives execute trusted catalog code in the caller's host process.
set -euo pipefail
if [ "$#" -ne 4 ]; then
  echo "Usage: $0 owner/repo full-source-commit android|jvm-linux-x64 destination" >&2
  exit 2
fi
script_root=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
download=$(mktemp -d)
trap 'rm -rf "$download"' EXIT
gh release download "typed-catalog-preview-$2" --repo "$1" --dir "$download" \
  --pattern '*.zip' --pattern bundle.json --pattern components.json --pattern ui-builder.json
python3 "$script_root/install-typed-catalog-bundle.py" "$download" "$4" "$2" "$3"
