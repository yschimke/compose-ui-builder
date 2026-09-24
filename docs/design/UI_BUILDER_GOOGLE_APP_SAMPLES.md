# Building real app UIs against the real composables: what worked, what is missing

Five Google app screens — Gmail, Photos, Calendar, Keep and Play — were authored as
[UI builder designs](fixtures/ui-builder/designs/README.md) in `m3-catalog`, framed for a tablet,
and looked at again on a foldable and a phone. The renders are in
[`renders/google-app-designs/`](https://github.com/yschimke/compose-preview-server/blob/e26ab4f6e345e5cc2d3f8fea6156396a8ea5fe60/renders/google-app-designs/README.md).

This document is the answer to the question the exercise was set to ask: **does this approach work
for building tablet UIs with the real composables and the AndroidX adaptive libraries?**

The short answer is yes, and the useful half is the list of what is missing.

## What worked

**The adaptive libraries do the adapting.** `layout/supporting-pane-scaffold` is
`androidx.compose.material3.adaptive`'s own `SupportingPaneScaffold`, given a directive computed
from the frame's constraints. Gmail authored at 1280 dp shows a list beside a conversation; at
841 dp it is one pane; at 411 dp it is one pane. Nothing in the document says so — no breakpoint, no
`if`, no second design. `calculatePaneScaffoldDirective` decided, exactly as it will in the app, and
the generated Kotlin calls the same function.

**The generated Kotlin is the screen, not a description of it.** The export of the Gmail design is
770 lines calling `SupportingPaneScaffold`, `Scaffold`, `FloatingActionButton`, `ListItem`, `Card`,
`LazyColumn`, `FilterChip`, `Icon` and `Text` with ordinary modifiers, with a provenance comment
above each node naming the design node it came from. It is readable, and a person could keep working
in it.

**The local loop is fast and honest.** `DesignFixturesTest` replays a committed design, hashes it,
validates it against the pinned catalog and requires the Compose export to accept it;
`composePreviewRender` draws it. Four of the five screens were built without the server in the loop
at all.

**The catalog is wide enough for real screens.** Nothing in these five is a placeholder for a
*component* the catalog lacks the concept of. Every gap below is a gap in one component's vocabulary
or in one layout primitive — not a missing idea.

## What is missing

Ordered by how much it cost.

### 1. There is no navigation component at all

No `NavigationBar`, no `NavigationRail`, no `NavigationDrawer`, and above all no
`NavigationSuiteScaffold` — the one AndroidX component whose whole job is the thing this exercise is
about, picking the rail or the bar from the window size class.

Every one of these five screens needs one, so every one of them has a `layout/column` of
`m3/icon-button` and `m3/text` standing in for a rail, in `Kit.rail`. It is 11 nodes per screen for
something that should be one, and — the part that matters — **it does not adapt**. At 411 dp the
hand-rolled rail is still an 88 dp rail, where the real `NavigationSuiteScaffold` would have become a
bottom bar. The one place these designs stop being responsive is the one place the catalog has no
component.

`ListDetailPaneScaffold` is missing too, and it is the more common of the two pane scaffolds. Gmail
and Calendar are both list-detail screens wearing `SupportingPaneScaffold`.

### 2. The pane scaffold's width properties are accepted and ignored — **fixed**

`mainPanePreferredWidthDp`, `supportingPanePreferredWidthDp` and `paneSpacingDp` are declared by the
catalog, stored in the document, carried through the wire, echoed into the generated source's
provenance comment — and never read. The canvas draws the library's default weights and the
generated `BuilderSupportingPaneScaffold` takes no width arguments at all.

Gmail asks for a 400 dp list beside a 760 dp conversation and gets roughly 810/360, which is close to
the opposite. There is no diagnostic: unlike `variant` or `contentAlignment` below, these three drop
silently.

All three are read now, through the library's own API rather than by this repository partitioning
anything itself: a pane's preferred width is `PaneScaffoldScope.preferredWidth`, parent data the
scaffold's measure policy reads, and the gap between partitions is the directive's
`horizontalPartitionSpacerSize`. The generated `BuilderSupportingPaneScaffold` takes all three, and
a design that stated none of them exports byte-identical source, because an unstated width is
written as `null` and wraps nothing.

The belief that blocked this was that "the scaffold partitions the window itself", so it could not
be told otherwise. It can — that is what `preferredWidth` is for.

One consequence worth knowing about, because it is visible in the Jetcaster benchmark: honouring the
widths widens that design's supporting pane, its rows wrap over fewer lines, and content that used
to fall below the fold now fits. The fold therefore lands mid-row where it used to land cleanly
between rows. That is the design's content measured against the design's own frame; nothing in the
renderer chooses it.

### 3. No flow row, so a chip group cannot wrap — **fixed**

Material chip groups wrap. `layout/row` does not, and there is no `FlowRow`. At 411 dp Gmail's four
filter chips and Photos' five squeeze until "Attachments" is one letter per line — the single worst
thing in the compact renders, and there is no way to author around it short of branching on width,
which is what an adaptive layout exists to avoid.

`layout/flow-row` now exists, in the **foundation** (see [Where each gap
belongs](#where-each-gap-belongs)). It is `androidx.compose.foundation.layout.FlowRow`, on the
Layout shelf beside `layout/row`, and every property it reads is one a row or a column already
declared: along a line it arranges like a row (`horizontalArrangement`, `horizontalSpacingDp`) and
down the lines like a column (`verticalArrangement`, `verticalSpacingDp`), plus a
`maxItemsInEachRow` ceiling for a design that wants a fixed number per line. The canvas draws it,
both export lanes write it, and the generated file carries the `ExperimentalLayoutApi` opt-in only
when it actually wrote one.

The samples use it now: Gmail's and Photos' filter chips and Keep's note labels are
`layout/flow-row`s, so at 411 dp "Attachments" wraps onto its own line instead of breaking one
letter per line.

### 4. No staggered grid

`LazyVerticalStaggeredGrid` is what Keep's note board *is*. `layout/lazy-grid` gives uniform rows, so
the sample's short notes carry dead space under them.

### 5. No fixed column count

`layout/lazy-grid.columns` accepts only `adaptiveGrid` — `GridCells.Adaptive`. A calendar's
mini-month is seven columns because a week has seven days, at every width, and it cannot be said.
The sample builds it from five `layout/row`s instead. Filed as
[#901](https://github.com/yschimke/compose-preview-server/issues/901), which also covers the part
that made it expensive: `fixedGrid` was refused **only** by the server, after the local reducer,
validator, exporter and `DesignFixturesTest` had all accepted it.

### 6. Shapes are `small`/`medium`/`large` or a number — there is no circle

`shapeFor` understands the three size tokens and a numeric dp, and errors on anything else. A circle
— an avatar, a rail pill, a FAB — is spelled as half the height (`m_clip("20")` on a 40 dp box), so
the shape and the size have to be kept in step by hand, and getting it wrong is a render-time error
rather than a validation one.

### 7. The colour vocabulary is 13 roles, and the rest fail silently

`background`, `surface`, four `surfaceContainer*`, `primary`/`onPrimary`, `tertiary`/`onTertiary`,
`onSurface`, `onSurfaceVariant`, `outlineVariant`, `transparent`. **No `secondary`,
`secondaryContainer`, `primaryContainer`, `tertiaryContainer`, `error` or any `on*` for them** —
which rules out the ordinary Material spelling of a selected navigation item, an error state, or a
tonal surface.

The failure mode is the problem. `CapabilityValidator` declares these properties `string` with no
allowed values, so an unknown token validates, exports and commits; `UiBuilderNode.color` then
resolves it to the component default. The first version of the Gmail rail asked for
`secondaryContainer`, passed every gate, and drew no pill at all.

### 8. Several M3 components export as stand-ins, not the real thing — **search bar fixed**

The generated source is honest about this but a reader has to notice. `m3/search-bar` exports as
`BuilderSearchBar` — a `Surface` with a `BasicTextField` — not `androidx.compose.material3.SearchBar`.
The same goes for the carousel (a `Row`, not `HorizontalUncontainedCarousel`), the snackbar, the
dialog and the floating toolbar. For a screen whose point is "these are the real composables", the
search bar is a conspicuous one to approximate, and it is the component four of these five screens
open with.

The record lane — `ScreenDocumentProjection` and `ScreenGenerator`, which is what the server's
Compose export and the native render compile — now writes `androidx.compose.material3.SearchBar`
and `SearchBarDefaults.InputField` from authored component records, `HorizontalFloatingToolbar`
with `FloatingToolbarDefaults.standardFloatingToolbarColors`, and `PrimaryTabRow`/`Tab`. The
`BuilderSearchBar` and `BuilderHorizontalFloatingToolbar` stand-ins remain in
`CapabilityComposeCodeExporter` only, because that exporter compiles against the builder's own
material3, where the floating toolbar is not public. Play's "Recommended for you" strip is a
`layout/lazy-row` rather than a carousel: `HorizontalUncontainedCarousel` takes an index-keyed
`content` lambda that a design of five authored cards has no shape for, and a horizontally scrolling
row is what that strip is in the real app.

### 9. Silently unemitted properties

The export warns, which is good, but the warnings name things a design will reasonably ask for:

| property | consequence |
| --- | --- |
| `m3/card.variant` | an outlined card exports as a filled `Card` |
| `m3/icon-button.variant` | a filled or tonal icon button exports as a plain one |
| `layout/box.contentAlignment` | ignored entirely — alignment comes from the *child's* `alignment` |
| `m3/search-input-field.readOnly` | an editable field where the design said otherwise |

`layout/box.contentAlignment` is **fixed**: the canvas and `CapabilityComposeCodeExporter` read it
now, as the record lane already did, with a child's own `alignment` still winning. It was why every
sample's hand-rolled rail drew its icon in the top-left corner of the 56 × 32 dp indicator — four of
the five samples also never set it, and now do.

### 10. A literal cannot go in a search field — **fixed**

`m3/search-input-field.value` must be an object, so a screen that only wants to *show* a query has
to declare a state variable for it. Every one of these five designs carries a `*Query` state variable
that nothing reads.

`m3/search-input-field.value` is declared `["object", "string"]` now (m3-catalog's policy and the
frozen fixtures together), and the canvas and both exporters read a literal as the text the field
shows. The five samples dropped their unused `*Query` variables, which is also what lets them
export: a build without stateful authoring refuses any design holding a state read.

## Exporting the samples as Kotlin

`GoogleAppDesignExportTest` runs every sample through `ScreenExportGate`, the gate the server's
export and the editor's Code pane share. `DesignFixturesTest` never could have caught this: it
exports through `CapabilityComposeCodeExporter`, which has a hand-written emitter for every id.

| design | export | what it took |
| --- | --- | --- |
| Photos | ✅ | the gradient lowered to `Box(Modifier.background(Brush.…Gradient(listOf(…))))`; `kotlin.collections` joined the generator's expression packages for `listOf` |
| Keep | ✅ | `Text.textDecoration` and `FilterChip.shape` on the record; the duplicate `contentDescription` on each icon button removed (its `Icon` already carries it) |
| Play | ✅ | `PrimaryTabRow` with a literal index; the carousel became a lazy row |
| Gmail, Calendar | ❌, one reason | `SupportingPaneScaffold` is written as `calculatePaneScaffoldDirective(currentWindowAdaptiveInfo())` and `calculateThreePaneScaffoldValue(…)`, with each pane's width as `Modifier.preferredWidth`. The value needs `directive.maxHorizontalPartitions`, a member read off an expression, and `ScreenGenerator` has no form for one yet |

Calendar's "Up next" items were `m3/list-item`s with a `startAccentColor` bar, which only a draw
lambda can express; they are a coloured dot beside two lines now, which is also how the app marks
an event's calendar. Both pane scaffolds dropped their 12 dp `paneSpacingDp` for Material's own
24 dp partition spacer — `PaneScaffoldDirective.copy` is the same member-call gap, and 24 dp is the
spacing the adaptive guidance specifies.

## Where each gap belongs

The question every gap above eventually becomes is *which repository owns the fix*, and the answer
is not the same for all of them. It follows from which library publishes the component, not from
which screen wanted it:

| Gap | Library | Owner | State |
| --- | --- | --- | --- |
| Flow row (3) | `androidx.compose.foundation.layout` | **foundation**, this repository | `layout/flow-row` |
| Pane widths (2) | `androidx.compose.material3.adaptive.layout` | **this repository** — the authored `layout/` scaffold is ours | read through `preferredWidth` |
| Staggered grid (4) | `androidx.compose.foundation.lazy.staggeredgrid` | **foundation**, this repository | open |
| Fixed grid columns (5) | `GridCells.Fixed`, foundation | **m3-catalog** for the vocabulary | [m3-catalog#465](https://github.com/yschimke/m3-catalog/issues/465); the wrapper half closed with [#906](https://github.com/yschimke/compose-preview-server/pull/906) |
| `NavigationSuiteScaffold` (1) | `androidx.compose.material3.adaptive.navigationsuite` | **m3-catalog** | discovered, unshelved |
| `ListDetailPaneScaffold` (1) | `androidx.compose.material3.adaptive.layout` | **m3-catalog** | discovered, unshelved, and unexportable |

**Nothing here needs a new catalog.** The split is the one `ComposeFoundationCatalog` already
states: `layout/`, `shape/` and `asset/` are the builder's own vocabulary, owned here because they
are `androidx.compose.foundation` and `androidx.compose.ui` — one declaration per component rather
than one per design system — and m3-catalog declines to claim them on exactly those grounds.
Anything under `androidx.compose.material3`, adaptive or not, is m3-catalog's, and arrives through
discovery.

So the two halves of gap 1 are not this repository's to close. The published m3 catalog already
*discovers* `m3/navigation-suite-scaffold`, `m3/navigation-suite-item` and
`m3/list-detail-pane-scaffold` — they are in its 110 components — but no sticker declares them, so
no catalog id gives them a shelf and nothing offers them on a palette. The pane scaffolds are
refused by the export lane on top of that: *"no placeholder can be written for required parameter
`directive: PaneScaffoldDirective`"*. A sticker in m3-catalog is what unblocks the first; a
directive the projection can construct is what unblocks the second, and that half **is** this
repository's.

What the published catalog does already offer is the plain navigation family —
`m3/navigation-rail`, `m3/wide-navigation-rail`, `m3/short-navigation-bar` and their items, shelved
under "Navigation rail" and "Navigation bar". Those replace `Kit.rail`'s eleven hand-rolled nodes
with one, which is worth having; they are not adaptive, and the rail still does not become a bottom
bar at 411 dp.

## Two process notes

**A design pins a catalog revision, and a rotated revision orphans it.** Every design created before
this session is unreadable — `ui_builder_get_design` answers `catalogUnavailable` for all twelve,
including the repository's own reference designs. Whatever the intended lifecycle, "your saved work
stops opening" is a sharp edge.

**Agent grants are dropped by a server restart** mid-task, with no warning and a message that reads
like a configuration error rather than "ask again".

## Where the samples are

| | |
| --- | --- |
| designs | `docs/design/fixtures/ui-builder/designs/google-*.json` |
| renders | [`renders/google-app-designs/`](https://github.com/yschimke/compose-preview-server/blob/e26ab4f6e345e5cc2d3f8fea6156396a8ea5fe60/renders/google-app-designs/README.md) |
| tablet previews | `DesignFixturePreviews.kt` |
| three-size previews | `GoogleAppSizePreviews.kt` |
| live | `https://preview.coo.ee/ui-builder/google-gmail-tablet` and the four beside it |
