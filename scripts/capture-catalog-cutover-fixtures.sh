#!/usr/bin/env bash
# Capture what each catalog repository publishes for the UI builder, pinned to a delivery commit,
# into the test resources `CatalogCutoverReadinessTest` reads.
#
# The cutover readiness test asks one question per catalog: with the catalog-owned flag on, could
# this builder serve it from nothing but what the catalog publishes? Answering that against a
# fixture captured on a different day from the policy, the record and the seed documents would
# answer a question nobody asked, so all of a catalog's files come from ONE delivery commit, and
# `source.json` records which.
#
# Re-capturing is a reviewed change like any golden: run this, then read the test's diff. The
# test's gap ledger is expected to shrink as catalogs close their gaps; a capture that GROWS it is
# a regression in a catalog repository, not a fixture to accept.
#
#   scripts/capture-catalog-cutover-fixtures.sh            # all catalogs
#   scripts/capture-catalog-cutover-fixtures.sh wear-m3    # one builder catalog id
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out="${root}/ui-builder-runtime/src/test/resources/catalog-cutover"

# <builder catalog id> <delivery repository> <delivery system>
#
# The builder id is the policy's `catalog.id`; the delivery system names the `design-artifacts/*`
# branch. They differ for Wear (`wear-m3` is published as `wear-m3-catalog`), which is exactly the
# identity distinction compose-preview-server's entrypoint had to migrate once (#994).
catalogs=(
  "m3-catalog yschimke/m3-catalog-out m3-catalog"
  "wear-m3 yschimke/wear-m3-catalog-out wear-m3-catalog"
  "remote-m3 yschimke/remote-m3-catalog-out remote-m3"
  "a2ui-catalog yschimke/a2ui-catalog-out a2ui-catalog"
  "glimmer-catalog yschimke/glimmer-catalog-out glimmer-catalog"
  "remote-widgets yschimke/remote-m3-catalog-out remote-widgets"
  "compose-foundation yschimke/m3-catalog-out compose-foundation"
)


only="${1:-}"
for entry in "${catalogs[@]}"; do
  read -r id repo system <<<"${entry}"
  [[ -z "${only}" || "${only}" == "${id}" ]] || continue

  sha="$(git ls-remote "https://github.com/${repo}" "refs/heads/design-artifacts/${system}" | cut -f1)"
  [[ -n "${sha}" ]] || {
    echo "FAIL: ${repo} has no design-artifacts/${system} branch" >&2
    exit 1
  }
  base="https://raw.githubusercontent.com/${repo}/${sha}"
  dir="${out}/${id}"
  rm -rf "${dir}"
  mkdir -p "${dir}/designs"

  curl -fsS "${base}/ui-builder.json" | gzip -9n >"${dir}/ui-builder.json.gz"
  curl -fsS "${base}/components.json" | gzip -9n >"${dir}/components.json.gz"

  # The seed documents the policy names, verbatim. A named document that is missing from the
  # delivery branch fails the capture: the readiness test would otherwise report a template the
  # catalog declares and cannot supply as merely "no templates".
  templates="$(gzip -dc "${dir}/ui-builder.json.gz" |
    python3 -c 'import json,sys; [print(t) for t in json.load(sys.stdin)["statusSemantics"].get("templates", [])]')"
  while IFS= read -r template; do
    [[ -n "${template}" ]] || continue
    curl -fsS "${base}/${template}" >"${dir}/designs/$(basename "${template}")"
  done <<<"${templates}"

  runtime_id="$(curl -fsS "${base}/ui-builder/runtime.zip" -o "${dir}/runtime.zip.tmp" &&
    unzip -p "${dir}/runtime.zip.tmp" runtime-manifest.json |
    python3 -c 'import json,sys; print(json.load(sys.stdin)["runtimeId"])' || true)"
  rm -f "${dir}/runtime.zip.tmp"

  python3 - "${dir}/source.json" "${repo}" "${system}" "${sha}" "${runtime_id}" <<'PY'
import json, sys
path, repo, system, sha, runtime = sys.argv[1:]
json.dump(
    {
        "repository": repo,
        "branch": f"design-artifacts/{system}",
        "commit": sha,
        "rendererRuntimeId": runtime or None,
    },
    open(path, "w"),
    indent=2,
)
open(path, "a").write("\n")
PY
  echo "captured ${id} from ${repo}@${sha:0:12}"
done
