# Remote Compose composition in the UI Builder

## Decision

The UI Builder document is the composite document. A `remote-compose/document` node embeds one
Remote Compose wire document and participates in the outer tree like any other container. This
keeps ownership, ordering, modifiers, collaboration, and export orchestration in one existing
document instead of introducing a second manifest.

The implementation uses the common Compose Multiplatform player API from `rc-players`. It is not a
Wasm-specific player design: JVM, Android, iOS, and Wasm hosts can use the same node renderer. The
current Base64 source is intentionally transport-neutral. A later URL, bundle, or repository
resolver should load and verify bytes outside the renderer, then supply the same decoded document.
The URL half of that is now built — see [`documentUrl`](#documenturl) — and it is one of two ways a
design holds Remote Compose content, the other being the vocabulary switch described in the same
section.

The checked-in JVM Compose render evidence captures the same fixture immediately
[before](evidence/ui-builder-remote-compose/before.png) and
[after](evidence/ui-builder-remote-compose/after.png) registering its `hero.card` slot. The render
test regenerates both images while also asserting the nested slot's centre pixel.

The preview deployment exposes this adapter as a second catalog-scoped builder at
`/ui-builder/remote-m3/`. Catalog registration is intentionally insufficient: the server's
`--ui-builder-catalogs` allowlist admits only reviewed authoring adapters, currently `m3-catalog`
and `remote-m3`. Each instance creates an exact catalog pin. The Remote M3 catalog starts with the
two stable Wear widget host preview sizes as slot-bearing scaffolds—Small 216×76dp and Large
216×124dp—and exposes only six relevant fill components. Their geometry is copied locally from the
published 240dp-screen squircle preview contract so the builder does not acquire a runtime
dependency on Glance preview tooling.

| Default catalog instance | Explicit Remote Compose catalog instance |
| --- | --- |
| ![M3 catalog UI-builder instance](evidence/ui-builder-remote-compose/m3-catalog-builder.png) | ![Remote M3 UI-builder instance](evidence/ui-builder-remote-compose/remote-m3-builder.png) |

The catalog-scoped site also owns the complete first-use path. Opening the builder without a
design selects an enabled catalog and template, validates the design ID, and navigates into the
new live document without requiring a hand-authored URL. Each compatible catalog row has an **Add**
action as well as drag-and-drop. Selection, search, and generated-operation numbering survive
authoritative collaboration snapshots, so authors can add a container and immediately add its
children without reselecting or re-searching after every save.

| New Remote M3 widget | Authored entirely in the website |
| --- | --- |
| ![New widget catalog, template, and design ID chooser](evidence/ui-builder-remote-compose/new-widget-dialog.png) | ![Large Wear widget with a Column and edited Text](evidence/ui-builder-remote-compose/new-widget-complete.png) |

## Where a document comes from

`documentBase64` is the node's only required property, and until the palette below existed nothing
in the editor could produce one. The inspector offered it as a plain string field, so embedding a
real component meant pasting a couple of kilobytes of Base64 by hand; the reviewed `remote-m3`
adapter could therefore *hold* Remote Compose content without any author being able to *put* it
there.

The source of those bytes is the serving catalog's own published document. Every sticker in
`yschimke/wear-m3-catalog`'s `:remote-catalog` travels as data — `CapturingWearWidgetPreview` and
`RemoteSticker` offer the captured `RemoteDocument` to `IrSidecarChannel`, `BundlePreviewTask` packs
it as the preview's IR, and the server serves it verbatim at `GET /{system}/render/{id}.rc`. The
UI builder now reads that lane:

1. `GET /{catalog}/api/previews` names which previews publish one. That is a new `remoteCompose`
   field on the previews API, additive since `compose-preview-serve/v3` and set from the host's own
   `hasRemoteComposeDoc`. `modes` could not answer it — a Remote Compose sticker and a Jetpack
   Compose preview are both `snapshot` — and probing `.rc` per preview is 476 requests to learn
   something the host already knows.
2. **Add or drop** fetches `render/<id>.rc`, Base64-encodes it, and the reducer decodes it before
   building the operation. A drag carries the published capture and highlights the exact compatible
   slot; the editor captures that slot on release and revalidates it after the fetch rather than
   silently retargeting to a later selection. The renderer decodes the bytes too, because playing
   them is what it does; refusing here is what stops a catalog lane's HTML error page from becoming
   a saved design revision that every collaborator sees as an error box.
3. The insert lands as one `InsertNode` carrying the bytes, not an insert followed by a property
   write. The intermediate state — a `remote-compose/document` with no document — renders as its
   own diagnostic, and collaborators would watch it appear.

The palette is **not** a set of components. Declaring `remote-m3`'s 476 published stickers as
`ComponentCapability` entries would put the whole sheet through the capability wire, the validator
and the exporter to describe content that is always the same component with different bytes. The
component stays one; a source is a named set of bytes it can be given.

Nothing new is configured to connect the two halves: `/ui-builder/remote-m3/` authors against the
capability adapter named `remote-m3` and `/remote-m3/` serves the published catalog of the same
name from the same box, so the palette finds its content by that shared id. A box serving one
without the other gets an empty palette rather than an error, exactly as a box with no device-preset
route gets the inspector's raw fields.

| Before: the editor's catalog panel | After: the same panel with the palette |
| --- | --- |
| ![Editor chrome with only the component catalog](evidence/ui-builder-remote-compose/palette-before.png) | ![The same panel with a Remote Compose documents section grouped by component family](evidence/ui-builder-remote-compose/palette-after.png) |

Both are `@Preview`s in `:ui-builder` — `UiBuilderLayoutInspectorPreview` and the new
`UiBuilderRemoteComposePalettePreview`, at the same size and the same selection — so the next change
to either state of the panel is diffed without anyone remembering to render it.

### The source picker is visual, and its ids are not its names

The serving catalog does not always publish a human label for every Remote Compose state. In that
case `/api/previews` repeats the stable sticker id, such as
`button-child__ideal__icon-disabled__compact`. The picker keeps that exact id for filtering,
fetching, and insertion, but no longer draws it twice. It presents the family as **Button child**
and the state as **Icon disabled · Compact**; `ideal` is a capture lane and is deliberately omitted.
A real authored catalog label still wins unchanged.

Each visible source row also lazily requests the catalog's existing `render/<id>.png` and fits it
into the same thumbnail tile as an ordinary authoring component. Lazy loading matters here: the
deployed sheet contains hundreds of sources, and opening the picker must not download all of them.
An offline host or failed image keeps a neutral component glyph; Add still fetches and validates the
`.rc` document independently, so a missing thumbnail never changes what can be inserted.

The catalog can publish the same semantic sticker once per capture frame (`compact`, `large`, and
the other window-size classes). Those are evidence variants, not authoring choices: the outer
design supplies the frame into which the embedded document is laid out. The picker therefore
collapses ids that differ only in their final capture-frame segment, keeps the compact id as the
deterministic fetch source, and removes the frame suffix from the displayed state. Other state
segments remain distinct and searchable.

![Remote Compose picker with capture-size duplicates collapsed](../evidence/ui-builder-remote-compose/picker-deduplicated.png)

| Before: ids used as labels, with no picture | After: state names and rendered thumbnails |
| --- | --- |
| ![Remote Compose rows showing repeated technical ids](evidence/ui-builder-remote-compose/picker-before.png) | ![Remote Compose rows showing concise state names and thumbnails](evidence/ui-builder-remote-compose/picker-after.png) |

This is the transport-neutral resolver the decision above anticipated, in its smallest honest form:
bytes are loaded and verified outside the renderer and the same decoded document is supplied. What
it is not yet is the *suspendable, size-limited resolver with content hashes and caching* of
follow-up 1 — the bytes are copied into the design, so a design does not track the catalog when the
catalog republishes. The lightweight PNGs are fetched only for rows the lazy list composes; the
larger `.rc` bytes are still fetched only for the source an author adds.

## Native options for the visual editor

The editor's canvas is already native Compose, not a picture of one: `/ui-builder/` is a Compose
Multiplatform/Wasm application, `UiBuilderRenderer` composes the document with real Material 3, and
a `remote-compose/document` node inside it is played by `RcComposePlayer` from `rc-player-compose`
in the same composition. There is no PNG round trip and no server render in the authoring loop, and
a nested document keeps its own runtime state across recomposition.

That answers "could the editor use the CMP Wasm player?" with "it is what it uses". The remaining
question is which *other* players the same document can be checked against, and this repository
already runs four of them over the identical `ir/<id>.rc` bytes:

| Player | Where it runs | What it is for here |
| --- | --- | --- |
| `cmp-wasm` | the browser, in-process | the editor canvas, and the `/wasm/<system>/` catalog frontend |
| `cmp-jvm` | the server, isolated desktop-player subprocess | headless render evidence and the parity wall's published rasters |
| `cmp-android` | the Android daemon | the on-device Compose rendition |
| `java` | AOSP's view-backed `RemoteComposePlayer`, drawing into a framework `Canvas` | the non-Compose reference the others are checked against |

So the useful framing is not "is there a more native editor than the Wasm one" — the same
`rc-players` API compiles for JVM, Android, iOS and Wasm, and a desktop or Android host of this
editor would render the identical node tree. It is that a Remote Compose document has four
independent rasterisers, the compare wall exists to show where they disagree, and an authoring
surface that draws with one of them should be read beside it rather than trusted alone.

Two adjacent native surfaces are deliberately **not** this editor, and should not be confused with
it:

- `:wasm-ui`'s UI Composer (`/wasm/compose-m3/?compose=1`) composes *compiled* catalog composables
  and fills regions their preview source declares with `PreviewSlot`. It is native in a stronger
  sense — the real component, not a document — and correspondingly cannot author anything the
  frontend was not compiled with, which is why it substitutes only for `compose-m3` and falls back
  to snapshots elsewhere. Remote Compose needs the opposite property: content that arrives as data.
- The JVM Compose render port (`ServeUiBuilderNativePreview`, the editor's **Native** pane) renders
  a saved revision with real Compose on the host. It is the target-platform surface beside the Wasm
  panes, not a replacement for them. The workspace's three panes are switched on and off
  independently — `Editor` and `Preview` are both this browser's Wasm, and only `Native` leaves it —
  so a host with no compile lane plays the exported document in the native pane instead; for
  `remote-m3` that is the common CMP player on the same wire document.

![Editor, static target preview, and interactive preview](../evidence/ui-builder-remote-compose/workspace-three-panes.png)

## Two ways in, and one way back out

The decision above is about an embedded *document* — bytes the design carries and the player draws.
That is one of the two ways a mobile or Wear design holds Remote Compose, and it is the only one
that existed first. The other is the **vocabulary switch**.

| | `remote-compose/document` | `remote-compose/inline` |
| --- | --- | --- |
| What the node holds | a wire document somebody else published | a subtree this design authors |
| Where the bytes come from | `documentBase64`, or `documentUrl` resolved by the host | a capture of the generated `@RemoteComposable` body |
| Who wrote the content | the serving catalog, or whoever published the URL | the person in this editor |
| What the canvas draws | the real player, on the real bytes | Compose stand-ins in a marked frame, or [the real player once the subtree is captured](#playing-inline-content) |
| What the generator writes | nothing — it is data | the body, through `InlineRemoteContentExporter` |

Both are offered by `m3-catalog`, `wear-m3` and — for the document, and for the custom component
below — `remote-m3`, so a phone screen, a watch screen and a widget body can each hold the other
kind of content. A Wear widget already *is* a document, so it is offered no inline switch: that
would be a second answer to a question its container has answered.

### `documentUrl`

The transport-neutral resolver [the decision](#decision) anticipated, in its second honest form.
`documentBase64` wins wherever both are set — a design that carries its own bytes must not start
depending on the network when it is reopened — and neither property is `required` on its own,
because the node needs exactly one and the catalog wire shape has no way to say "one of these".

Resolution stays outside the renderer, exactly as the decision requires. The editor collects the
URLs the design references, fetches each once, keyed by URL rather than by node, and answers a
composition local with the decoded document *or the failure*. The third answer is `null` — not
resolved yet — which the node draws as a waiting state rather than an error, because a design
pointing at a URL nobody has fetched is not a broken design. A host with no resolver answers `null`
for everything, which is what every host but the browser editor does today.

Which URLs are reachable is not a policy this feature invented: the browser resolver is
`sameOriginRequestUrl`, which resolves against the page and throws on anything that leaves the
origin. A design pointing at another host therefore draws that refusal as its own diagnostic rather
than quietly sending the page's `?token=` somewhere it does not belong.

### The vocabulary switch, and why it is ancestry rather than a trait

A `remote-compose/inline` node says *everything below me is `@RemoteComposable`*. Its `content` slot
takes one child and accepts only the `RemoteAuthorable` trait — the ids
[`RemoteContentEmitter`](../../ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/RemoteContentEmitter.kt)
can actually write, which is `layout/box`, `layout/column`, `layout/row`, `m3/surface`, `m3/text`
and the custom component below.

Those are the same ids `remote-m3` already publishes as stand-ins for remote components, and that is
the point rather than an accident: `layout/column` inside remote content becomes `RemoteColumn`, and
the identical node inside a mobile screen becomes `androidx.compose.foundation.layout.Column`. The
component does not change; where it sits does. Slot acceptance cannot say that — it decides from the
two components alone, and every generic container accepts `AnyContent` — so the scope is resolved
from the ancestry instead, once, in
[`RemoteScopes`](../../ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/RemoteScopes.kt),
and the canvas, the export gate and the emitter all read it from there.

### Custom components, and the way back out

`remote-compose/custom` is the return direction. A Remote Compose document cannot call an
application's composables, so the only way host content gets inside one is a `LAYOUT_CUSTOM`
operation naming a renderer the host registered — which is the same seam the [named
slots](#named-slots) below already use, reached from the other side. The node carries the registry
`name`, the `widthDp`/`heightDp` the document reserves for content it cannot measure, and a
`content` slot holding ordinary Compose.

So a design can nest all three:

```
Scaffold
  Column
    Text
    Remote Compose            <- remote-compose/inline
      RemoteColumn            <- layout/column, in the remote vocabulary
        RemoteText            <- m3/text
        Custom("field")       <- remote-compose/custom
          BasicTextField      <- ordinary Compose again
        RemoteText
    Text
```

On the canvas that draws as three scopes in one composition, each frame labelled. In a real preview
the custom component draws **only where a renderer of that name is registered**; an unregistered one
stays a player support issue rather than silently borrowing arbitrary outer content, which is the
same rule the named slots keep.

### What the generators do, and what they refuse

The Compose exporters refuse an inline node by name — `REMOTE_CONTENT_NOT_COMPOSE` — and say nothing
about its subtree, because judging `RemoteColumn` by whether Compose can call it is asking about the
wrong language. What they refuse is only the *call site*: `captureSingleRemoteDocument` takes a
`RemoteCreationDisplayInfo`, a `RemoteDensity` and a density behaviour whose disagreements have
already shipped as bugs in this stack twice, and which of them an application wants is not a decision
a design makes. `InlineRemoteContentExporter` writes the half that is the design's — the
`@RemoteComposable` function — and the editor's generated-code pane shows it beneath whatever the
screen generator said, so a design holding remote content is never answered with a bare refusal.

### The palette and the emitter are one vocabulary, checked as two

A `remote-m3` design is authorable in whatever the catalog declares and exportable in whatever
[`RemoteContentEmitter`](../../ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/RemoteContentEmitter.kt)
can write, and for a while those were two independent lists. The catalog borrowed each component's
modifiers from `m3-catalog` — 28 of them on a widget node — the canvas drew all 28, and the emitter
wrote three. `size`, `background`, `weight` and `align` were the ones an ordinary widget needs, so a
coloured button in a corner beside a column of text that truncates was authorable, drawable and
unexportable, and the author learned it after the design was built
([#508](https://github.com/yschimke/compose-preview-server/issues/508)). The component dimension had
the same shape: `asset/image` was in the palette and reached the emitter's catch-all `else`, whose
sentence — "has no Remote Compose counterpart this generator can write" — is the one a *typo* in a
component id gets.

The list now has one source, `REMOTE_CONTENT_MODIFIERS`, and 21 entries: everything
`remote-creation-compose` 1.0.0-alpha18 publishes a `RemoteModifier` for, plus the three alignments,
which are written as an argument of the enclosing container rather than as a call of their own. Four
are refused by name with the reason and the route — `matchParentSize`, `aspectRatio`, `shadow`,
`testTag` — and the catalog does not offer them.

It is **checked** as two lists rather than derived as one, because it cannot be derived:
`:ui-builder-runtime` owns the catalog and `CheckUiBuilderRuntimeBoundary` keeps `:ui-builder-export`
off its classpath deliberately, so the catalog's copy cannot import the emitter's. `:server` sees
both, and `RemoteM3VocabularyParityTest` there fails when the palette advertises a component or a
modifier the emitter cannot write — a red build the moment the palette changes, rather than a
refusal an author finds at the end of a design. `RemoteContentVocabularyTest` in `:ui-builder` walks
every name in the set through a real export, so the set cannot claim something the emitter refuses.

`asset/image` is emitted in the **content** slot as `RemoteImage`, with the bitmap threaded through
as a parameter of the content function and of the generated `GlanceWearWidget` — the design names
the key, the application supplies the picture, because a widget's artwork is data rather than a
constant a generator could bake in. In the **background** slot it still refuses, and that refusal is
about the brush chain rather than about images: `WearWidgetBrush.image` is built in
`provideWidgetData`, outside composition, where nothing resolves an asset key.

### Choosing the density behaviour at the call site

The generator refuses to pick it, but the decision is not arbitrary, and the rule is short enough to
state here so that whoever writes the call site is not left guessing:

- **One target device → assume a constant.** Capture at that device's density and let the document
  carry absolute values. It is smaller, it is what every published `remote-m3` document already
  does, and nothing about it can be wrong when there is exactly one density to be right about.
- **Several targets whose densities differ → use expressions.** Defer to the host and let the
  document resolve at paint time. `RemoteContext`'s system variables (`ID_*`) cover density, so this
  is a capability of the format rather than a workaround, and it is the only way one document is
  correct on all of them.

Form factor is what usually decides which case applies: every Wear id in the device catalog is
density 2.0, so a watch-only design is the single-target case almost by construction, while a design
that has to be right on a phone *and* a watch is not — 42 of the 56 ids `DeviceDimensions` knows are
not 2.0, and 2.625 is merely the commonest.

What a wrong choice costs is why the call site is refused rather than guessed: a constant captured
against the wrong density draws at the wrong size on every host that disagrees, and `ServeBundleHost`
had the matching bug on the replay side — a hardcoded 2.625, the desktop renderer's phone-shaped
default, scaling every Wear document by 1.31 against its own baked PNG until it was taught to ask
the device.

The one place this repository **records** a document is already the single-target case, and by
construction rather than by choice. `PlaygroundRcCaptureService` captures a snippet's `.rc` by
rendering it on one Robolectric daemon, against a `previews.json` that `PlaygroundPreviews`
synthesises with `params = PreviewParams()` — no density and no device — so the daemon's own
resolution reaches its 2.0 default and the document is constant-folded at the density the render
that produced it used. One snippet, one daemon, one density to be right about: the constant is
correct here, and would stay correct if the default moved, because the capture and the render read
it from the same place. Expressions become the question only where one document has to serve hosts
that disagree, which is a capture this repository does not perform.

That is also the lane a **design's** inline content is captured through — see
[Playing inline content](#playing-inline-content) below — so it inherits the single-target case
rather than opening a second one.

The custom component used to be the one node no generator could write, and it is not any more —
which is why this section no longer names a single refusal.
`remote-creation-compose` 1.0.0-alpha18 publishes
`RemoteCustomComponent(name, modifier, properties)`, which emits the `LAYOUT_CUSTOM` operation
directly, so `RemoteContentEmitter` writes it from the node's `name` and reserves the node's
`widthDp`/`heightDp` as `RemoteModifier.size(…)` — the bounds a player lays the component out from,
since it cannot measure content it does not have. What the body never writes is the node's `content`
slot: those children are host Compose the application draws under that name, and a
`@RemoteComposable` body calling them is the one thing the vocabulary cannot do. An **unnamed** node
is still refused, because a hole nothing can be registered against is not a component with a default.

`RemoteCustomComponent` is `@RestrictTo(LIBRARY_GROUP)` upstream. Both generated files already open
with `@file:Suppress("RestrictedApi")`, so the call compiles and lints clean; what the annotation
costs is a promise — a restricted API can change shape between alphas, and this call site is one of
the places a `remote-creation-compose` bump has to be read against.

### Playing inline content

The canvas draws an inline subtree with Compose stand-ins in a marked frame, and that is a strictly
weaker guarantee than the `remote-compose/document` beside it, which `RcComposePlayer` plays on real
bytes. Two nodes in one design, one authoritative and one an approximation, is the gap
[`ServeUiBuilderInlineCapture`](../../server/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeUiBuilderInlineCapture.kt)
closes — by getting bytes, rather than by drawing better.

`POST /api/ui-builder/v1/designs/{designId}/remote-content/{nodeId}/capture` joins four things that
already existed and adds no fifth: `InlineRemoteContentExporter` writes the body, the playground's
`remote-compose` mode compiles it and runs `captureSingleRemoteDocument` on the Robolectric daemon,
the captured `.rc` is published as a `/d/<id>` permalink, and that URL is the shape the editor's own
resolver already fetches and plays. Gated on `ui-builder-export` like the native render, and for the
same reason: it compiles and runs the Kotlin an export hands back.

Two details are load-bearing and neither is obvious:

- **The capture compiles against the *host's* catalog, not the design's.** An inline body is written
  in `RemoteColumn`, `RemoteText` and `RemoteCustomComponent`, which come from
  `remote-creation-compose` rather than from whichever Material catalog the screen around it is
  pinned to. So the target is the first served catalog this box offers `remote-compose` on — a
  property of the deployment. A host with none refuses by naming itself, exactly as
  `NO_NATIVE_CATALOG` does.
- **The generated entry wraps the body in `RemoteOverridablePreview` rather than annotating it.**
  `@PreviewWrapper` is `AnnotationRetention.BINARY`, so the only path that recovers a wrapper FQN is
  `previews.json`'s `params.wrapperClassName`, which the Gradle plugin fills at discovery time; a
  playground snippet's manifest is synthesised from discovered ids and carries no params. An
  annotation would compile and then be silently ignored — a capture that returns no document for a
  reason nothing reports.

On the canvas, `LocalRemoteComposeCaptures` answers with a captured document *or nothing*, keyed by
node id. Nothing is the common answer and the honest one: it is what every node says before anyone
has asked for a capture and what a host with no capture lane always says, and the node then draws
its stand-ins exactly as before. Where there is a document, `RcComposePlayer` draws it, the frame
stays as the boundary marker and stops standing in for the content, and the design's own
`remote-compose/custom` nodes register their `content` slots by `name` — so what fills the hole is
the same Compose an application would register on a watch. `UI_BUILDER_WEAR_SCREEN.md`'s *never fake
a component* rule is then satisfied by drawing rather than by a note.

| Described: nothing captured | Played: the captured document |
| --- | --- |
| ![Inline content drawn with Compose stand-ins in a marked frame](evidence/ui-builder-remote-compose/inline-described.png) | ![The same node played by RcComposePlayer, its custom component filled by the design's own Compose](evidence/ui-builder-remote-compose/inline-played.png) |

Both are rendered by `InlineRemoteContentPlaybackTest` from the same preview document through the
same renderer, so they cannot drift from the assertions beside them. On the right the design's
`Search` and `Recent` labels are gone because the fixture document carries only the custom
operation — a real capture carries everything the body wrote — and `Recently played`, which sits
*outside* the remote content, is still drawn: the player is given the shape the document's header
declares rather than every pixel the column has left, which is the one place an inline node
deliberately differs from an embedded one.

#### What the capture costs

The density decision is `RemoteOverridablePreview`'s, made once, with its reasoning written down
beside it: `RemoteDensityBehavior.Legacy`, because that is the only value that describes what the
creation library actually writes — padding, gaps, border widths and clip radii are already pixels at
capture, `heightIn`/`widthIn` are dp, and `Dp` would scale the first group a second time (a bug this
stack shipped three times). The generation density is then stamped into the header so a player can
scale the dp-typed dimensions back. This document does not re-state that decision and the lane does
not re-make it; it names the call site that already did.

What that costs here is the same thing it costs everywhere: the document is constant-folded at the
**daemon's** density, and the canvas that plays it is at the browser's. A design pinned to a 2.625
phone whose content was captured at the daemon's 2.0 is not wrong — the header carries the
generation density and the player scales — but it is a document baked for one density being replayed
at another, and the two agree only as far as that stamp and the player's scaling take them.
Absolute sizes survive it; anything the creation library wrote as pixels at capture time is fixed at
capture time. `Legacy` is also the legacy track, and its own kdoc concedes that "historically some
layout properties might have behaved differently"; the forward-looking value is `Dp`, and it stays
unusable until the creation library honours it.

The other cost is staleness, and it is why the capture is a *lookup* rather than design state. Bytes
captured from revision 3 describe revision 3; edit the subtree and they describe something that is
no longer there. Keyed by node id in a composition local, a capture lives as long as the host that
made it and is re-taken when the design changes — where a `capturedDocumentUrl` property written
into the design would persist, reopen, and quietly play the wrong content.

## Named slots

Every key in the node's `slots` map registers a Remote Compose custom-component config of the same
name. When the child document emits that custom component, the player renders the UI Builder nodes
listed in the matching slot inside the custom component's measured bounds.

For example, the outer node can declare a `hero.card` slot and the nested Remote Compose document
can emit a custom component configured as `hero.card`. Slot names are authored by documents, so the
existing `DynamicSlots` trait marks components that accept document-authored names without changing
the released catalog wire shape. Existing named slots participate in validation, tree editing,
moving, duplication, and deletion.

Unregistered custom configs remain a player support issue rather than silently selecting arbitrary
outer content. Multiple children in one slot deliberately overlay in a `Box`; authors that need
linear layout should put a UI Builder layout node in the slot.

## State and events

Each nested player owns its Remote Compose runtime state, including animations, gestures, and
document-local variables. Recomposition does not recreate that state.

The optional `namedValues` property is the explicit host-to-child boundary. Entries are keyed by
the Remote Compose named-variable name and use one of these shapes:

```json
{
  "USER:title": { "type": "text", "value": "Featured" },
  "USER:progress": { "type": "float", "value": 0.5 },
  "USER:selectedId": { "type": "integer", "value": 7 },
  "USER:query": { "type": "stateText", "variable": "searchQuery" }
}
```

Literal `long` and ARGB `color` values are also supported. `stateText` follows an outer UI Builder
state variable. Updates are applied incrementally through the player's snapshot-backed named-value
holder, so they do not reset the child runtime.

Child-to-host events reuse UI Builder event bindings:

- a named host action dispatches the binding with that exact name;
- numeric and metadata host actions dispatch `hostAction:<id>`;
- debug messages are not application events.

This boundary is intentionally directional. Remote Compose document-local mutations stay local;
documents publish values they want the host to observe through host actions.

## Theme and other player services

The node defaults to `inherit`, mapping the outer light/dark/system environment to the nested
player. It may override this with `light`, `dark`, or `system`. Typefaces, system colours, resource
resolution, logging, haptics, time, and accessibility are player host services rather than fields
in the composite document. Defaults are used in the first slice; a shared host-services object is
the next extension if applications need to inject those consistently across nested players.

## No borrow crosses into `@RemoteComposable`

The `wear-m3` catalog settled a neighbouring question with a rule — [never fake a component so it
runs in Wasm](UI_BUILDER_WEAR_SCREEN.md#the-line-a-component-is-never-faked-so-it-can-run-in-wasm) —
and with one carve-out: foundation may be borrowed, because `Box`, `Column` and `Row` are one
declaration that mobile Compose and Wear Compose genuinely share. **The rule applies here. The
carve-out cannot.**

A `remote-m3` design generates a `@RemoteComposable` body, and Remote Compose is a separate
composable world rather than a library layered on the usual one: a `@RemoteComposable` function
cannot call `@UiComposable` content, so it no more compiles against
`androidx.compose.foundation.layout.Column` than against `androidx.compose.material3.Text`. Foundation
does not cross that boundary; nothing does. Everything a widget body is written in comes from the
remote APIs — `remote-creation-compose` (`RemoteColumn`, `RemoteRow`, `RemoteBox`, `RemoteModifier`,
`.rs` / `.rdp` / `.rsp`), `remote-foundation`, and `remote-material3` (`RemoteText`,
`RemoteMaterialTheme`) — which is what
[`RemoteContentEmitter`](../../ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/RemoteContentEmitter.kt)
imports, and all it imports.

So the "borrow foundation, rename the Material" split is not available to this catalog. The ids it
publishes for authoring — `layout/box`, `layout/column`, `layout/row`, `m3/surface`, `m3/text` — are
stand-ins for remote components all the way down, and the generated code translates every one of
them: `layout/column` becomes `RemoteColumn`, `m3/text` becomes `RemoteText`. Renaming them
`remote-m3/…` would be an id migration, so the palette stops implying Jetpack Compose components a
widget can never hold. It is not the foundation carve-out `wear-m3` got, and applying that carve-out
here — leaving `layout/column` because "foundation is shared" — would keep in the palette the one id
whose sharing claim is provably false.

## The Lottie element compiles; it does not play

`remote-m3/lottie` is the one component in this catalog whose export is the entire feature, and the
verb matters. Horologist's [`remotecompose/lottie`][horologist-lottie] is a **Lottie compiler**:
`LottieAnimation(json = …)` is a `@RemoteComposable` that parses the animation while the document is
being built and re-emits every layer, shape and keyframe as Remote Compose operations over the
document's own animation clock. What reaches the watch is a `.rc` document that draws the animation
— no Lottie runtime on the device, no JSON, no fetch. That is why the element belongs here and
nowhere else: a `wear-m3` screen or an `m3-catalog` phone screen exports ordinary Compose, where the
answer to "play a Lottie" is `lottie-compose`, a different library this element would misdescribe.

It has two source properties, which are two halves of one source rather than two options:

* `url` — where the animation came from. The builder resolves it into `json` **once**, while the
  design is open, and the fetch obeys the host's own rule: `sameOriginRequestUrl` refuses anything
  that leaves the page's origin, so an animation served from a CDN is pasted in rather than fetched.
* `json` — the animation itself, and the only half the export can use. A generated widget has no
  network at the moment it needs the bytes, so an element still carrying only a URL is refused **by
  name** rather than written as source that cannot fetch. The canvas says the same thing where the
  node sits, so an author learns it before pressing export.

`progress` is the third property and the one worth reading twice: leaving it unset is what makes the
compiled document animate, driving the frame from `ANIMATION_TIME`; setting it (0 first frame, 1
last) pins the animation to one frame, which is what a glanceable widget that must not move wants.

The animation is emitted as a top-level `private const val` rather than inline — a minified Lottie
is a few thousand columns — and past a JVM string constant's 64KiB cap it is refused with the route
that does work (`res/raw` plus `LottieAnimation(rawRes = R.raw.…)`).

Horologist publishes no artifact for that module, so `yschimke/rc-players` vendors it at
`third_party/horologist-lottie`, pinned and unmodified; its `PROVENANCE.md` carries the commit and
the note that the copy should be deleted the moment upstream publishes. The vendored module is what
the exported source is compiled against, which is what keeps this file's promise checkable.

[horologist-lottie]: https://github.com/google/horologist/tree/main/remotecompose/lottie

## Follow-up features useful to every CMP player

1. A suspendable, size-limited document resolver with content hashes, caching, cancellation, and
   cycle/depth limits. Browser fetch, Android resources, files, and bundles become adapters.
   `documentUrl` is the first adapter and the caching half of this — one fetch per URL, the failure
   stored so it is not retried every frame — without the content hashes or the size limit.
2. Player support preflight exposed before composition, including missing custom configs, fonts,
   images, opcodes, and capability versions.
3. Bidirectional observable named values, avoiding the current need for a host action when the host
   wants to observe document-local changes.
4. Structured event payload bindings so outer state actions can consume a named action's value or
   metadata instead of only reacting to its name.
5. A stable host-services interface for typography, system colours, resources, diagnostics,
   accessibility, clocks, and effects, inherited through recursively nested players.
6. Explicit recursion budgets and stable instance keys for child documents so navigation and
   editor selection can address nested content safely.
7. Renderer-neutral export policy: flatten through a capable player, preserve the nested document,
   or report a blocking unsupported feature. The first slice blocks structured SVG export rather
   than producing a misleading fallback.
8. Declarative mappings between custom-component properties/return channels and the outer
   document's typed state, so filled slots can consume child parameters without adopting RC wire
   property ids throughout the UI Builder tree.
