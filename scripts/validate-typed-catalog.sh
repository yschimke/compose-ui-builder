#!/usr/bin/env bash
set -euo pipefail

# Read-only checks: no publication, installation, formatter, or saved-design mutation.
builder_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
tools_root=$(cd "${1:-$builder_root/../compose-ai-tools}" && pwd)
# Developer environments use build-brief; CI can run the same graph with plain Gradle logs.
gradle_runner=()
if command -v build-brief >/dev/null; then gradle_runner=(build-brief); fi

"${gradle_runner[@]}" "$tools_root/gradlew" -p "$tools_root/gradle-plugin" --max-workers=2 \
  :preview-discovery:test --tests '*Typed*Adapter*Test' :preview-discovery:ktfmtCheck

"${gradle_runner[@]}" "$builder_root/gradlew" -p "$builder_root" --max-workers=2 \
  -PlocalBuilds=tools "-PlocalBuild.tools=$tools_root" -PtypedAdapterCatalog=true \
  :ui-builder-renderer-sdk:jvmTest --tests '*TypedCanvasAdapterTest' \
  :ui-builder-runtime:test --tests '*TypedCatalogPublicationTest' \
  :ui-builder:jvmTest --tests '*TypedCatalogCanvasTest' \
  :ui-builder-renderer-sdk:compileKotlinWasmJs \
  :ui-builder-renderer-sdk:ktfmtCheck :ui-builder-runtime:ktfmtCheck :ui-builder:ktfmtCheck
