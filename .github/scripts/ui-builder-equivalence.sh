#!/usr/bin/env bash
#
# Is a catalog repository ready to describe itself?
#
# Two of the three catalogs the UI builder serves are written in Kotlin in THIS repository —
# synthesised at startup from the packaged Material 3 one — and the plan in
# docs/design/UI_BUILDER_CATALOG_CONTRACT.md moves that description into the repositories that own
# the components. The phases are ordered so those repositories publish first and this one cuts over
# afterwards, which turns "is wear-m3-catalog ready?" from a judgement into a question somebody can
# answer. This script is how it gets answered.
#
# It compares the CATALOG-LEVEL facts a catalog states against the same facts in the frozen golden —
# the JSON `SynthesisedCatalogGoldenTest` writes out of the generator that runs today. Where they
# agree, the catalog can describe itself; where they differ, either the catalog is wrong or the
# difference is deliberate and belongs in the reviewed-difference list beside it.
#
# TWO SHAPES, one comparison. Through phases 2 and 3 the only thing a catalog has is its authored
# `ui-builder.policy.json`, in a checkout — no catalog can publish until the pipeline release lands.
# From phase 1 onwards the delivery branch also carries the GENERATED `ui-builder.json`, where the
# same facts ride under `statusSemantics` and `menu` is called `componentMenu`. `--policy` accepts
# either and says which it read: a gate that only understood the source would be useless at the
# cutover, and one that only understood the published file would be useless until then.
#
# What it deliberately does NOT do
#
#   Compare components. The published builder catalog is POLICY; the component record beside it is
#   the inventory, and composing the two into a capability catalog is the loader this repository has
#   not written yet (phase 4). Pretending to compare them here would mean a second implementation of
#   that composition, in bash, whose disagreements with the real one nobody would ever see.
#
#   The catalog's own MENU is a different thing and is compared per component: which shelf each one
#   lands on and which parameter its variant control writes to are catalog-level policy, stated in
#   the published file, and phase 4 reads them to build the insert panel. Only the entries the
#   catalog states, though — the frozen catalog's menu also carries the BUILDER's components
#   (`asset/image`, `layout/box`, `remote-compose/*`), which are not the catalog's to state.
#
#   Fail a build. Through phases 2 and 3 a catalog that publishes nothing reports "not yet", and one
#   that differs reports what differs. `--strict` turns that into exit 1, which is what the cutover
#   PR turns on once a catalog is meant to be equivalent.
#
#   VALIDATE a policy. Every catalog-level fact phase 4 consumes is COMPARED here — `code`,
#   `templates` and `frame.seedDevice` alongside the platform, the surfaces and the frame — but
#   whether `code.strategy` names a strategy that exists, or a `templates` path resolves to a
#   document, is the authoring pipeline's question. compose-ai-tools answers it in
#   `validate-ui-builder-policy.mjs`, and a second implementation of it here would disagree with
#   the real one exactly where that matters. What this gate says is that the two documents agree,
#   or that a fact only one of them states has been read.
#
# Usage:
#   .github/scripts/ui-builder-equivalence.sh --policy <path> --golden <path> [--differences <path>]
#                                             [--catalog-id <id>] [--record <path>] [--strict]
#
#   --policy       a catalog's authored ui-builder.policy.json, or its generated ui-builder.json
#                  (a local checkout, or fetched from the delivery branch)
#   --golden       docs/design/fixtures/ui-builder/<id>-capabilities-v1.json
#   --component-id-prefix
#                  the prefix the caller believes this catalog's components carry (`wear-m3/`,
#                  `m3/`). An assertion in its own right, exactly like --catalog-id and for the same
#                  reason: it decides which of the frozen catalog's components this one is
#                  answerable for, and taking it from the document under test lets that document
#                  choose its own scope. The remote-m3 golden holds both `m3/…` and `remote-m3/…`,
#                  so a generated Remote catalog declaring `m3/` — a real prefix, present in the
#                  golden — could drop its own component and pass. Required by --strict for a
#                  document that publishes a per-component menu.
#   --catalog-id   the catalog the caller MEANT to check. An assertion in its own right, not a
#                  fallback for a silent policy: it is checked against the golden's id AND against
#                  the policy's, so asking for one catalog while holding another's policy and its
#                  matching golden fails — those two agree with each other, and only the caller
#                  knows which catalog was intended. Required for a policy that defaults its id from
#                  the cover sheet (:remote-catalog and m3-catalog both do). Semantics never
#                  identify a catalog — a second `wear` catalog can agree on every compared field —
#                  so without an id `--strict` cannot tell "ready" from "you read the wrong file".
#   --shapes       the decoder shape table (default: decoder-shapes.json beside this script,
#                  generated by DecoderShapeFixtureTest from the real SerialDescriptors)
#   --record       the catalog's components.json. Turns on the COMPONENT ID comparison: which ids
#                  the catalog would put on the shelf, how many collided, and which the frozen
#                  catalog has that this one would not. Off without it, so a caller who omits it
#                  gets the catalog-level checks only and can read `--strict` success for a shelf
#                  that shares almost nothing with the frozen one. Pass it at the cutover.
#   --differences  a JSON array of {"field": …, "why": …, "policy": …} — differences somebody has,
#                  each field named at most ONCE: a second entry for a field would silently replace
#                  the first, leaving a review decision nothing ever judged.
#                  read and accepted. `why` is printed, because an unexplained exemption is how a
#                  gate stops meaning anything; `policy` is the exact value that was reviewed,
#                  because an exemption that outlives the thing it exempted is the other way. A
#                  waiver naming only the field would approve every future value of it — an
#                  accidentally emptied menu passing under the entry that reviewed a deliberate
#                  reordering — so a changed value re-surfaces as a stale exemption.
#
# Seven things fail under `--strict`: a difference nobody has accepted, a fact the frozen catalog
# states that the catalog is silent about, a fact the CATALOG states that the frozen one cannot
# check (accepted with `"frozen": null` once somebody has read it — the mirror of the silence rule,
# because one catches a catalog describing too little and the other a catalog describing something
# WRONG), a stale or unexplained exemption, a MISSING policy file, a policy that is not this
# catalog's — a different catalog from the golden's or from `--catalog-id`, a document naming TWO
# catalogs because a field that identifies one shape rides as ordinary payload in another, or one
# whose components are not the ones `--component-id-prefix` says they are — and a
# SCHEMA this gate cannot vouch for on EITHER document — a future major, an unrecognised family, a
# family that does not match the shape the document actually has, an unreadable string, or none at
# all where only a capability document may
# omit one. Refused
# outright rather than compared, because the fields this gate happens to recognise in a `…/v2` say
# nothing about the semantics it does not. The last three matter most: `--strict` is the cutover
# asserting readiness, so "there is no catalog here", "this describes nothing at all" and "somebody
# fetched a real file belonging to a different catalog" must none of them read as success. An
# exemption counts as stale once the disagreement it describes stops existing — whether the two
# sides AGREE again, both go SILENT, or either one stops stating the field. Any of those and it sits
# there re-authorising a return to the waived value with nobody re-reading it.
#
# What `builtins` can and cannot tell you. A builtin the frozen catalog DOES carry has its SLOT
# NAMES compared — like for like, and what slot validation and the structural templates route on —
# but not its role: a policy says `screen-root` where the frozen component says `Scaffold`, and
# composing one vocabulary into the other is the phase-4 loader rather than something to reimplement
# here. A declared builtin the frozen catalog carries no
# component for is a real difference and is reported — one field per id, `builtins.<id>`, so a
# deliberate addition can be reviewed and pinned like any other fact the frozen catalog cannot
# check, and so a waiver approves the builtin somebody read rather than the whole set. The reverse — a builtin the catalog OUGHT to
# declare and does not — is not checkable here, because the frozen catalog does not distinguish a
# builtin from a record component; that one is caught by the generator's `policy.builtin.*`
# diagnostics and by the phase-4 loader. Said plainly rather than left as a field that silently
# never differs.
set -euo pipefail

policy=""
golden=""
differences=""
catalog_id=""
component_id_prefix=""
record=""
shapes_override=""
strict=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --policy) policy="$2"; shift 2 ;;
    --golden) golden="$2"; shift 2 ;;
    --differences) differences="$2"; shift 2 ;;
    --catalog-id) catalog_id="$2"; shift 2 ;;
    --component-id-prefix) component_id_prefix="$2"; shift 2 ;;
    --record) record="$2"; shift 2 ;;
    --shapes) shapes_override="$2"; shift 2 ;;
    --strict) strict=1; shift ;;
    # The whole leading comment block, not a hardcoded line range: the range was `2,40p` and every
    # paragraph added above `Usage:` pushed the flags further out of it, so `--help` had quietly
    # stopped printing the flags it exists to document.
    -h|--help) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [[ -z "${policy}" || -z "${golden}" ]]; then
  echo "usage: $0 --policy <ui-builder.policy.json> --golden <…-capabilities-v1.json> [--differences <json>] [--catalog-id <id>] [--component-id-prefix <prefix>] [--record <components.json>] [--strict]" >&2
  exit 2
fi

if [[ ! -f "${golden}" ]]; then
  echo "ui-builder-equivalence: no golden at ${golden}." >&2
  echo "  Run scripts/regenerate-goldens.sh" >&2
  exit 2
fi

if [[ ! -f "${policy}" ]]; then
  # Through phases 2 and 3 this is the expected answer for a catalog that has not authored a policy
  # yet, and saying so is the point of a readiness check. Under --strict it is not: --strict is the
  # cutover asserting this catalog IS ready, and "there is no file" and "somebody fetched the wrong
  # path" must not read as success to the automation about to switch a reader over.
  echo "ui-builder-equivalence: ${policy} does not exist — this catalog does not describe itself yet."
  if [[ "${strict}" == "1" ]]; then
    echo "  --strict asserts readiness, and a catalog with no policy is not ready." >&2
    exit 1
  fi
  exit 0
fi

if [[ -n "${record}" && ! -f "${record}" ]]; then
  echo "ui-builder-equivalence: no component record at ${record}." >&2
  exit 2
fi

# The generated table lives beside this script. `--shapes` overrides it for the self-test,
# which needs to check that its ABSENCE is a usage error rather than a quiet pass.
shapes="${shapes_override:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)/decoder-shapes.json}"
node - "${policy}" "${golden}" "${differences}" "${strict}" "${catalog_id}" "${component_id_prefix}" "${record}" "${shapes}" <<'NODE'
const { readFileSync } = require("node:fs");
const [
  ,
  ,
  policyPath,
  goldenPath,
  differencesPath,
  strictFlag,
  expectedId,
  expectedPrefixArg,
  recordPath,
  shapesPath,
] = process.argv;
const expectedPrefix = expectedPrefixArg || "";
const strict = strictFlag === "1";

const read = (path) => JSON.parse(readFileSync(path, "utf8"));
const source = read(policyPath);
const golden = read(goldenPath);
const acceptedEntries = differencesPath ? read(differencesPath) : [];
const accepted = new Map(acceptedEntries.map((entry) => [entry.field, entry]));
// A `Map` keeps the LAST of two entries naming one field and drops the first without a word — so
// "every waiver is judged", the rule this whole file is built around, quietly stopped being true
// the moment somebody pasted an entry twice. The dropped one could be the unexplained or stale
// half, and `--strict` would report zero unusable exemptions and pass. Counted here rather than
// deduplicated, because two review decisions about one field are two people disagreeing, or one
// person editing the wrong copy, and neither is for this gate to resolve by picking one.
const duplicated = [
  ...new Set(
    acceptedEntries
      .map((entry) => entry.field)
      .filter((field, index, all) => all.indexOf(field) !== index),
  ),
].sort();

const semantics = golden.statusSemantics ?? {};

// Either shape. A generated `ui-builder.json` carries the facts under `statusSemantics` and calls
// the shelf order `componentMenu`; the authored `ui-builder.policy.json` carries them at the top
// level and calls it `menu`. Reading only one would make the gate useless either before the
// pipeline release or after the cutover, and it is the same facts either way.
const published = source.statusSemantics !== undefined;
const facts = published ? source.statusSemantics : source;
// A capability document is a third shape: it also carries `statusSemantics`, but its builtins have
// been materialised into top-level `components` alongside the record ones, so nothing in it says
// which were builtins. Named rather than lumped in with the generated artifact, because the two
// differ in exactly the way the builtin check depends on.
const capabilities = published && source.benchmark !== undefined;
const shape = !published
  ? "authored ui-builder.policy.json"
  : capabilities
    ? "capability document"
    : "generated ui-builder.json";
const menu = published ? facts.componentMenu : facts.menu;
const declaredBuiltins = Object.keys(facts.builtins ?? {}).sort();

// A future MAJOR is refused rather than compared. The contract's compatibility rule is that a
// reader ignores fields it does not understand and refuses a future major — and a gate that
// compared `…/v2` field by field would be reporting readiness for a document whose semantics it
// does not know, which is the readiness question answered by assuming the answer.
const SCHEMA_FAMILIES = {
  "compose-ui-builder-policy": 1,
  "compose-ui-builder-catalog": 1,
  "compose-ui-builder-capabilities": 1,
};
// And which family each SHAPE must declare. Knowing the family is not the same as the family being
// the right one: this gate detects the shape STRUCTURALLY — does the document carry
// `statusSemantics`, does it carry `benchmark` — so an authored policy labelled
// `compose-ui-builder-catalog/v1` was read as a policy, compared field by field, and passed. The
// argument is the one already made for an unrecognised family, one step in: the fields this gate
// happens to recognise say nothing about what the document MEANS, and a document whose label and
// whose contents disagree has one of the two wrong. Which one is not for this gate to guess.
const SHAPE_FAMILIES = {
  "authored ui-builder.policy.json": "compose-ui-builder-policy",
  "generated ui-builder.json": "compose-ui-builder-catalog",
  "capability document": "compose-ui-builder-capabilities",
};
// BOTH documents, not just the policy. The frozen catalog is regenerated from this repository's own
// types, so `CatalogCapabilityV1` moving to a v2 is the likelier of the two — and a v1 policy
// against a v2 golden is exactly as unreadable as the reverse, for the same reason: the fields that
// still line up say nothing about the ones whose meaning changed.
// Which shape each of the two documents is. The golden is a capability document by construction;
// the source is whichever shape was detected above. Defined before the function that reads it,
// because a `const` used ahead of its own initialiser is a temporal-dead-zone crash waiting for the
// first caller who reorders anything.
const shapeOf = (label) => (label === "catalog" ? shape : "capability document");

const refuseFutureMajor = (label, doc, mustDeclare, expectedFamily) => {
  const schema = doc?.schema;
  // A schema that is PRESENT and unreadable is refused, and a shape that must declare one and does
  // not is refused too. Only `family/version` says anything; `compose-ui-builder-policy-v999`, a
  // number, or an object all skipped every check below and let matching fields report readiness.
  // The exemption stays exactly where it was argued for: a CAPABILITY document (one carrying
  // `benchmark`) legitimately has no schema, and refusing those would make this an allowlist.
  if (schema === undefined || schema === null) {
    if (!mustDeclare) return false;
    console.log(`  x schema: the ${label} declares none, and only a capability document may.`);
    console.log(`      Without one this gate cannot say whether it understands the document.`);
    console.log("");
    console.log(`ui-builder-equivalence: refused — the ${label} declares no schema.`);
    return true;
  }
  if (typeof schema !== "string" || !schema.includes("/")) {
    console.log(`  x schema: the ${label} is ${JSON.stringify(schema)}, which is not`);
    console.log(`      'family/version', so this gate cannot tell what it is looking at.`);
    console.log("");
    console.log(`ui-builder-equivalence: refused — unreadable schema ${JSON.stringify(schema)}.`);
    return true;
  }
  const family = schema.slice(0, schema.lastIndexOf("/"));
  // The WHOLE version token has to be a version, not a number with something after it.
  // `Number.parseInt` reads a numeric prefix and discards the rest, so `v1junk` and `v999x` both
  // arrived here as a supported major and every field then compared cleanly — the same "it parsed,
  // therefore I understand it" the `.includes("/")` guard was fixed for one round earlier, one
  // character further along.
  //
  // A pre-release suffix stays legal (`v1-candidate` is what the frozen catalogs declare today) and
  // so does a minor (`v1.5` is the same major, which a reader is required to accept). What is
  // refused is a token this gate cannot read as a version at all.
  const version = String(schema.slice(schema.lastIndexOf("/") + 1));
  const major = /^v?(\d+)(\.\d+)*(-[A-Za-z0-9.]+)?$/.exec(version)
    ? Number.parseInt(version.replace(/^v/, ""), 10)
    : Number.NaN;
  const known = SCHEMA_FAMILIES[family];
  // An UNRECOGNISED family is refused for the same reason a future major is, and the reason is the
  // one this gate keeps coming back to: `compose-ui-builder-polciy/v999` still spells `platform` and
  // `componentMenu` the way this gate reads them, so every comparison passes and `--strict` reports
  // readiness for a document it has never heard of. "Not a family I know" and "a version I know is
  // too new" are the same answer — I do not know what this means — and only one of them was being
  // given.
  if (known === undefined) {
    console.log(`  x schema: the ${label} is ${JSON.stringify(schema)}, which is not a family this`);
    console.log(`      gate knows (${Object.keys(SCHEMA_FAMILIES).join(", ")}). The fields it`);
    console.log(`      happens to recognise say nothing about what the document means.`);
    console.log("");
    console.log(`ui-builder-equivalence: refused — unrecognised schema ${schema}.`);
    return true;
  }
  if (family !== expectedFamily) {
    console.log(`  x schema: the ${label} is ${JSON.stringify(schema)}, but its shape is`);
    console.log(`      ${shapeOf(label)}, which declares '${expectedFamily}'. A document whose`);
    console.log(`      label and whose contents disagree is not one this gate can vouch for.`);
    console.log("");
    console.log(`ui-builder-equivalence: refused — ${schema} on ${shapeOf(label)}.`);
    return true;
  }
  if (!Number.isFinite(major)) {
    console.log(`  x schema: the ${label} is ${JSON.stringify(schema)}, whose version is not a`);
    console.log(`      number, so this gate cannot tell whether it understands it.`);
    console.log("");
    console.log(`ui-builder-equivalence: refused — unreadable schema ${schema}.`);
    return true;
  }
  if (major <= known) return false;
  console.log(`  x schema: the ${label} is ${JSON.stringify(schema)}, a future major of '${family}'.`);
  console.log(`      This gate understands v${known}. Comparing the fields it happens to`);
  console.log(`      recognise would report readiness for semantics it does not know.`);
  console.log("");
  console.log(`ui-builder-equivalence: refused — unsupported schema ${schema}.`);
  return true;
};
// A capability document is the one shape allowed to be schema-less; everything else must say what
// it is. `capabilities` is computed above from the source's own `benchmark`; the golden is a
// capability document by construction.
if (
  refuseFutureMajor("catalog", source, !capabilities, SHAPE_FAMILIES[shape]) ||
  refuseFutureMajor("frozen catalog", golden, false, "compose-ui-builder-capabilities")
) {
  process.exit(strict ? 1 : 2);
}

// `$comment` keys are prose for a reader and are never part of a comparison — and object keys are
// sorted, because two generators emitting the same object in different insertion orders is not a
// difference and a gate that said it was would block the cutover on nothing. Arrays are left alone:
// shelf order and allowed values are information.
const strip = (value) => {
  if (Array.isArray(value)) return value.map(strip);
  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value)
        .filter(([key]) => !key.startsWith("$comment"))
        .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
        .map(([key, child]) => [key, strip(child)]),
    );
  }
  return value;
};

const canonical = (value) => JSON.stringify(strip(value));
// Two fields whose ORDER is not information, unlike the shelf order beside them.
//
// `colorTokens.roles` and `assetRegistry.keys` are read by `declaredStrings` in BOTH the runtime
// and the export, and both return a `Set<String>` — so a catalog listing exactly the frozen roles in
// a different order accepts exactly the same values and no consumer can tell. Comparing them by
// array order would block a cutover on a byte-order change, which is the mirror of the mistake this
// gate is otherwise built to avoid: reporting a difference that is not one is as useless as missing
// one that is.
//
// Both, not one. They are read by the same helper, and teaching this about a single one of them is
// the shape of half-fix this file has been corrected for repeatedly.
const asSet = (value) =>
  Array.isArray(value) ? JSON.stringify([...new Set(value.map(strip))].sort()) : canonical(value);
const show = (value) => (value === undefined ? "(absent)" : canonical(value));

const fields = [
  ["platform", facts.platform, semantics.platform],
  ["platformLabel", facts.platformLabel, semantics.platformLabel],
  // Compared, not merely carried. A surface entry says which backend renders a catalog
  // authoritatively and how honest the other one is, and phase 4 reads it to CHOOSE the native
  // backend — so a policy claiming `native.backend: "desktop"` for a catalog the frozen one renders
  // under Robolectric would change what gets drawn while every other field still matched. Left out
  // of this list it could not differ, and a field that cannot differ is the omission this gate
  // already had to be fixed for once.
  ["previewSurfaces", facts.previewSurfaces, semantics.previewSurfaces],
  ["componentMenu.groupOrder", menu?.groupOrder, semantics.componentMenu?.groupOrder],
  ["frame.adapter", facts.frame?.adapter, semantics.frame?.adapter],
  // The rest of what phase 4 CONSUMES, for the reason `previewSurfaces` is here: a field this list
  // leaves out cannot differ, and a field that cannot differ is checked by nothing. `seedDevice`
  // decides which device a new design opens on, `code` is what the emitter is routed through
  // (`code.strategy`, plus the imports and structural templates a generated screen is built from),
  // and `templates` names the documents the New-design chooser offers. Each of them would have
  // reached the cutover unread while `platform` and the shelf order agreed.
  //
  // Compared, not validated. Whether `code.strategy` is a strategy that exists, or a `templates`
  // path resolves to a document, is the authoring pipeline's question and is answered by
  // compose-ai-tools' `validate-ui-builder-policy.mjs` — a second implementation of it here, in a
  // gate that only holds the frozen catalog, would disagree with the real one where it matters.
  // What this gate can say is that the catalog and the frozen catalog do or do not agree, and that
  // a fact only the catalog states has been read by somebody.
  ["frame.seedDevice", facts.frame?.seedDevice, semantics.frame?.seedDevice],
  ["frame.geometry", facts.frame?.geometry, semantics.frame?.geometry],
  [
    "colorTokens.roles",
    facts.colorTokens?.roles,
    semantics.colorTokens?.roles,
    asSet,
  ],
  // Consumed by `PropertyValueKinds.declaredAssetKeys` and the runtime's own reader, and compared
  // by nothing — so a generated catalog declaring an EMPTY registry passed its real golden. A set
  // for the same reason as the roles above: one helper reads both.
  //
  // Compared only when the CATALOG states one, which is the difference between this field and
  // `componentPacks`. The contract's table of what a catalog declares does not list an asset
  // registry, and the frozen one holds `jetcaster.cover.*`, `ui-builder.gate0.cover` and
  // `editor.placeholder` — the packaged catalog's artwork and the editor's own insert placeholder,
  // none of it wear-m3-catalog's to ship. Treating its silence as a gap would demand that every
  // catalog declare the builder's assets, and a gap cannot be waived, so all three catalogs would
  // have been permanently not-ready with no route through. A catalog that states one is answerable
  // for it; a catalog that states none has nothing to say rather than too little.
  ...(facts.assetRegistry?.keys === undefined
    ? []
    : [["assetRegistry.keys", facts.assetRegistry.keys, semantics.assetRegistry?.keys, asSet]]),
  ["code", facts.code, semantics.code],
  ["templates", facts.templates, semantics.templates],
  // Documented in the contract's own table of what a catalog declares and two surfaces read — the
  // palette and the export — and nothing compared it. No catalog states one today and no frozen
  // catalog carries one, so this is silent until somebody does, which is exactly when a field that
  // cannot differ stops being harmless. The fifth time on this file.
  ["componentPacks", facts.componentPacks, semantics.componentPacks],
];

// EVERY component's shelf, not just the order of the shelves.
//
// `componentMenu.groupOrder` names the sections; `componentMenu.components` says which section each
// component lands in and which of its parameters the variant control writes to. Only the first was
// compared, so a generated catalog could move every component to a different shelf, or drop every
// `variantProperty`, and `--strict` would still report readiness — the insert panel at cutover
// bearing no resemblance to the frozen one while the section headings matched.
//
// A SUBSET, deliberately. The frozen catalog is a capability document: its menu carries the
// builder's own components (`asset/image`, `layout/box`, `remote-compose/*`) alongside the
// catalog's, and a generated `ui-builder.json` carries only the catalog's. Comparing the two maps
// whole would report a permanent difference nobody can fix, and waiving a thirty-entry map is a
// rubber stamp rather than a review. So each entry the CATALOG states is checked against the frozen
// entry for the same id; ids only the frozen one has are the builder's, not this catalog's to
// state, and the `builtins` check below is what covers that direction.
//
// Fed into the same `fields` list rather than compared separately, so waiver pinning, obsolescence
// and the unreviewed-field rule all apply to a shelf assignment exactly as they do to the frame —
// a second implementation of those rules here is how they would start to disagree. Only entries
// that DIFFER or already carry a waiver are added: adding the agreeing ones would print thirty `=`
// lines, and a waiver for an entry that has come back into agreement still has to be judged stale.
// A builtin the frozen catalog carries no component for, as one field per id.
//
// This was counted straight into `differences`, outside the model every other discrepancy goes
// through — so it could not be waived at all. Worse than that: the failure told the reader to put
// it in `--differences`, and an entry naming `builtins` was then reported a SECOND time by the
// sweep for waivers naming no compared field. The gate asked for a review decision and then
// refused the only place to record one, which is a check that cannot be satisfied rather than a
// check that can be answered.
//
// Per id, because a catalog that deliberately adds one builtin has reviewed THAT builtin, and a
// waiver naming the whole set would approve the next one nobody looked at. Each lands on the
// policy-only path — the catalog states it and the frozen catalog has no component to check it
// against — so it is accepted with `"frozen": null`, exactly like `frame.adapter`.
//
// The frozen catalog's own component ids are the one thing this can be checked against; a
// capability document does not distinguish a builtin from a record component, so it states nothing
// here and the note below says so rather than printing a silent pass.
const frozenIds = new Set((golden.components ?? []).map((component) => component.componentId));
const unknownBuiltins = capabilities
  ? []
  : declaredBuiltins.filter((id) => !frozenIds.has(id));
for (const id of unknownBuiltins) {
  fields.push([`builtins.${id}`, facts.builtins?.[id] ?? null, undefined]);
}
// A builtin the frozen catalog DOES carry: its id being present was reported as `= builtins (N, all
// present)`, which reads as agreement and checked nothing about the definition. Reassigning
// `wear-m3/screen-scaffold` a different structural role, or giving it different slots, passed.
//
// The SLOT NAMES, and deliberately not the role. Slot names are like for like — the policy keys its
// `slots` by name and the frozen component carries `slots[].name` — and they are what slot
// validation and the structural templates route on. The two `role` fields are NOT the same
// vocabulary: a policy says `screen-root` where the frozen component says `Scaffold`, and composing
// one into the other is the phase-4 loader this gate declines to reimplement in bash. Comparing
// them would report a permanent difference nobody could fix, which is the failure mode this file
// has now been corrected for twice.
let unservable = 0;
// WHY it is unservable, alongside the count. The epilogue used to state one remedy — declare
// `statusSemantics.components` so the ids stop colliding — because collisions were the only thing
// that set this counter. Six other refusals set it now, and a strict run that fails on a
// `parameters: null` should not close by advising a fix for a problem it did not report. Each
// refusal states its own remedy; the epilogue prints the ones that fired.
const unservableRemedies = [];
const refuse = (remedy) => {
  unservable += 1;
  unservableRemedies.push(remedy);
};

const frozenComponents = new Map(
  (golden.components ?? []).map((component) => [component.componentId, component]),
);

// ---------------------------------------------------------------------------------------------
// Which COMPONENTS the catalog would put on the shelf (--record).
//
// The header says this gate does not compare components, because composing a policy with a record
// is the loader's job and a second implementation in bash would disagree with the real one where it
// mattered. That still holds and this is not that: it compares component **ids**, which need no
// composition — an id is either declared by the policy or derived from the record entry, and
// nothing else about the component is read.
//
// It exists because a catalog can agree about every catalog-level fact above and still put a
// completely different shelf in front of an author. Measured on a real published file: 63 of 104
// record components collided on a derived id, and the 41 that survived shared exactly ONE id with
// the frozen catalog's 41. Note what passes there: any check comparing COUNTS. 41 against 41.
//
// One field per id, not one for the set, for the same reason the builtins above are: a catalog that
// deliberately adds one component has reviewed THAT component, and a waiver naming the whole set
// would approve the next one nobody looked at. Routed through `fields` rather than counted
// straight into `differences`, also for the reason recorded above — a discrepancy the gate reports
// but gives no way to record a decision about is a check that cannot be satisfied.
//
// `slug` is the one piece of the loader duplicated here, and it is trusted ONLY on ASCII.
//
// Six rounds of review found five ways a JavaScript port of a Kotlin `Char` loop diverges — a case
// conversion for a case property, a code point for a code unit, an ASCII digit class for a Unicode
// category, that category at one of two call sites — and each was fixed. The sixth has no fix in
// that style: Node and the JVM simply disagree about a character.
//
//   U+0295  ʕ   Java isLowerCase() = true    Node \p{Lowercase} = false
//   U+02B0  ʰ   Java isLowerCase() = true    Node \p{Lowercase} = true
//
// Measured on this box, Java 17 against Node 22. There is no property regex that fixes that,
// because it is not a wrong predicate — it is two different Unicode tables, and chasing them would
// mean shipping a copy of the JVM's in bash.
//
// So the derivation is REFUSED where it cannot be trusted rather than guessed at. A record leaf
// that is not pure ASCII is reported and blocks, and the reason says the gate cannot reproduce the
// reader's id for it. Every id this gate does derive is one the two implementations provably agree
// on; the alternative is an id that is silently wrong, which is the failure this whole comparison
// exists to prevent. Component leaves come from Kotlin identifiers and `componentIds`, so in
// practice this refuses nothing — and when it does fire, a human should look.
//
// `SLUG_PINS` stay, and the non-ASCII ones now document the divergences rather than guarantee the
// behaviour: they are unreachable in the comparison, because a non-ASCII leaf never gets that far.
// The four ASCII pins are the live contract, checked against the Kotlin in
// `PublishedUiBuilderCatalogTest`.
const SLUG_PINS = [
  ["RTLText", "rtl-text"],
  ["CheckboxButton", "checkbox-button"],
  ["Button2", "button2"],
  ["TopAppBar", "top-app-bar"],
  // Kotlin's `lowercaseChar()` is a SINGLE-character mapping; JavaScript's `toLowerCase()` is not,
  // and expands U+0130 to `i` + a combining dot. Taking the first code unit matches the Kotlin, and
  // this pin is what proves it — the ASCII cases above cannot see the difference.
  ["\u0130Button", "i-button"],
  // Above the BMP: Kotlin's `Char` loop sees two surrogates, neither a letter, so both separate.
  ["A\u{10400}B", "a-b"],
  // A numeric character outside the decimal category: `isDigit()` is false, so it separates.
  ["Widget\u00B2X", "widget-x"],
  // A decimal digit outside ASCII: `isDigit()` is true, so the letter after it starts a word.
  ["A\u0662B", "a\u0662-b"],
  // A letter that is lowercase by PROPERTY and has no distinct case conversion.
  ["\u02B0A", "\u02B0-a"],
];

// Single-character lowercase, matching Kotlin's `Char.lowercaseChar()`. JavaScript's
// `toLowerCase()` is not single-character — it expands U+0130 to `i` plus a combining dot — and the
// first code unit is what Kotlin produces. Safe because the loop below hands this ONE code unit.
const lowerChar = (ch) => {
  const lowered = ch.toLowerCase();
  return lowered.length > 0 ? lowered[0] : ch;
};

// Iterated by UTF-16 CODE UNIT, not by code point, because `String.forEachIndexed` in Kotlin walks
// `Char`s and a `Char` is a code unit. The difference is only visible above the BMP and it is not
// cosmetic: for `A𐐀B` Kotlin sees two surrogate `Char`s, neither of which is a letter, so both
// become separators and the id is `a-b`. A code-point loop sees one letter and keeps it — and
// `lowerChar` then truncates the surrogate pair to its high half, putting an unpaired surrogate in
// the id. Two divergences, both from iterating the string the way JavaScript makes natural rather
// than the way the reader does.
const slug = (name) => {
  let out = "";
  for (let index = 0; index < name.length; index += 1) {
    const ch = name[index];
    // `\p{Nd}`, not `\p{N}`. Kotlin's `isLetterOrDigit()` is `isLetter() || isDigit()`, and
    // `isDigit()` is the DECIMAL digit category alone — so `Widget²` loses the superscript there
    // and would keep it under the broader `\p{N}`.
    if (/[\p{L}\p{Nd}]/u.test(ch)) {
      const previous = index > 0 ? name[index - 1] : null;
      const next = index + 1 < name.length ? name[index + 1] : null;
      // The Unicode case PROPERTIES, not a round-trip heuristic. Kotlin asks `isUpperCase()` and
      // `isLowerCase()`, which are the `Uppercase`/`Lowercase` properties — and those include
      // characters with no distinct case conversion at all. U+02B0 MODIFIER LETTER SMALL H is
      // lowercase to the JVM while both JavaScript conversions return it unchanged, so
      // `c === c.toLowerCase() && c !== c.toUpperCase()` called it neither: `ʰA` slugged `ʰa` here
      // and `ʰ-a` in the reader.
      const isUpper = (c) => c !== null && /\p{Uppercase}/u.test(c);
      const isLower = (c) => c !== null && /\p{Lowercase}/u.test(c);
      const startsWord =
        previous !== null &&
        isUpper(ch) &&
        // `\p{Nd}` here too, and for the same reason as the admission test above: Kotlin's
        // `previous.isDigit()` is the decimal category, not `[0-9]`. An Arabic-Indic two before an
        // uppercase letter starts a word in the reader and did not here.
        (isLower(previous) || /\p{Nd}/u.test(previous) || (isUpper(previous) && isLower(next)));
      if (startsWord && out.length > 0 && !out.endsWith("-")) out += "-";
      out += lowerChar(ch);
    } else if (out.length > 0 && !out.endsWith("-")) {
      out += "-";
    }
  }
  return out.replace(/^-+|-+$/g, "");
};

for (const [name, expected] of SLUG_PINS) {
  if (slug(name) !== expected) {
    console.log("");
    console.log(
      `  x slug: this gate derives ${JSON.stringify(slug(name))} from ${JSON.stringify(name)}, ` +
        `but the reader derives ${JSON.stringify(expected)} — the two have drifted and every ` +
        `component comparison would be measuring the wrong ids.`,
    );
    process.exit(2);
  }
}

if (recordPath) {
  const recordFile = read(recordPath);

  // ------------------------------------------------------------------------------------------
  // Decode before comparing.
  //
  // Three findings in one round — a policy VALUE that is not an object, an AUTHORED policy standing
  // in for a generated one, and a record whose `componentIds` is a string that this code happily
  // indexes — are all the same mistake: this traverses raw JSON where the reader decodes into
  // types, so every shape Kotlin's decoder rejects is a shape the gate reads as ordinary data and
  // certifies. Patching them one at a time is how the previous eight rounds went; the checks below
  // close the family instead, by asserting the runtime's shape before any id is derived.
  //
  // The rule they all serve is the one already stated at the collision threshold, the empty shelf
  // and the malformed policy map: where the reader refuses, so does this, unwaivably.
  const isPlainObject = (value) =>
    typeof value === "object" && value !== null && !Array.isArray(value);

  // The shapes, generated from the decoders rather than transcribed from them.
  //
  // `.github/scripts/decoder-shapes.json` is written by `DecoderShapeFixtureTest` out of the live
  // `SerialDescriptor`s of `ComponentRecordFile` and `UiBuilderComponentPolicy`, and that test fails
  // if it ever stops matching. Each entry carries the property's `kind`, whether the key may be
  // ABSENT (`optional`, the property has a Kotlin default) and whether an explicit NULL decodes
  // (`nullable`, the property's type is nullable) — different questions kotlinx answers separately.
  //
  // It is generated because the hand-written version drifted, in the way a second implementation
  // always eventually does: it was transcribed by reading the data classes in a sibling checkout
  // that turned out to be OLDER than the artifact `:server` resolves, so it missed six fields the
  // published `TargetParameter` and `BuilderPolicy` carry, and certified a record that set one of
  // them to the wrong type. Nothing said the checkout was stale, and nothing would have.
  //
  // Unknown keys stay legal: both readers set `ignoreUnknownKeys`, so refusing what the reader
  // skips would fail catalogs for being newer than the gate.
  let decoderShapes;
  try {
    decoderShapes = JSON.parse(readFileSync(shapesPath, "utf8"));
  } catch (error) {
    console.log(`ui-builder-equivalence: cannot read the decoder shape table at ${shapesPath}.`);
    console.log(`  ${error.message}`);
    console.log("  Regenerate it with UPDATE_DECODER_SHAPES=1 ./gradlew :server:test --tests");
    console.log("  '*DecoderShapeFixtureTest*'. Without it --record cannot say what decodes.");
    process.exit(2);
  }
  const recordFileShape = decoderShapes.record?.members;
  const componentPolicyShape = decoderShapes.componentPolicy?.members;
  if (!recordFileShape || !componentPolicyShape) {
    console.log(`ui-builder-equivalence: ${shapesPath} is missing record or componentPolicy.`);
    process.exit(2);
  }

  // One walker for all three. `errors` collects `<path> <what is wrong>` so a message can name the
  // field rather than the document.
  const checkShape = (value, shape, at, errors) => {
    if (!isPlainObject(value)) {
      errors.push(`${at} is not an object`);
      return;
    }
    for (const [key, spec] of Object.entries(shape)) {
      const path = `${at}.${key}`;
      const present = Object.prototype.hasOwnProperty.call(value, key);
      if (!present) {
        // Absent is only fine where Kotlin has a default; otherwise the decoder throws
        // MissingFieldException before any consumer sees the record.
        if (!spec.optional) errors.push(`${path} is missing, and the property has no default`);
        continue;
      }
      const entry = value[key];
      if (entry === null) {
        if (!spec.nullable) errors.push(`${path} is null, and the property is not nullable`);
        continue;
      }
      switch (spec.kind) {
        case "string":
          if (typeof entry !== "string") errors.push(`${path} is not a string`);
          break;
        case "boolean":
          if (typeof entry !== "boolean") errors.push(`${path} is not a boolean`);
          break;
        case "int":
          // Kotlin `Int` is 32-bit and kotlinx refuses a JSON number outside it, so integrality is
          // not the whole test: 2147483648 is an integer here and an overflow there.
          if (typeof entry !== "number" || !Number.isInteger(entry))
            errors.push(`${path} is not an integer`);
          else if (entry < -2147483648 || entry > 2147483647)
            errors.push(`${path} is outside the range of a Kotlin Int`);
          break;
        // A map, or a free-form JsonElement. The reader accepts any shape there, so asserting one
        // would refuse records it decodes.
        case "any":
          break;
        case "enum":
          if (typeof entry !== "string" || !spec.values.includes(entry))
            errors.push(`${path} is not one of ${spec.values.join(", ")}`);
          break;
        case "stringList":
          if (!Array.isArray(entry) || entry.some((item) => typeof item !== "string"))
            errors.push(`${path} is not a list of strings`);
          break;
        case "objectList":
          if (!Array.isArray(entry)) errors.push(`${path} is not a list`);
          else
            entry.forEach((item, index) => {
              if (spec.members) checkShape(item, spec.members, `${path}[${index}]`, errors);
              else if (!isPlainObject(item)) errors.push(`${path}[${index}] is not an object`);
            });
          break;
        case "object":
          if (spec.members) checkShape(entry, spec.members, path, errors);
          else if (!isPlainObject(entry)) errors.push(`${path} is not an object`);
          break;
        default:
          throw new Error(`unknown shape kind ${spec.kind}`);
      }
    }
  };

  // `--record` answers "which components would this catalog put on the shelf", and only the
  // GENERATED artifact can answer it. An authored `ui-builder.policy.json` carries catalog-level
  // facts; per-component ids live in `@BuilderComponent` annotations and reach the shelf through
  // the generator, so deriving from an authored policy silently ignores every id an annotation
  // overrides and can agree with the golden by luck.
  //
  // Tested on the detected SHAPE, not on `published`: a capability document also carries
  // `statusSemantics`, so `published` is true for one, and it is the shape this question is least
  // able to answer — its builtins have already been materialised into `components`, so the ids it
  // holds are an OUTPUT of the composition this flag exists to predict. Checking the shape covers
  // both wrong shapes and any third one added later, which checking a flag that happens to exclude
  // one of them does not.
  if (shape !== "generated ui-builder.json") {
    const article = /^[aeiou]/i.test(shape) ? "an " : "a ";
    refuse(
      "Fetch the generated ui-builder.json from the catalog's delivery branch, or drop --record " +
        "to run the catalog-level checks alone.",
    );
    console.log("");
    console.log(
      `  x components: --record needs the GENERATED ui-builder.json, and this is ${article}${shape}. ` +
        (published
          ? `A capability document's components are the RESULT of composing a policy with a ` +
            `record, so deriving ids from it would compare the composition against itself.`
          : `An authored policy's per-component ids live in @BuilderComponent annotations that ` +
            `only the generator resolves — deriving them here would ignore every id an ` +
            `annotation overrides.`),
    );
  }

  // Every policy VALUE decodes as `UiBuilderComponentPolicy`, so a null, an array, or a MEMBER of
  // the wrong type fails the reader's decode exactly as a malformed map does. Checking only that
  // the value is an object was the container half of the same mistake this block exists to close:
  // `{"record": 7}` is an object, and `record` is a `String`.
  const badPolicyValues = [];
  if (isPlainObject(facts.components)) {
    for (const [componentId, entry] of Object.entries(facts.components)) {
      const errors = [];
      checkShape(entry, componentPolicyShape, `components[${JSON.stringify(componentId)}]`, errors);
      badPolicyValues.push(...errors);
    }
  }
  if (badPolicyValues.length > 0) {
    refuse(
      "Correct the policy entries named above so each decodes as UiBuilderComponentPolicy; the " +
        "server refuses the whole file, not the entry.",
    );
    console.log("");
    console.log(
      `  x components: ${badPolicyValues.length} problem(s) in statusSemantics.components that ` +
        `fail the reader's decode:`,
    );
    for (const problem of badPolicyValues.slice(0, 8)) console.log(`      ${problem}`);
    if (badPolicyValues.length > 8)
      console.log(`      … and ${badPolicyValues.length - 8} more`);
  }

  // And the record itself, in full. `ComponentRecordSource` decodes it as `ComponentRecordFile`; a
  // document that is merely valid JSON can carry a `componentIds` STRING, which `[0]` reads as a
  // character and `.split("/").pop()` turns into a plausible-looking id. Checking the three fields
  // the DERIVATION happens to read left every other one to fail at the reader instead — an absent
  // `symbol`, a `module` the file never states, a `parameters: null` — and the reader failing means
  // no record at all, not one component short.
  const recordShapeErrors = [];
  checkShape(recordFile, recordFileShape, "record", recordShapeErrors);
  if (recordShapeErrors.length > 0) {
    refuse(
      "Correct the record fields named above. The reader decodes components.json as a whole, so a " +
        "single bad field leaves the catalog with NO record, not one component short.",
    );
    console.log("");
    console.log(
      `  x components: ${recordPath} is not a component record the reader can decode, so no id ` +
        `derived from it means anything:`,
    );
    for (const problem of recordShapeErrors.slice(0, 8)) console.log(`      ${problem}`);
    if (recordShapeErrors.length > 8)
      console.log(`      … and ${recordShapeErrors.length - 8} more`);
  }

  // The prefix the POLICY declares, falling back to the caller's assertion — never the frozen
  // catalog's. Deriving with the golden's prefix guarantees the derived ids carry the prefix they
  // are about to be compared against, so a policy declaring the WRONG prefix passes: the ids line
  // up and nothing else here reads the field for an authored document. `facts` is the policy's
  // block, `semantics` the golden's; I reached for the wrong one.
  const prefix = (facts.componentIdPrefix ?? expectedPrefix ?? "").trim();
  // A plain object, or the artifact is refused. The reader decodes this field as
  // `Map<String, UiBuilderComponentPolicy>`, so `null` or an array fails its decode and returns
  // `Unusable` — while `?? {}` read `null` as "no policy" and `Object.entries([])` read an array as
  // an empty one, both of which let the gate compare ids for an artifact the server will not load.
  // The same rule as the collision and empty-shelf cases: where the reader refuses, so does this.
  const rawComponents = facts.components;
  const componentsIsMap =
    rawComponents === undefined ||
    (typeof rawComponents === "object" && rawComponents !== null && !Array.isArray(rawComponents));
  if (!componentsIsMap) {
    refuse(
      "Publish statusSemantics.components as a map of component id to policy, or omit it.",
    );
    console.log("");
    console.log(
      `  x components: statusSemantics.components is ${
        Array.isArray(rawComponents) ? "an array" : JSON.stringify(rawComponents)
      }, which is not a map of component id to policy — the reader fails to decode this file and ` +
        `refuses it, so the ids below cannot be compared and this cannot be waived.`,
    );
  }
  const declaredComponents = componentsIsMap ? (rawComponents ?? {}) : {};
  const policyByRecordId = new Map(
    Object.entries(declaredComponents).map(([componentId, entry]) => [
      entry?.record,
      [componentId, entry],
    ]),
  );

  const takenSet = new Set();
  const underivable = [];
  let collisions = 0;
  let eligible = 0;
  // Everything below derives ids from the record, and it runs only if the reader would have a
  // record to derive them from. When the shape check refused the file, one bad field used to
  // produce four more reports: an "empty shelf" refusal, a `0 record entries` summary, and a
  // `not offered` DIFFERENCE for every component the frozen catalog has — differences a reviewer
  // could waive, which would file a considered decision against a finding that was an artefact of
  // the first one. The refusal above is the whole answer; the rest is silent until it is fixed.
  if (recordShapeErrors.length === 0) {
    // Derive only from a record the reader would actually decode.
    //
    // Guarding the ARRAY was half the guard: `components: [null, …]` is a real list, so the loop
    // still reached `component.canonicalId` and died with a Node stack trace — which in a
    // report-only run (no `--strict`) turned a diagnostic exit 0 into a crash exit 1, the gate
    // failing a caller who asked it only to describe.
    //
    // Skipping the whole derivation, rather than the bad entries, is the point. `ComponentRecordFile`
    // decodes as a WHOLE: one bad field means the catalog has no record at all, so a shelf derived
    // from the well-formed remainder would be a shelf the server never builds. The refusal above
    // already says so; deriving anyway would put a number next to it that contradicts it.
    const recordComponents = recordFile.components;
    for (const component of recordComponents) {
      const declared = policyByRecordId.get(component.canonicalId);
      // An excluded component never enters the shelf, so it can neither claim an id nor collide with
      // one — the loader skips it before its collision check and so must this. Counting it would
      // report a surplus id for a component the server will never offer.
      if (declared?.[1]?.excluded != null) continue;
      eligible += 1;
      let componentId = declared?.[0];
      if (componentId === undefined) {
        const first = (component.componentIds ?? [])[0];
        const candidate = first ? first.split("/").pop() : "";
        const leaf = candidate && candidate.trim() ? candidate : (component.symbol?.name ?? "");
        // See the note above `SLUG_PINS`: outside ASCII this port and the reader are not provably the
        // same function, so the id is refused rather than derived.
        if (/[^\x20-\x7E]/.test(leaf)) {
          underivable.push(`${component.canonicalId} (leaf ${JSON.stringify(leaf)})`);
          continue;
        }
        componentId = prefix + slug(leaf);
      }
      if (takenSet.has(componentId)) collisions += 1;
      else takenSet.add(componentId);
    }
    for (const builtinId of Object.keys(facts.builtins ?? {})) takenSet.add(builtinId);

    // The prefix scopes ONE side, the frozen catalog's. It says which of the frozen ids this catalog
    // is answerable for — the golden also carries the BUILDER's own, `layout/box`, `asset/image`,
    // `remote-compose/*`, which no catalog states — and it must not be used to filter what the
    // COMPOSITION produced. A policy is free to map a record entry to an id outside its own prefix,
    // and the reader puts that id on the shelf; filtering it out here reported "the same ids on both
    // sides" for a shelf carrying a component the frozen catalog has never heard of. A gate that
    // narrows the evidence to the shape it expects only ever confirms itself.
    const goldenOwned = [...frozenIds].filter((componentId) =>
      prefix ? componentId.startsWith(prefix) : true,
    );
    const composedSet = takenSet;
    const shared = goldenOwned.filter((componentId) => composedSet.has(componentId));
    // Declared builtins outside the frozen catalog are already reported per id by `builtins.<id>`
    // above; excluded here so one mistake is not two findings.
    const declaredBuiltinIds = new Set(Object.keys(facts.builtins ?? {}));
    // Measured against the ids this catalog is ANSWERABLE FOR, not against every id the frozen
    // catalog happens to carry. A policy mapping a record entry to `layout/box` produces an id the
    // reader puts on the shelf and the catalog has no business publishing — and `frozenIds.has()`
    // waved it through, because the golden does contain it as a BUILDER component. Neither missing
    // nor surplus, so the "same ids on both sides" line printed over it.
    const ownedSet = new Set(goldenOwned);
    const surplus = [...takenSet].filter(
      (componentId) => !ownedSet.has(componentId) && !declaredBuiltinIds.has(componentId),
    );

    // A composition yielding nothing is refused by the reader outright — `taken.isEmpty()` returns
    // `Unusable` — so it cannot be waived here either. Without this the missing frozen ids each go
    // down the ordinary retirement path, and a differences file accepting them all made an EMPTY
    // catalog pass readiness.
    if (underivable.length > 0) {
      refuse(
        "Declare an explicit id in statusSemantics.components for each component named above; the " +
          "reader will not derive one from a non-ASCII leaf.",
      );
      console.log("");
      console.log(
        `  x components: ${underivable.length} record component(s) have a non-ASCII name, and this ` +
          `gate cannot reproduce the reader's id for them — Node and the JVM disagree about some ` +
          `characters, so a derived id here would be a guess. Declare these in ` +
          `\`statusSemantics.components\` and the gate reads the id instead of deriving it.`,
      );
      for (const entry of underivable.slice(0, 10)) console.log(`      ${entry}`);
      if (underivable.length > 10) console.log(`      … and ${underivable.length - 10} more`);
    }
    if (takenSet.size === 0 && underivable.length === 0) {
      refuse(
        "An empty shelf cannot be certified ready. Check that the record and the policy describe the " +
          "same catalog.",
      );
      console.log("");
      console.log(
        `  x components: composing this policy with the record yields no components at all — the ` +
          `server refuses that outright, so it cannot be waived.`,
      );
    }

    console.log("");
    console.log(
      `  components: ${recordComponents.length} record entries, ${eligible} eligible -> ` +
        `${takenSet.size} id(s)${collisions > 0 ? `, ${collisions} collided` : ""}; ` +
        `${shared.length} of the frozen catalog's ${goldenOwned.length} matched`,
    );

    // The collision rate is over ELIGIBLE entries, not the whole record: an excluded entry never
    // competes for an id, so counting it inflates the denominator and lets a policy that excludes
    // most of its record hide a shelf where everything left collides.
    //
    // Above the reader's own threshold this is NOT waivable, and that is the difference between a
    // readiness gate and a rubber stamp: `PublishedUiBuilderCatalog` refuses such a file outright, so
    // there is no decision for anybody to record — a waiver would have this gate certify a catalog
    // the server categorically will not serve. Mirrors the reader's `maxOf(1, eligible * 0.10)`
    // exactly; below it the server composes, so it is reported and left alone.
    const collisionAllowance = Math.max(1, Math.trunc(eligible * 0.1));
    if (collisions > collisionAllowance) {
      refuse(
        "The collisions are the catalog's to resolve, by declaring statusSemantics.components rather " +
          "than leaving every id to be derived.",
      );
      console.log(
        `  x components: ${collisions} of ${eligible} eligible record component(s) collided on an ` +
          `already-taken id, over the reader's allowance of ${collisionAllowance} — the server ` +
          `refuses a file this far from naming its components, so this cannot be waived.`,
      );
    } else if (collisions > 0) {
      console.log(
        `  ! components: ${collisions} of ${eligible} eligible record component(s) collided on an ` +
          `already-taken id. Under the reader's allowance of ${collisionAllowance}, so the server ` +
          `composes and skips them — but each one is a component this catalog does not offer.`,
      );
    }
    // BOTH sides asserted, never `undefined`. An absence spelled `undefined` lands on the
    // policy-silent path, which counts a gap and returns before any waiver is read — so a catalog
    // deliberately retiring one component could not record that decision anywhere, and an exact
    // `--differences` entry for it was reported obsolete on top. Stating "not offered" makes it an
    // ordinary difference between two known values, which is what it is.
    for (const componentId of goldenOwned.filter((id) => !composedSet.has(id))) {
      fields.push([`components.${componentId}`, "not offered", "offered by the frozen catalog"]);
    }
    for (const componentId of surplus) {
      fields.push([`components.${componentId}`, "offered by this catalog", "not offered"]);
    }
    if (collisions === 0 && surplus.length === 0 && shared.length === goldenOwned.length) {
      console.log(`  = components: the same ${shared.length} id(s) on both sides`);
    }
  }
}
if (!capabilities) {
  for (const id of declaredBuiltins.filter((builtin) => frozenIds.has(builtin))) {
    const frozenSlots = (frozenComponents.get(id)?.slots ?? []).map((slot) => slot?.name);
    fields.push([
      `builtins.${id}.slots`,
      Object.keys(facts.builtins?.[id]?.slots ?? {}),
      frozenSlots,
      asSet,
    ]);
  }
}

// A menu with NO `components` member is a menu that lists no components.
//
// The guard was `if (statedEntries && …)`, so an omitted map skipped both sweeps entirely while an
// empty one `{}` was handled — and those two documents say exactly the same thing. Every
// catalog-owned component would have vanished at cutover with the gate reporting ready, which is
// the finding from one round ago arriving through the shape I did not think of rather than the one
// I did. The published shapes carry a `componentMenu`; an authored policy carries `menu` with no
// components at all, and asking it for per-component shelves is what this must not start doing.
let malformedEntries;
let unrecognisedPrefix = null;
let disagreeingPrefix = null;
let undeclaredPrefix = false;
let unassertedPrefix = false;
// A `components` that is not an object — an array, a string, a number — says the same thing about
// the catalog's shelves as an empty one does: nothing this gate can read. The type guard treated it
// as "no map to compare" and skipped BOTH sweeps, so `[]` passed where `{}` blocked. That is the
// finding from one round ago arriving through the third shape of the same question, which is the
// argument for answering it once here rather than at the guard.
const declaredEntries = published && menu !== undefined ? (menu.components ?? {}) : undefined;
const entriesAreReadable =
  declaredEntries !== undefined &&
  declaredEntries !== null &&
  typeof declaredEntries === "object" &&
  !Array.isArray(declaredEntries);
const statedEntries = declaredEntries === undefined ? undefined : entriesAreReadable ? declaredEntries : {};
const frozenEntries = semantics.componentMenu?.components;
let agreeingEntries = 0;
if (declaredEntries !== undefined && !entriesAreReadable) {
  malformedEntries = declaredEntries;
}
if (statedEntries) {
  for (const id of Object.keys(statedEntries).sort()) {
    const field = `componentMenu.components.${id}`;
    const frozenEntry = frozenEntries?.[id];
    if (frozenEntry !== undefined && canonical(statedEntries[id]) === canonical(frozenEntry)) {
      agreeingEntries += 1;
      if (!accepted.has(field)) continue;
    }
    fields.push([field, statedEntries[id], frozenEntry]);
  }
  // And the other end. Sweeping only the catalog's own keys detected a shelf that MOVED and not one
  // that vanished: a generated menu omitting a component — an empty `components` map included —
  // matched nothing, reached no field, and passed. The component then loses its shelf and its
  // variant control at cutover with the gate reporting ready, which is the failure this comparison
  // was added for, arriving from the direction I did not sweep.
  //
  // Restricted to the catalog's OWN ids, because the frozen menu also carries the builder's
  // (`asset/image`, `layout/box`, `remote-compose/*`) and a catalog is not silent about those — it
  // has nothing to say about them. `componentIdPrefix` is published for exactly this kind of
  // question; without it there is no way to tell the two apart and the sweep is skipped rather than
  // guessed at.
  //
  // Stated as `null` rather than left undefined, and the difference is deliberate. For a top-level
  // fact, silence is a GAP that no waiver can fill — a catalog that says nothing about its platform
  // has not been reviewed, it is unfinished. Here the catalog publishes a menu and that menu does
  // not list this id, which is an assertion: the component is gone. So it is a DIFFERENCE, and a
  // deliberate retirement can be reviewed and pinned like any other.
  // The PUBLISHED prefix, and no fallback derived from the catalog id.
  //
  // Deriving `<catalogId>/` looks harmless and is wrong for a real catalog: m3-catalog's components
  // are `m3/…`, so the derived `m3-catalog/` matches none of its 25 frozen entries. A guess that
  // silently matches nothing disables the sweep exactly as a wrong published prefix would.
  // `componentIdPrefix` is published on the generated shape precisely so a consumer can name a
  // component this file says nothing about, which is the same question asked here.
  // The CALLER's assertion first, the document's second.
  //
  // Corroborating the document's prefix against the golden proved it matched something, not that it
  // was this catalog's — and the remote-m3 golden holds both `m3/…` (25, the packaged Material 3
  // catalog's) and `remote-m3/…` (1, its own). A generated Remote catalog declaring `m3/` therefore
  // named a real, corroborated prefix, reproduced those 25 entries, dropped its own
  // `remote-m3/lottie`, and passed `--strict` — the document under test choosing the scope it would
  // be judged on. Reproduced before this was changed, and it exits 0.
  //
  // So which components a catalog answers for is an assertion from outside it, exactly as its
  // identity is. `--catalog-id` was made an independent assertion for this same reason one round
  // earlier; this is that argument applied to the other thing a document can misdescribe about
  // itself.
  const declaredPrefix = facts.componentIdPrefix || null;
  const catalogPrefix = expectedPrefix || declaredPrefix;
  if (expectedPrefix && declaredPrefix && expectedPrefix !== declaredPrefix) {
    disagreeingPrefix = [expectedPrefix, declaredPrefix];
  }
  // A generated artifact has to publish its OWN prefix, and the caller's flag does not stand in for
  // it. `componentIdPrefix` is non-null on the generated shape and phase 4 reads it to name a
  // component the file says nothing about — so one omitted or empty is a malformed artifact, and the
  // truthy-only check above let a correct `--component-id-prefix` paper over it. The exemption is
  // the capability document, which publishes none by construction.
  if (!capabilities && published && !declaredPrefix) {
    undeclaredPrefix = true;
  }
  // And a document that publishes a per-component menu while nobody said which components are its.
  // Informational on its own; under `--strict` it is a refusal, because `--strict` asserts this
  // catalog is ready to replace that frozen one and the comparison behind that assertion was
  // scoped by the catalog itself.
  // Only where the scope actually decides something: a golden with no per-component menu has
  // nothing for a prefix to include or exclude, so demanding the assertion there would be a flag
  // for its own sake rather than for the hole it closes.
  if (!expectedPrefix && frozenEntries) unassertedPrefix = true;
  // CORROBORATED, not trusted. The prefix decides which frozen entries this catalog is answerable
  // for, and it is supplied by the document being checked — so a wrong one silently suppresses the
  // whole sweep, which is the thing under test deciding what gets tested. It is not hypothetical:
  // m3-catalog's components are `m3/…` while its catalog id is `m3-catalog`, so the id-derived
  // fallback matches none of its 25 frozen entries, and remote-m3's frozen menu is mostly the
  // packaged Material 3 catalog's `m3/…` with a single `remote-m3/…` of its own.
  //
  // So the golden has to recognise the prefix. If no frozen entry carries it, either the prefix is
  // wrong or these two files are not about the same catalog — both of which `--strict` exists to
  // refuse, and neither of which may read as "nothing to compare".
  //
  // A prefix the frozen catalog has never heard of is a disagreement about which components belong
  // to this catalog, and blocks. NO prefix at all is covered by the assertion rule above rather
  // than by a second message about the same situation: a capability document publishes none — its
  // builtins are materialised in and it does not distinguish them — and `--component-id-prefix` is
  // how a caller supplies it.
  const prefixCorroborated =
    catalogPrefix !== null &&
    Object.keys(frozenEntries ?? {}).some((id) => id.startsWith(catalogPrefix));
  if (frozenEntries && catalogPrefix !== null && !prefixCorroborated) {
    unrecognisedPrefix = catalogPrefix;
  } else if (frozenEntries && catalogPrefix) {
    for (const id of Object.keys(frozenEntries).sort()) {
      if (!id.startsWith(catalogPrefix)) continue;
      if (statedEntries[id] !== undefined) continue;
      fields.push([`componentMenu.components.${id}`, null, frozenEntries[id]]);
    }
  }
}

let differences = 0;
let gaps = 0;
let stale = 0;
let unstated = 0;

// A waiver names the values somebody reviewed — BOTH of them — not the field. Waiving the field
// approves every future value of it; pinning only one side leaves the other free to move under a
// waiver that no longer describes the discrepancy being waived.
const waiverVerdict = (waiver, stated, frozen) => {
  if (waiver.why === undefined || String(waiver.why).trim() === "") {
    return "the accepted difference carries no `why`";
  }
  if (waiver.policy === undefined) return "the accepted difference names no reviewed policy value";
  if (waiver.frozen === undefined) return "the accepted difference names no reviewed frozen value";
  // `null` is how a waiver says "and the other side states NOTHING", which is a reviewed fact like
  // any other — the field is absent, and somebody looked at that. `undefined` still means the entry
  // forgot to say, which is the thing the presence checks above are for. Normalising them here
  // keeps those two apart while letting an absent side be pinned.
  const same = (a, b) => canonical(a ?? null) === canonical(b ?? null);
  if (!same(waiver.policy, stated)) {
    return `the catalog changed since this was accepted\n      reviewed: ${show(waiver.policy)}\n      now:      ${show(stated)}`;
  }
  if (!same(waiver.frozen, frozen)) {
    return `the frozen catalog changed since this was accepted\n      reviewed: ${show(waiver.frozen)}\n      now:      ${show(frozen)}`;
  }
  return null;
};

console.log(`  read ${policyPath} as a ${shape}`);

// An exemption is only ever as good as the disagreement it describes — the third and last way that
// can stop being true. Convergence was one; both sides going SILENT is the other, and this branch
// returned before the waiver was consulted, so the entry survived to re-authorise the exact old
// discrepancy the day it came back.
const waiverIsObsolete = (field, why) => {
  if (!accepted.has(field)) return false;
  stale += 1;
  console.log(`  ! ${field}: ${why}.`);
  console.log(`      The accepted difference for it is obsolete and should be deleted; left in`);
  console.log(`      place it would silently re-authorise a return to ${show(accepted.get(field).policy)}`);
  return true;
};

// EVERY waiver is judged here, once, before any branch decides what to print.
//
// I added this check to the branches one at a time — the field agreeing, then both sides silent —
// and each time a branch I had not thought about still slipped past it. There is one question worth
// asking and it does not depend on which branch a field lands in: DOES THE DISAGREEMENT THIS WAIVER
// DESCRIBES STILL EXIST? It does only when both sides state the field and the two values differ.
// Asked here, a field shape nobody has thought of yet cannot acquire a fourth exemption from the
// rule, because the branches no longer carry it.
const comparedFields = new Set(fields.map(([field]) => field));
for (const [field, stated, frozen, compare = canonical] of fields) {
  // What a waiver can legitimately be reviewing, now that a policy-only fact needs one too:
  //
  //   both sides state it and the values differ  — a difference somebody accepted
  //   the catalog states it and the frozen one does not — a fact nothing here can check
  //
  // Anything else and the thing the waiver describes has stopped existing: the two agree again,
  // both went silent, or the catalog dropped the field while the frozen one kept it (which is a
  // gap, and blocking on its own — a waiver cannot make a missing fact present).
  const reviewable =
    stated !== undefined && (frozen === undefined || compare(stated) !== compare(frozen));
  if (reviewable) continue;
  waiverIsObsolete(
    field,
    stated === undefined && frozen === undefined
      ? "neither the catalog nor the frozen catalog states this"
      : stated === undefined
        ? "the catalog does not state this at all"
        : "this agrees with the frozen catalog",
  );
}
// And a waiver naming a field NOTHING compares. Iterating `fields` judged every waiver that could
// be reached from a compared field and silently skipped the rest — so a mistyped `colorTokens.rolez`
// sat in the differences file being counted as nothing at all, which is the same "an unchecked
// exemption is worse than none" this whole rule exists for. "Every waiver is judged" has to mean
// every waiver, not every waiver whose field happens to be on the list.
for (const field of accepted.keys()) {
  if (comparedFields.has(field)) continue;
  stale += 1;
  console.log(`  ! ${field}: no such compared field, so this exemption waives nothing.`);
  console.log(`      Compared fields are: ${[...comparedFields].join(", ")}.`);
}
for (const field of duplicated) {
  stale += 1;
  console.log(`  ! ${field}: named by more than one accepted difference, so only the last was read`);
  console.log(`      and the others were judged by nothing. Keep the one somebody means.`);
}

for (const [field, stated, frozen, compare = canonical] of fields) {
  if (stated === undefined) {
    if (frozen === undefined) {
      continue;
    }
    // The golden states this and the catalog does not. Silently skipping it is how a gate goes
    // green for a catalog that has described nothing at all — the failure that makes a readiness
    // check worse than no check, because it answers the question wrongly.
    gaps += 1;
    console.log(`  ? ${field}: the frozen catalog states ${show(frozen)}; the catalog is silent`);
    continue;
  }
  if (frozen === undefined) {
    // The catalog states a fact the frozen one has no opinion about — and phase 4 CONSUMES these:
    // `platformLabel` names the platform in the chooser, `frame.adapter` picks what draws the
    // canvas, `frame.geometry` is the measured padding a design is laid out against. Nothing has
    // compared them to anything, so an arbitrary label or a wrong padding table would have reached
    // the cutover with the gate reporting ready.
    //
    // This was informational while its mirror image — the frozen catalog states it and the policy
    // is silent — was blocking, and that asymmetry is indefensible: both are a fact nobody
    // compared. One risks a catalog that describes too little, the other a catalog that describes
    // something WRONG, and only the first was being caught. Accepted with a reviewed entry pinning
    // `"frozen": null`, which is somebody saying they looked at a value the golden cannot check.
    const waiver = accepted.get(field);
    if (waiver === undefined) {
      unstated += 1;
      console.log(`  x ${field}: stated as ${show(stated)}; the frozen catalog says nothing, so`);
      console.log(`      nothing here has checked it. Accept it with "frozen": null once read.`);
      continue;
    }
    const problem = waiverVerdict(waiver, stated, frozen);
    if (problem !== null) {
      stale += 1;
      console.log(`  ! ${field}: ${problem}`);
      continue;
    }
    console.log(`  ! ${field}: unchecked by the frozen catalog, accepted — ${waiver.why}`);
    continue;
  }
  if (compare(stated) === compare(frozen)) {
    // A waiver on a field that now AGREES has outlived the discrepancy it was written for. Skipping
    // the check here left it valid indefinitely, so if the catalog ever returned to the exact value
    // that was waived, the old entry would authorise the regression with nobody re-reading it —
    // which is the failure the reviewed-value pinning exists to prevent, reintroduced by the happy
    // path. An exemption is only ever as good as the disagreement it describes.
    // Its waiver, if any, was already judged obsolete by the sweep above.
    if (accepted.has(field)) continue;
    console.log(`  = ${field}`);
    continue;
  }
  const waiver = accepted.get(field);
  if (waiver === undefined) {
    differences += 1;
    console.log(`  x ${field}`);
    console.log(`      catalog: ${show(stated)}`);
    console.log(`      frozen:  ${show(frozen)}`);
    continue;
  }
  const problem = waiverVerdict(waiver, stated, frozen);
  if (problem !== null) {
    stale += 1;
    console.log(`  ! ${field}: ${problem}`);
    continue;
  }
  console.log(`  ! ${field}: accepted difference — ${waiver.why}`);
}

// Said once rather than thirty times: the entries that agree are the reason this comparison is worth
// having, and printing each of them would bury the ones that do not.
if (agreeingEntries > 0) {
  console.log(`  = componentMenu.components (${agreeingEntries} shelf assignment(s) agree)`);
}

if (capabilities) {
  // Not "no builtins" — "this shape cannot tell". Reporting nothing here would look identical to a
  // catalog that declares none, which is the difference between a check and its absence.
  console.log(`  ~ builtins: a capability document does not distinguish them from record`);
  console.log(`      components, so there is nothing here to check.`);
} else if (declaredBuiltins.length > 0) {
  if (unknownBuiltins.length === 0) {
    // Says what it checked. "All present" read as agreement about the definitions, and the only
    // thing this shape can compare like for like is the id and the slot names.
    console.log(
      `  = builtins (${declaredBuiltins.length}: ids and slot names present in the frozen catalog;` +
        ` a builtin's role is a different vocabulary on each side and is not compared)`,
    );
  }
  // The unknown ones were reported above, by the field loop, one per id.
}

// The prefix the catalog names, which the frozen catalog does not recognise. Reported with the
// identity checks below rather than as a difference, because that is what it means: the two files
// disagree about which components belong to this catalog, and no per-component comparison beneath
// that disagreement is worth reading.
if (unrecognisedPrefix !== null) {
  console.log("");
  console.log(
    `  x componentIdPrefix: this catalog's components are ${JSON.stringify(unrecognisedPrefix)}, ` +
      `and the frozen catalog has no component under it`,
  );
  console.log(`      So nothing could be compared: either the prefix is wrong, or this policy and`);
  console.log(`      this golden are not about the same catalog. Not a difference to waive.`);
}

if (malformedEntries !== undefined) {
  differences += 1;
  console.log("");
  console.log(
    `  x componentMenu.components: ${show(malformedEntries)}, which is not a map of component id to` +
      ` shelf`,
  );
  console.log(`      Compared as if it named none, so every frozen entry below is reported.`);
}
if (disagreeingPrefix !== null) {
  console.log("");
  console.log(
    `  x componentIdPrefix: --component-id-prefix asked for ${JSON.stringify(disagreeingPrefix[0])}, ` +
      `but this catalog declares ${JSON.stringify(disagreeingPrefix[1])}`,
  );
  console.log(`      Not a difference to waive — the caller and the document disagree about which`);
  console.log(`      components belong to this catalog.`);
}
if (undeclaredPrefix) {
  console.log("");
  console.log(`  x componentIdPrefix: this generated catalog publishes none, and phase 4 reads it to`);
  console.log(`      name a component the file says nothing about. A caller's --component-id-prefix`);
  console.log(`      scopes this comparison; it does not stand in for the artifact's own field.`);
}
if (unassertedPrefix) {
  console.log(
    `  ? componentMenu.components: no --component-id-prefix was given, so which frozen components` +
      ` this catalog answers for came from the catalog itself`,
  );
}
// WHICH CATALOG IS THIS? Asked before anything above is believed.
//
// Every comparison so far has been of catalog-level SEMANTICS, and semantics do not identify a
// catalog: a second `wear` catalog with the same shelves, roles and frame agrees with this golden
// on every enumerated field while being a different catalog entirely. The id was printed and never
// checked, so `--strict` — whose whole job is to assert "this catalog is ready to replace that
// frozen one" — could answer yes about the wrong file. That is the same failure as the missing
// policy the header already argues about, arriving through a path that exists rather than one that
// does not.
//
// The authored shape may legitimately be silent: :remote-catalog defaults its id from its cover
// sheet's `system` on purpose. So `--catalog-id` lets the caller supply the id it believes it
// fetched. Silence with no fallback is not resolvable, and under --strict that is a refusal rather
// than a shrug — an unidentified catalog cannot be asserted ready.
// Wherever the file names itself: the authored policy's `catalogId`, the generated
// ui-builder.json's `catalog.id`, or a capability document's `benchmark.catalogSystemId`.
//
// WHICH of them, though, is decided by the shape — not by whichever happens to come first.
// A chain of `??` reads the fields in the author's order rather than the document's, and a
// capability document carries `catalog.id` as ordinary payload while being identified by
// `benchmark.catalogSystemId`. So a document whose benchmark says `remote-m3` and whose
// `catalog.id` says `wear-m3` was read as `wear-m3`, matched the Wear golden and the Wear
// `--catalog-id`, and passed `--strict` — the gate approving the wrong catalog through the one
// check that exists to stop exactly that. The fallback is kept for a shape that leaves its own
// field empty while plainly naming itself elsewhere, but it is a fallback now and not a race.
const IDENTIFIERS = {
  "authored ui-builder.policy.json": ["catalogId", source.catalogId],
  "generated ui-builder.json": ["catalog.id", source.catalog?.id],
  "capability document": ["benchmark.catalogSystemId", source.benchmark?.catalogSystemId],
};
const namedIds = Object.values(IDENTIFIERS).filter(([, value]) => value !== undefined);
const [shapeField, shapeId] = IDENTIFIERS[shape];
// Which field ACTUALLY supplied the id, not which one should have: a shape that leaves its own
// field empty falls back, and a message naming the empty field would be describing a value it did
// not carry — the sort of nearly-right report that costs somebody an hour.
const [identifyingField, declaredId] =
  shapeId !== undefined ? [shapeField, shapeId] : (namedIds[0] ?? [shapeField, null]);
const goldenId = golden.benchmark?.catalogSystemId ?? null;
let misidentified = 0;
// Counted apart from the identity checks below, and NOT folded into them. Both block, but they
// fail for different reasons and are fixed by different things: an id problem means the wrong pair
// of files was fetched, a prefix problem means the two files disagree about — or say nothing about
// — which components this catalog answers for. Summing them made an unasserted prefix print the
// catalog-id remedy, so the gate told a caller who HAD passed --catalog-id to pass --catalog-id.
const misprefixed =
  (unrecognisedPrefix === null ? 0 : 1) +
  (disagreeingPrefix === null ? 0 : 1) +
  (undeclaredPrefix ? 1 : 0) +
  (unassertedPrefix ? 1 : 0);

// A document that names itself TWICE must agree with itself, whichever name won above. Two
// identifiers disagreeing is not a difference to waive and not a question of precedence: one of
// them is wrong, this gate cannot know which, and picking a winner silently is how the wrong
// catalog gets approved. Reported before the golden is consulted, because it is true regardless of
// what the golden says.
const conflicting = namedIds.filter(([, value]) => value !== declaredId);
for (const [field, value] of conflicting) {
  misidentified += 1;
  console.log("");
  console.log(
    `  x catalog id: this ${shape} is identified by \`${identifyingField}\` as ` +
      `${JSON.stringify(declaredId)}, but it also declares \`${field}\`: ${JSON.stringify(value)}`,
  );
  console.log(`      A document that names two different catalogs has one of them wrong.`);
}

// `--catalog-id` is an INDEPENDENT assertion, not a fallback for a silent policy.
//
// Treating it as a fallback meant the caller's explicit target vanished the moment the file
// declared anything — so automation asking for `remote-m3` and handed the Wear policy against the
// Wear golden passed, because those two agree with each other and nobody ever compared them to what
// was asked for. The whole point of the flag is that the caller knows which catalog it MEANT, which
// is exactly the knowledge a mis-fetch destroys.
if (goldenId === null) {
  // The golden names no catalog, so there is nothing to be wrong about. Silent on purpose: this is
  // the shape of a hand-written fixture, not of a real frozen catalog.
  if (expectedId) console.log(`  ~ catalog id: --catalog-id ${JSON.stringify(expectedId)}; the frozen catalog names none`);
} else {
  if (expectedId && expectedId !== goldenId) {
    misidentified += 1;
    console.log("");
    console.log(
      `  x catalog id: --catalog-id asked for ${JSON.stringify(expectedId)}, but this golden is ` +
        `${JSON.stringify(goldenId)} — the wrong pair of files was fetched.`,
    );
  }
  if (declaredId !== null && declaredId !== goldenId) {
    misidentified += 1;
    console.log("");
    console.log(`  x catalog id: this is ${JSON.stringify(declaredId)}, the golden is ${JSON.stringify(goldenId)}`);
    console.log(`      Not a difference to waive — a policy for another catalog was read.`);
  }
  if (declaredId !== null && expectedId && declaredId !== expectedId) {
    misidentified += 1;
    console.log(
      `  x catalog id: --catalog-id asked for ${JSON.stringify(expectedId)}, the policy declares ` +
        `${JSON.stringify(declaredId)}`,
    );
  }
  if (declaredId === null && !expectedId) {
    misidentified += 1;
    console.log("");
    console.log(
      `  ? catalog id: the policy declares none and no --catalog-id was given, so there is nothing ` +
        `to check against the frozen catalog's ${JSON.stringify(goldenId)}`,
    );
  }
  if (misidentified === 0) {
    const how = declaredId !== null ? "declared" : "supplied with --catalog-id";
    console.log(`  = catalog id (${how}: ${JSON.stringify(declaredId ?? expectedId)})`);
  }
}

const id = declaredId ?? expectedId ?? "(unidentified)";
const blocking = differences + gaps + stale + misidentified + misprefixed + unstated + unservable;
console.log("");
console.log(
  `ui-builder-equivalence: ${id} — ${differences} difference(s), ${gaps} unstated fact(s) the ` +
    `frozen catalog has, ${stale} unusable exemption(s), ${unstated} unreviewed field(s) the frozen catalog ` +
    `has no opinion about.`,
);
if (misidentified > 0 && strict) {
  console.log("`--strict` asserts THIS catalog is ready to replace THAT frozen one, which cannot be");
  console.log("asserted about a catalog nobody has identified. Pass --catalog-id when the policy");
  console.log("defaults its id from the cover sheet.");
  process.exit(1);
}
if (misprefixed > 0 && strict) {
  console.log("`--strict` asserts THIS catalog is ready to replace THAT frozen one, which cannot be");
  console.log("asserted while the two files disagree about which components this catalog owns.");
  console.log("Pass --component-id-prefix to state it, and publish `componentIdPrefix` so a reader");
  console.log("with no caller to ask can find it too.");
  process.exit(1);
}
if (unservable > 0 && strict) {
  console.log("A file the reader refuses is not a difference anybody can accept — `--differences`");
  console.log("cannot make a catalog servable. What each refusal above needs:");
  for (const remedy of [...new Set(unservableRemedies)]) console.log(`  - ${remedy}`);
  process.exit(1);
}
if (blocking > 0 && strict) {
  console.log("A difference is either wrong, or deliberate and belongs in the list passed with");
  console.log("--differences — with a `why`, the `policy` value reviewed AND the `frozen` value it");
  console.log("was reviewed against, so the exemption fails when either side moves.");
  process.exit(1);
}
NODE
