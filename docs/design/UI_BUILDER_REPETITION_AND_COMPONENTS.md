# Repetition, reusable components, and reuse across files

**Status: the fold is built (2026-09); everything after it is a plan.** Three questions asked of a
generated screen at once — can the format loop, can a designer make a reusable component that
becomes its own composable, and can a component be shared between the designs in a project. They
are one design because they share a prerequisite, and answering them separately is how a document
model grows three overlapping ways of saying the same thing.

## What provoked it

A twelve-cell contribution row, exported:

```kotlin
Column(modifier = Modifier.padding(start = 0.dp, top = 4.dp, end = 0.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp), content = {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically, content = {
        Surface(modifier = Modifier.size(width = 11.dp, height = 11.dp), shape = RoundedCornerShape(3.dp), color = Color(4293124572L), content = { })
        Surface(modifier = Modifier.size(width = 11.dp, height = 11.dp), shape = RoundedCornerShape(3.dp), color = Color(4293124572L), content = { })
        // … ten more, character for character
```

Nothing is wrong with it. The design really does hold twelve nodes, because holding twelve nodes is
the only thing the document can say, and the canvas really does draw twelve squares. It is a
faithful screen that no one can read, which for a generator whose output is meant to be handed to a
person and kept is a real defect rather than a cosmetic one.

## The constraint all three run into

The document is a flat map of nodes plus a slot-per-parent child list
([`UiBuilderDocument`](../../ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderDocument.kt)),
and three separate walks agree with it one-for-one:

- the canvas — `RenderNode(document, nodeId, …)`, reporting `onBounds(node.id, …)` per node
  ([`UiBuilderRenderer`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderRenderer.kt));
- the exporters — `emitNode(nodeId, …)` in
  [`CapabilityComposeCodeExporter`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/CapabilityComposeCodeExporter.kt),
  and `ScreenGenerator` fed by
  [`ScreenDocumentProjection`](../../ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/export/ScreenDocumentProjection.kt);
- everything anchored to a node id: selection, the inspector, comments, `PlaygroundNodeBoundsService`,
  the SVG recorder.

So **one node id is one drawn box is one emitted call**, and it is load-bearing far outside the
renderer. Any construct where one node draws *n* boxes — a loop over data, an instance of a
component whose body is defined once — needs an **instance path** (`cell#3`, `cell#3/label`)
wherever a bare node id is used today. That is the real cost of both, it is the same cost twice, and
it is worth paying once rather than half-paying it in two places.

[`UiBuilderInstancePath`](../../ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderInstancePath.kt)
is that identity, and the canvas measures against it now. A path is deliberately no longer than it
has to be: with no repeat above a box the path **is** the node id, so every key written today is
character-for-character the one that was written before, and the chain — `row#2/cell#4/label` —
begins at the outermost repeat rather than at the root. That is what let the seam land before
anything draws a second copy.

What still carries a bare node id, and why:

| Surface | Carries | What moving it costs |
| --- | --- | --- |
| Canvas bounds, the selection hit-test | the path | done; a selected node outlines **every** box it drew |
| `compose-ui-builder-inspection/v1` snapshot | the node id | a published wire schema |
| Semantic actions (`CatalogRuntimeAction.nodeId`) | the node id | a wire field: the action names the box to press |
| The native lane's `testTag` | the node id | `ScreenDocumentProjection` tags, and `ServeSemanticsTags.index` drops a duplicated tag rather than guessing — which is exactly what a second copy would produce |

Each of those is a change whose reviewer needs a second copy in front of them to judge, so each
belongs with the construct that first draws one.

## 1. Loops

### 1a. Fold repetition at export — built, no format change

The twelve cells only *print* badly. Both generators now join a run of siblings that would emit
identical text and write it once:

```kotlin
// repeated:12 nodes:cell-0,cell-1,cell-2,…
repeat(12) {
    Surface(modifier = Modifier.size(width = 11.dp, height = 11.dp), shape = RoundedCornerShape(3.dp), color = Color(4293124572L), content = { })
}
```

The fold is how the same composition is *spelled*, never what it is: the run emits the calls it
replaced, in the same order, in the same parent scope, and `repeat` is `inline`, so the body is
composed exactly where the separate calls were. What may fold is therefore the question of what is
genuinely interchangeable, and each generator answers it in the strongest terms available to it:

| Where | What is compared | What is refused |
| --- | --- | --- |
| `ScreenGenerator` (`screen/generator/`, compose-ai-tools) | the children's **generated text** | a slot filled through a `SlotItem` — `item { … }`, where child identity has consequences the text does not show |
| `CapabilityComposeCodeExporter` | a subtree signature: component, properties, modifiers, event bindings, and the same question of every child per slot, ids excluded | a subtree carrying `stableKey` or `scrollStateKey` at any depth — the design's own claim that this node is a particular one — and the lazy containers, whose children are already wrapped in `item(key = …)` |

Three is the shortest run folded: two of anything is a pair a reader takes in at a glance, and
folding it costs two lines to save one.

Nothing else moves. The document is unchanged, the canvas is unchanged, comments still anchor, and
the checked-in Jetcaster benchmark source regenerates byte-identically — it holds no run of three
identical siblings, which is the honest reason rather than a claim of safety.

The follow-up, not built: a run that is identical **but for one leaf scalar** is the shape a real
heatmap has, and is a `listOf(…).forEach { … }` rather than a `repeat`. It needs the emitters to
print an expression where they currently print a literal, which is a narrow change in
`ScreenGenerator` and a wider one in the capability exporter, whose value accessors have no emitter
context to consult.

### 1b. A loop in the document — built for the canvas (2026-09)

A loop needs data, a node, and a way for a template to read a row. All three exist now, and only one
of them was new:

1. **The rows live in the document**, as the `data` property of the loop: a `list` value whose
   entries are `object` values. `ObjectValueV1` and `ListValueV1` were already on the wire, so
   nothing was added for this — the canvas has something deterministic to draw because the design
   carries it.
2. **`layout/for-each`** — a catalog component with a `data` property and a `template` slot holding
   exactly one child, on the Layout shelf, inserted with three starter rows so a fresh one draws
   three cells rather than nothing.
3. **A binding reads the row** — `{"type":"binding","value":"shade"}`, the same reader a component
   body uses for its arguments, resolved by the same substitution at the same point in `RenderNode`.
   That was the fear in this section's earlier draft ("the document stops being a scene graph"), and
   it turned out to cost nothing extra: the reader arrived with components, and a loop only changes
   where the dictionary comes from.

Instance paths carry the copies (`loop#2/cell`), which is why this needed step 2 first.

**The export — in the editor's own lane.** This matters, and the first version of this section did
not say it. `CapabilityComposeCodeExporter` is what the Issues panel reads and what writes the
checked-in benchmark fixture. What the **code pane** shows, and what the server's export and native
preview write, is the record-driven lane (`ScreenExportGate` → `ScreenDocumentProjection` →
`ScreenGenerator`), which has no loop: a design holding one is refused there by name, and
`ForEachRowsTest` pins that so the claim cannot rot. Teaching that lane a loop means a `forEach` in
`ScreenGenerator`, which lives in `compose-ai-tools` and reaches this repository through a release.

What the editor lane generates, with the same derivation a component's parameters use — because a
row and a placement's arguments are the same dictionary seen from two sides:

```kotlin
Column(modifier = Modifier, verticalArrangement = Arrangement.spacedBy(0.dp)) {
    kotlin.collections.listOf(LoopRow(shade = Color(0xFF9BE9A8)), LoopRow(shade = Color(0xFF40C463))).forEach { row ->
        Surface(…, color = row.shade, …) { }
    }
}

private data class LoopRow(val shade: Color)
```

The rows are checked at the gate rather than at each use, because the design carries them: a row
missing a key its template reads is `MISSING_ROW_VALUE`, and one holding the wrong shape is
`INVALID_ROW_VALUE` — a defaulted parameter and a `Color.Unspecified` both compile and draw the
wrong cell. Three more refusals came out of review, each of them source that looks right and does
not compile or does not behave: an identity (`stableKey`, `scrollStateKey`) inside a template, where
every row would reach one `key("…")` and remembered state would follow whichever row composed last;
two row keys that generate one Kotlin identifier; and a loop's own signature reaching into a nested
loop's template, whose bindings read the inner rows.

**A template may be a placement**, which is the composition worth having both for: one body, defined
once, drawn per row. A placement reads through its `arguments` rather than its properties, so the
signature reads those too and the call prints `Cell(containerColor = row.shade, …)`. `kotlin.collections.listOf` is qualified and the lambda binds a named `row` (never `it`)
for the capture reason the sibling fold names.

**A loop inside a lazy container is refused** (`LOOP_IN_LAZY_CONTAINER`). Its children are emitted
as `item(key = …)` blocks, and rows belong in `items(rows, key = { … })` — a different call, with a
key per row rather than one for the loop; a `forEach` inside an `item` would compose every row as a
single recycling unit. That emitter is the remaining piece.

## 2. A reusable component that becomes its own composable

Booked already — [`UI_BUILDER_PRODUCT_SPEC.md`](UI_BUILDER_PRODUCT_SPEC.md) §13 P1, "reusable user
components/templates made from catalog nodes" — and the generated file shape anticipates it: the
capability exporter writes the screen function and then appends supporting functions. A user
component is another entry in that tail.

**Built (2026-09).** The document carries `components` and a node's `component`; the Wasm canvas
draws a placement; validation counts a body as reachable through the component that owns it; and the
Compose export writes one `private @Composable fun` per placed component, with the keys its body
reads as parameters:

```kotlin
Row(…) {
    ContributionCell(containerColor = Color(0xFFEBEDF0), modifier = Modifier)
    ContributionCell(containerColor = Color(0xFF9BE9A8), modifier = Modifier)
    …
}

@Composable
private fun ContributionCell(containerColor: Color, modifier: Modifier = Modifier) {
    Box(modifier) {
        Surface(modifier = Modifier.size(width = 24.dp, height = 24.dp), color = containerColor, …) { }
    }
}
```

Three rules the export follows, each of them a refusal rather than a guess:

- **A parameter exists only where an emitter can print an expression.** `BINDABLE_PROPERTIES` is the
  one statement of which property of which component that is, read by the gate and by the emitter so
  they cannot disagree; a binding on anything else is `UNSUPPORTED_BINDING`, named before a line is
  generated rather than silently exported as the component's own default.
- **Every placement passes every key the body reads**, or `MISSING_ARGUMENT`. A defaulted parameter
  would compile and draw the wrong cell.
- **Parameter order is sorted key order**, so a re-export of an unchanged design is byte-identical.

`Box(modifier)` wraps the body because the modifiers a placement carries belong to the placement —
the canvas draws it the same way, which is what keeps the preview and the generated screen agreeing
about which box was sized.

A placement is **not a catalog component**: no catalog declares `design/component-instance`, both
validators exempt it by id, and where a placement may sit is the question of what its body root is.
A synthetic catalog entry would put a tile on the m3 palette that draws nothing until a component
exists, and would claim `design/…` is part of Material 3.

A **symbol** in the document, its body reached from `components[*].root` while its nodes stay in the
ordinary `nodes` map, so every existing reducer, validator and renderer path is reused:

```jsonc
"components": {
  "contribution-cell": { "name": "ContributionCell", "root": "cell-root" }
}
```

No parameter list — [compose-preview-contracts#57](https://github.com/yschimke/compose-preview-contracts/pull/57)
settled that. What an instance passes is a dictionary and what a body reads is a key of it
(`{"type":"binding","value":"shade"}`), so a generator derives the signature from those keys rather
than from a second statement of the same fact, free to disagree with the bodies and instances it
describes.

An **instance** is an ordinary node: `componentId: "design/component-instance"` and a `component`
field carrying `componentKey` and `arguments`. Its own field rather than a property key, so a reader
that has never heard of components can see what the node is and draw a placeholder.

- **Argument values** are the value wrappers the format already types — string, bool, int, float,
  colour, enum. No domain types: a component taking an application's `Episode` is what a component
  pack is for ([`UI_BUILDER_COMPONENT_PACKS.md`](UI_BUILDER_COMPONENT_PACKS.md)). A slot-typed
  parameter — a `@Composable () -> Unit` — is not yet expressible and waits for a design that needs
  one.
- **Bindings resolve once, at the placement.** `RenderNode` substitutes a bound property from the
  arguments in scope before any accessor reads it, so colour, text, dimension and the modifier chain
  see an ordinary value and know nothing about placements. A key the placement did not pass is left
  as it stands, so the accessor's own fallback draws the component's default rather than the design
  refusing to draw.
- **The canvas** gains an argument scope and recurses into the symbol root on an instance — built.
  The `ancestors` guard `RenderNode` already carries makes a recursive component draw nothing rather
  than take the composition down, and the placement opens a path scope
  (`UiBuilderInstancePath.placement()`) so the same body under two instances is two boxes.
- **The export** emits one `private @Composable fun` per placed component, after the screen
  function, and the instance becomes a call — built, and detailed above. A slot-typed parameter
  (`content: @Composable () -> Unit`) is the one part not built: it needs a body to hold a slot the
  placement fills, and waits for a design that needs one.
- **Refusals**, all built and all named before a line is generated: a scaffold in a body
  (`SCAFFOLD_IN_COMPONENT_BODY`); a component that places itself at any depth (`COMPONENT_CYCLE`) —
  the canvas stops that with its ancestor guard, generated source has none; a body that reads state
  or handles an event (`COMPONENT_BODY_READS_STATE`, `COMPONENT_BODY_HANDLES_EVENT`), because a body
  is a function of its arguments and the screen's variables are not in scope in its function; a
  placement that handles an event, which its call cannot carry; an argument of the wrong shape,
  which would otherwise become `Color.Unspecified` and paint nothing; two components that generate
  one function name, or a key that generates a parameter name the wrapper already has; and a binding
  on a node no body reaches, where there is no dictionary to read. A placement sits where its body
  would sit: it has no capability of its own, so the validator resolves it to its body root's, and
  the slot that would refuse the body refuses the placement.

It composes with 1b rather than duplicating it: a loop's template is an instance with one argument
per row field. Which is why this is worth building **before** data-driven loops, not after.

## 3. Reuse across the files of a project

Not a third mechanism. The repository already has both halves of this loop, and they are the same
two halves as [`UI_BUILDER_PROJECT_DESIGNS.md`](UI_BUILDER_PROJECT_DESIGNS.md).

**The near half — a component library by convention**, read exactly the way a project's designs are:

```text
ui-builder/designs/index.json        the designs a project has (built)
ui-builder/components/index.json     the components a project has (proposed)
ui-builder/components/<file>.json    one component symbol per file
```

A local directory (uncached, because it is the half that changes under you) and a served catalog's
`design-artifacts/<system>` branch (cached against that catalog's last load). Symbols reach the
palette as `project/<id>`.

**Built so far**, in `ServeUiBuilderComponentLibrary`: both sources, the index and symbol reads,
both honesty rules below, and two admin routes — a listing and one symbol with its digest and body.
A published symbol file is an ordinary `DesignDocumentV1` declaring exactly one component plus the
nodes its body is made of, so there is no second wire type, no second validator, and no contracts
release in the path. **Not built**: the editor side — a `project/<id>` on the palette, the import
that records id and digest into a design, and the drift report when a later read disagrees. What separates this from a component pack is the thing that makes it
worth having at all: these are catalog nodes all the way down, so the Wasm canvas draws them
properly rather than as a named placeholder — a pack cannot, and says so.

Two rules keep it honest:

- **Referenced, not copied** — a copy is not reuse. But a shared symbol changing under a design is
  the catalog-pin problem again, so an instance records the `symbolId` **and** the symbol's content
  digest, and a design whose library moved is reported as drifted with a preview, exactly as an
  explicit catalog-revision migration is. Never silently redrawn.
- **A symbol may only use components from the pinned catalog** (plus the packs the host admits), or
  a design that imports it cannot be drawn.

**The far half — graduation.** Once a component stops changing daily, generate it into the app's own
Kotlin and commit it. Discovery's `components.json` then picks it up, `ComponentRecordPacks`
projects it, and it returns as `<catalog>/<component>` on the palette of every design in the
project, exported as a call to real code. That is the end state, and it means the library convention
only has to carry a symbol during the phase where it is still moving — the failure mode
`UI_BUILDER_PROJECT_DESIGNS.md` names for designs, avoided the same way for components.

## Order

1. **The export fold** — done, both generators.
2. **Instance paths** for bounds, selection and comments — the prerequisite (1b) and (2) share.
   The canvas is done; the wire surfaces in the table above move with the first construct that
   draws a second copy.
3. **Component symbols and instances**, in-document — built, canvas and export.
4. **`ui-builder/components/`** — the library and its two rules are built on the server
   (`ServeUiBuilderComponentLibrary`, listed and fetched under
   `/admin/ui-builder/component-library`); the editor does not yet import from it. Graduation still
   needs nothing new.
5. **Data-driven loops** — built on the canvas and in the editor's export lane. Two emitters are
   still refused by name: `items(rows, key = { … })` for a loop inside a lazy container, and the
   record-driven lane the code pane and the server use.
