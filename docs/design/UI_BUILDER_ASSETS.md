# UI builder: images in a design

How a design comes to contain a photograph — the asset registry, the one lane that fills it, and
what every renderer and exporter does with a key. Closes
[#491](https://github.com/yschimke/compose-preview-server/issues/491)
([#478](https://github.com/yschimke/compose-preview-server/issues/478),
[#484](https://github.com/yschimke/compose-preview-server/issues/484), and the `asset/image` row of
[#477](https://github.com/yschimke/compose-preview-server/issues/477)).

## The shape

`DesignDocumentV1` has always carried `assets: Map<String, AssetBindingV1>`, and `asset/image` has
always named one of its keys through `assetKey`. What was missing was everything around it: no
lane wrote the map after `createDesign`, the renderer threw on any key it had not been compiled
with, and the export had no record for the component. So:

- **The document names the picture, the host stores it.** A binding is
  `{mediaType, contentDigest: "sha256:…", source: {type: "uploaded", storageKey}, widthPx, heightPx}`,
  and `storageKey` is the digest. The bytes live in `UiBuilderAssetStore` — one file per digest
  under `<ui-builder-dir>/assets/`, beside the references and the comments. Not embedded in the
  document, deliberately: the service keeps a whole-document snapshot per retained revision inside
  one state file, and a 300 KiB photograph embedded there would be rewritten on every accepted edit
  until the file's ceiling refused the next one. Content addressing means the same picture under
  two keys, or in two designs, is stored once, and re-pointing a key deletes nothing.
- **One way to fill it, and it comes first.** `PUT /api/ui-builder/v1/designs/{designId}/assets/{assetKey}`
  with the raw PNG, JPEG, GIF or WebP body, or the `ui_builder_put_asset` MCP tool with the same
  bytes in `imageBase64`; then the `asset/image` node naming the key, which the reducer accepts
  because the key is now pinned. Both are gated as a write to the design (`ui-builder-write`, and the design's own
  access list), sniff the bytes rather than trusting a content type, and answer the same accepted
  outcome an apply does — with the new revision to quote as `baseRevision`. The same bytes under
  the same key answer `idempotentReplay` and move nothing. `GET` on the same path hands the bytes
  back to anyone who may read the design; the browser canvas fetches through it.
- **What a key may be.** 1 to 64 characters of letters, digits, `.`, `_` or `-`, starting with a
  letter or digit — a node id's alphabet, so a key is safe in a URL path and never becomes a
  filename. A picture is at most `maximumAssetBytes` (1 MiB) and a design holds at most
  `maximumAssetsPerDesign` (64); both are `UiBuilderServiceLimits` fields.

## A commit without an operation

The released `DesignMutationV1` set has no asset write, and adding one means releasing
`ui-builder-protocol`. The route is the same kind of door the native-preview route is — beside the
envelope, until the wire grows the request. Inside the service an asset write behaves as a commit
in every way but one: the revision and the sequence move, the document hash changes, a revision
snapshot is retained so `getSnapshot(revision)` still answers, and every live subscriber hears
about it. What it cannot do is appear in the delta log, because no `CommittedOperationV1` can
describe it. So the log is cut at that sequence: `retainedFromSequence` becomes the write's own
sequence, a subscriber behind it is caught up with a whole snapshot (which the protocol client
already handles as `SnapshotRequired`), and live subscribers are sent that snapshot immediately,
without the owner's access list. It is not undoable, for the same reason — undo compensates an
operation record, and there is none. Re-pointing the key, or deleting the node that names it, is
how it is taken back.

## Resolution, once

Every lane that draws or exports an `asset/image` asks `UiBuilderDocument.resolveAsset(key)` and
gets one of five answers: the registry's **embedded** bytes, an **uploaded** binding a host has to
fetch, one of the two **project-owned** Jetcaster covers, the **generated** gate-0 cover, or
**missing**. The lanes used to disagree — the canvas knew three keys and threw on the rest, the SVG
recorder knew two and threw on the third — and the disagreement was #484: one accepted node took a
whole design down, in the editor, behind `ui_builder_export`, and for every collaborator with the
tab open.

- **The canvas and the daemon render** draw embedded bytes from the document, ask
  `LocalUiBuilderAssetBitmaps` (keyed by content digest) for uploaded ones — the editor fetches
  them over the `GET` route and decodes once per digest — and draw a picture-shaped placeholder
  carrying the key for anything else. Nothing throws. A daemon render sees only the projected
  document string, so `ProductionUiBuilderExportExecutor` inlines the stored bytes into it as
  embedded sources before the render; a binding it cannot find travels as it is and draws the
  placeholder.
- **The SVG export** declares a node as a raster fallback only when it has bytes it can embed
  offline (embedded or project-owned); everything else is drawn as the vector placeholder, which
  the recorder needs no raster correlation for. Provenance for a design asset is
  `design-asset/v1/<key>/<digest>/rendered-<w>x<h>`.
- **The Compose export** has a record for `androidx.compose.foundation.Image` now
  (`m3-catalog-components-v1.json`). `ScreenDocumentProjection` writes the call with the design's
  description, `ContentScale`, `Alignment` and modifiers, and stands
  `ColorPainter(MaterialTheme.colorScheme.surfaceVariant)` in for the one argument no generated
  Kotlin can carry. It says so twice: a header line per asset naming the node, the key and the
  digest to bundle, and an `ASSET_PLACEHOLDER` warning on the artifact. The generator's expression
  allowlist is unchanged — both symbols are under `androidx.compose` — and the native preview lane
  compiles and runs the result.

## What the reducer refuses, and what it draws anyway

[#497](https://github.com/yschimke/compose-preview-server/pull/497) made `assetKey` a checked
value: both reducers refuse, at commit, a key that is not in the catalog's
`statusSemantics.assetRegistry.keys` — the two Jetcaster covers, the gate-0 cover and the editor's
own insert placeholder. This lane widens that set by exactly the keys the design has pinned: the
runtime's `validateWrite` and the editor's `CapabilityValidator` both read `document.assets`, so a
key resolves if the catalog ships it **or** the design uploaded it, and is refused otherwise with a
message that names both routes. The order that follows is put first, then insert; an insert naming
a key nobody has put is refused by name, not committed and drawn blank.

The placeholder stays as the second line. A design committed before the rule, a binding whose
bytes are not on this host, an embedded payload that does not decode, a canvas with no resolver for
uploaded bytes — each is an ordinary state, and each draws a picture-shaped frame carrying the key
rather than failing the composition. The canvas and a PNG label it; a structured SVG keeps the
frame and glyph and drops the label, because that recorder fails closed on text it cannot attribute
to an authored node, which is the right rule for an export.

## What is deliberately not here

- **External URLs.** Still refused, as #478 says they should be: a stored, hash-checked asset is
  the answer, and the host fetches nothing on a design's behalf.
- **A browser upload control.** The canvas draws uploaded assets; putting one in from the editor
  is a follow-up. The route is the same one it will use.
- **Deletion and eviction.** A key is re-pointed or its node deleted; the bytes stay, bounded by
  the per-asset and per-design caps. An operator can clear `<ui-builder-dir>/assets/` of digests no
  design names, and losing that directory loses pictures and no design content — the same blast
  radius as the reference overlays.
- **A protocol mutation.** When `ui-builder-protocol` grows a `setAsset` mutation and an asset
  change record, this lane becomes an ordinary operation with a delta and an undo, and the route
  stays as the raw-bytes door in front of it.
