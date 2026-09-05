# Compose UI Builder: getting started

The UI builder is a separate Compose/Wasm authoring surface. `/ui-builder/` remains the default
`m3-catalog` instance, and explicitly enabled catalogs are also available at
`/ui-builder/<catalog>/`. It does not replace the existing `/wasm/<catalog>/` preview application.
Each design remains pinned to one catalog while the service can host a small operator-selected set.
Publishing a preview catalog never enables authoring for it automatically.

## Create a design in the website

Start the server with UI-builder persistence and open `/ui-builder/`. The website opens its New
design chooser when no design is named. The same chooser is available from **New design** in every
live editor.

1. Choose **Material 3** for a blank Compose screen, or **Remote Material 3** for a Wear widget.
2. For a widget, choose **Small widget** (216×76dp) or **Large widget** (216×124dp).
3. Enter a path-safe design ID and select **Create**. The dialog submits it as a form `POST`, and
   the redirect it is answered with lands you on the new design's own URL. The current operator
   token and collaboration identity ride along and are carried into that URL.
4. For a widget, search for a Column, Row, Box, or Surface and select **Add** (or drag it) to place
   it in the scaffold's single `content` slot. The inserted container stays selected; search for
   Text or other content and select **Add** again to fill its children slot. This container-first
   step is how one widget holds multiple ordered items.
5. Select a node and edit it in Properties. Changes are committed to the live revision
   automatically; Undo, Redo, Duplicate, and Delete use the same collaboration log.

A design has one URL, and it names the catalog and the design:

```text
/ui-builder/remote-m3/my-remote-screen
```

Opening it opens the design. It does not create one: a `GET` never writes, so a mistyped link
reports a design that is not there rather than quietly making it.

Creating is a `POST`. The New design dialog submits an ordinary form to
`/ui-builder/<catalog>` with the id, the template and any state variables, and the server answers
`303 See Other` with the design's permalink — which the browser follows, so the URL you end up on,
bookmark and share is the plain one above, and reloading it re-opens rather than re-creates. A
design id that already exists is not an error and is not overwritten: you land on the design that
is already there.

Automation that already holds a document uses the design's own API resource instead:

```shell
curl -X PUT --header 'If-None-Match: *' --data @design.json \
  https://<server>/api/ui-builder/v1/designs/my-remote-screen
```

`If-None-Match: *` is required, because that route creates and never replaces: without it the
answer is `428`, and against a design that already exists it is `412`. A successful `201` carries
the editor permalink in `Location`. Credentials are intentionally absent from these examples:
supply them through the server and client credential facilities, never in a shared URL, shell
history, or process arguments.

The `blank` template is a real, valid document: a `layout/scaffold` root with an empty
`layout/box` in its required content slot. In `remote-m3`, creation starts with the Small Wear widget scaffold instead:
a 216×76dp host frame with an empty content slot. Use `template=wear-widget-large` for the
216×124dp form. These copy the stable 240dp-screen preview contract—200×60dp or 200×108dp content,
8dp host padding, and 26dp corners—without depending on preview-only Glance code.

Catalog Add actions and drops resolve the concrete compatible slot before submitting the insert.
The selected node, catalog search, inspector mode, and generated operation sequence survive each
authoritative collaboration snapshot. Text, booleans,
catalog choices, bounded numbers, and declared colors are validated locally and then submitted as
the ordinary authoritative `SetProperty` operation. A rejection names the node and field beside
the control. Help returns to this guide. SVG and Compose export use the current committed revision
through the server or MCP adapter.

The canvas uses real catalog components where a supported Compose/Wasm adapter exists. A
compatibility adapter is explicit capability metadata, not a claim that an unavailable platform
API was silently substituted. The Jetcaster supporting-pane scaffold, for example, retains its
semantic component identity while its general adaptive Material adapter remains marked
unsupported. Inspect capability notes before treating a design as portable to another runtime.

Operators select the reviewed adapters with
`--ui-builder-catalogs m3-catalog,remote-m3`. The packaged deployment uses exactly that allowlist;
other served catalogs remain preview-only until added explicitly.
`remote-m3` is a deliberately small adapter: Small and Large Wear widget scaffolds plus Box, Row,
Column, Surface, Text, and nested Remote Compose document. It is not an alias for every M3
capability.

## Starting from a worked widget

`remote-m3`'s New Widget dialog offers four templates. Two are empty host frames — **Small widget**
(216×76dp) and **Large widget** (216×124dp) — and two are finished designs reproducing the widgets
in [`android/wear-os-samples`' `WearWidget` sample](https://github.com/android/wear-os-samples/pull/1386):

| Template | Host | What it is |
| --- | --- | --- |
| **Hello widget** | Small | Centred text on the theme's `primary`, matching `HelloWidgetContent`. |
| **Weather widget** | Large | A location over a large reading on the sample's sunny blue, matching `WeatherContent`. |

They exist to answer one question: can the designer express a real widget? Both are built entirely
from ordinary catalog components — `m3/surface`, `layout/box`, `layout/column`, `m3/text` — with no
widget-specific authoring vocabulary, so anything you can do to them you can do to your own.

Note where the boundary falls. The scaffold **is** the host frame, modelled on
`androidx.glance.wear.composable.WearWidgetContainer`: picking Small or Large picks the content box
(200×60dp or 200×108dp on a 240dp screen), and the frame adds padding around it. It carries the
container's own four parameters and nothing else —

| Property | Default | What it is |
| --- | --- | --- |
| `background` | `#FF272430` | The **widget's** background, which the host paints as the rounded rect. The default is the literal `WearWidgetContainer` forks from Wear Material 3's `surfaceContainerLow` and applies when a widget declares none. |
| `horizontalPaddingDp` | `8` | `WearWidgetParams.horizontalPaddingDp`. |
| `verticalPaddingDp` | `8` | `WearWidgetParams.verticalPaddingDp`. |
| `cornerRadiusDp` | `26` | `WearWidgetParams.cornerRadiusDp` — 26 squircle, 999 round, 0 rectangular. |

The background belongs on the scaffold, not on a surface inside it. On-device the coloured squircle
**is** the widget: `WearWidgetDocument(background = …)` hands the brush to the container, which
paints it as the round rect and insets the content by the padding. Filling the content slot with a
coloured surface instead draws a coloured rectangle inside a differently-coloured frame, which is
not what any widget looks like.

The radius is drawn behind the content rather than clipped, exactly as upstream does it, so content
that overflows a corner is visible instead of silently cut off.

### Gradients and images

`WearWidgetBrush` has four factories — `color`, `verticalGradient`, `horizontalGradient` and
`image` — and it is a **chain**: the container folds over every element, drawing each into the same
rounded rect before the content. The `background` property is the solid-colour element, the one a
string can carry. The other three are authored in the container's **`background` slot**, which is
that chain: drop a **Linear gradient layer** or an **Image asset** into it and it paints across the
whole frame, clipped to the corner radius, under the padded content. The slot accepts draw layers
and images only — a Text is not a brush, and upstream has no way to express one as a background.

A gradient layer's `direction` picks the axis, matching `verticalGradient` against
`horizontalGradient`. The default fill applies only when the chain is *entirely* empty, which is
what `WearWidgetBrush.isEmpty()` asks: a widget declaring a gradient and no colour gets the
gradient, not the gradient over `#272430`.

![The four widget background brushes: default, colour, gradient and image](design/evidence/ui-builder-remote-compose/widget-background-brushes.png)

The image tile shows the brush slot composing and clipping a bitmap to the frame. Whether arbitrary
widget artwork resolves in the browser is the builder's asset-registry question, not this
scaffold's: `asset/image` currently draws real pixels for the project-owned artwork keys and a
placeholder otherwise.

A blank widget declares none of this, so both empty templates open on the default frame:

![The empty Small and Large host frames on the default background](design/evidence/ui-builder-remote-compose/empty-widget-containers.png)

| Hello widget | Weather widget |
| --- | --- |
| ![The Hello widget design in the Small host frame](design/evidence/ui-builder-remote-compose/hello-widget.png) | ![The Weather widget design in the Large host frame](design/evidence/ui-builder-remote-compose/weather-widget.png) |

These are the builder's own renderer drawing the templates, not screenshots. They are not a compile
of the sample: the builder draws with Compose Material 3 while the widget runs Remote Compose on a
watch, so a template reproduces the sample's *design* and the pixel-fidelity question belongs to the
parity lanes.

## The code a widget generates

**Code** on a widget design does not show a Compose export, and could not: `remote-m3` has no
component record, and a widget ships as a `WearWidgetDocument` of Remote Compose rather than as a
screen someone calls. It shows what the design actually becomes —

```kotlin
// Generated from a Compose UI builder design. Do not edit by hand.
@file:Suppress("RestrictedApi")

import android.content.Context
import androidx.compose.remote.creation.compose.layout.RemoteAlignment
import androidx.compose.remote.creation.compose.layout.RemoteBox
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.creation.compose.modifier.RemoteModifier
import androidx.compose.remote.creation.compose.modifier.fillMaxSize
import androidx.compose.remote.creation.compose.state.rs
import androidx.compose.remote.creation.compose.state.rsp
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.glance.wear.GlanceWearWidget
import androidx.glance.wear.WearWidgetBrush
import androidx.glance.wear.WearWidgetData
import androidx.glance.wear.WearWidgetDocument
import androidx.glance.wear.color
import androidx.glance.wear.core.WearWidgetParams
import androidx.glance.wear.tooling.preview.SquircleSmallWidgetPreviewParams
import androidx.glance.wear.tooling.preview.WearWidgetPreview
import androidx.wear.compose.remote.material3.RemoteColorScheme
import androidx.wear.compose.remote.material3.RemoteMaterialTheme
import androidx.wear.compose.remote.material3.RemoteText

@RemoteComposable
@Composable
fun HelloWidgetContent() {
    RemoteMaterialTheme {
        RemoteBox(modifier = RemoteModifier.fillMaxSize(), contentAlignment = RemoteAlignment.Center) {
            RemoteText(
                text = "Hello, World!".rs,
                color = RemoteMaterialTheme.colorScheme.onPrimary,
                fontSize = 20.rsp,
            )
        }
    }
}

class HelloWidget : GlanceWearWidget() {
    override suspend fun provideWidgetData(
        context: Context,
        params: WearWidgetParams,
    ): WearWidgetData {
        val colorScheme = RemoteColorScheme()
        return WearWidgetDocument(background = WearWidgetBrush.color(colorScheme.primary)) {
            HelloWidgetContent()
        }
    }
}

@Preview(name = "Squircle Preview", device = "spec:width=1000dp,height=1000dp,dpi=320")
@Composable
fun HelloWidgetSquirclePreview(
    @PreviewParameter(SquircleSmallWidgetPreviewParams::class) params: WearWidgetParams
) = WearWidgetPreview(HelloWidget(), params)
```

Read what is *not* there: the host container. `remote-m3/widget-container-*` is this builder's
stand-in for `WearWidgetContainer`, and on-device the launcher draws that around widget content from
`WearWidgetParams`. So the scaffold's background becomes the `WearWidgetBrush` handed to
`WearWidgetDocument`, its size picks the preview-params provider, and its padding and radius are
checked against the shipped spec rather than emitted — a widget cannot choose them, and a design
that moved them is refused by name rather than generating a preview that draws a frame it does not
have.

![The Code pane showing a widget's generated Kotlin](design/evidence/ui-builder-remote-compose/widget-code-pane.png)

Refusals work the way the Compose exporter's do: a node with no Remote Compose counterpart is named
rather than approximated. An image background is the one to expect — `WearWidgetBrush.image` takes a
`RemoteImageBitmap`, which generated source cannot name from an asset key, so the refusal says to
supply the bitmap in `provideWidgetData` and add the call by hand.

## Adding a published Remote Compose component

Under the component list, a catalog that offers `remote-compose/document` also shows **Remote
Compose documents** — every preview the *serving* catalog of the same name publishes an
`ir/<id>.rc` for, grouped by component family and filtered by the same search box as the components
above. On `preview.coo.ee` that is the `remote-m3` sheet: the 28 Remote Compose components of
[yschimke/wear-m3-catalog](https://github.com/yschimke/wear-m3-catalog)'s `:remote-catalog`, in all
their published states.

**Add** fetches the document from the catalog's own `render/<id>.rc` lane, decodes it to check it
is one, and inserts a `remote-compose/document` node already holding those bytes. The canvas then
plays it in-process, like any other node — the same `RcComposePlayer` the deployed player lanes
use, so what you author is what the watch draws. A row is greyed while a fetch is in flight and
while no compatible slot is selected; a document that arrives and does not decode is refused with
the fetched id named, and nothing is written.

There is no drag handle on these rows, unlike the components above: the bytes are a network round
trip away, so a drag would have to promise an insert on release that it cannot make. The insert
resolves its slot when the bytes arrive rather than when the row is pressed, so moving the
selection mid-fetch refuses rather than inserting where you are no longer looking.

The palette is empty — and absent — when the box serves no catalog of that name, when that catalog
publishes no Remote Compose documents, or when the authoring catalog has nowhere to put one.

## Compose export needs a component record

The generator emits a call site only where a **discovered component record** proves one can be
written — the component is public, top-level, importable, not an overload collision, its signature
actually recovered — and refuses by name otherwise. A host with no record for a catalog cannot
export it, and says so rather than guessing.

The packaged deployment ships one for `m3-catalog` and installs it automatically; nothing needs to
be set. For a host you start yourself:

```text
--ui-builder-components m3-catalog=<components.json>
```

`components.json` is a preview bundle's own discovery output. The record shipped here covers the
component ids whose Compose mapping is unambiguous; ids it does not cover are **absent rather than
guessed**, so they refuse by name instead of emitting Kotlin that does not compile.

The capability is per catalog. `remote-m3` has no record and is not meant to — Remote Compose is
kept out of the Compose exporter by design — so a host serving both advertises Compose export on
`m3-catalog` and not on `remote-m3`.

## Reading the code a design produces

**Code** in the toolbar opens the Kotlin the export would write, under the canvas, updating on
every edit. It is the export's own output: same generator, same record, same allow-list. A design
the generator cannot express shows the reasons where the source would be, rather than blanking —
those reasons are the actionable half, and they are the same list the Issues tab reports.

**Native** compiles the design and renders it with real Compose on the host, beside the browser's
canvas rather than instead of it — a difference between the two renderers is the thing worth
seeing. It needs a host with a compile lane (`--playground-bundle`); where there is none, the
control is absent rather than present and failing. The render is tagged with each design node id,
so the server's annotation lane can report where every node landed in the frame.

## Build against a reference

The **Screen** inspector's Reference section attaches a picture to the design and draws it over the
canvas. Import a file, paste one straight onto the page (Figma's "copy as PNG" on a frame or a
component puts it on the clipboard), or press **Snapshot** to render the design as it stands and
build the reference from that. Whatever you attach is kept with the design and comes back the next
time it is opened, along with the alignment you left it at.

Four ways to compare, because they answer different questions:

| Mode | The question it answers |
| --- | --- |
| **Overlay** | Is this in the right place? The mock over the canvas, at an opacity you choose |
| **Difference** | Is this *exactly* right? Matching pixels go black, so anything you can see is a difference |
| **Split** | Does it look the same? A wipe, with the two sides not fighting for the same pixels |
| **Boxes** | Are the boxes the right size? An SVG's own rectangles, stroked over the canvas |

Scale and nudge line the picture up when the export was not taken at the frame's size.

### Marking one up, and going round again

Choosing a markup tool takes the pointer: while one is in hand, dragging on the canvas draws instead
of selecting, and everything but the pen is dragged out to the size you want. Draw, box, rounded
box, ellipse, arrow, a text label, and an image placeholder. Every mark is removable on its own.

**Erase** is the one that makes a real screenshot editable. A screenshot is one flat picture — there
is no card to delete — so painting a region in the screen's own background colour is how space gets
cleared. The palette carries the design's `background` and `surface` colours beside the marker
colours for exactly that. Clear the space, then build the real components into it and compare them
against everything still standing around them.

**Image…** drops a picture on the frame where you put it rather than fitted to it — one copied out
of Figma, say — and drag moves it. **Component…** does the same with a component from this catalog:
it is composed by the renderer that draws the canvas, photographed, trimmed to its own edges, and
placed as a picture. **Flatten** bakes the picture, the pieces and the marks into one reference and
clears them, so the next round of adjustment is measured against what was just agreed rather than
against the mock from three rounds ago.

### Building a placed component for real

A piece captured from the catalog remembers what it is a picture of, so once it is where it belongs,
**Build for real** turns it into an actual node: the slot is the deepest one under the piece that
accepts that component, and the insertion is the same one a drag from the catalog performs. The
picture then disappears, because the real thing is standing where it was. It is an ordinary document
edit — it undoes, and every collaborator sees it.

Nothing guesses. A piece with no provenance — a screenshot region, a picture pasted from Figma — has
no Build button, because deciding which component *that* is, is a judgement rather than a lookup;
that is the case to hand to an agent.

None of this is part of the design. No node holds it, the Compose and SVG exports cannot see it, and
nothing here reaches another collaborator's canvas — it is scaffolding for the person doing the
work. [`design/UI_BUILDER_REFERENCE_OVERLAY.md`](design/UI_BUILDER_REFERENCE_OVERLAY.md) says why
that line is drawn where it is, and where the one-way door between the two halves sits.

## Property coverage

| Catalog shape | Inspector | Round trip |
| --- | --- | --- |
| `string` | Text input | Preserves an existing encoded value type; new values use `string` |
| `boolean` | Toggle | `bool` |
| `number` / `integer` with editor bounds | Bounded input and step controls | `float` / `int` |
| `allowedValues` | Choice menu | Preserves the existing semantic type, such as `typographyToken` |
| Explicit local color capability | Color/token input | Literal ARGB/RGB colors and declared Material tokens |
| Object, array, nullable unions, or unbounded numbers | Read-only | No unsafe shape guessing |

Text currently covers content, typography style, weight, style, color, font size, line height,
letter spacing, minimum/maximum lines, wrapping, overflow, alignment, decoration, box alignment,
and layout weight. Environment axes—viewport, density, font scale, locale, theme, and layout
direction—are deliberately not node properties.

Existing `size`, `fillMaxWidth`, and `padding` modifiers render and export. Their JSON is visible in
the inspector, but modifier parameter editing is read-only until the released Design API has an
authoritative modifier mutation; the builder does not invent a browser-only operation.

## Connect an MCP agent

The builder is reachable over the server's own `/mcp` endpoint — the same one the catalog tools use,
with the same bearer. Seven tools, one per protocol request plus the native render:

| Tool | Capability | What it answers |
| --- | --- | --- |
| `ui_builder_list_catalogs` | `ui-builder-read` | What a design's `catalogPin` may name |
| `ui_builder_list_designs` | `ui-builder-read` | The designs on this box |
| `ui_builder_get_design` | `ui-builder-read` | One whole document, and the revision to quote next |
| `ui_builder_create_design` | `ui-builder-write` | A design, from a document or copied from one |
| `ui_builder_apply` | `ui-builder-write` | `DesignMutationV1` operations — insert, set, delete, move |
| `ui_builder_export` | `ui-builder-export` | The generator's Kotlin, or its refusals |
| `ui_builder_render_native` | `ui-builder-export` | A frame compiled by real Compose on the host, plus where each node drew on it |

They are absent from `tools/list` on a box that serves no builder, and `ui_builder_render_native` is
absent on one that cannot compile — a client reads what this server can do off the tool list rather
than off a failed call. Replies are the released `McpResponseEnvelopeV1`, except the native render,
which has no request type in the contract and says so in its own description.

A session runs: list catalogs → create or open a design → read its revision → apply mutations →
export. `baseRevision` is how a concurrent edit is detected, so quote the revision you read rather
than guessing it. The command's actor is filled from the presented grant and never from the message.

For a token-gated server, enable agent grants with an operator ceiling containing:

```text
ui-builder-read,ui-builder-write,ui-builder-export
```

The agent should `POST /agent-access/request` with a JSON `capabilities` array containing those
three names, show the returned approval URL and user code to the operator, and poll the returned
poll URL only at the advertised interval. Store the approved bearer in the MCP host's secret or
environment facility. Do not embed the bearer in a URL, checked-in configuration, or command-line
argument. Browser and MCP changes then converge through the same revision log; neither automates
the other's UI.

The UI-builder tools keep their own named read/write/export capabilities, while the catalog tools
beside them use cumulative `preview`/`live` scopes; both are checked per call, off the request the
credential arrived on. [`design/CATALOG_MCP.md`](design/CATALOG_MCP.md) carries the whole surface
and explains why this is one endpoint rather than the sidecar the product spec planned.
