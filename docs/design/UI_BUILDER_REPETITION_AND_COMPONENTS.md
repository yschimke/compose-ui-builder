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
component whose body is defined once — needs an **instance path** (`nodeId#3`,
`nodeId/childId#3`) wherever a bare node id is used today. That is the real cost of both, it is the
same cost twice, and it is worth paying once rather than half-paying it in two places.

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

### 1b. A loop in the document — deliberately not yet

A genuine loop needs data, and the format has none: `stateVariables` declares `Boolean`, `Int`,
`Double` and `String` (`StateDeclaration`), and the canvas threads state as `Map<String, String?>`.
Three things, not one:

1. a list-valued declaration — `valueType: "list"` with an `itemType` of named scalar fields — whose
   rows live **in the document**, or the canvas has nothing deterministic to draw;
2. a `layout/for-each` node: a `dataRef` and a `template` slot holding exactly one child;
3. property values that can be bindings — `{"type":"binding","path":"item.title"}` beside the
   existing `color` / `colorToken` / `string` wrappers, checked where the value is written, the rule
   [`UI_BUILDER_VALUE_SEMANTICS.md`](UI_BUILDER_VALUE_SEMANTICS.md) settled.

Export is then `items(rows, key = { it.id }) { item -> … }` in a lazy container and `rows.forEach`
elsewhere. But (3) is the moment the document stops being a scene graph and becomes a small
expression language whose interpreter is the canvas, and every id-anchored surface above needs the
instance path first. Hold it until a design needs data-driven repetition; take 1a, which is most of
the readability, for none of that.

## 2. A reusable component that becomes its own composable

Booked already — [`UI_BUILDER_PRODUCT_SPEC.md`](UI_BUILDER_PRODUCT_SPEC.md) §13 P1, "reusable user
components/templates made from catalog nodes" — and the generated file shape anticipates it: the
capability exporter writes the screen function and then appends supporting functions. A user
component is another entry in that tail.

A **symbol** in the document, its body reached from `components[*].root` while its nodes stay in the
ordinary `nodes` map, so every existing reducer, validator and renderer path is reused:

```jsonc
"components": {
  "contribution-cell": {
    "name": "ContributionCell",
    "params": [
      { "name": "level",   "valueType": "int",    "default": 0 },
      { "name": "label",   "valueType": "string", "nullable": true },
      { "name": "content", "valueType": "slot" }
    ],
    "root": "cell-root"
  }
}
```

An **instance** is an ordinary node: `componentId: "design/component-instance"`, a `symbolId`
property, arguments under the parameter names in the existing value wrappers, and `slots` for
slot-typed parameters.

- **Parameter types** are exactly the kinds the format already types — string, bool, int, float,
  colour, enum — plus `slot`, which is a `@Composable () -> Unit`. No domain types: a component
  taking an application's `Episode` is what a component pack is for
  ([`UI_BUILDER_COMPONENT_PACKS.md`](UI_BUILDER_COMPONENT_PACKS.md)).
- **The canvas** gains an argument scope and recurses into the symbol root on an instance. The
  `ancestors` guard `RenderNode` already carries makes a recursive component draw nothing rather
  than take the composition down.
- **The export** emits one `private @Composable fun ContributionCell(level: Int, label: String? =
  null, modifier: Modifier = Modifier, content: @Composable () -> Unit)` per symbol, after the
  screen function, and the instance becomes a call. Parameter order is declaration order so the
  generated file stays diff-stable.
- **Refusals**: no scaffold in a component body; a cycle through instances, refused at the reducer
  beside `validateTopology`; an instance only where the slot accepts the symbol's root component,
  which is the capability the symbol has.

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
palette as `project/<id>`. What separates this from a component pack is the thing that makes it
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
3. **Component symbols and instances**, in-document.
4. **`ui-builder/components/`**, reusing the designs reader; graduation needs nothing new.
5. **Data-driven loops**, last, against a design that needs one.
