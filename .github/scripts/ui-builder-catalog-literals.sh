#!/usr/bin/env bash
#
# The catalogs the UI builder serves are DATA, and this is the line that keeps them that way.
#
# `docs/design/UI_BUILDER_CATALOG_CONTRACT.md` moves each catalog's knowledge — its platform, its
# shelves, its frame, how its screens are written — out of this repository's Kotlin and into the
# catalog that owns it. Phase 4 reads the published file; phase 5 deletes the Kotlin. Between those
# two the literal count only ever goes down, and this script is what makes that a fact rather than
# an intention: a pull request may remove a name from the allowlist below, and may not add one.
#
# Why a text scan. The rule is about a NAME appearing in a place, not about a type or an edge, so
# there is nothing a compiler or a classpath check could see. The sibling
# `ui-builder-project-boundary.sh` is a text scan for the same reason and this is deliberately the
# same shape.
#
# The allowlist is the phase-0 state, captured when the gate was written. Every item of phases 4 and
# 5 shrinks it; at phase 5 it is empty and this script's `allowed` array goes with it. A file that
# no longer carries a catalog name is reported as a stale entry rather than left to rot, because an
# allowlist nobody prunes stops describing anything.
#
# Usage: .github/scripts/ui-builder-catalog-literals.sh [--list]
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)
cd "${repo_root}"

# The catalog names that must not be written into these modules. `m3-catalog` is deliberately NOT
# here: it is the packaged fallback a standalone builder opens on, which the contract keeps.
names='wear-m3|remote-m3|wear-m3-catalog'

# The modules whose main sources are held to the rule. Tests are exempt: a test naming a catalog is
# describing a fixture, not building one into the product.
modules=(ui-builder ui-builder-export ui-builder-runtime server)

# Files that carried a catalog name when this gate was written. SHRINKS EVERY STEP.
allowed=(
  # Landed in #605, after this gate was written, and allowlisted rather than refused: it carries the
  # Wear widget host geometry, which is catalog knowledge and is item 17's to move into
  # `statusSemantics.frame.geometry`. #605 halved the debt already — the canvas and the native lane
  # kept separate copies of these four rows and now share one — so the remaining move is a single
  # call site rather than two. Its own docstring records the rest: wear-m3-catalog's stickers
  # hard-code the same spec, which is the transcription this contract ends.
  ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/WearWidgetHostShape.kt
  server/src/main/kotlin/ee/schimke/composeai/cli/serve/CatalogLiveRouting.kt
  server/src/main/kotlin/ee/schimke/composeai/cli/serve/ComponentRecordPacks.kt
  server/src/main/kotlin/ee/schimke/composeai/cli/serve/ComponentRecordSource.kt
  server/src/main/kotlin/ee/schimke/composeai/cli/serve/DesignCommand.kt
  server/src/main/kotlin/ee/schimke/composeai/cli/serve/LocalUiBuilder.kt
  server/src/main/kotlin/ee/schimke/composeai/cli/serve/ScreenGeneratorComposeExportExecutor.kt
  server/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeCommandOptions.kt
  server/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeHttpServer.kt
  server/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeIssueReport.kt
  server/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeOptions.kt
  server/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeRenderMatte.kt
  server/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeRunner.kt
  server/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeSites.kt
  server/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeThemeCss.kt
  server/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeUiBuilderInlineCapture.kt
  server/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeUiBuilderNativePreview.kt
  server/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeWeb.kt
  ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/InlineRemoteContentExporter.kt
  ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/RemoteContentEmitter.kt
  ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/RemoteScopes.kt
  ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderCatalogPlatform.kt
  ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderComponentPacks.kt
  ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderNewDesignSeed.kt
  ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderPreviewSurfaces.kt
  ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderTemplates.kt
  ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/WearScreenCodeExporter.kt
  ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/WearWidgetCodeExporter.kt
  ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/WearWidgetNativePreviewExporter.kt
  ui-builder-runtime/src/main/kotlin/ee/schimke/composeai/uibuilder/service/ProductionUiBuilderRuntime.kt
  ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/ComponentMenu.kt
  ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/ComponentPackRecords.kt
  ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/RemoteComposeSources.kt
  ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/StarterContent.kt
  ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderEditor.kt
  ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderEditorState.kt
  ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderInspection.kt
  ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderRenderer.kt
  ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/capability/CapabilityCatalog.kt
  ui-builder/src/jvmMain/kotlin/ee/schimke/composeai/uibuilder/ComponentPackPreviews.kt
  ui-builder/src/jvmMain/kotlin/ee/schimke/composeai/uibuilder/InlineRemoteContentPreview.kt
  ui-builder/src/jvmMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderEditorChromePreview.kt
  ui-builder/src/jvmMain/kotlin/ee/schimke/composeai/uibuilder/WearComponentPalettePreview.kt
  ui-builder/src/jvmMain/kotlin/ee/schimke/composeai/uibuilder/WearScreenSamplePreview.kt
  ui-builder/src/jvmMain/kotlin/ee/schimke/composeai/uibuilder/WearWidgetSamplePreview.kt
  ui-builder/src/wasmJsMain/kotlin/ee/schimke/composeai/uibuilder/BrowserRequestUrl.kt
  ui-builder/src/wasmJsMain/kotlin/ee/schimke/composeai/uibuilder/Main.kt
)

listing=false
[[ "${1:-}" == "--list" ]] && listing=true

found=()
for module in "${modules[@]}"; do
  while IFS= read -r file; do
    [[ -n "${file}" ]] && found+=("${file}")
  done < <(
    find "${module}/src" -type d \( -name main -o -name '*Main' \) -not -path '*Test*' 2>/dev/null |
      while read -r dir; do
        grep -rlE "${names}" --include='*.kt' "${dir}" 2>/dev/null || true
      done | sort -u
  )
done

if [[ "${listing}" == true ]]; then
  printf '%s\n' "${found[@]}" | sort -u
  exit 0
fi

status=0

# A name in a file nobody allowed: the thing this gate exists to stop.
for file in $(printf '%s\n' "${found[@]}" | sort -u); do
  permitted=false
  for entry in "${allowed[@]}"; do
    [[ "${file}" == "${entry}" ]] && permitted=true && break
  done
  if [[ "${permitted}" == false ]]; then
    if [[ ${status} -eq 0 ]]; then
      echo "ui-builder-catalog-literals: a catalog name was written into a module that must not know one." >&2
      echo "  Catalog knowledge belongs in the catalog's own ui-builder.policy.json — see" >&2
      echo "  docs/design/UI_BUILDER_CATALOG_CONTRACT.md. Read it from the published catalog instead." >&2
      echo "" >&2
    fi
    echo "  ${file}" >&2
    grep -nE "${names}" "${file}" | head -3 | sed 's/^/      /' >&2
    status=1
  fi
done

# An allowlist entry that no longer needs to be there. Reported, never fatal: the pull request that
# cleaned the file up is not the one that should have to notice.
for entry in "${allowed[@]}"; do
  still=false
  for file in "${found[@]}"; do
    [[ "${file}" == "${entry}" ]] && still=true && break
  done
  if [[ "${still}" == false ]]; then
    echo "ui-builder-catalog-literals: ${entry} no longer names a catalog — remove it from the allowlist." >&2
  fi
done

if [[ ${status} -eq 0 ]]; then
  echo "ui-builder-catalog-literals: ${#allowed[@]} allowlisted file(s), no new catalog names."
fi
exit ${status}
