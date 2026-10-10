#!/usr/bin/env bash
set -euo pipefail

# Read-only checks: no publication, installation, formatter, or saved-design mutation.
builder_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
tools_root=$(cd "${1:-$builder_root/../compose-ai-tools}" && pwd)
command -v build-brief >/dev/null || { echo 'build-brief must be on PATH' >&2; exit 1; }

build-brief "$tools_root/gradlew" -p "$tools_root/gradle-plugin" --max-workers=2 \
  :preview-discovery:test --tests '*Typed*Adapter*Test'

build-brief "$builder_root/gradlew" -p "$builder_root" --max-workers=2 \
  -PlocalBuilds=tools "-PlocalBuild.tools=$tools_root" -PtypedAdapterCatalog=true \
  :ui-builder-renderer-sdk:jvmTest --tests '*TypedCanvasAdapterTest' \
  :ui-builder-runtime:test --tests '*TypedCatalogPublicationTest' \
  :ui-builder:jvmTest --tests '*TypedCatalogCanvasTest' \
  :ui-builder-renderer-sdk:compileKotlinWasmJs
