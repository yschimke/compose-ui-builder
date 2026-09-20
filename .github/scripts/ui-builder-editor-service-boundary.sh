#!/usr/bin/env bash
#
# :ui-builder is the editor. Hosted persistence and collaboration belong to
# :ui-builder-runtime; Desktop's deliberately local file adapter lives under the `local` package.
# Keep a second service/store implementation from quietly returning to any editor source set.
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)
cd "${repo_root}"

source_root="ui-builder/src"
status=0

while IFS= read -r hit; do
  file=${hit%%:*}
  [[ "${file}" =~ ^ui-builder/src/[^/]+Main/kotlin/ ]] || continue
  [[ "${file}" == */local/* ]] && continue
  echo "ui-builder-editor-service-boundary: hosted service/store declaration in ${hit}" >&2
  echo "  Move authoritative persistence and collaboration to :ui-builder-runtime." >&2
  status=1
done < <(
  grep -rnE --include='*.kt' \
    '\b(class|interface|object)[[:space:]]+[A-Za-z0-9_]*(DesignService|DesignStore)\b|\b(class|object)[[:space:]]+(Persistent|InMemory)[A-Za-z0-9_]*Service\b' \
    "${source_root}" || true
)

if [[ "${status}" -eq 0 ]]; then
  echo "ui-builder-editor-service-boundary: hosted persistence stays in :ui-builder-runtime."
fi

exit "${status}"
