# Compose UI Builder: getting started

The UI builder is a separate Compose/Wasm authoring surface. `/ui-builder/` remains the default
`m3-catalog` instance, and explicitly enabled catalogs are also available at
`/ui-builder/<catalog>/`. It does not replace the existing `/wasm/<catalog>/` preview application.
Each design remains pinned to one catalog while the service can host a small operator-selected set.
Publishing a preview catalog never enables authoring for it automatically.

## Before you start: Java 21

The UI builder's PNG and SVG export needs the server to be running on **Java 21 or newer**, even
though the server distribution itself targets Java 17. The design is rasterized by a Compose preview
compiled for 21 and unpacked into a daemon that runs on the server's own JVM, so the JVM you launch
the server with is the one that has to load it.

On an older JVM everything in this guide still works except PNG/SVG export, and the server says so
on startup — one line naming the version it found and the version it needs. Point `JAVA_HOME` at a
Java 21 JDK and restart to get the export back. `README.md` has the full picture, including why the
rest of the distribution stays on 17.

## Running it locally

One command:

```bash
compose-preview-server ui --no-project
```

That opens the builder against the design systems packaged inside it — `m3-catalog` and
`remote-m3` — and needs nothing else: no Gradle project, no `compose-preview` build host, and no
catalog to fetch. Designs are saved under `~/.compose-preview/ui-builder-state` and survive a
restart. A browser is opened on the builder; `--no-open` prints the URL instead.

The builder bundle it serves is already inside the server distribution, so downloading
`compose-preview-server-<version>.tar.gz` from a release is the whole install. Releases also carry
`compose-preview-ui-builder-web-<version>.zip` on its own, for serving the bundle yourself or
pointing an existing server at it with `--ui-builder-dir`. To build it from this repository
instead:

```bash
./gradlew :ui-builder:wasmFrontendDist   # writes ui-builder/build/wasmDist
```

### Against your own project

`ui` without `--no-project` is the other mode, and the one the rest of this guide's export sections
assume:

```bash
compose-preview-server ui --module app
```

It discovers and builds the module's `@Preview` functions and hands the builder that module's
`components.json`, so the Compose export writes code that calls **your** composables rather than
only the packaged design system's. That needs the `compose-preview` build host, because discovering
and building a Gradle project is work the server asks for over a pipe rather than doing itself —
without one it says so rather than serving a builder that looks like it worked.

### The flags underneath, and one that is easy to confuse

Both modes are `serve` with flags added, and every flag stays available:

- **`--ui-builder-catalogs <system>[,…]`** — the design systems the builder may author against. Each
  must have a *packaged adapter*; a catalog with none is refused at startup rather than fetched.
  This is the one that matters for the builder.
- **`--ui-builder-state-dir <dir>|none`** — where saved designs live. Defaults to
  `ui-builder-state` beside `--catalogs-file`, or `~/.compose-preview/ui-builder-state` standalone.
  `none` serves the builder's assets with no editable design API at all.
- **`--ui-builder-dir <dir>`** — the bundle to serve. Defaults to the one packaged beside the binary.
- **`--ui-builder-comment-webhook <url>`** — announce a design's **Talk** activity outward. A new
  thread, a reply and a resolution are posted to one incoming-webhook URL with the thread's
  permalink, so somebody reading a chat window hears about a comment without opening the builder.
  Reactions and acknowledgements deliberately do not fire. Only `https` is accepted (`http://` on
  loopback aside, for a test receiver), because a Slack or Teams hook URL is a credential — it is
  never logged, and the server names it by a digest. Off unless you set it. Set
  `--github-auth-callback-base-url` too on any host behind a proxy: the permalink is built from it,
  and without it the links carry the bind address. The deployed image derives it from `DOMAIN`.
  There is one destination for the whole host. Where a design has a `links.thread`, the event
  carries it as `design.thread` so a relay can thread the message; the server never posts to it,
  because a chat permalink is not an endpoint and `links` is written by anyone who can edit the
  design.
- **`--ui-builder-comment-webhook-format plain|slack|teams|google-chat`** — which body that hook
  receives. `plain` is this server's own event JSON and is the default; the other three are the
  incoming-webhook shapes those platforms accept. Named rather than guessed from the hostname: a
  hook behind a relay has a host that says nothing about what parses the body.
- **`--catalogs <system>[@<owner>/<repo>][,…]`** is **not** part of this. It fetches published
  catalogs from their `design-artifacts/<system>` branches and serves them as browsable preview
  sites at `/<system>/`. Publishing a catalog never enables authoring for it, and authoring against
  one never requires serving it — they are separate features that happen to share catalog ids.

The designs, the reference overlays and the comment threads are separate directories under the
state dir, so losing one loses only what it was.

Two things worth knowing before the first run. Export needs Java 21, as above. And on `remote-m3`
the **native preview lane cannot compile a widget**: a widget's generated source is Remote Compose
rather than Jetpack Compose, so `--ui-builder-native-catalog` has nothing to offer it and the Wasm
canvas is the authority — which is why the canvas and the generator have to agree about the same
design, and are tested against each other rather than separately.

## Create a design in the website

Start the server as above and open `/ui-builder/`. The website opens its New
design chooser when no design is named. The same chooser is available from **New design** in every
live editor.

1. Choose **Material 3** for a blank Compose screen, or **Remote Material 3** for a Wear widget.
   The chooser groups catalogs by platform — Mobile, Wear, Remote Compose — once the host enables
   more than one kind.
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

## The workspace

The editor opens on the canvas, with both docks closed: the design is the thing being worked on, so
it gets the window. Everything else is a panel behind a switch on one of the two rails.

| Rail | Switch | What it opens |
| --- | --- | --- |
| Left | **Insert** | The catalog for this design's pinned system, and the Remote Compose palette where the catalog publishes one. Drag a row onto the canvas, or select **Add** to drop it into the slot the panel names. |
| Left | **Layers** | The document as a tree. Filter it, ctrl/⌘-click and shift-click to build a selection, drag a row to reorder. |
| Right | **Properties** | The selected layer's catalog properties, its state bindings and its modifiers. |
| Right | **Theme** | The colours and typography the whole design is drawn with. |
| Right | **Screen** | The frame and its density, the presets that fill it in, the variant panes drawn beside the design, and the reference picture. |
| Right | **Issues** | What the export would refuse, counted on the rail itself. |
| Right | **Code** | The Kotlin the Compose export would write. |

Pressing a switch that is already lit closes that panel and gives the space back to the canvas.

The top bar carries what is global: the design's name and catalog, undo and redo, the
**Design / Preview** switch, **Export**, which renderer draws the canvas, and who else is in the
document. What
can be done to a *selection* — duplicate, copy, cut, paste, delete, wrap, unwrap — appears in a bar
above the canvas while there is one, and nowhere at all while there is not. Every one of those
still has the keyboard chord it always had; **Keyboard shortcuts** in the top bar's overflow menu
lists them.

The strip under the canvas is status rather than control: the committed revision, the node count,
the size of the selection, where a drag would land while one is in flight, the last rejection, and
the session.

A design has one URL, and it names the catalog and the design:

```text
/ui-builder/remote-m3/my-remote-screen
```

Opening it opens the design. It does not create one: a `GET` never writes, so a mistyped link
reports a design that is not there rather than quietly making it.

**Both segments are canonical, and the short form redirects to them.** `/ui-builder/<designId>`,
with the catalog left out, is not a design URL: the routing reads the first segment as a catalog
name, and the app reads the catalog back out of `location.pathname` before it has fetched anything.
It is a link people and agents build anyway, because the design's *API* resource below **is**
catalog-free — `/api/ui-builder/v1/designs/<designId>` names a design with its id alone, since the
server reads the catalog out of the stored document's `catalogPin` — so an id that works against
the API used to produce a `404` that looks like a deleted design
([#509](https://github.com/yschimke/compose-preview-server/issues/509)).

The server now answers it with `302` to `/ui-builder/<catalog>/<designId>`, reading the missing
segment from that same `catalogPin`. It does that **as the caller**: designs are private to their
owner and collaborators, and a redirect that fired for any id that exists would tell a stranger
both that a (fairly guessable) id is taken and which catalog it pins. Whoever cannot open the
design still gets a `404`, and so does an id that names nothing — which is also what keeps a
genuinely missing asset a `404` instead of silently rendering the app shell.

Creating is a `POST`. The New design dialog opens on a form factor — Mobile, Wear, RemoteCompose
— with a generated id already filled in (a `cheeky-raccoon`, reshuffled or overwritten as you
like) and its state variables folded away until asked for. It submits an ordinary form to
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
through the server or MCP adapter, and the same SVG and PNG are one press away in the top bar — see
[Getting the design out](#getting-the-design-out-figma-a-link-a-file).

The canvas uses real catalog components where a supported Compose/Wasm adapter exists. A
compatibility adapter is explicit capability metadata, not a claim that an unavailable platform
API was silently substituted. The Jetcaster supporting-pane scaffold, for example, retains its
semantic component identity while its general adaptive Material adapter remains marked
unsupported. Inspect capability notes before treating a design as portable to another runtime.

Operators select the reviewed adapters with `--ui-builder-catalogs`. The packaged deployment
defaults to `m3-catalog,remote-m3,wear-m3`; a served catalog outside that list stays preview-only
until added explicitly. Enabling an adapter is a claim that what an author sees is what they get,
and `wear-m3` has a render behind that claim — the Kotlin it generates is compiled by real Wear
Compose in compose-ai-tools' `wear-m3` harness catalog, and the stitched capture matches the canvas
to a dp. It spent a period outside the default for a deployment reason rather than a capability one
— it is the only builder catalog needing Robolectric and an Android SDK, and nobody was authoring
Wear designs on the public box — and it is back because that lane is wanted again. A box that does
not want to pay for it drops it with `--ui-builder-catalogs m3-catalog,remote-m3`.

### Which renderer is telling you the truth

The **Design / Preview** switch and the render-surface menu are two different questions, and on
`wear-m3` the answer to the second one is not the browser. Wear Material 3 is an Android AAR, and
the canvas is Compose Multiplatform for Wasm: it draws Wear's containers as measured stand-ins and
Wear's controls as named placeholders, because there is no version of it that can link the real
library. That is stated in the catalog rather than left to be discovered — the render-surface menu
describes the Wasm entry as "stand-ins, for authoring", and pressing **Preview** switches to the
host's renderer before it switches mode.

The host's renderer compiles the design's own generated Kotlin against a real Wear classpath and
draws it on the Android/Robolectric daemon. It needs one line of configuration, because the bundle
carrying `androidx.wear.compose:compose-material3` is a different served catalog from the design's
own id:

```text
--ui-builder-native-catalog wear-m3=wear-m3-catalog
```

The packaged deployment passes exactly that. A host without it says so, naming the flag, rather than
compiling Wear source against a desktop classpath and reporting the wall of unresolved imports as
though the design were broken.

`m3-catalog` needs none of this: its canvas draws the same Material 3 its export names, so the
browser is authoritative and the native lane is the second opinion rather than the only one.

`remote-m3` is a deliberately small adapter: Small and Large Wear widget scaffolds plus Box, Row,
Column, Surface, Text, and nested Remote Compose document. It is not an alias for every M3
capability.

### A card's content is a box, in every lane

`m3/card` stacks its children the way `layout/box` does: two children with no alignment sit on top
of each other at the card's origin, `matchParentSize` fills the card, and `align` places a child at
one of the nine box positions. That is what the canvas draws and what the Properties panel offers a
card's child, and it is also what the export writes — `Card { Box { … } }` — whether the code comes
from the code pane or from the record-driven generator the native render compiles, so a card that
lays a gradient under a title renders the same in all three. A card whose children should read top
to bottom holds one `layout/column`, which is what the card starter content already does.

### The frame's ground is the theme's, unless the root fills the frame

Every renderer paints the pixels no node reaches in the theme's `background` — the canvas from
`environment.theme`, the native lane from the preview's own backdrop. A node paints its
`containerColor` across the area it is measured to and no further, and that includes the root: an
`m3/surface` with no size modifier wraps its content, exactly as `Surface` does, so its colour is
a patch behind the content and the rest of the frame stays the theme's. A dark chat mock whose
root carries `containerColor: #313338` and no `fillMaxSize` renders on the light theme's ground,
with nothing refused and nothing to search for.

Two ways to make the two agree. Give the root `fillMaxSize`, and its `containerColor` *is* the
ground. Or set `environment.theme` to `dark`, and the ground is the dark theme's. The export
attaches `ROOT_SURFACE_DOES_NOT_FILL_FRAME` to every artifact of a design whose coloured root does
neither, and the **Issues** panel shows the same line, so the fact is stated where an agent and a
person each look rather than inferred from a picture.

## Several items, and several views of them

A design is usually one screen on one device, drawn once. Two switches let go of that, and they are
independent: what is *in* the design, and how many pictures of it you look at.

### A board holds several items

**Add beside**, a switch in the insert panel under the line that says where the next Add lands,
places what you add as a top-level item rather than inside the selection. What that means depends on
what the design already is, and the line above the switch says which of the three is about to
happen before you press it:

| The design | The line reads | What the Add does |
| --- | --- | --- |
| empty | "Adds as this design's first item" | the component becomes the root. No board yet. |
| already rooted in a column | "Adds beside *n* item(s) on the board" | appends into that column |
| any other root | "Adds beside the design, on a new board" | wraps the root in a board and puts the two side by side |

The second row is worth reading twice if you imported a design or built one that starts with a
column: **a root `layout/column` already is the board.** Adding beside appends into it rather than
wrapping it, so your column is not preserved as a separate item inside a new one, and the spacing and
alignment the items get are the ones already on it, not the 24 dp a fresh board is created with.
That is the honest consequence of a board being an ordinary node — but it does mean the first Add
beside on such a design changes nothing structurally, and the items simply join what was there.

A board is an ordinary `layout/column`, not a mode. It is in the document, the layers panel lists
it, and selecting it gives you the **Properties** panel's `verticalSpacingDp`,
`horizontalAlignment` and `verticalArrangement`. The third one matters most on an imported design,
because it can override the first: `spaceBetween`, `spaceAround` and `spaceEvenly` position the
items from the height available and **ignore `verticalSpacingDp` entirely**, while `center` and
`bottom` keep the spacing and move the block. A column whose items refuse to sit where the spacing
says is usually carrying one of those three.

The spacing is kept only while it is **positive**, and that catches out the one case you would reach
for it: the Properties panel lets you type a negative `verticalSpacingDp` so children overlap. On
the canvas no arrangement honours it — `center`, `bottom` and the default top all test
`spacing > 0f` and otherwise fall back to plain `Arrangement.Center`, `Arrangement.Bottom` or
`Arrangement.Top`.

The generated Kotlin does not match the canvas here, and the mismatch is worth knowing before you
trust either. `center` and `bottom` drop the negative value in the export too, but the **default**
arrangement does not: its branch emits `Arrangement.spacedBy(<the signed value>)` unguarded. So a
board left on the default arrangement with a negative gap draws no overlap in the builder and
exports a `Column` that overlaps. Neither lane is following the other; treat a negative spacing as
unsupported until they agree.

Items are reordered by dragging them in Layers, exactly like any other children. Nothing downstream
treats it specially: the Kotlin export writes the `Column` it is, and the screen projection sees the
same.

Two Adds it will not do:

- **An Add with no compatible slot is still refused.** Add beside is a switch you reach for, not a
  fallback: a scaffold does not grow a neighbour every time a chip fails to fit inside it.
- **A Wear screen scaffold or a widget container cannot become an item of a board.** Both
  record-free emitters route on the root component, so an item that stopped being the root would
  lose its emitter and its native preview lane.

Both say why, in the place that matches what is being refused. Refusing to *wrap* the current root
is about the design, so it replaces the destination line above the switch. Refusing a **component**
is about that component, so it appears on that component's row, where its id normally sits — the row
keeps its disabled **Add** and explains itself. Screen readers get the same sentence on the Add
button's label, since a disabled button is not always reachable by touch exploration.

A root board **cannot be unwrapped**: Unwrap needs the selected container to have a parent, and a
board is the document root. **Delete** on it is a different action and takes the whole subtree, its
items included. Undo takes back a board the last Add created; a board that has outlived that command
has no one-press way back to a single screen.

### The frame is not the device

The **Screen** panel's first field is **Frame** — the width, height, density and theme the design is
measured in. **Set frame from** fills in the *geometry* from a device preset — width, height and
density, and nothing else. Theme, locale, font scale and layout direction survive the pick on
purpose: a device is a frame rather than a whole environment, and someone checking an RTL screen
across three devices should not have to re-pick RTL three times. A hand-typed 1400 × 1000 frame is
a frame and reads as `Custom size` rather than claiming a phone.

A board obeys the frame like anything else: its items are laid out down the middle of that width, at
that density, under that theme. Nothing is hidden on one.

### Variants are extra panes, not extra documents

Below the frame, two lists put more pictures of the same document beside the one you are editing:

| Field | What it draws | Stored? |
| --- | --- | --- |
| **Also shown and exported as** | the devices the design claims | yes, shared with collaborators |
| **Also compare** | Dark, RTL and 1.5× font | no — a way of looking, off again when the design is reopened |

The first is worth knowing about: that list was always stored, and the editor used not to draw it. A
design could claim three devices and show its author one.

**"Exported as" means the record-driven Compose export**, which writes the claimed devices into
`@Preview(device = …)`. A `wear-m3` screen or a `remote-m3` widget is emitted by the record-free
exporters instead, and those write their own fixed preview annotations — so on those designs the
list still changes what you *see* here, and does not reach the generated Kotlin.

The panes are for checking, and **exactly one of them takes edits** — the first, at the design's own
frame. No variant pane carries a selection, a drop target or a comment pin. That is deliberate
rather than unfinished: there is one document behind all of them, so an edit made on the tablet pane
would be an edit to the tree the phone pane draws.

A wider pane is worth having when the design actually responds to width. A supporting-pane scaffold
does, and since it is `androidx.compose.material3.adaptive`'s own `SupportingPaneScaffold`, what
decides is what would decide in the app:

- both `mainPaneVisible` and `supportingPaneVisible` are on — these say which panes the design *has*,
  which is a different question from how many fit;
- `layoutMode` is anything but `singlePane`. `adaptive`, `twoPane` and `expandedTwoPane` all hand the
  decision to the library; `singlePane` pins it to one pane at every width. The editor inserts
  `adaptive` by default, and unlike the old stand-in that value now does what its name says;
- the **frame** is wide enough for the library's expanded breakpoint. That is a window size class,
  not a sum of the authored pane widths, and it is computed from the scaffold's own constraints —
  so a `width` or `widthIn` on the scaffold, or any narrower ancestor, holds it single-pane on the
  widest tablet in the list. Check the node's own constraints before reaching for a bigger preset.

`mainPanePreferredWidthDp`, `supportingPanePreferredWidthDp` and `paneSpacingDp` no longer form a
threshold. They are the proportions the **editor canvas** lays the panes out in, where every declared
pane is always drawn so none of them becomes uneditable; the variant panes beside it are where the
design actually collapses.

In the wide editor layout, **`Native`** replaces the builder's canvas with the host's pane, and the
compare chips say why they are inert rather than accepting a choice that would draw nothing.
**`Both`** does not replace it: the canvas is kept beside the host's pane — that surface exists to
compare the two — so the device variants are still drawn there. The device list stays live either
way, on the same terms as above: it reaches the record-driven Compose export. On the designs most
likely to be host-rendered — a `wear-m3` screen, a `remote-m3` widget — the record-free exporters
write their own fixed preview annotations, so there the list reaches the generated Kotlin no longer,
and reaches a variant pane only while one is drawn: in `Both`, or in the compact layout below.

The compact layout below 840 dp is different: it draws the builder canvas whatever surface is
selected, so the chips stay active there.

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
scaffold's: `asset/image` draws the bytes the design's `assets` map pins under its key (put there
with `PUT /api/ui-builder/v1/designs/{designId}/assets/{assetKey}` or `ui_builder_put_asset`), the
project-owned artwork keys, and a placeholder carrying the key otherwise — see
[`design/UI_BUILDER_ASSETS.md`](design/UI_BUILDER_ASSETS.md).

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
import androidx.glance.wear.GlanceWearWidget
import androidx.glance.wear.WearWidgetBrush
import androidx.glance.wear.WearWidgetData
import androidx.glance.wear.WearWidgetDocument
import androidx.glance.wear.color
import androidx.glance.wear.core.WearWidgetParams
import androidx.glance.wear.tooling.preview.RectangularSmallWidgetPreviewParams
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

@Preview(name = "Squircle Preview")
@Composable
fun HelloWidgetSquirclePreview() =
    WearWidgetPreview(
        HelloWidget(),
        SquircleSmallWidgetPreviewParams().values.maxBy { it.widthDp },
    )

@Preview(name = "Rectangular Preview")
@Composable
fun HelloWidgetRectangularPreview() =
    WearWidgetPreview(
        HelloWidget(),
        RectangularSmallWidgetPreviewParams().values.maxBy { it.widthDp },
    )
```

Read what is *not* there: the host container. `remote-m3/widget-container-*` is this builder's
stand-in for `WearWidgetContainer`, and on-device the launcher draws that around widget content from
`WearWidgetParams`. So the scaffold's background becomes the `WearWidgetBrush` handed to
`WearWidgetDocument`, its size picks the preview-params providers, and its padding and radius are
checked against the shipped spec rather than emitted — a widget cannot choose them, and a design
that moved them is refused by name rather than generating a preview that draws a frame it does not
have.

Read what *is* there twice: the widget is previewed in both host container shapes. The squircle is
the host's default and the frame the builder's canvas draws, so it is the one to compare the design
against; the rectangular frame is a genuinely different spec — 192×60dp of content inside 16/12dp of
padding at the Small size, against the squircle's 200×60 inside a uniform 8dp — and it is the render
recommended as the image for the widget picker editor. Both come from the shipped size-specific
providers, so neither invents a frame, and the widget itself is the same in both: a
`GlanceWearWidget` describes content, and the container around it is the host's.

The `@Preview` carries **no `device`**, and that is deliberate rather than an omission. A widget's
canvas is its `WearWidgetParams`, so a screen spec beside it says nothing the params do not, and a
renderer that honours it draws the widget across a phone-sized canvas instead of the 216×124dp
frame the design was authored in.

It is also **one** preview rather than a fan-out. The params still come from the shipped provider —
the preview invents no frame — but the generator picks the widest footprint the container ships
instead of unrolling a preview per value with `@PreviewParameter`. A scaffold does not need both
the constrained 182×112dp and the 216×124dp the design is authored against to show what it looks
like. The cost, stated: an overflow that only appears at the narrower footprint no longer shows up
in the generated preview, so a widget whose content is close to the width has to be checked there
deliberately.

![The Code pane showing a widget's generated Kotlin](design/evidence/ui-builder-remote-compose/widget-code-pane.png)

### A picture in the content slot

An image is the one node whose bytes stay out of the generated file, and the reason is not a
limitation: album art, an avatar or a logo is *application data* that changes long after the file is
written, so baking today's bytes in would generate a widget that draws the picture the design was
built with forever. The design names an asset **key**; the generated code takes a bitmap.

```kotlin
@RemoteComposable
@Composable
fun NowPlayingWidgetContent(albumArt: RemoteImageBitmap) {
    RemoteRow(modifier = RemoteModifier.fillMaxSize()) {
        RemoteImage(
            remoteBitmap = albumArt,
            contentDescription = "Album art".rs,
            modifier = RemoteModifier.size(60.rdp, 60.rdp).clip(RemoteRoundedCornerShape(8.rdp)),
            contentScale = ContentScale.Crop,
        )
        …
    }
}

class NowPlayingWidget(
    // The design's `album-art` asset.
    private val albumArt: RemoteImageBitmap = ImageBitmap(1, 1).rb,
) : GlanceWearWidget() { … }
```

One parameter per distinct key, named after it, defaulted to a **blank** 1×1 bitmap — which is what
lets the generated `@Preview` beside it still compile, and is deliberately not a picture: a
placeholder that looked like artwork would be a preview showing something the design does not have.
Pass the real bitmap when the application constructs the widget.

The **background** slot inlines its bytes instead, and the difference is the host. A widget is drawn
by the **system**, out of the application's process and without its resources, so a background
picture cannot be a name the drawing side resolves — no `R.drawable`, no asset path, nothing looked
up at draw time. The pixels have to travel inside the document, which leaves generated source
carrying them:

```kotlin
val background = WearWidgetBrush.image(coverWide)

private val COVER_WIDE_PNG: String =
    listOf(
        "iVBORw0KGgoAAAANSUhEUgAAAbAAAAD4CAIAAAACUCTIAABrS0lEQVR42uzcV3Qbd5bvez53T0/Hdc890zk7yJYl",
        …
    )
        .joinToString("")

private fun decodeInlineBitmap(encoded: String): RemoteImageBitmap {
    val bytes = Base64.decode(encoded, Base64.NO_WRAP)
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        .asImageBitmap()
        .rb
}
```

The bytes are chunked into a list joined at runtime rather than one `const val`, because a JVM
string constant is capped at 65535 bytes and a photograph passes that easily. They are declared
above the decode that reads them, because a top-level `val` is initialised in declaration order.

A key whose bytes this host cannot read still refuses, by name — the export says which node names
which asset rather than emitting a picture it does not have.

### What else a widget body can say

The modifiers are `RemoteModifier`'s, not Compose's, and the palette offers exactly the ones the
generator can write — `size`, `width`, `height`, `widthIn`, `heightIn`, `fillMax*`, `padding`,
`background`, `border`, `clip`, `alpha`, `offset`, `rotate`, `scale`, `zIndex`, `wrapContentSize`,
the scrolls, `weight` and the three alignments. Four Compose modifiers are missing from a widget's
inspector on purpose, because Remote Compose has no counterpart: `matchParentSize` (use
`fillMaxSize`), `aspectRatio` (state a `size`), `shadow` (a played document draws no elevation) and
`testTag`.

Two of them are written by the *container* rather than as a call: `background` with a shape becomes
`clip(shape).background(colour)`, because `RemoteModifier.background` takes no shape, and an
alignment becomes the row's, column's or box's own argument, because a played document aligns its
content as a group. That last one is why a box whose children ask to be aligned differently from one
another is refused: `RemoteBox` has one `contentAlignment` for all of them.

Refusals work the way the Compose exporter's do: a node or modifier with no Remote Compose
counterpart is named, with the reason and the route that does work, rather than approximated.

## Authoring a Wear screen

**New design → Wear Material 3** offers two templates: **Wear screen**, an empty `ScreenScaffold`
with its clock over an empty `TransformingLazyColumn`, and **Activity list**, the same shape with
wear-m3-catalog's own rows in it. Either opens on the small round watch frame — the scaffold reads
its diameter from the document, so picking a watch under the Screen inspector's **Set frame from** is what
changes it.

The list comes with the scaffold rather than being something to add afterwards: `ScreenScaffold`
exists to hold one, its `contentPadding` means nothing until something reads it, and the generator
refuses a scaffold whose content slot holds anything else. Add rows to the list and they stack in a
straight column — more than a screenful is the normal case, and the canvas shows the whole extent:

![The Wear list screen drawn as a long-screenshot stadium](design/evidence/ui-builder-wear-screen/wear-screen-stadium.png)

That is the frame's width, the content's height and round caps — the Wear long-screenshot form,
because that is what you are building. Comparing where a list wraps at 192, 227 and 240dp is three
columns rather than three scroll positions:

![The same design at the three round screen sizes](design/evidence/ui-builder-wear-screen/wear-screen-breakpoints.png)

The content components are `m3-catalog`'s, borrowed, except `wear-m3/list-header` which is Wear's
own. A `wear-m3` design can also hold **any published `remote-m3` component**: the Remote Compose
palette lists every preview that catalog publishes an `ir/<id>.rc` for, and the bytes are played
in-process by the same player the deployed lanes use — so a row dropped in from there is drawn by
the renderer a watch would use rather than by a re-creation.

### Code, and the preview that checks it

**Code** shows the real thing, which is the point of the stand-in. Unlike the widget container, the
screen scaffold is *emitted* rather than erased: the generated Kotlin calls `ScreenScaffold`, wraps
it in the `AppScaffold` that owns `TimeText`, and carries `Modifier.transformedHeight(this, spec)`
and `SurfaceTransformation(spec)` on every row.

![The Code pane on a Wear screen design](design/evidence/ui-builder-wear-screen/wear-screen-code-pane.png)

It emits **two** previews, and they answer different questions. `@WearPreviewDevices` is the screen
as a watch shows it — one screenful, transformed, at every round size. The second is a
`@ScrollingPreview(modes = [ScrollMode.LONG])` capture at `wearos_small_round`, and that one is the
canvas's own picture: `LONG` stitches the whole scroll with the row transformation off, which is
exactly what the stadium draws. Render it and you should get the canvas back.

You do. Left to right: wear-m3-catalog's hand-written component, the builder's canvas, and the
generated Kotlin compiled and captured on Android —

![The reference, the canvas, and the generated screen rendered for real](design/evidence/ui-builder-wear-screen/wear-screen-round-trip.png)

192 × 496dp on all three, rows at 72 → 136dp, 64dp tall with 4dp gaps, spanning 10 → 182dp.
[`design/UI_BUILDER_WEAR_SCREEN.md`](design/UI_BUILDER_WEAR_SCREEN.md) carries the measurements,
where each number came from, and what is still not the watch — chiefly that none of these three is a
live frame, where `SurfaceTransformation` scales each row by its distance from the bezel.

## Getting the design out: Figma, a link, a file

**Export** in the top bar lists the same three verbs the catalog viewer's preview pages offer, for
each format the design's catalog can render. SVG leads, because pasting into Figma is what the
menu is for.

| Row | What it does |
| --- | --- |
| **Copy SVG** | Puts the Figma-compatible SVG markup on the clipboard. Paste into a Figma page and it lands as editable layers. |
| **Copy PNG** | Puts the picture on the clipboard as an image, for Figma, Slack, a document. |
| **Copy SVG link** / **Copy PNG link** | Copies a **live** URL. The server draws the current committed revision every time it is opened, so a link pasted into a pull request or a README keeps up with the design. |
| **Download SVG** / **Download PNG** | Saves the current design as a file named after the design. |

Every row renders the committed revision through the server's own export lane — the one the MCP
`ui_builder_export` tool and the protocol's `exportDesign` request use — so what you paste is what
an agent would have been handed, with none of the editor's chrome, selection or reference overlay
in it. A sentence beside the button says what happened: `SVG copied — paste it into Figma`, or why
the browser refused.

The live address behind the links is a plain `GET`, one per format:

```text
/api/ui-builder/v1/designs/<designId>/export.svg
/api/ui-builder/v1/designs/<designId>/export.png
```

It answers with the artifact's media type, its digest as the `ETag`, and the revision it drew in
`X-UI-Builder-Revision`. `?revision=N` pins an earlier retained revision; `?download=1` asks for an
attachment. The route is gated on `ui-builder-export`, exactly like the protocol export, and the
copied link carries **no credential**: whoever opens it presents their own token, session or grant.
A design's catalog decides which rows appear — a catalog whose renderer cannot draw SVG has no SVG
rows, and one that cannot render at all has no Export button. On a server running below Java 21
there is no render lane, so there is no menu; the startup line says so.

### From a shell: `compose-preview-server design`

The menu is the browser's door. The shell's is `design`, a command on the server binary that is a
**client**: it talks to a server that is already up and exits, rather than starting one
([#529](https://github.com/yschimke/compose-preview-server/issues/529)).

```shell
compose-preview-server design list                       # what this credential can see
compose-preview-server design render my-widget -o cover.png   # or --format svg
compose-preview-server design export my-widget -o Widget.kt   # the generated Kotlin
compose-preview-server design get    my-widget > design.json  # the document
```

`--server <url>` picks the host (a local one by default, `$COMPOSE_PREVIEW_SERVER` otherwise) and
`--revision N` pins, exactly as `?revision=` does on the URLs above. Every verb runs the same
export lane as the menu and the MCP tool: same gate, same renderer, same artifact.

Three things it does deliberately:

- **The credential comes from the environment**, `$COMPOSE_PREVIEW_TOKEN` or the older
  `$COMPOSE_PREVIEW_UI_BUILDER_TOKEN`, and there is no `--token` flag — a credential on a command
  line lands in a shell history and a CI log. Each verb asks the server for the least it needs:
  `ui-builder-read` to list, get and render, `ui-builder-export` to export.
- **With no credential it asks a human**, through the server's own device-code flow: it prints the
  approval link and the code, waits, and carries on. So does a token a restart has invalidated,
  which is otherwise the most confusing failure on this surface — an unauthorised caller and a
  design that does not exist are deliberately indistinguishable
  ([#509](https://github.com/yschimke/compose-preview-server/issues/509)). `--no-authorize` turns
  that off for CI, where nobody is there to approve.
- **A refusal is not an empty file.** When the generator cannot express a design — `asset/image`
  has no Remote Compose counterpart, an image background needs a `RemoteImageBitmap` — those
  diagnostics go to stderr, nothing is written, and the exit code is non-zero.

### When the server is the thing that is broken

Everything above asks a host to render, which is no help when that host's render lane is what you
are trying to debug: `exception: null` + `image: null` + a valid preview id is the identical
observable for a missing sidecar, a render that timed out and a render that threw, and the reason
only ever reaches the server's log
([#481](https://github.com/yschimke/compose-preview-server/issues/481)). `--local` runs the same
generator, compiler and daemon **in this process** instead, and says which of those it was
([#551](https://github.com/yschimke/compose-preview-server/issues/551)):

```shell
compose-preview-server design get my-widget --server https://preview.coo.ee > doc.json
compose-preview-server design render --document doc.json --local \
  --catalog wear-m3.bundle --assets ./assets -o replay.png
compose-preview-server design export my-widget --local --components m3-catalog=components.json
```

- `--document <file>` reads the design off disk, so a document captured from a broken host replays
  against a known-good tree — or yesterday's bundle, or under a debugger. Without it, `--local`
  still asks a server for the design, but only to **read** it; the render happens here.
- `--catalog <bundle>` is the classpath a render compiles against, and its manifest picks the
  daemon (an `android` bundle renders on Robolectric, a desktop one on Skiko). `--assets <dir>` is
  the uploaded bytes a widget inlines; `--components <catalog>=<components.json>` is the record a
  record-driven catalog's call sites are proven against. A local `export` needs none of the three.
- **The output says why.** A missing frame prints the compiler's own diagnostics, the classpath
  entry count and which daemon opener was built — being chattier than the HTTP surface is the point
  of the mode rather than a slip.

It does **not** replace [`scripts/ui-builder/design-sync.mjs`](../scripts/ui-builder/design-sync.mjs),
which moves a design's *document* between a live host and a committed operations fixture in both
directions ([below](#keep-a-design-in-the-repository)). `design` gets artifacts out; the script
versions the design itself. They read the same environment variables, so a shell set up for one
works with the other.

## Letting somebody else in

A design belongs to whoever created it, and nobody else can open it until you say so. Two doors,
per design:

- **The page.** `/ui-builder/<catalog>/<designId>/access` — visible to the owner, and to an agent
  acting for them. It lists who can open the design and shares it with somebody else.
- **The MCP tools.** `ui_builder_design_access` reads that list; `ui_builder_share_design` changes
  it, so "share this with @colleague" is one tool call.

You name the other party by **actor id**, which is how this server spells an identity:
`github:<login>` for a signed-in person, `operator` for the token holder, `agent:<fingerprint>` for
an agent's approved grant. An agent reads its own from `GET /agent-access/whoami`; a browser reads
yours from `GET /api/ui-builder/v1/identity`, and the share page prints it.

A **viewer** may open and export; an **editor** may also change the design. Neither may share it on
— a design has exactly one owner, and sharing never hands that over.

### Your agent is already in

An agent working under a grant **you** approved acts for you: designs it creates are owned by *you*,
and designs you own are open to it, with the read/write/export capabilities you ticked when you
approved. Nothing has to be shared for that, and nothing outlives the grant — it is your access,
borrowed. Its edits and comments are still recorded under the agent's own id, so the history says
who did what. `docs/design/AGENT_ACCESS_GRANTS.md` has the whole rule.

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

The same document is readable as text at **`render/<id>.rc.json`** — the operation stream the
`.rc` bytes carry, projected into JSON. It is for a person, a `diff` in a review, or `jq` in a
script, where the `.rc` lane is bytes for a player, and it answers the question a picture cannot:
whether a sticker's padding changed or its render just antialiased differently. It is not the
dialect you *write* — that is AndroidX's authoring JSON, and the two are not inverses; see
[compose-ai-tools' `REMOTE_COMPOSE_JSON.md`](https://github.com/yschimke/compose-ai-tools/blob/main/docs/design/REMOTE_COMPOSE_JSON.md).
A document this server can serve but cannot inflate — a bundle baked on a newer Remote Compose
alpha than the server links — answers `422` naming the document, rather than failing the request
as a server error.

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

## Component packs: another catalog's components on your palette

A host can offer a served catalog's own composables inside the builder's catalogs as a **component
pack** — Confetti's `SessionCard` beside `m3/card` in a Material 3 screen. A pack is scoped to a
platform, so a mobile pack appears in `m3-catalog` designs and never in a Wear widget, which could
not call it. The operator admits one:

```text
--ui-builder-packs confetti-mobile=mobile,confetti-wear=wear
```

A `wear` pack lands in `wear-m3` designs instead, and a Wear screen holding one of its components
is written and compiled against that catalog's own Android bundle. The pack's components are
projected from that catalog's own discovered component record — every
composable of the project's own the producer proved a call site for, with its literal parameters as
properties and its `@Composable` lambdas as slots — so nothing is transcribed by hand. The record
comes from the served catalog's delivery branch (the `components.json` published beside
`catalog.json`, or the one inside its live bundle); a catalog rendered before records existed
yields none, and the pack is logged as not offered until it republishes. Only a catalog that
publishes no record needs `--ui-builder-components <catalog>=<components.json>`. Admitting a pack
only makes it available. In the editor, **Component packs…** in the toolbar overflow (or
**Packs…** at the top of the Insert panel) lists the packs the host admitted with a switch each;
switch one on and its components appear on a shelf named for the pack. The choice is remembered per
catalog in your browser, not in the design.

A pack component is drawn on the canvas as a named, captioned placeholder — the browser cannot link
another application's classes, for the reason it cannot link Wear Compose — and rendered as itself
by **Preview**, which compiles the design against the pack's own served bundle. A design drawing on
two packs has no bundle that carries both and the native preview says so. Export and the code pane
write the real call site from the pack's record.
[`design/UI_BUILDER_COMPONENT_PACKS.md`](design/UI_BUILDER_COMPONENT_PACKS.md) has the model and
the projection rules.

### The export's one classpath requirement

A screen holding an `m3/icon` names `Icons.Filled.AccountCircle` and its kin. The picker exposes all
11,385 vectors shipped by Compose's Material Icons core + extended artifacts: `filled/…`,
`outlined/…`, `rounded/…`, `twoTone/…`, `sharp/…`, and `autoMirrored/<style>/…` where supplied.
The original 46 bare keys remain accepted compatibility aliases (including
`genres -> Icons.Filled.Category`) but are hidden from search so they do not duplicate rows.

Most vectors are in **`androidx.compose.material:material-icons-extended`** rather than in `-core`.
A generated file cannot add a dependency to the project it lands in, so a project pasting such an
export needs that artifact; everything else the export names is Compose UI and Material 3. Exported
source contains only the icon members the design uses, rather than a resolver for the whole catalog.

The inventory is generated from the resolved 1.7.3 core and extended desktop jars. The same build
step generates the Wasm/native vector bindings and Compose-free export mapping, then verifies the
checked-in capability allowlist. Search evaluates the full index but composes at most 80 matching
rows. Vector lookup is generated separately from the metadata, so opening the picker creates
neither thousands of menu items nor all 11,385 `ImageVector` objects; only the current and visible
matches are materialised.

Measured on the production `wasmJsBrowserProductionWebpack` output for this change, the UI-builder
Wasm application grows from 8,737,944 bytes to 26,519,707 bytes (+204%); gzip grows from 2,372,654
to 5,275,042 bytes (+122%). Skiko remains a separate unchanged 8,652,729-byte asset. The generated
binding source is 2,579,301 bytes and the Compose-free export index is 585,985 bytes; neither is
checked in. The capability JSON grows from 75,688 to 338,449 bytes. These are the explicit costs of
making every advertised vector render locally in Wasm. Generated screen source does not inherit
that cost: its `builderIcon` resolver contains only keys used by that design.

The capability is per catalog. `remote-m3` has no record and is not meant to — Remote Compose is
kept out of the Compose exporter by design — so a host serving both advertises Compose export on
`m3-catalog` and not on `remote-m3`.

## Reading the code a design produces

**Code** on the right rail opens the Kotlin the export would write, beside the canvas, updating on
every edit. It is the export's own output: same generator, same record, same allow-list. A design
the generator cannot express shows the reasons where the source would be, rather than blanking —
those reasons are the actionable half, and they are the same list the Issues tab reports.

**Native** compiles the design and renders it with real Compose on the host, beside the browser's
canvas rather than instead of it — a difference between the two renderers is the thing worth
seeing. It needs a host with a compile lane (`--playground-bundle`); where there is none, the
control is absent rather than present and failing. The render is tagged with each design node id,
so the server's annotation lane can report where every node landed in the frame.

## Build against a reference

The **Screen** panel's Reference section attaches a picture to the design and draws it over the
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
| `allowedValues` | Choice menu | `enum`, or the existing semantic type such as `typographyToken` |
| Explicit local color capability | Color/token input | Literal ARGB/RGB colors and declared Material tokens |
| Object, array, nullable unions, or unbounded numbers | Read-only | No unsafe shape guessing |

Text currently covers content, typography style, weight, style, color, font size, line height,
letter spacing, minimum/maximum lines, wrapping, overflow, alignment, decoration, box alignment,
and layout weight. Environment axes—viewport, density, font scale, locale, theme, and layout
direction—are deliberately not node properties.

A property the catalog gives `allowedValues` takes the **`enum` wrapper**, not `string`. The two
carry the same JSON scalar and both used to be accepted, so one authored intent could be committed
two ways and then export down two unrelated paths; the reducer now rejects `string` on such a
property, naming the node, the field and the wrapper it wants. A semantic type — a `style` written
as `typographyToken` — is a different claim about the value and is still preserved. Designs that
hold the old spelling keep rendering, keep exporting, and are rewritten to `enum` by the next edit
to that field.

The same shape of rule covers what a value *means*. A property named `color` or `…Color` is a
colour and takes a `color` (`#RRGGBB` / `#AARRGGBB`) or `colorToken` wrapper naming one of the
theme roles in the catalog's `statusSemantics.colorTokens`; `assetKey` names one of the keys in
`statusSemantics.assetRegistry`. Either written otherwise is refused at commit, on the write that
chooses the value, with the node and the field named — and a design that already holds such a
value renders it as a visible placeholder rather than failing the frame. The decision, and why
the line is "the canvas cannot draw it" rather than "the export cannot write it", is
[`design/UI_BUILDER_VALUE_SEMANTICS.md`](design/UI_BUILDER_VALUE_SEMANTICS.md).

An optional property can be **unset** again, so the component's own default applies: the editor's
`removeNodeProperty` operation names the node and the field, and on the wire
`removeNodeProperty` is its own mutation (a `setProperty` whose value is `{"type": "null"}` still
means the same thing). A required property cannot be unset — the
refusal names it — and unsetting a property the node does not hold is accepted as the no-op it is.
Undo puts the value back.

Existing `size`, `fillMaxWidth`, and `padding` modifiers render and export. Their JSON is visible in
the inspector, but modifier parameter editing is read-only until the released Design API has an
authoritative modifier mutation; the builder does not invent a browser-only operation.

## Keep a design in the repository

A live design lives in the server's own state directory and nowhere else. To version one, put it in
the repository as an operations fixture under
[`docs/design/fixtures/ui-builder/designs/`](design/fixtures/ui-builder/designs/README.md):

```shell
COMPOSE_PREVIEW_UI_BUILDER_TOKEN=… node scripts/ui-builder/design-sync.mjs export my-widget \
  --server https://preview.coo.ee --out docs/design/fixtures/ui-builder/designs/my-widget.json
```

If the design belongs to an app rather than to this repository, the same file has a second home:
`ui-builder/designs/` in the app's own checkout, with an `index.json` beside it. Point a host at it
with `--ui-builder-designs ./ui-builder/designs` and every design in there is listed under **From
the projects** on `/admin/ui-builder`, ready to open and carry on with. That is the loop an app uses
while a screen is still being designed — export when it is worth keeping, open when somebody picks
it up — and [`design/UI_BUILDER_PROJECT_DESIGNS.md`](design/UI_BUILDER_PROJECT_DESIGNS.md) has the
convention and the two sources in full.

From there the build owns it. `DesignFixturesTest` replays the file, checks its hash, validates
every node against the catalog it pins and requires the Compose export to accept it; a `@Preview`
in `DesignFixturePreviews.kt` renders it through `composePreviewRender`, so the visual-diff bot
reports when a catalog change moves its pixels. `import` opens a committed file as a fresh live
design again. The builder's own screens are kept this way, which is how the builder is designed
with the builder.

## Connect an MCP agent

The builder is reachable over the server's own `/mcp` endpoint — the same one the catalog tools use,
with the same bearer. One tool per protocol request, plus the ones the contract does not define:

| Tool | Capability | What it answers |
| --- | --- | --- |
| `ui_builder_list_catalogs` | `ui-builder-read` | What a design's `catalogPin` may name, as a summary with the pin; `full: true` for the whole capability |
| `ui_builder_list_designs` | `ui-builder-read` | The designs on this box |
| `ui_builder_get_design` | `ui-builder-read` | One whole document, and the revision to quote next; the pinned catalog only with `includeCatalog: true` |
| `ui_builder_await_design` | `ui-builder-read` | Waits for somebody else to change the design, and returns what they changed |
| `ui_builder_create_design` | `ui-builder-write` | A design, from a document or copied from one |
| `ui_builder_apply` | `ui-builder-write` | `DesignMutationV1` operations — insert, set, removeNodeProperty (a null set unsets too), delete, move |
| `ui_builder_export` | `ui-builder-export` | The generator's Kotlin, or its refusals |
| `ui_builder_put_asset` | `ui-builder-write` | A picture behind an `assetKey`, for an `asset/image` node to draw |
| `ui_builder_design_access` | `ui-builder-read` | Who can open the design: its owner, and everyone it is shared with |
| `ui_builder_share_design` | `ui-builder-write` | Shares it with an actor id as `viewer` or `editor`, or takes that back |
| `ui_builder_rename_design` | `ui-builder-write` | A new title, from anybody who may write the design; the revision does not move |
| `ui_builder_delete_design` | `ui-builder-write` | Removes a design — its **owner** only, so a session cleans up after itself and nobody else |
| `ui_builder_render_native` | `ui-builder-export` | A frame compiled by real Compose on the host, plus where each node drew on it |
| `ui_builder_list_comments` | `ui-builder-read` | The discussion on a design, and the cursor to wait from |
| `ui_builder_await_comments` | `ui-builder-read` | Waits for the next thing anybody says about the design |
| `ui_builder_post_comment` | `ui-builder-write` | A reply, or a new thread pinned to a mark, a node or a point |
| `ui_builder_acknowledge_comment` | `ui-builder-write` | Says you have **read** a thread, or the whole discussion — not that it is settled |
| `ui_builder_react_to_comment` | `ui-builder-write` | An emoji on one comment, or `on: false` to take it back; the lightest acknowledgement |
| `ui_builder_resolve_comment_thread` | `ui-builder-write` | Closes a thread once it is answered, or reopens one |

They are absent from `tools/list` on a box that serves no builder, and `ui_builder_render_native` is
absent on one that cannot compile — a client reads what this server can do off the tool list rather
than off a failed call. Replies are the released `McpResponseEnvelopeV1`, except the native render,
the rename and the delete, which have no request type in the contract and say so in their own
descriptions; and a snapshot's `catalog` is left out unless asked for, because an agent pays for it
as context on every call and the design's `catalogPin` already names it.

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
