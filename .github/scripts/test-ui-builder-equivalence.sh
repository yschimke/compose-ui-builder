#!/usr/bin/env bash
#
# Tests for ui-builder-equivalence.sh. A readiness gate that silently passes is worth nothing, so
# the three answers it has to be able to give each get a case: "not yet", "these differ", and
# "somebody read that difference and accepted it".
#
# Usage: .github/scripts/test-ui-builder-equivalence.sh
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)
gate="${repo_root}/.github/scripts/ui-builder-equivalence.sh"
work=$(mktemp -d)
trap 'rm -rf "${work}"' EXIT

failures=0
check() {
  local name="$1" expected="$2" actual="$3"
  if [[ "${expected}" == "${actual}" ]]; then
    echo "ok   ${name}"
  else
    echo "FAIL ${name}: expected ${expected}, got ${actual}"
    failures=$((failures + 1))
  fi
}

cat >"${work}/golden.json" <<'JSON'
{ "benchmark": { "catalogSystemId": "wear-m3" },
  "statusSemantics": { "platform": "wear", "componentMenu": { "groupOrder": ["A", "B"] } } }
JSON

# Same semantics, different catalog. Every compared field agrees; only the id says otherwise.
cat >"${work}/impostor.json" <<'JSON'
{ "schema": "compose-ui-builder-policy/v1", "catalogId": "wear-m3-tv", "platform": "wear",
  "menu": { "groupOrder": ["A", "B"] } }
JSON

# Declares no id — legitimate for a module defaulting it from its cover sheet.
cat >"${work}/unnamed.json" <<'JSON'
{ "schema": "compose-ui-builder-policy/v1", "platform": "wear",
  "menu": { "groupOrder": ["A", "B"] } }
JSON

# A waiver for a field that now AGREES with the frozen catalog.
cat >"${work}/converged.json" <<'JSON'
[ { "field": "componentMenu.groupOrder", "why": "reviewed back when these disagreed",
    "policy": ["B", "A"], "frozen": ["A", "B"] } ]
JSON

# previewSurfaces: the golden claims an android backend, this policy claims desktop.
cat >"${work}/surfaces-golden.json" <<'JSON'
{ "benchmark": { "catalogSystemId": "wear-m3" },
  "statusSemantics": { "platform": "wear", "componentMenu": { "groupOrder": ["A"] },
    "previewSurfaces": { "native": { "fidelity": "authoritative", "backend": "android" } } } }
JSON

cat >"${work}/surfaces-policy.json" <<'JSON'
{ "schema": "compose-ui-builder-policy/v1", "catalogId": "wear-m3", "platform": "wear",
  "menu": { "groupOrder": ["A"] },
  "previewSurfaces": { "native": { "fidelity": "authoritative", "backend": "desktop" } } }
JSON

cat >"${work}/agrees.json" <<'JSON'
{ "schema": "compose-ui-builder-policy/v1", "catalogId": "wear-m3", "platform": "wear",
  "menu": { "groupOrder": ["A", "B"] } }
JSON

cat >"${work}/differs.json" <<'JSON'
{ "schema": "compose-ui-builder-policy/v1", "catalogId": "wear-m3", "platform": "wear",
  "menu": { "$comment": "prose is never compared", "groupOrder": ["B", "A"] } }
JSON

cat >"${work}/accepted.json" <<'JSON'
[ { "field": "componentMenu.groupOrder", "why": "the catalog's own sections, deliberately",
    "policy": ["B", "A"], "frozen": ["A", "B"] } ]
JSON

cat >"${work}/unpinned.json" <<'JSON'
[ { "field": "componentMenu.groupOrder", "why": "no reviewed value recorded" } ]
JSON

cat >"${work}/stale.json" <<'JSON'
[ { "field": "componentMenu.groupOrder", "why": "reviewed something else",
    "policy": ["C", "D"], "frozen": ["A", "B"] } ]
JSON

# States nothing the golden states. The gate must not call this ready.
cat >"${work}/silent.json" <<'JSON'
{ "schema": "compose-ui-builder-policy/v1", "catalogId": "wear-m3", "platform": "wear" }
JSON

cat >"${work}/unexplained.json" <<'JSON'
[ { "field": "componentMenu.groupOrder", "policy": ["B", "A"], "frozen": ["A", "B"] } ]
JSON

cat >"${work}/frozen-moved.json" <<'JSON'
[ { "field": "componentMenu.groupOrder", "why": "reviewed against a different frozen value",
    "policy": ["B", "A"], "frozen": ["X", "Y"] } ]
JSON

# The published shape: the same facts under statusSemantics, with `menu` called `componentMenu`.
cat >"${work}/published.json" <<'JSON'
{ "schema": "compose-ui-builder-catalog/v1", "catalog": { "id": "wear-m3" },
  "statusSemantics": { "platform": "wear", "componentIdPrefix": "wear-m3/",
    "componentMenu": { "groupOrder": ["A", "B"] } } }
JSON

# The same facts as the golden, with object members in the other order.
cat >"${work}/reordered-golden.json" <<'JSON'
{ "statusSemantics": { "platform": "wear",
  "frame": { "adapter": "frame/round-screen",
    "geometry": { "contentPadding": [ { "screenDp": 192, "horizontalDp": 10 } ] } } } }
JSON

cat >"${work}/reordered-policy.json" <<'JSON'
{ "schema": "compose-ui-builder-policy/v1", "platform": "wear",
  "frame": { "adapter": "frame/round-screen",
    "geometry": { "$comment": "prose", "contentPadding": [ { "horizontalDp": 10, "screenDp": 192 } ] } } }
JSON

set +e
"${gate}" --policy "${work}/absent.json" --golden "${work}/golden.json" >"${work}/out" 2>&1
check "a catalog with no policy reports not-yet rather than failing" 0 $?
grep -q "does not describe itself yet" "${work}/out" ||
  { echo "FAIL missing-policy message"; failures=$((failures + 1)); }

"${gate}" --policy "${work}/agrees.json" --golden "${work}/golden.json" --strict >/dev/null 2>&1
check "agreement passes under --strict" 0 $?

"${gate}" --policy "${work}/differs.json" --golden "${work}/golden.json" >"${work}/out" 2>&1
check "a difference is reported without --strict, and does not fail" 0 $?
grep -q "1 difference(s)" "${work}/out" ||
  { echo "FAIL difference not counted"; failures=$((failures + 1)); }

"${gate}" --policy "${work}/differs.json" --golden "${work}/golden.json" --strict >/dev/null 2>&1
check "a difference fails under --strict" 1 $?

"${gate}" --policy "${work}/differs.json" --golden "${work}/golden.json" \
  --differences "${work}/accepted.json" --strict >"${work}/out" 2>&1
check "an accepted difference passes under --strict" 0 $?
grep -q "the catalog's own sections, deliberately" "${work}/out" ||
  { echo "FAIL the accepted reason is not printed"; failures=$((failures + 1)); }

"${gate}" --policy "${work}/agrees.json" --golden "${work}/missing-golden.json" >/dev/null 2>&1
check "a missing golden is a usage error, not a pass" 2 $?

# A gate that answers "ready" for a policy stating nothing is worse than no gate: it answers the
# question wrongly rather than declining to answer it.
"${gate}" --policy "${work}/silent.json" --golden "${work}/golden.json" --strict >"${work}/out" 2>&1
check "a policy silent about a fact the golden states fails --strict" 1 $?
grep -q "the catalog is silent" "${work}/out" ||
  { echo "FAIL silence not reported"; failures=$((failures + 1)); }

# An exemption that outlives the thing it exempted approves every future value of the field —
# an accidentally emptied menu passing under the entry that reviewed a deliberate reordering.
"${gate}" --policy "${work}/differs.json" --golden "${work}/golden.json" \
  --differences "${work}/unpinned.json" --strict >"${work}/out" 2>&1
check "an exemption naming no reviewed value fails --strict" 1 $?
grep -q "names no reviewed policy value" "${work}/out" ||
  { echo "FAIL unpinned exemption not reported"; failures=$((failures + 1)); }

"${gate}" --policy "${work}/differs.json" --golden "${work}/golden.json" \
  --differences "${work}/stale.json" --strict >"${work}/out" 2>&1
check "an exemption whose reviewed value has changed fails --strict" 1 $?
grep -q "catalog changed since this was accepted" "${work}/out" ||
  { echo "FAIL stale exemption not reported"; failures=$((failures + 1)); }

# Two generators emitting one object in different insertion orders is not a difference, and a
# gate that said it was would block the cutover on nothing.
"${gate}" --policy "${work}/reordered-policy.json" --golden "${work}/reordered-golden.json" \
  --strict >"${work}/out" 2>&1
check "object key order is not a difference" 0 $?

# An exemption with the values but no reason is the failure the `why` field exists to prevent.
"${gate}" --policy "${work}/differs.json" --golden "${work}/golden.json" \
  --differences "${work}/unexplained.json" --strict >"${work}/out" 2>&1
check "an exemption with no why fails --strict" 1 $?
grep -q "carries no \`why\`" "${work}/out" ||
  { echo "FAIL unexplained exemption not reported"; failures=$((failures + 1)); }

# Pinning only the catalog side leaves the frozen side free to move under a waiver that no longer
# describes the discrepancy it waived.
"${gate}" --policy "${work}/differs.json" --golden "${work}/golden.json" \
  --differences "${work}/frozen-moved.json" --strict >"${work}/out" 2>&1
check "an exemption whose reviewed frozen value has changed fails --strict" 1 $?
grep -q "frozen catalog changed" "${work}/out" ||
  { echo "FAIL moved frozen side not reported"; failures=$((failures + 1)); }

# The generated artifact is the shape the cutover fetches; the authored policy is all a catalog has
# before the pipeline release. Both have to work, or the gate is useless at one end or the other.
"${gate}" --policy "${work}/published.json" --golden "${work}/golden.json" --strict >"${work}/out" 2>&1
check "the generated ui-builder.json shape is read too" 0 $?
grep -q "as a generated ui-builder.json" "${work}/out" ||
  { echo "FAIL published shape not detected"; failures=$((failures + 1)); }

# --strict is the cutover asserting readiness; "there is no catalog" must not read as success.
"${gate}" --policy "${work}/absent.json" --golden "${work}/golden.json" --strict >/dev/null 2>&1
check "a missing policy fails --strict" 1 $?

# Semantics do not identify a catalog. A second `wear` catalog agreeing on every compared field is
# still the wrong file, and "you fetched a real path belonging to somebody else" has to be as loud
# as "you fetched nothing".
"${gate}" --policy "${work}/impostor.json" --golden "${work}/golden.json" --strict >"${work}/out" 2>&1
check "a policy for another catalog fails --strict despite agreeing on everything" 1 $?
grep -q "a policy for another catalog was read" "${work}/out" ||
  { echo "FAIL wrong catalog not reported"; failures=$((failures + 1)); }

# Defaulting the id from the cover sheet is legitimate, so the caller can supply it — but only the
# caller can, and without it the gate must decline to assert readiness rather than assume.
"${gate}" --policy "${work}/unnamed.json" --golden "${work}/golden.json" --strict >"${work}/out" 2>&1
check "an unidentifiable catalog fails --strict" 1 $?
grep -q "there is nothing to check against" "${work}/out" ||
  { echo "FAIL unidentified catalog not reported"; failures=$((failures + 1)); }

"${gate}" --policy "${work}/unnamed.json" --golden "${work}/golden.json"   --catalog-id wear-m3 --strict >"${work}/out" 2>&1
check "--catalog-id supplies the id a policy defaults" 0 $?

"${gate}" --policy "${work}/unnamed.json" --golden "${work}/golden.json"   --catalog-id wear-m3-tv --strict >/dev/null 2>&1
check "--catalog-id naming the wrong catalog still fails --strict" 1 $?

# `--catalog-id` is an assertion, not a fallback. Treating it as a fallback meant the caller's
# explicit target vanished the moment the file declared anything — so asking for one catalog while
# holding another's policy AND its matching golden passed, because those two agree with each other.
"${gate}" --policy "${work}/agrees.json" --golden "${work}/golden.json" \
  --catalog-id remote-m3 --strict >"${work}/out" 2>&1
check "--catalog-id disagreeing with a self-consistent pair fails --strict" 1 $?
grep -q "the wrong pair of files was fetched" "${work}/out" ||
  { echo "FAIL mis-fetched pair not reported"; failures=$((failures + 1)); }

# A capability document names its catalog at `benchmark.catalogSystemId`. It plainly identifies
# itself, so it must not read as unidentified.
cat >"${work}/capability-shaped.json" <<'JSON'
{ "benchmark": { "catalogSystemId": "wear-m3" },
  "statusSemantics": { "platform": "wear", "componentMenu": { "groupOrder": ["A", "B"] } } }
JSON
"${gate}" --policy "${work}/capability-shaped.json" --golden "${work}/golden.json" \
  --strict >"${work}/out" 2>&1
check "a document naming its catalog at benchmark.catalogSystemId is identified" 0 $?

# The other way a waiver outlives its disagreement: both sides go SILENT. The entry then survives
# to re-authorise the exact old discrepancy the day it returns.
cat >"${work}/absent-both.json" <<'JSON'
{ "schema": "compose-ui-builder-policy/v1", "catalogId": "wear-m3", "platform": "wear",
  "menu": { "groupOrder": ["A", "B"] } }
JSON
cat >"${work}/waiver-for-absent.json" <<'JSON'
[ { "field": "colorTokens.roles", "why": "reviewed back when both sides had one",
    "policy": ["primary"], "frozen": ["secondary"] } ]
JSON
"${gate}" --policy "${work}/absent-both.json" --golden "${work}/golden.json" \
  --differences "${work}/waiver-for-absent.json" --strict >"${work}/out" 2>&1
check "a waiver for a field neither side states fails --strict" 1 $?
grep -q "is obsolete and should be deleted" "${work}/out" ||
  { echo "FAIL both-absent waiver not reported"; failures=$((failures + 1)); }

# A future MAJOR is refused rather than compared. Comparing the fields it happens to recognise
# would report readiness for semantics this gate does not know.
cat >"${work}/future-major.json" <<'JSON'
{ "schema": "compose-ui-builder-policy/v2", "catalogId": "wear-m3", "platform": "wear",
  "menu": { "groupOrder": ["A", "B"] } }
JSON
"${gate}" --policy "${work}/future-major.json" --golden "${work}/golden.json" \
  --strict >"${work}/out" 2>&1
check "a future schema major is refused under --strict" 1 $?
grep -q "future major" "${work}/out" ||
  { echo "FAIL future major not reported"; failures=$((failures + 1)); }

# The current major still passes, so the refusal is a version check and not a schema allowlist that
# breaks on the next minor.
"${gate}" --policy "${work}/agrees.json" --golden "${work}/golden.json" --strict >/dev/null 2>&1
check "the current schema major still passes" 0 $?

# The THIRD way a waiver outlives its disagreement: the frozen catalog stops stating the field while
# the policy keeps it. Regenerating the goldens can do exactly that.
cat >"${work}/policy-only-golden.json" <<'JSON'
{ "benchmark": { "catalogSystemId": "wear-m3" },
  "statusSemantics": { "platform": "wear" } }
JSON
cat >"${work}/waiver-for-policy-only.json" <<'JSON'
[ { "field": "componentMenu.groupOrder", "why": "reviewed back when the frozen catalog had one",
    "policy": ["A", "B"], "frozen": ["A", "B"] } ]
JSON
"${gate}" --policy "${work}/agrees.json" --golden "${work}/policy-only-golden.json" \
  --differences "${work}/waiver-for-policy-only.json" --strict >"${work}/out" 2>&1
check "a waiver pinning a frozen value the frozen catalog dropped fails --strict" 1 $?
grep -q "frozen catalog changed since this was accepted" "${work}/out" ||
  { echo "FAIL dropped frozen value not reported"; failures=$((failures + 1)); }

# The same shape, reviewed honestly: `"frozen": null` is somebody saying they looked at a fact the
# frozen catalog cannot check. That is the ONLY way a policy-only fact passes --strict now.
cat >"${work}/waiver-frozen-null.json" <<'JSON'
[ { "field": "componentMenu.groupOrder", "why": "the frozen catalog states no menu; read against the catalog's own sections",
    "policy": ["A", "B"], "frozen": null } ]
JSON
"${gate}" --policy "${work}/agrees.json" --golden "${work}/policy-only-golden.json" \
  --differences "${work}/waiver-frozen-null.json" --strict >"${work}/out" 2>&1
check "a policy-only fact passes once reviewed with \"frozen\": null" 0 $?

# …and without any entry it blocks, because nothing has checked it. This is the mirror of the
# silence check: one catches a catalog that describes too little, this one a catalog that describes
# something WRONG.
"${gate}" --policy "${work}/agrees.json" --golden "${work}/policy-only-golden.json" \
  --strict >"${work}/out" 2>&1
check "an unreviewed policy-only fact fails --strict" 1 $?
grep -q "the frozen catalog says nothing, so" "${work}/out" ||
  { echo "FAIL unreviewed policy-only fact not reported"; failures=$((failures + 1)); }

# A future major on the FROZEN side is as unreadable as one on the policy side, and the golden is
# the likelier of the two to move — it is regenerated from this repository's own types.
cat >"${work}/future-golden.json" <<'JSON'
{ "schema": "compose-ui-builder-capabilities/v2",
  "benchmark": { "catalogSystemId": "wear-m3" },
  "statusSemantics": { "platform": "wear", "componentMenu": { "groupOrder": ["A", "B"] } } }
JSON
"${gate}" --policy "${work}/agrees.json" --golden "${work}/future-golden.json" \
  --strict >"${work}/out" 2>&1
check "a future schema major on the frozen catalog is refused" 1 $?
grep -q "frozen catalog is" "${work}/out" ||
  { echo "FAIL future golden major not reported"; failures=$((failures + 1)); }

# An unrecognised family is refused for the same reason a future major is: a misspelled
# `compose-ui-builder-polciy` still spells `platform` and `componentMenu` the way this gate reads
# them, so every comparison passes and readiness is reported for a document it has never heard of.
cat >"${work}/foreign-schema.json" <<'JSON'
{ "schema": "compose-ui-builder-polciy/v999", "catalogId": "wear-m3", "platform": "wear",
  "menu": { "groupOrder": ["A", "B"] } }
JSON
"${gate}" --policy "${work}/foreign-schema.json" --golden "${work}/golden.json" \
  --strict >"${work}/out" 2>&1
check "an unrecognised schema family is refused" 1 $?
grep -q "not a family this" "${work}/out" ||
  { echo "FAIL unrecognised family not reported"; failures=$((failures + 1)); }

# A schema-less document is NOT refused — capability fixtures legitimately carry none, and refusing
# them would be an allowlist rather than a version check.
"${gate}" --policy "${work}/capability-shaped.json" --golden "${work}/golden.json" \
  --strict >/dev/null 2>&1
check "a document with no schema at all is still read" 0 $?

# Every waiver is judged means EVERY waiver — including one naming a field nothing compares, which
# the sweep over compared fields could never reach.
cat >"${work}/waiver-unknown-field.json" <<'JSON'
[ { "field": "colorTokens.rolez", "why": "a typo nobody noticed",
    "policy": ["primary"], "frozen": ["secondary"] } ]
JSON
"${gate}" --policy "${work}/agrees.json" --golden "${work}/golden.json" \
  --differences "${work}/waiver-unknown-field.json" --strict >"${work}/out" 2>&1
check "a waiver naming no compared field fails --strict" 1 $?
grep -q "no such compared field" "${work}/out" ||
  { echo "FAIL unknown-field waiver not reported"; failures=$((failures + 1)); }

# A schema that is PRESENT and unreadable is refused: `family-version` says nothing this gate can
# check, and it previously skipped every schema check and let matching fields report readiness.
cat >"${work}/malformed-schema.json" <<'JSON'
{ "schema": "compose-ui-builder-policy-v999", "catalogId": "wear-m3", "platform": "wear",
  "menu": { "groupOrder": ["A", "B"] } }
JSON
"${gate}" --policy "${work}/malformed-schema.json" --golden "${work}/golden.json" \
  --strict >"${work}/out" 2>&1
check "a schema that is not family/version is refused" 1 $?
grep -q "not" "${work}/out" ||
  { echo "FAIL malformed schema not reported"; failures=$((failures + 1)); }

# A policy with NO schema is refused too — only a capability document may omit one.
cat >"${work}/no-schema-policy.json" <<'JSON'
{ "catalogId": "wear-m3", "platform": "wear", "menu": { "groupOrder": ["A", "B"] } }
JSON
"${gate}" --policy "${work}/no-schema-policy.json" --golden "${work}/golden.json" \
  --strict >"${work}/out" 2>&1
check "a policy declaring no schema is refused" 1 $?
grep -q "only a capability document may" "${work}/out" ||
  { echo "FAIL missing schema not reported"; failures=$((failures + 1)); }

# A waiver outlives its disagreement. Left valid, it would silently re-authorise a return to the
# exact value it once waived, with nobody re-reading it.
"${gate}" --policy "${work}/agrees.json" --golden "${work}/golden.json"   --differences "${work}/converged.json" --strict >"${work}/out" 2>&1
check "a waiver for a field that now agrees fails --strict" 1 $?
grep -q "is obsolete and should be deleted" "${work}/out" ||
  { echo "FAIL converged waiver not reported"; failures=$((failures + 1)); }

# previewSurfaces chooses the native backend at phase 4. A field left out of the comparison cannot
# differ, and a field that cannot differ is not being checked.
"${gate}" --policy "${work}/surfaces-policy.json" --golden "${work}/surfaces-golden.json" \
  --strict >"${work}/out" 2>&1
check "a preview surface claiming the wrong backend fails --strict" 1 $?
grep -q "previewSurfaces" "${work}/out" ||
  { echo "FAIL previewSurfaces not compared"; failures=$((failures + 1)); }
# The rest of what phase 4 consumes. Each was absent from the comparison, so each could be anything
# at all while `platform` and the shelf order agreed — the same "a field that cannot differ is not
# being checked" as previewSurfaces above, three more times.
cat >"${work}/consumed.json" <<'JSON'
{ "schema": "compose-ui-builder-policy/v1", "catalogId": "wear-m3", "platform": "wear",
  "menu": { "groupOrder": ["A", "B"] },
  "code": { "strategy": "templates" },
  "templates": ["ui-builder/designs/blank.json"],
  "frame": { "seedDevice": "id:wearos_small_round" } }
JSON
"${gate}" --policy "${work}/consumed.json" --golden "${work}/golden.json" \
  --strict >"${work}/out" 2>&1
check "the code, templates and seed device a catalog states are reviewed" 1 $?
for field in "code" "templates" "frame.seedDevice"; do
  grep -q "x ${field}: stated as" "${work}/out" ||
    { echo "FAIL ${field} not compared"; failures=$((failures + 1)); }
done

# And once read, they pass — a fact the frozen catalog has no opinion about is reviewable like any
# other, not permanently blocking.
cat >"${work}/consumed-reviewed.json" <<'JSON'
[ { "field": "code", "why": "the emitter strategy this catalog owns", "frozen": null,
    "policy": { "strategy": "templates" } },
  { "field": "templates", "why": "its own seed design", "frozen": null,
    "policy": ["ui-builder/designs/blank.json"] },
  { "field": "frame.seedDevice", "why": "the device a new design opens on", "frozen": null,
    "policy": "id:wearos_small_round" } ]
JSON
"${gate}" --policy "${work}/consumed.json" --golden "${work}/golden.json" \
  --differences "${work}/consumed-reviewed.json" --strict >/dev/null 2>&1
check "the same three pass once reviewed with \"frozen\": null" 0 $?

# The shape is detected structurally, so a KNOWN family is not the same as the RIGHT one: this is an
# authored policy wearing the generated catalog's label, and it was compared field by field and
# passed.
cat >"${work}/mislabelled.json" <<'JSON'
{ "schema": "compose-ui-builder-catalog/v1", "catalogId": "wear-m3", "platform": "wear",
  "menu": { "groupOrder": ["A", "B"] } }
JSON
"${gate}" --policy "${work}/mislabelled.json" --golden "${work}/golden.json" \
  --strict >"${work}/out" 2>&1
check "a schema family that does not match the shape is refused" 1 $?
grep -q "but its shape is" "${work}/out" ||
  { echo "FAIL mislabelled family not reported"; failures=$((failures + 1)); }

# A capability document is identified by `benchmark.catalogSystemId`; `catalog.id` is ordinary
# payload in that shape. Reading the identifiers in the author's order rather than the document's
# let the payload shadow the identity, and the gate approved the wrong catalog through the one check
# that exists to stop exactly that.
cat >"${work}/two-names.json" <<'JSON'
{ "benchmark": { "catalogSystemId": "remote-m3" }, "catalog": { "id": "wear-m3" },
  "statusSemantics": { "platform": "wear", "componentMenu": { "groupOrder": ["A", "B"] } } }
JSON
"${gate}" --policy "${work}/two-names.json" --golden "${work}/golden.json" \
  --catalog-id wear-m3 --strict >"${work}/out" 2>&1
check "a document naming two catalogs fails --strict" 1 $?
grep -q "names two different catalogs" "${work}/out" ||
  { echo "FAIL conflicting identifiers not reported"; failures=$((failures + 1)); }
grep -q 'identified by `benchmark.catalogSystemId` as "remote-m3"' "${work}/out" ||
  { echo "FAIL identity not taken from the shape's own field"; failures=$((failures + 1)); }

# The fallback survives the shape rule: a document whose own shape-field is empty is still
# identified by whatever it does name. Selecting by shape must not turn "names itself elsewhere"
# into "unidentified", which is a refusal under --strict.
cat >"${work}/fallback-name.json" <<'JSON'
{ "benchmark": { "renderedAt": "never" }, "catalog": { "id": "wear-m3" },
  "statusSemantics": { "platform": "wear", "componentMenu": { "groupOrder": ["A", "B"] } } }
JSON
"${gate}" --policy "${work}/fallback-name.json" --golden "${work}/golden.json" \
  --strict >"${work}/out" 2>&1
check "a shape whose own id field is empty is still identified" 0 $?
grep -q 'catalog id (declared: "wear-m3")' "${work}/out" ||
  { echo "FAIL fallback identifier not used"; failures=$((failures + 1)); }

# Two entries for one field: a `Map` keeps the last and drops the first silently, so the rule this
# gate is built around — every waiver is judged — stopped holding the moment somebody pasted one
# twice. The dropped half is the one that would have failed.
cat >"${work}/duplicate-waiver.json" <<'JSON'
[ { "field": "componentMenu.groupOrder", "why": "an older decision nobody removed",
    "policy": ["Z"], "frozen": ["A", "B"] },
  { "field": "componentMenu.groupOrder", "why": "the catalog's own sections, deliberately",
    "policy": ["B", "A"], "frozen": ["A", "B"] } ]
JSON
"${gate}" --policy "${work}/differs.json" --golden "${work}/golden.json" \
  --differences "${work}/duplicate-waiver.json" --strict >"${work}/out" 2>&1
check "two accepted differences for one field fail --strict" 1 $?
grep -q "named by more than one accepted difference" "${work}/out" ||
  { echo "FAIL duplicate waiver not reported"; failures=$((failures + 1)); }

# A generated catalog's per-component shelves, which only `groupOrder` used to stand in for. Moving
# every component to a different shelf, or dropping a `variantProperty`, left the section headings
# matching and the insert panel unrecognisable.
cat >"${work}/menu-golden.json" <<'JSON'
{ "benchmark": { "catalogSystemId": "wear-m3" },
  "statusSemantics": { "platform": "wear",
    "componentMenu": { "groupOrder": ["A", "B"],
      "components": { "wear-m3/button": { "group": "A", "variantProperty": "variant" },
                      "wear-m3/card": { "group": "B" },
                      "layout/box": { "group": "A" } } } } }
JSON

# Same shelves as the golden for the two components it publishes; the builder's own `layout/box` is
# not this catalog's to state and its absence is not a difference.
cat >"${work}/menu-agreeing.json" <<'JSON'
{ "schema": "compose-ui-builder-catalog/v1", "catalog": { "id": "wear-m3" },
  "statusSemantics": { "platform": "wear", "componentIdPrefix": "wear-m3/",
    "componentMenu": { "groupOrder": ["A", "B"],
      "components": { "wear-m3/button": { "group": "A", "variantProperty": "variant" },
                      "wear-m3/card": { "group": "B" } } } } }
JSON
"${gate}" --policy "${work}/menu-agreeing.json" --golden "${work}/menu-golden.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --strict >"${work}/out" 2>&1
check "a generated menu agreeing with the frozen one passes" 0 $?
grep -q "2 shelf assignment(s) agree" "${work}/out" ||
  { echo "FAIL agreeing shelves not summarised"; failures=$((failures + 1)); }

# One component moved to another shelf, one variantProperty dropped.
cat >"${work}/menu-moved.json" <<'JSON'
{ "schema": "compose-ui-builder-catalog/v1", "catalog": { "id": "wear-m3" },
  "statusSemantics": { "platform": "wear", "componentIdPrefix": "wear-m3/",
    "componentMenu": { "groupOrder": ["A", "B"],
      "components": { "wear-m3/button": { "group": "A" },
                      "wear-m3/card": { "group": "A" } } } } }
JSON
"${gate}" --policy "${work}/menu-moved.json" --golden "${work}/menu-golden.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --strict >"${work}/out" 2>&1
check "a component moved to another shelf fails --strict" 1 $?
grep -q "componentMenu.components.wear-m3/card" "${work}/out" ||
  { echo "FAIL moved shelf not reported"; failures=$((failures + 1)); }
grep -q "componentMenu.components.wear-m3/button" "${work}/out" ||
  { echo "FAIL dropped variantProperty not reported"; failures=$((failures + 1)); }

# And a shelf move is reviewable like any other difference, with both sides pinned.
cat >"${work}/menu-reviewed.json" <<'JSON'
[ { "field": "componentMenu.components.wear-m3/card", "why": "moved on purpose at cutover",
    "policy": { "group": "A" }, "frozen": { "group": "B" } },
  { "field": "componentMenu.components.wear-m3/button", "why": "variants land in phase 5",
    "policy": { "group": "A" }, "frozen": { "group": "A", "variantProperty": "variant" } } ]
JSON
"${gate}" --policy "${work}/menu-moved.json" --golden "${work}/menu-golden.json" \
  --differences "${work}/menu-reviewed.json" --catalog-id wear-m3 --component-id-prefix wear-m3/ \
  --strict >"${work}/out" 2>&1
check "a reviewed shelf move passes --strict" 0 $?

# An authored policy states no per-component shelves at all — they come from the record's
# @CatalogGroup, not from the policy — so this comparison must not turn every authored catalog into
# a wall of unstated facts.
"${gate}" --policy "${work}/agrees.json" --golden "${work}/golden.json" --strict >"${work}/out" 2>&1
check "an authored policy is not asked for per-component shelves" 0 $?
grep -q "componentMenu.components" "${work}/out" &&
  { echo "FAIL authored policy asked for shelves"; failures=$((failures + 1)); }

# A builtin the frozen catalog has no component for was counted straight into `differences`, outside
# the model every other discrepancy goes through — so it could not be waived at all, and an entry
# naming `builtins` was then reported a SECOND time as a waiver naming no compared field. The gate
# asked for a review decision and refused the only place to record one.
cat >"${work}/builtin-golden.json" <<'JSON'
{ "benchmark": { "catalogSystemId": "wear-m3" },
  "components": [ { "componentId": "wear-m3/screen-scaffold" } ],
  "statusSemantics": { "platform": "wear", "componentMenu": { "groupOrder": ["A"] } } }
JSON
cat >"${work}/builtin-policy.json" <<'JSON'
{ "schema": "compose-ui-builder-policy/v1", "catalogId": "wear-m3", "platform": "wear",
  "menu": { "groupOrder": ["A"] },
  "builtins": { "wear-m3/screen-scaffold": { "role": "screen-root" },
                "wear-m3/new-thing": { "role": "list" } } }
JSON
"${gate}" --policy "${work}/builtin-policy.json" --golden "${work}/builtin-golden.json" \
  --strict >"${work}/out" 2>&1
check "an unknown builtin fails --strict" 1 $?
grep -q "x builtins.wear-m3/new-thing: stated as" "${work}/out" ||
  { echo "FAIL unknown builtin not reported per id"; failures=$((failures + 1)); }

# Per id, and reviewable exactly like any other fact the frozen catalog cannot check.
cat >"${work}/builtin-reviewed.json" <<'JSON'
[ { "field": "builtins.wear-m3/new-thing", "why": "a deliberate addition, reviewed",
    "policy": { "role": "list" }, "frozen": null } ]
JSON
"${gate}" --policy "${work}/builtin-policy.json" --golden "${work}/builtin-golden.json" \
  --differences "${work}/builtin-reviewed.json" --strict >"${work}/out" 2>&1
check "a reviewed builtin addition passes --strict" 0 $?

# And the waiver goes stale when the builtin it describes is gone, rather than sitting there ready
# to re-authorise it.
cat >"${work}/builtin-dropped.json" <<'JSON'
{ "schema": "compose-ui-builder-policy/v1", "catalogId": "wear-m3", "platform": "wear",
  "menu": { "groupOrder": ["A"] },
  "builtins": { "wear-m3/screen-scaffold": { "role": "screen-root" } } }
JSON
"${gate}" --policy "${work}/builtin-dropped.json" --golden "${work}/builtin-golden.json" \
  --differences "${work}/builtin-reviewed.json" --strict >"${work}/out" 2>&1
check "a waiver for a builtin no longer declared fails --strict" 1 $?

# The other end of the menu. Sweeping only the catalog's own keys detected a shelf that MOVED and
# not one that VANISHED, so a generated menu omitting a component — an empty map included — matched
# nothing, reached no field, and passed while that component lost its shelf at cutover.
cat >"${work}/drop-golden.json" <<'JSON'
{ "benchmark": { "catalogSystemId": "wear-m3" },
  "statusSemantics": { "platform": "wear",
    "componentMenu": { "groupOrder": ["A"],
      "components": { "wear-m3/button": { "group": "A" },
                      "wear-m3/card": { "group": "A" },
                      "layout/box": { "group": "A" } } } } }
JSON
cat >"${work}/drop-policy.json" <<'JSON'
{ "schema": "compose-ui-builder-catalog/v1", "catalog": { "id": "wear-m3" },
  "statusSemantics": { "platform": "wear", "componentIdPrefix": "wear-m3/",
    "componentMenu": { "groupOrder": ["A"],
      "components": { "wear-m3/button": { "group": "A" } } } } }
JSON
"${gate}" --policy "${work}/drop-policy.json" --golden "${work}/drop-golden.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --strict >"${work}/out" 2>&1
check "a component missing from a generated menu fails --strict" 1 $?
grep -q "x componentMenu.components.wear-m3/card" "${work}/out" ||
  { echo "FAIL dropped component not reported"; failures=$((failures + 1)); }
# The builder's own components are not this catalog's to state, so their absence is not a difference.
grep -q "componentMenu.components.layout/box" "${work}/out" &&
  { echo "FAIL builder-owned id reported as dropped"; failures=$((failures + 1)); }

cat >"${work}/drop-empty.json" <<'JSON'
{ "schema": "compose-ui-builder-catalog/v1", "catalog": { "id": "wear-m3" },
  "statusSemantics": { "platform": "wear", "componentIdPrefix": "wear-m3/",
    "componentMenu": { "groupOrder": ["A"], "components": {} } } }
JSON
"${gate}" --policy "${work}/drop-empty.json" --golden "${work}/drop-golden.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --strict >"${work}/out" 2>&1
check "an empty generated menu fails --strict" 1 $?

# A retirement is a decision somebody can record: the catalog publishes a menu and that menu does
# not list this id, which is an assertion rather than the silence a waiver may not fill.
cat >"${work}/drop-reviewed.json" <<'JSON'
[ { "field": "componentMenu.components.wear-m3/card", "why": "retired at cutover, reviewed",
    "policy": null, "frozen": { "group": "A" } } ]
JSON
"${gate}" --policy "${work}/drop-policy.json" --golden "${work}/drop-golden.json" \
  --differences "${work}/drop-reviewed.json" --catalog-id wear-m3 --component-id-prefix wear-m3/ \
  --strict >"${work}/out" 2>&1
check "a reviewed retirement passes --strict" 0 $?

# A menu with no `components` member says exactly what an empty one says. The guard handled `{}`
# and skipped an omitted map entirely, so every catalog-owned component could vanish at cutover
# with the gate reporting ready.
cat >"${work}/drop-nomap.json" <<'JSON'
{ "schema": "compose-ui-builder-catalog/v1", "catalog": { "id": "wear-m3" },
  "statusSemantics": { "platform": "wear", "componentIdPrefix": "wear-m3/",
    "componentMenu": { "groupOrder": ["A"] } } }
JSON
"${gate}" --policy "${work}/drop-nomap.json" --golden "${work}/drop-golden.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --strict >"${work}/out" 2>&1
check "a generated menu with no components member fails --strict" 1 $?
grep -q "x componentMenu.components.wear-m3/card" "${work}/out" ||
  { echo "FAIL absent map not swept"; failures=$((failures + 1)); }

# The prefix decides which frozen entries the catalog answers for, and it comes from the document
# being checked — so a wrong one silently suppresses the whole sweep. m3-catalog makes this real:
# its components are `m3/…` while its catalog id is `m3-catalog`.
cat >"${work}/drop-badprefix.json" <<'JSON'
{ "schema": "compose-ui-builder-catalog/v1", "catalog": { "id": "wear-m3" },
  "statusSemantics": { "platform": "wear", "componentIdPrefix": "nope/",
    "componentMenu": { "groupOrder": ["A"], "components": {} } } }
JSON
"${gate}" --policy "${work}/drop-badprefix.json" --golden "${work}/drop-golden.json" \
  --catalog-id wear-m3 --component-id-prefix nope/ --strict >"${work}/out" 2>&1
check "a prefix the frozen catalog does not recognise fails --strict" 1 $?
grep -q "x componentIdPrefix" "${work}/out" ||
  { echo "FAIL unrecognised prefix not reported"; failures=$((failures + 1)); }

# A capability document publishes no prefix — its builtins are materialised in and it does not
# distinguish them — so `--component-id-prefix` is how a caller supplies one, and with it a frozen
# catalog checked against itself passes.
cat >"${work}/drop-noprefix.json" <<'JSON'
{ "benchmark": { "catalogSystemId": "wear-m3" },
  "statusSemantics": { "platform": "wear",
    "componentMenu": { "groupOrder": ["A"],
      "components": { "wear-m3/button": { "group": "A" },
                      "wear-m3/card": { "group": "A" },
                      "layout/box": { "group": "A" } } } } }
JSON
"${gate}" --policy "${work}/drop-noprefix.json" --golden "${work}/drop-golden.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --strict >"${work}/out" 2>&1
check "a caller's prefix supplies what a capability document does not publish" 0 $?

# And with neither: nobody has said which components are this catalog's, so the comparison behind
# `--strict`'s assertion was scoped by the document itself.
"${gate}" --policy "${work}/drop-noprefix.json" --golden "${work}/drop-golden.json" \
  --catalog-id wear-m3 --strict >"${work}/out" 2>&1
check "an unasserted ownership prefix fails --strict" 1 $?
grep -q "no --component-id-prefix was given" "${work}/out" ||
  { echo "FAIL unasserted prefix not reported"; failures=$((failures + 1)); }

# A version token has to be a version all the way through: `parseInt` reads a numeric prefix and
# discards the rest, so `v1junk` arrived as a supported major and every field compared cleanly.
cat >"${work}/version-junk.json" <<'JSON'
{ "schema": "compose-ui-builder-policy/v1junk", "catalogId": "wear-m3", "platform": "wear",
  "menu": { "groupOrder": ["A", "B"] } }
JSON
"${gate}" --policy "${work}/version-junk.json" --golden "${work}/golden.json" \
  --strict >"${work}/out" 2>&1
check "a version with trailing text is refused" 1 $?

# And the two shapes that are real versions still pass: a pre-release suffix is what the frozen
# catalogs declare today, and a minor is the same major, which a reader must accept.
for ok_version in "v1-candidate" "v1.5"; do
  cat >"${work}/version-ok.json" <<JSON
{ "schema": "compose-ui-builder-policy/${ok_version}", "catalogId": "wear-m3", "platform": "wear",
  "menu": { "groupOrder": ["A", "B"] } }
JSON
  "${gate}" --policy "${work}/version-ok.json" --golden "${work}/golden.json" \
    --strict >/dev/null 2>&1
  check "a ${ok_version} schema is still read" 0 $?
done

# Order is not information for these two. Both the runtime and the export read them through one
# `declaredStrings` helper that returns a `Set<String>`, so a catalog listing exactly the frozen
# roles in a different order accepts exactly the same values — and blocking a cutover on a
# byte-order change no consumer can observe is as useless as missing a difference that is real.
cat >"${work}/sets-golden.json" <<'JSON'
{ "benchmark": { "catalogSystemId": "wear-m3" },
  "statusSemantics": { "platform": "wear", "componentMenu": { "groupOrder": ["A"] },
    "colorTokens": { "roles": ["background", "surface", "primary"] },
    "assetRegistry": { "keys": ["editor.placeholder", "cover.one"] } } }
JSON
cat >"${work}/sets-reordered.json" <<'JSON'
{ "schema": "compose-ui-builder-policy/v1", "catalogId": "wear-m3", "platform": "wear",
  "menu": { "groupOrder": ["A"] },
  "colorTokens": { "roles": ["primary", "background", "surface"] },
  "assetRegistry": { "keys": ["cover.one", "editor.placeholder"] } }
JSON
"${gate}" --policy "${work}/sets-reordered.json" --golden "${work}/sets-golden.json" \
  --strict >"${work}/out" 2>&1
check "a reordered role and key list is not a difference" 0 $?

# The asset registry is consumed by two readers and was compared by nothing, so a catalog declaring
# an EMPTY one passed its real golden.
cat >"${work}/sets-emptied.json" <<'JSON'
{ "schema": "compose-ui-builder-policy/v1", "catalogId": "wear-m3", "platform": "wear",
  "menu": { "groupOrder": ["A"] },
  "colorTokens": { "roles": ["background", "surface", "primary"] },
  "assetRegistry": { "keys": [] } }
JSON
"${gate}" --policy "${work}/sets-emptied.json" --golden "${work}/sets-golden.json" \
  --strict >"${work}/out" 2>&1
check "an emptied asset registry fails --strict" 1 $?
grep -q "x assetRegistry.keys" "${work}/out" ||
  { echo "FAIL emptied registry not reported"; failures=$((failures + 1)); }

# And a catalog that states no registry is not silent about a fact it owes. The frozen one lists the
# packaged catalog's artwork and the editor's own insert placeholder — none of it a catalog's to
# ship — so treating the silence as a gap would demand every catalog declare the builder's assets,
# and a gap cannot be waived.
cat >"${work}/sets-silent.json" <<'JSON'
{ "schema": "compose-ui-builder-policy/v1", "catalogId": "wear-m3", "platform": "wear",
  "menu": { "groupOrder": ["A"] },
  "colorTokens": { "roles": ["background", "surface", "primary"] } }
JSON
"${gate}" --policy "${work}/sets-silent.json" --golden "${work}/sets-golden.json" \
  --strict >"${work}/out" 2>&1
check "a catalog stating no asset registry is not a gap" 0 $?

# A builtin the frozen catalog carries: its id being present was reported as agreement and checked
# nothing about the definition, so changing its slots passed.
cat >"${work}/builtin-slots-golden.json" <<'JSON'
{ "benchmark": { "catalogSystemId": "wear-m3" },
  "components": [ { "componentId": "wear-m3/screen-scaffold", "role": "Scaffold",
      "slots": [ { "name": "content" }, { "name": "edgeButton" } ] } ],
  "statusSemantics": { "platform": "wear", "componentMenu": { "groupOrder": ["A"] } } }
JSON
cat >"${work}/builtin-slots-policy.json" <<'JSON'
{ "schema": "compose-ui-builder-policy/v1", "catalogId": "wear-m3", "platform": "wear",
  "menu": { "groupOrder": ["A"] },
  "builtins": { "wear-m3/screen-scaffold": { "role": "list",
    "slots": { "content": {}, "somethingElse": {} } } } }
JSON
"${gate}" --policy "${work}/builtin-slots-policy.json" --golden "${work}/builtin-slots-golden.json" \
  --strict >"${work}/out" 2>&1
check "a known builtin whose slots changed fails --strict" 1 $?
grep -q "builtins.wear-m3/screen-scaffold.slots" "${work}/out" ||
  { echo "FAIL builtin slots not compared"; failures=$((failures + 1)); }

# `[]` says the same thing about a catalog's shelves as `{}` does, and the type guard read it as "no
# map to compare" and skipped both sweeps — so the empty-map fix from one round ago passed through
# the third shape of the same question.
cat >"${work}/drop-arraymap.json" <<'JSON'
{ "schema": "compose-ui-builder-catalog/v1", "catalog": { "id": "wear-m3" },
  "statusSemantics": { "platform": "wear", "componentIdPrefix": "wear-m3/",
    "componentMenu": { "groupOrder": ["A"], "components": [] } } }
JSON
"${gate}" --policy "${work}/drop-arraymap.json" --golden "${work}/drop-golden.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --strict >"${work}/out" 2>&1
check "a components map that is not a map fails --strict" 1 $?
grep -q "which is not a map of component id to" "${work}/out" ||
  { echo "FAIL malformed components map not reported"; failures=$((failures + 1)); }
grep -q "componentMenu.components.wear-m3/card" "${work}/out" ||
  { echo "FAIL malformed map not swept as empty"; failures=$((failures + 1)); }

# A generated artifact publishes its own prefix; the caller's flag scopes the comparison and does
# not stand in for the field phase 4 reads.
cat >"${work}/drop-noprefixgen.json" <<'JSON'
{ "schema": "compose-ui-builder-catalog/v1", "catalog": { "id": "wear-m3" },
  "statusSemantics": { "platform": "wear",
    "componentMenu": { "groupOrder": ["A"], "components": { "wear-m3/button": { "group": "A" } } } } }
JSON
"${gate}" --policy "${work}/drop-noprefixgen.json" --golden "${work}/drop-golden.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --strict >"${work}/out" 2>&1
check "a generated catalog publishing no prefix fails --strict" 1 $?
grep -q "x componentIdPrefix: this generated catalog publishes none" "${work}/out" ||
  { echo "FAIL undeclared prefix not reported"; failures=$((failures + 1)); }

# A prefix problem and an id problem are both blocking and are fixed by different things. Summing
# them into one counter made the epilogue print the catalog-id remedy for a prefix failure — telling
# a caller who HAD passed --catalog-id to pass --catalog-id — and suppressed the `= catalog id`
# confirmation for an id that was in fact checked and correct. Found by comparing a real golden
# against itself, which no fixture in this file was shaped to ask.
"${gate}" --policy "${work}/drop-noprefixgen.json" --golden "${work}/drop-golden.json" \
  --catalog-id wear-m3 --strict >"${work}/out" 2>&1
check "an unasserted prefix does not print the catalog-id remedy" 1 $?
grep -q "which components this catalog owns" "${work}/out" ||
  { echo "FAIL prefix failure did not print the prefix remedy"; failures=$((failures + 1)); }
grep -q "Pass --catalog-id when the policy" "${work}/out" &&
  { echo "FAIL prefix failure printed the catalog-id remedy"; failures=$((failures + 1)); }
grep -q '= catalog id (declared: "wear-m3")' "${work}/out" ||
  { echo "FAIL a correct id was not confirmed under a prefix failure"; failures=$((failures + 1)); }

# --record: the component IDS the catalog would put on a shelf.
#
# The gate's other checks are catalog-LEVEL facts, and a catalog can agree about every one of them
# while offering an entirely different set of components. m3-catalog is that case in the field: 63
# of its 104 record components collided on a derived id and the 41 survivors shared ONE id with the
# frozen catalog's 41. A check comparing counts reports 41 against 41 and passes.
cat >"${work}/rec-golden.json" <<'JSON'
{ "benchmark": { "catalogSystemId": "wear-m3" },
  "statusSemantics": { "platform": "wear", "componentIdPrefix": "wear-m3/",
    "componentMenu": { "groupOrder": ["A"],
      "components": { "wear-m3/button": { "group": "A" },
                      "wear-m3/card": { "group": "A" },
                      "layout/box": { "group": "A" } } } },
  "components": [ { "componentId": "wear-m3/button" }, { "componentId": "wear-m3/card" },
                  { "componentId": "layout/box" } ] }
JSON
cat >"${work}/rec-policy.json" <<'JSON'
{ "schema": "compose-ui-builder-catalog/v1", "catalog": { "id": "wear-m3" },
  "statusSemantics": { "platform": "wear", "componentIdPrefix": "wear-m3/",
    "componentMenu": { "groupOrder": ["A"],
      "components": { "wear-m3/button": { "group": "A" },
                      "wear-m3/card": { "group": "A" } } } } }
JSON
# Distinct leaves: the two ids the frozen catalog owns, derived. `layout/box` is the BUILDER's and
# is out of scope by prefix, which is what stops the comparison demanding a catalog publish it.
cat >"${work}/rec-ok.json" <<'JSON'
{ "schemaVersion": 1, "module": ":w", "variant": "debug", "components": [
  { "canonicalId": ":w/A.Button", "componentIds": ["Controls/Button"],
    "symbol": { "name": "Button", "callable": "a.Button", "jvmOwner": "A", "origin": "PROJECT" },
    "parameters": [], "slots": [], "code": { "imports": [] } },
  { "canonicalId": ":w/A.Card", "componentIds": ["Containers/Card"],
    "symbol": { "name": "Card", "callable": "a.Card", "jvmOwner": "A", "origin": "PROJECT" },
    "parameters": [], "slots": [], "code": { "imports": [] } } ] }
JSON
"${gate}" --policy "${work}/rec-policy.json" --golden "${work}/rec-golden.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --record "${work}/rec-ok.json" --strict \
  >"${work}/out" 2>&1
check "a record deriving the frozen catalog's ids passes --strict" 0 $?
grep -q "= components: the same 2 id(s) on both sides" "${work}/out" ||
  { echo "FAIL matching component ids not confirmed"; failures=$((failures + 1)); }

# The m3-catalog shape, minimised: a `Group/Variant` taxonomy whose LEAF is the variant, so both
# components derive `wear-m3/filled` and one collides. The shelf is then one id the frozen catalog
# has never heard of, and neither of the two it does.
cat >"${work}/rec-collide.json" <<'JSON'
{ "schemaVersion": 1, "module": ":w", "variant": "debug", "components": [
  { "canonicalId": ":w/A.FilledButton", "componentIds": ["Button/Filled"],
    "symbol": { "name": "FilledButton", "callable": "a.FilledButton", "jvmOwner": "A", "origin": "PROJECT" },
    "parameters": [], "slots": [], "code": { "imports": [] } },
  { "canonicalId": ":w/A.FilledCard", "componentIds": ["Card/Filled"],
    "symbol": { "name": "FilledCard", "callable": "a.FilledCard", "jvmOwner": "A", "origin": "PROJECT" },
    "parameters": [], "slots": [], "code": { "imports": [] } } ] }
JSON
"${gate}" --policy "${work}/rec-policy.json" --golden "${work}/rec-golden.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --record "${work}/rec-collide.json" --strict \
  >"${work}/out" 2>&1
check "a record whose derived ids collide fails --strict" 1 $?
grep -q "1 of 2 eligible record component(s) collided" "${work}/out" ||
  { echo "FAIL collision not reported"; failures=$((failures + 1)); }
grep -q "wear-m3/button" "${work}/out" ||
  { echo "FAIL missing frozen component not named"; failures=$((failures + 1)); }
grep -q "wear-m3/filled" "${work}/out" ||
  { echo "FAIL surplus derived component not named"; failures=$((failures + 1)); }

# Without --record the gate must behave exactly as it did before this flag existed. A new input
# that changes the answer for every existing caller is not an addition.
"${gate}" --policy "${work}/rec-policy.json" --golden "${work}/rec-golden.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --strict >"${work}/out" 2>&1
check "no --record leaves the component comparison out entirely" 0 $?
grep -q "components:" "${work}/out" &&
  { echo "FAIL component line printed without --record"; failures=$((failures + 1)); }

# A component the catalog deliberately retires must be WAIVABLE. Spelling the absence `undefined`
# put it on the policy-silent path, which counts a gap and returns before any waiver is read — so
# the decision could not be recorded anywhere, and an exact `--differences` entry for it was
# reported obsolete on top. Reported by Codex on #655; the same bug this file already carries a
# correction for under `builtins`.
cat >"${work}/rec-retire.json" <<'JSON'
{ "schemaVersion": 1, "module": ":w", "variant": "debug", "components": [
  { "canonicalId": ":w/A.Button", "componentIds": ["Controls/Button"],
    "symbol": { "name": "Button", "callable": "a.Button", "jvmOwner": "A", "origin": "PROJECT" },
    "parameters": [], "slots": [], "code": { "imports": [] } } ] }
JSON
"${gate}" --policy "${work}/rec-policy.json" --golden "${work}/rec-golden.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --record "${work}/rec-retire.json" --strict \
  >"${work}/out" 2>&1
check "a retired component fails --strict when nobody has reviewed it" 1 $?
grep -q "components.wear-m3/card" "${work}/out" ||
  { echo "FAIL retired component not named"; failures=$((failures + 1)); }

cat >"${work}/rec-waiver.json" <<'JSON'
[ { "field": "components.wear-m3/card", "why": "retired on purpose",
    "policy": "not offered", "frozen": "offered by the frozen catalog" } ]
JSON
"${gate}" --policy "${work}/rec-policy.json" --golden "${work}/rec-golden.json" \
  --differences "${work}/rec-waiver.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --record "${work}/rec-retire.json" --strict \
  >"${work}/out" 2>&1
check "a reviewed retirement passes --strict" 0 $?
grep -q "no such compared field" "${work}/out" &&
  { echo "FAIL the waiver was reported as naming nothing"; failures=$((failures + 1)); }

# Collisions past the READER's allowance cannot be waived. The gate certifying a catalog the
# server categorically refuses is worse than not checking at all — a readiness gate whose pass does
# not mean the thing can be served. Reported by Codex on #655, with the exact reproduction below.
cat >"${work}/rec-overcollide.json" <<'JSON'
{ "schemaVersion": 1, "module": ":w", "variant": "debug", "components": [
  { "canonicalId": ":w/A.B1", "componentIds": ["Button/Filled"],
    "symbol": { "name": "B1", "callable": "a.B1", "jvmOwner": "A", "origin": "PROJECT" },
    "parameters": [], "slots": [], "code": { "imports": [] } },
  { "canonicalId": ":w/A.B2", "componentIds": ["Card/Filled"],
    "symbol": { "name": "B2", "callable": "a.B2", "jvmOwner": "A", "origin": "PROJECT" },
    "parameters": [], "slots": [], "code": { "imports": [] } },
  { "canonicalId": ":w/A.B3", "componentIds": ["Dialog/Filled"],
    "symbol": { "name": "B3", "callable": "a.B3", "jvmOwner": "A", "origin": "PROJECT" },
    "parameters": [], "slots": [], "code": { "imports": [] } } ] }
JSON
cat >"${work}/rec-anywaiver.json" <<'JSON'
[ { "field": "components.collisions", "why": "trying to wave this through",
    "policy": "anything", "frozen": "anything" },
  { "field": "components.wear-m3/button", "why": "trying to wave this through",
    "policy": "not offered", "frozen": "offered by the frozen catalog" },
  { "field": "components.wear-m3/card", "why": "trying to wave this through",
    "policy": "not offered", "frozen": "offered by the frozen catalog" },
  { "field": "components.wear-m3/filled", "why": "trying to wave this through",
    "policy": "offered by this catalog", "frozen": "not offered" } ]
JSON
"${gate}" --policy "${work}/rec-policy.json" --golden "${work}/rec-golden.json" \
  --differences "${work}/rec-anywaiver.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --record "${work}/rec-overcollide.json" \
  --strict >"${work}/out" 2>&1
check "collisions past the reader's allowance cannot be waived" 1 $?
grep -q "this cannot be waived" "${work}/out" ||
  { echo "FAIL unwaivable collision not reported as such"; failures=$((failures + 1)); }
grep -q "declaring" "${work}/out" ||
  { echo "FAIL the remedy does not say what to do instead"; failures=$((failures + 1)); }

# The prefix used for derivation is the POLICY's, never the frozen catalog's. Taking it from the
# golden makes the derived ids carry the prefix they are about to be compared against, so a policy
# declaring a WRONG prefix lines up and passes.
cat >"${work}/rec-wrongprefix.json" <<'JSON'
{ "schema": "compose-ui-builder-catalog/v1", "catalog": { "id": "wear-m3" },
  "statusSemantics": { "platform": "wear", "componentIdPrefix": "wrong/",
    "componentMenu": { "groupOrder": ["A"],
      "components": { "wear-m3/button": { "group": "A" },
                      "wear-m3/card": { "group": "A" } } } } }
JSON
"${gate}" --policy "${work}/rec-wrongprefix.json" --golden "${work}/rec-golden.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --record "${work}/rec-ok.json" --strict \
  >"${work}/out" 2>&1
check "a policy declaring the wrong prefix cannot pass on the golden's" 1 $?
grep -q "wrong/button" "${work}/out" ||
  { echo "FAIL derivation did not use the policy's prefix"; failures=$((failures + 1)); }

# An id the policy maps OUTSIDE its own prefix is still on the shelf. The prefix scopes which
# FROZEN ids this catalog answers for; using it to filter the composition too hid a component the
# server would offer and let the gate report "the same ids on both sides". Reported by Codex on #655.
cat >"${work}/rec-outside.json" <<'JSON'
{ "schema": "compose-ui-builder-catalog/v1", "catalog": { "id": "wear-m3" },
  "statusSemantics": { "platform": "wear", "componentIdPrefix": "wear-m3/",
    "components": { "other/extra": { "record": ":w/A.Extra" } },
    "componentMenu": { "groupOrder": ["A"],
      "components": { "wear-m3/button": { "group": "A" },
                      "wear-m3/card": { "group": "A" } } } } }
JSON
cat >"${work}/rec-outside-record.json" <<'JSON'
{ "schemaVersion": 1, "module": ":w", "variant": "debug", "components": [
  { "canonicalId": ":w/A.Button", "componentIds": ["Controls/Button"],
    "symbol": { "name": "Button", "callable": "a.Button", "jvmOwner": "A", "origin": "PROJECT" },
    "parameters": [], "slots": [], "code": { "imports": [] } },
  { "canonicalId": ":w/A.Card", "componentIds": ["Containers/Card"],
    "symbol": { "name": "Card", "callable": "a.Card", "jvmOwner": "A", "origin": "PROJECT" },
    "parameters": [], "slots": [], "code": { "imports": [] } },
  { "canonicalId": ":w/A.Extra", "componentIds": ["Extras/Extra"],
    "symbol": { "name": "Extra", "callable": "a.Extra", "jvmOwner": "A", "origin": "PROJECT" },
    "parameters": [], "slots": [], "code": { "imports": [] } } ] }
JSON
"${gate}" --policy "${work}/rec-outside.json" --golden "${work}/rec-golden.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --record "${work}/rec-outside-record.json" \
  --strict >"${work}/out" 2>&1
check "an id mapped outside the prefix is still compared" 1 $?
grep -q "components.other/extra" "${work}/out" ||
  { echo "FAIL out-of-prefix id not reported"; failures=$((failures + 1)); }
grep -q "the same 2 id(s) on both sides" "${work}/out" &&
  { echo "FAIL claimed agreement while offering an extra component"; failures=$((failures + 1)); }

# An id outside the prefix that the FROZEN catalog does happen to carry — `layout/box` is the
# builder's own — is still not this catalog's to publish. Checking surplus against every frozen id
# rather than the owned ones waved it through and printed agreement. Reported by Codex on #655, the
# same prefix-scoping mistake one predicate along.
cat >"${work}/rec-builderid.json" <<'JSON'
{ "schema": "compose-ui-builder-catalog/v1", "catalog": { "id": "wear-m3" },
  "statusSemantics": { "platform": "wear", "componentIdPrefix": "wear-m3/",
    "components": { "layout/box": { "record": ":w/A.Extra" } },
    "componentMenu": { "groupOrder": ["A"],
      "components": { "wear-m3/button": { "group": "A" },
                      "wear-m3/card": { "group": "A" } } } } }
JSON
"${gate}" --policy "${work}/rec-builderid.json" --golden "${work}/rec-golden.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --record "${work}/rec-outside-record.json" \
  --strict >"${work}/out" 2>&1
check "a builder-owned id the catalog publishes is still surplus" 1 $?
grep -q "components.layout/box" "${work}/out" ||
  { echo "FAIL builder-owned surplus id not reported"; failures=$((failures + 1)); }
grep -q "the same 2 id(s) on both sides" "${work}/out" &&
  { echo "FAIL claimed agreement while publishing a builder id"; failures=$((failures + 1)); }

# A policy excluding every record component composes to nothing, which the reader refuses outright.
# Waiving each missing frozen id must not make an EMPTY catalog pass readiness.
cat >"${work}/rec-empty.json" <<'JSON'
{ "schema": "compose-ui-builder-catalog/v1", "catalog": { "id": "wear-m3" },
  "statusSemantics": { "platform": "wear", "componentIdPrefix": "wear-m3/",
    "components": { "wear-m3/button": { "record": ":w/A.Button", "excluded": "gone" },
                    "wear-m3/card": { "record": ":w/A.Card", "excluded": "gone" } },
    "componentMenu": { "groupOrder": ["A"],
      "components": { "wear-m3/button": { "group": "A" },
                      "wear-m3/card": { "group": "A" } } } } }
JSON
cat >"${work}/rec-emptywaiver.json" <<'JSON'
[ { "field": "components.wear-m3/button", "why": "retired", "policy": "not offered",
    "frozen": "offered by the frozen catalog" },
  { "field": "components.wear-m3/card", "why": "retired", "policy": "not offered",
    "frozen": "offered by the frozen catalog" } ]
JSON
"${gate}" --policy "${work}/rec-empty.json" --golden "${work}/rec-golden.json" \
  --differences "${work}/rec-emptywaiver.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --record "${work}/rec-ok.json" --strict \
  >"${work}/out" 2>&1
check "an empty composition cannot be waived through" 1 $?
grep -q "no components at all" "${work}/out" ||
  { echo "FAIL empty composition not reported"; failures=$((failures + 1)); }

# A non-ASCII leaf is REFUSED, not derived. Node and the JVM disagree about some characters
# (U+0295 is lowercase to Java 17 and not to Node 22), so an id derived here would be a guess —
# and a guessed id produces exactly the false missing/surplus pair this comparison exists to
# detect. Reported by Codex on #655 as the sixth Unicode divergence, and the first with no fix in
# the same style.
cat >"${work}/rec-nonascii.json" <<'JSON'
{ "schemaVersion": 1, "module": ":w", "variant": "debug", "components": [
  { "canonicalId": ":w/A.Button", "componentIds": ["Controls/Button"],
    "symbol": { "name": "Button", "callable": "a.Button", "jvmOwner": "A", "origin": "PROJECT" },
    "parameters": [], "slots": [], "code": { "imports": [] } },
  { "canonicalId": ":w/A.Odd", "componentIds": ["Controls/\u0295Card"],
    "symbol": { "name": "Odd", "callable": "a.Odd", "jvmOwner": "A", "origin": "PROJECT" },
    "parameters": [], "slots": [], "code": { "imports": [] } } ] }
JSON
"${gate}" --policy "${work}/rec-policy.json" --golden "${work}/rec-golden.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --record "${work}/rec-nonascii.json" \
  --strict >"${work}/out" 2>&1
check "a non-ASCII leaf is refused rather than derived" 1 $?
grep -q "cannot reproduce the reader's id" "${work}/out" ||
  { echo "FAIL undecidable derivation not reported"; failures=$((failures + 1)); }
grep -q "statusSemantics.components" "${work}/out" ||
  { echo "FAIL the remedy does not say how to resolve it"; failures=$((failures + 1)); }

# Declaring the id makes it decidable again: the gate READS the id instead of deriving one, so
# nothing about the character matters. The escape hatch has to actually work or the refusal is a
# dead end rather than a redirection.
cat >"${work}/rec-declared.json" <<'JSON'
{ "schema": "compose-ui-builder-catalog/v1", "catalog": { "id": "wear-m3" },
  "statusSemantics": { "platform": "wear", "componentIdPrefix": "wear-m3/",
    "components": { "wear-m3/card": { "record": ":w/A.Odd" } },
    "componentMenu": { "groupOrder": ["A"],
      "components": { "wear-m3/button": { "group": "A" },
                      "wear-m3/card": { "group": "A" } } } } }
JSON
"${gate}" --policy "${work}/rec-declared.json" --golden "${work}/rec-golden.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --record "${work}/rec-nonascii.json" \
  --strict >"${work}/out" 2>&1
check "a declared id needs no derivation and passes" 0 $?
grep -q "= components: the same 2 id(s) on both sides" "${work}/out" ||
  { echo "FAIL declared non-ASCII component not compared"; failures=$((failures + 1)); }

# `statusSemantics.components` must be a MAP. The reader decodes it as
# `Map<String, UiBuilderComponentPolicy>`, so `null` or an array fails its decode and it refuses the
# file — while `?? {}` read null as "no policy" and `Object.entries([])` read an array as an empty
# one, letting the gate compare ids for an artifact the server will not load. Reported by Codex on
# #655; third instance of "where the reader refuses, so must this".
for shape in 'null' '[]'; do
  cat >"${work}/rec-badmap.json" <<JSON
{ "schema": "compose-ui-builder-catalog/v1", "catalog": { "id": "wear-m3" },
  "statusSemantics": { "platform": "wear", "componentIdPrefix": "wear-m3/",
    "components": ${shape},
    "componentMenu": { "groupOrder": ["A"],
      "components": { "wear-m3/button": { "group": "A" },
                      "wear-m3/card": { "group": "A" } } } } }
JSON
  "${gate}" --policy "${work}/rec-badmap.json" --golden "${work}/rec-golden.json" \
    --catalog-id wear-m3 --component-id-prefix wear-m3/ --record "${work}/rec-ok.json" --strict \
    >"${work}/out" 2>&1
  check "a components map that is ${shape} is refused under --record" 1 $?
  grep -q "not a map of component id to policy" "${work}/out" ||
    { echo "FAIL malformed components map (${shape}) not reported"; failures=$((failures + 1)); }
done

# DECODE BEFORE COMPARING. Three shapes the reader's typed decode rejects and a raw JSON traversal
# reads as ordinary data. All reported by Codex on #655 in one round, and all one mistake: the gate
# was comparing where the reader was decoding.

# 1. A policy VALUE that is not an object.
cat >"${work}/rec-badvalue.json" <<'JSON'
{ "schema": "compose-ui-builder-catalog/v1", "catalog": { "id": "wear-m3" },
  "statusSemantics": { "platform": "wear", "componentIdPrefix": "wear-m3/",
    "components": { "wear-m3/unused": null },
    "componentMenu": { "groupOrder": ["A"],
      "components": { "wear-m3/button": { "group": "A" },
                      "wear-m3/card": { "group": "A" } } } } }
JSON
"${gate}" --policy "${work}/rec-badvalue.json" --golden "${work}/rec-golden.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --record "${work}/rec-ok.json" --strict \
  >"${work}/out" 2>&1
check "a policy value that is not an object is refused" 1 $?
grep -q 'components\["wear-m3/unused"\] is not an object' "${work}/out" ||
  { echo "FAIL non-object policy value not reported"; failures=$((failures + 1)); }

# 2. An AUTHORED policy cannot answer the component-id question: per-component ids live in
#    @BuilderComponent annotations, which only the generator resolves. Deriving from it ignores
#    every id an annotation overrides, and can agree with the golden by luck.
cat >"${work}/rec-authored.json" <<'JSON'
{ "schema": "compose-ui-builder-policy/v1", "catalogId": "wear-m3", "platform": "wear",
  "componentIdPrefix": "wear-m3/",
  "menu": { "groupOrder": ["A"],
    "components": { "wear-m3/button": { "group": "A" }, "wear-m3/card": { "group": "A" } } } }
JSON
"${gate}" --policy "${work}/rec-authored.json" --golden "${work}/rec-golden.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --record "${work}/rec-ok.json" --strict \
  >"${work}/out" 2>&1
check "an authored policy cannot answer --record" 1 $?
grep -q "needs the GENERATED ui-builder.json" "${work}/out" ||
  { echo "FAIL authored policy accepted for component ids"; failures=$((failures + 1)); }

# 3. A record that is valid JSON but not a `ComponentRecordFile`. A `componentIds` STRING is the
#    dangerous shape: `[0]` reads a character and `.split("/").pop()` makes a plausible id out of it.
cat >"${work}/rec-badrecord.json" <<'JSON'
{ "schemaVersion": 1, "module": ":w", "variant": "debug", "components": [
  { "canonicalId": ":w/A.Button", "componentIds": "Button",
    "symbol": { "name": "Button", "callable": "a.Button", "jvmOwner": "A", "origin": "PROJECT" },
    "parameters": [], "slots": [], "code": { "imports": [] } } ] }
JSON
"${gate}" --policy "${work}/rec-policy.json" --golden "${work}/rec-golden.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --record "${work}/rec-badrecord.json" \
  --strict >"${work}/out" 2>&1
check "a record the reader could not decode is refused" 1 $?
grep -q "componentIds is not a list of strings" "${work}/out" ||
  { echo "FAIL malformed record shape not reported"; failures=$((failures + 1)); }

# A record path that does not exist is a usage error, not a pass. The same argument as the missing
# golden: a caller asserting readiness against a file nobody could read has asserted nothing.
"${gate}" --policy "${work}/rec-policy.json" --golden "${work}/rec-golden.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --record "${work}/nope.json" --strict \
  >"${work}/out" 2>&1
check "a missing record is a usage error, not a pass" 2 $?

# The same family, one level in. Round nine closed "the CONTAINER is the wrong type"; these are the
# three shapes that got through it because the container was right and something inside it was not.
# Each asserts the MESSAGE as well as the exit code: a refusal for an unrelated reason is how a
# self-test passes while verifying nothing, which is exactly how the authored-policy case first
# went green.

# 4. A CAPABILITY document standing in for a generated one. It carries `statusSemantics` too, so the
#    `published` flag the guard used to test is true for it — and its `components` are the OUTPUT of
#    the very composition --record predicts, so it would be compared against itself.
cat >"${work}/rec-capability.json" <<'JSON'
{ "schema": "compose-ui-builder-capabilities/v1-candidate",
  "benchmark": { "catalogSystemId": "wear-m3" },
  "statusSemantics": { "platform": "wear", "componentIdPrefix": "wear-m3/",
    "componentMenu": { "groupOrder": ["A"],
      "components": { "wear-m3/button": { "group": "A" },
                      "wear-m3/card": { "group": "A" } } } },
  "components": [ { "componentId": "wear-m3/button" }, { "componentId": "wear-m3/card" } ] }
JSON
"${gate}" --policy "${work}/rec-capability.json" --golden "${work}/rec-golden.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --record "${work}/rec-ok.json" --strict \
  >"${work}/out" 2>&1
check "a capability document cannot answer --record" 1 $?
grep -q "this is a capability document" "${work}/out" ||
  { echo "FAIL capability document not named as the reason"; failures=$((failures + 1)); }

# 5. A policy value that IS an object, whose `record` member is a number. `UiBuilderComponentPolicy`
#    declares it `String` and the reader is not lenient, so this fails its decode.
cat >"${work}/rec-badmember.json" <<'JSON'
{ "schema": "compose-ui-builder-catalog/v1", "catalog": { "id": "wear-m3" },
  "statusSemantics": { "platform": "wear", "componentIdPrefix": "wear-m3/",
    "components": { "wear-m3/button": { "record": 7 } },
    "componentMenu": { "groupOrder": ["A"],
      "components": { "wear-m3/button": { "group": "A" },
                      "wear-m3/card": { "group": "A" } } } } }
JSON
"${gate}" --policy "${work}/rec-badmember.json" --golden "${work}/rec-golden.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --record "${work}/rec-ok.json" --strict \
  >"${work}/out" 2>&1
check "a policy member of the wrong type is refused" 1 $?
grep -q 'components\["wear-m3/button"\].record is not a string' "${work}/out" ||
  { echo "FAIL wrong-typed policy member not named"; failures=$((failures + 1)); }

# 6. `traits` is a `List<String>` with a default, so it may be ABSENT — but not null, and not a list
#    of something else. Absent and null are different questions and only a table keeps them apart.
cat >"${work}/rec-badtraits.json" <<'JSON'
{ "schema": "compose-ui-builder-catalog/v1", "catalog": { "id": "wear-m3" },
  "statusSemantics": { "platform": "wear", "componentIdPrefix": "wear-m3/",
    "components": { "wear-m3/button": { "record": ":w/A.Button", "traits": [1] } },
    "componentMenu": { "groupOrder": ["A"],
      "components": { "wear-m3/button": { "group": "A" },
                      "wear-m3/card": { "group": "A" } } } } }
JSON
"${gate}" --policy "${work}/rec-badtraits.json" --golden "${work}/rec-golden.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --record "${work}/rec-ok.json" --strict \
  >"${work}/out" 2>&1
check "a policy list of the wrong element type is refused" 1 $?
grep -q 'traits is not a list of strings' "${work}/out" ||
  { echo "FAIL wrong-typed traits not named"; failures=$((failures + 1)); }

# 7. A record field the DERIVATION never reads. `parameters` is `List<TargetParameter>` with an
#    `emptyList()` default and neither reader sets `coerceInputValues`, so an explicit null throws
#    and the catalog has no record at all — not one component short.
cat >"${work}/rec-nullparams.json" <<'JSON'
{ "schemaVersion": 1, "module": ":w", "variant": "debug", "components": [
  { "canonicalId": ":w/A.Button", "componentIds": ["Controls/Button"],
    "symbol": { "name": "Button", "callable": "a.Button", "jvmOwner": "A", "origin": "PROJECT" },
    "parameters": null, "slots": [], "code": { "imports": [] } },
  { "canonicalId": ":w/A.Card", "componentIds": ["Containers/Card"],
    "symbol": { "name": "Card", "callable": "a.Card", "jvmOwner": "A", "origin": "PROJECT" },
    "parameters": [], "slots": [], "code": { "imports": [] } } ] }
JSON
"${gate}" --policy "${work}/rec-policy.json" --golden "${work}/rec-golden.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --record "${work}/rec-nullparams.json" \
  --strict >"${work}/out" 2>&1
check "a record field that is null where Kotlin is not nullable is refused" 1 $?
grep -q "record.components\[0\].parameters is null" "${work}/out" ||
  { echo "FAIL null non-nullable record field not named"; failures=$((failures + 1)); }

# 8. A record property with NO default. `symbol` is required, and a record missing it fails the
#    decode with MissingFieldException — while the derivation, which reads `symbol.name` only as a
#    fallback, would have happily used `componentIds` and never noticed.
cat >"${work}/rec-nosymbol.json" <<'JSON'
{ "schemaVersion": 1, "module": ":w", "variant": "debug", "components": [
  { "canonicalId": ":w/A.Button", "componentIds": ["Controls/Button"],
    "parameters": [], "slots": [], "code": { "imports": [] } },
  { "canonicalId": ":w/A.Card", "componentIds": ["Containers/Card"],
    "symbol": { "name": "Card", "callable": "a.Card", "jvmOwner": "A", "origin": "PROJECT" },
    "parameters": [], "slots": [], "code": { "imports": [] } } ] }
JSON
"${gate}" --policy "${work}/rec-policy.json" --golden "${work}/rec-golden.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --record "${work}/rec-nosymbol.json" \
  --strict >"${work}/out" 2>&1
check "a record property with no default cannot be absent" 1 $?
grep -q "record.components\[0\].symbol is missing" "${work}/out" ||
  { echo "FAIL missing required record property not named"; failures=$((failures + 1)); }

# 9. And one level further in still: `origin` is an ENUM, so a string that is not one of its
#    constants fails the decode as surely as a number would.
cat >"${work}/rec-badorigin.json" <<'JSON'
{ "schemaVersion": 1, "module": ":w", "variant": "debug", "components": [
  { "canonicalId": ":w/A.Button", "componentIds": ["Controls/Button"],
    "symbol": { "name": "Button", "callable": "a.Button", "jvmOwner": "A", "origin": "VENDORED" },
    "parameters": [], "slots": [], "code": { "imports": [] } } ] }
JSON
"${gate}" --policy "${work}/rec-policy.json" --golden "${work}/rec-golden.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --record "${work}/rec-badorigin.json" \
  --strict >"${work}/out" 2>&1
check "a record enum outside its constants is refused" 1 $?
grep -q "symbol.origin is not one of PROJECT, LIBRARY" "${work}/out" ||
  { echo "FAIL out-of-range enum not named"; failures=$((failures + 1)); }

# 10. The file's own required properties, which nothing in the derivation reads at all. A record
#     without `module` decodes no further than its first field.
cat >"${work}/rec-nomodule.json" <<'JSON'
{ "schemaVersion": 1, "variant": "debug", "components": [
  { "canonicalId": ":w/A.Button", "componentIds": ["Controls/Button"],
    "symbol": { "name": "Button", "callable": "a.Button", "jvmOwner": "A", "origin": "PROJECT" },
    "parameters": [], "slots": [], "code": { "imports": [] } } ] }
JSON
"${gate}" --policy "${work}/rec-policy.json" --golden "${work}/rec-golden.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --record "${work}/rec-nomodule.json" \
  --strict >"${work}/out" 2>&1
check "a record file missing a required property is refused" 1 $?
grep -q "record.module is missing" "${work}/out" ||
  { echo "FAIL missing record-file property not named"; failures=$((failures + 1)); }

# 11. An UNKNOWN key is not an error. Both readers set `ignoreUnknownKeys`, so a catalog newer than
#     this gate must still pass — a shape table that refuses what the reader skips would fail
#     catalogs for being ahead of it.
cat >"${work}/rec-newer.json" <<'JSON'
{ "schemaVersion": 1, "module": ":w", "variant": "debug", "somethingNewer": { "x": 1 },
  "components": [
  { "canonicalId": ":w/A.Button", "componentIds": ["Controls/Button"], "futureField": [1, 2],
    "symbol": { "name": "Button", "callable": "a.Button", "jvmOwner": "A", "origin": "PROJECT" },
    "parameters": [], "slots": [], "code": { "imports": [] } },
  { "canonicalId": ":w/A.Card", "componentIds": ["Containers/Card"],
    "symbol": { "name": "Card", "callable": "a.Card", "jvmOwner": "A", "origin": "PROJECT" },
    "parameters": [], "slots": [], "code": { "imports": [] } } ] }
JSON
"${gate}" --policy "${work}/rec-policy.json" --golden "${work}/rec-golden.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --record "${work}/rec-newer.json" --strict \
  >"${work}/out" 2>&1
check "a record carrying keys the gate does not know still passes" 0 $?

# Round eleven. Three more of the same family — the container was checked, what it CONTAINED was
# not; and one report that pointed at the wrong remedy.

# 12. A null ELEMENT inside a real array. `Array.isArray` passes, so the loop reached
#     `component.canonicalId` and died with a Node stack trace — which without --strict turned a
#     report-only run into exit 1. Report-only must stay exit 0 and diagnostic.
cat >"${work}/rec-nullelem.json" <<'JSON'
{ "schemaVersion": 1, "module": ":w", "variant": "debug", "components": [ null,
  { "canonicalId": ":w/A.Card", "componentIds": ["Containers/Card"],
    "symbol": { "name": "Card", "callable": "a.Card", "jvmOwner": "A", "origin": "PROJECT" },
    "parameters": [], "slots": [], "code": { "imports": [] } } ] }
JSON
"${gate}" --policy "${work}/rec-policy.json" --golden "${work}/rec-golden.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --record "${work}/rec-nullelem.json" \
  >"${work}/out" 2>&1
check "a null record entry is reported, not crashed on" 0 $?
grep -q "record.components\[0\] is not an object" "${work}/out" ||
  { echo "FAIL null record entry not named"; failures=$((failures + 1)); }
grep -qi "at evalTypeScript\|TypeError" "${work}/out" &&
  { echo "FAIL a stack trace reached the report"; failures=$((failures + 1)); }

# 13. And the refusal is the WHOLE answer. A record the reader rejects used to go on producing an
#     empty-shelf refusal, a `0 record entries` summary, and a `not offered` difference per frozen
#     component — differences a reviewer could waive, filing a considered decision against an
#     artefact of the first failure.
grep -q "not offered" "${work}/out" &&
  { echo "FAIL an undecodable record still produced waivable differences"; failures=$((failures + 1)); }
grep -q "yields no components at all" "${work}/out" &&
  { echo "FAIL an undecodable record still reported an empty shelf"; failures=$((failures + 1)); }
"${gate}" --policy "${work}/rec-policy.json" --golden "${work}/rec-golden.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --record "${work}/rec-nullelem.json" \
  --strict >"${work}/out" 2>&1
check "an undecodable record still fails --strict" 1 $?

# 14. A member of a NESTED serialized type. `parameters` was validated as a list of arbitrary
#     objects, so `TargetParameter.name` being a number decoded here and not in Kotlin.
cat >"${work}/rec-badparam.json" <<'JSON'
{ "schemaVersion": 1, "module": ":w", "variant": "debug", "components": [
  { "canonicalId": ":w/A.Button", "componentIds": ["Controls/Button"],
    "symbol": { "name": "Button", "callable": "a.Button", "jvmOwner": "A", "origin": "PROJECT" },
    "parameters": [ { "name": 7, "type": "String" } ], "slots": [], "code": { "imports": [] } },
  { "canonicalId": ":w/A.Card", "componentIds": ["Containers/Card"],
    "symbol": { "name": "Card", "callable": "a.Card", "jvmOwner": "A", "origin": "PROJECT" },
    "parameters": [], "slots": [], "code": { "imports": [] } } ] }
JSON
"${gate}" --policy "${work}/rec-policy.json" --golden "${work}/rec-golden.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --record "${work}/rec-badparam.json" \
  --strict >"${work}/out" 2>&1
check "a nested subobject member of the wrong type is refused" 1 $?
grep -q "record.components\[0\].parameters\[0\].name is not a string" "${work}/out" ||
  { echo "FAIL nested member not named"; failures=$((failures + 1)); }

# 15. `ComponentSlot.required` has no default, one level in — absent is a MissingFieldException.
cat >"${work}/rec-badslot.json" <<'JSON'
{ "schemaVersion": 1, "module": ":w", "variant": "debug", "components": [
  { "canonicalId": ":w/A.Button", "componentIds": ["Controls/Button"],
    "symbol": { "name": "Button", "callable": "a.Button", "jvmOwner": "A", "origin": "PROJECT" },
    "parameters": [], "slots": [ { "name": "content" } ], "code": { "imports": [] } } ] }
JSON
"${gate}" --policy "${work}/rec-policy.json" --golden "${work}/rec-golden.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --record "${work}/rec-badslot.json" \
  --strict >"${work}/out" 2>&1
check "a nested property with no default cannot be absent" 1 $?
grep -q "slots\[0\].required is missing" "${work}/out" ||
  { echo "FAIL missing nested required property not named"; failures=$((failures + 1)); }

# 16. The epilogue states the remedy for what actually failed. Every refusal shared one closing
#     paragraph about declaring `statusSemantics.components` to stop ids colliding, so a run that
#     failed on a malformed record closed by advising a fix for a problem it never reported.
grep -q "Correct the record fields named above" "${work}/out" ||
  { echo "FAIL the record remedy was not stated"; failures=$((failures + 1)); }
grep -q "collisions are the catalog's to resolve" "${work}/out" &&
  { echo "FAIL a decode failure closed with the collision remedy"; failures=$((failures + 1)); }

# And the converse: a genuine collision failure must still state the collision remedy.
"${gate}" --policy "${work}/rec-policy.json" --golden "${work}/rec-golden.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --record "${work}/rec-overcollide.json" \
  --strict >"${work}/out" 2>&1
check "a collision failure still states the collision remedy" 1 $?
grep -q "collisions are the catalog's to resolve" "${work}/out" ||
  { echo "FAIL the collision remedy was lost"; failures=$((failures + 1)); }

# Round twelve. The table stopped being hand-written, because a hand-written one drifted exactly
# as predicted: it was transcribed from a sibling checkout OLDER than the artifact :server
# resolves, so it missed six fields the published TargetParameter and BuilderPolicy carry.
# `DecoderShapeFixtureTest` now generates it from the live descriptors and fails on drift; these
# cases check that the gate reads it and uses it.

# 17. A field that exists only in the PUBLISHED artifact — absent from the checkout the old table
#     was typed from, and present in the real m3-catalog record.
cat >"${work}/rec-scopedsl.json" <<'JSON'
{ "schemaVersion": 1, "module": ":w", "variant": "debug", "components": [
  { "canonicalId": ":w/A.Button", "componentIds": ["Controls/Button"],
    "symbol": { "name": "Button", "callable": "a.Button", "jvmOwner": "A", "origin": "PROJECT" },
    "parameters": [ { "name": "x", "type": "String", "scopeDslReceiver": 7 } ],
    "slots": [], "code": { "imports": [] } },
  { "canonicalId": ":w/A.Card", "componentIds": ["Containers/Card"],
    "symbol": { "name": "Card", "callable": "a.Card", "jvmOwner": "A", "origin": "PROJECT" },
    "parameters": [], "slots": [], "code": { "imports": [] } } ] }
JSON
"${gate}" --policy "${work}/rec-policy.json" --golden "${work}/rec-golden.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --record "${work}/rec-scopedsl.json" \
  --strict >"${work}/out" 2>&1
check "a field only the published artifact carries is validated" 1 $?
grep -q "parameters\[0\].scopeDslReceiver is not a string" "${work}/out" ||
  { echo "FAIL generated-table field not validated"; failures=$((failures + 1)); }

# 18. Kotlin's Int is 32-bit and kotlinx refuses a JSON number outside it, so integrality alone is
#     not the test.
cat >"${work}/rec-bigint.json" <<'JSON'
{ "schemaVersion": 2147483648, "module": ":w", "variant": "debug", "components": [
  { "canonicalId": ":w/A.Button", "componentIds": ["Controls/Button"],
    "symbol": { "name": "Button", "callable": "a.Button", "jvmOwner": "A", "origin": "PROJECT" },
    "parameters": [], "slots": [], "code": { "imports": [] } },
  { "canonicalId": ":w/A.Card", "componentIds": ["Containers/Card"],
    "symbol": { "name": "Card", "callable": "a.Card", "jvmOwner": "A", "origin": "PROJECT" },
    "parameters": [], "slots": [], "code": { "imports": [] } } ] }
JSON
"${gate}" --policy "${work}/rec-policy.json" --golden "${work}/rec-golden.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --record "${work}/rec-bigint.json" \
  --strict >"${work}/out" 2>&1
check "an integer outside Kotlin's Int range is refused" 1 $?
grep -q "schemaVersion is outside the range of a Kotlin Int" "${work}/out" ||
  { echo "FAIL Int overflow not named"; failures=$((failures + 1)); }
# 2147483647 is the largest that decodes, and must still pass.
sed 's/2147483648/2147483647/' "${work}/rec-bigint.json" >"${work}/rec-maxint.json"
"${gate}" --policy "${work}/rec-policy.json" --golden "${work}/rec-golden.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --record "${work}/rec-maxint.json" \
  --strict >"${work}/out" 2>&1
check "the largest Kotlin Int still decodes" 0 $?

# 19. The table is an input now, so its absence is a usage error — never a quiet pass. A gate that
#     certified records with no shape table would be the "check that does not check" this file
#     keeps being about.
"${gate}" --policy "${work}/rec-policy.json" --golden "${work}/rec-golden.json" \
  --catalog-id wear-m3 --component-id-prefix wear-m3/ --record "${work}/rec-ok.json" --strict \
  --shapes "${work}/no-such-table.json" >"${work}/out" 2>&1
check "a missing shape table is a usage error, not a pass" 2 $?
grep -q "cannot read the decoder shape table" "${work}/out" ||
  { echo "FAIL missing shape table not reported"; failures=$((failures + 1)); }

set -e

if [[ ${failures} -gt 0 ]]; then
  echo "${failures} failure(s)"
  exit 1
fi
echo "all ui-builder-equivalence tests passed"
