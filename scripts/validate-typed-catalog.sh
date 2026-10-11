#!/usr/bin/env bash
set -euo pipefail
python3 -m unittest discover -s "$(dirname "${BASH_SOURCE[0]}")/tests" -p test_install_typed_catalog_bundle.py

# Read-only checks: no publication, installation, formatter, or saved-design mutation. The typed
# bridge is ordinary SDK source on the released tools API; pass extra Gradle arguments (for
# example `-PlocalBuilds=tools`) to check it against a tools checkout instead.
builder_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
# Developer environments use build-brief; CI can run the same graph with plain Gradle logs.
gradle_runner=()
if command -v build-brief >/dev/null; then gradle_runner=(build-brief); fi

"${gradle_runner[@]}" "$builder_root/gradlew" -p "$builder_root" --max-workers=2 "$@" \
  :ui-builder-renderer-sdk:jvmTest --tests '*TypedCanvasAdapterTest' \
  :ui-builder-runtime:test --tests '*TypedCatalogPublicationTest' \
  :ui-builder:jvmTest --tests '*TypedCatalogCanvasTest' \
  :ui-builder-renderer-sdk:compileKotlinWasmJs
