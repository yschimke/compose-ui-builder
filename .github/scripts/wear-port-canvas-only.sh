#!/usr/bin/env bash
#
# The CMP Wear port draws the canvas. It must never reach code generation or the native lane.
#
# `ee.schimke.wearcmp:*` is Wear Compose Material 3 / Foundation recompiled for Compose
# Multiplatform, and `:ui-builder` links it so the browser canvas can draw real Wear components
# instead of Material 3 lookalikes. That is the whole of its job.
#
# Two other lanes answer a different question and must keep answering it against the GENUINE
# AndroidX library:
#
#   - CODE GENERATION (`:ui-builder-export`) writes the Kotlin a user compiles in their own app.
#     That source names `androidx.wear.compose.material3`, and it has to be upstream's API, not a
#     port's. The exporter emits text and links neither library — the risk is not that it calls the
#     port, it is that somebody "helpfully" adds the dependency to get a symbol to import.
#
#   - NATIVE PREVIEW renders a design against a served catalog bundle on the Robolectric daemon,
#     which is where the real AARs live. That render is what the published kit rendition is measured
#     against; a port rendering it would make the comparison meaningless — the two lanes exist
#     precisely so they can disagree and the disagreement means something.
#
# So the port is allowed in exactly one module. Today that is also true structurally —
# `:ui-builder-export` and `:server` do not depend on `:ui-builder`, and `:ui-builder-render-bundle`
# takes a PNG rather than classes — but "no path exists right now" is not the same as "no path may
# be added", and this rule is one `implementation(libs.wearcmp...)` away from being lost silently.
# Nothing would fail: the export would keep emitting the same text, and the mistake would only
# surface as a user compiling generated code against an API that is not upstream's.
#
# A text scan rather than a resolved classpath, for the same reason
# `ui-builder-project-boundary.sh` gives: a declaration is what the rule is about, so a declaration
# is what gets read.
#
# Usage: .github/scripts/wear-port-canvas-only.sh
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)
cd "${repo_root}"

# The one module allowed to link the port: the canvas.
allowed_module="ui-builder"

status=0

# ── 1. Only the canvas may declare the port ───────────────────────────────────────────────────────
#
# A repository that is fenced to the port's group is not a dependency declaration: it is needed by
# the IntelliJ sandbox to resolve :ui-builder's transitive canvas dependency. Match the version
# catalog accessor used to declare the port instead, so the guard tests the rule it describes.
while IFS= read -r file; do
  file="${file#./}"
  module="${file%%/*}"
  [[ "${module}" == "${allowed_module}" ]] && continue
  # The version catalog and settings file name the port by definition; they declare nothing.
  [[ "${file}" == "gradle/libs.versions.toml" || "${file}" == "settings.gradle.kts" ]] && continue
  echo "wear-port-canvas-only: ${file} declares the CMP Wear port." >&2
  echo "  Only :${allowed_module} (the canvas) may link it. Code generation and the native lane" >&2
  echo "  must run against the real androidx.wear.compose AARs — see this script's header." >&2
  status=1
done < <(grep -rl --include='*.gradle.kts' -e 'libs\.wearcmp\.' . \
  --exclude-dir=build --exclude-dir=.git || true)

# ── 2. The exporter must not import Wear Compose at all ───────────────────────────────────────────
# It emits `androidx.wear.compose.material3` as TEXT. An actual import means it has linked some
# implementation of that package, and the only one available off Android is the port.
if [[ -d ui-builder-export/src ]]; then
  while IFS= read -r hit; do
    echo "wear-port-canvas-only: the exporter imports Wear Compose — ${hit}" >&2
    echo "  :ui-builder-export writes generated source as text and must not link the library it" >&2
    echo "  names. Emit the fully-qualified name as a string instead." >&2
    status=1
  done < <(grep -rn --include='*.kt' -e '^import androidx\.wear\.compose' ui-builder-export/src || true)
fi

if [[ "${status}" -eq 0 ]]; then
  echo "wear-port-canvas-only: the Wear port is confined to the canvas."
fi

exit "${status}"
