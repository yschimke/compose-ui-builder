# UI builder: exporting a design as a bundle

Where a design's pictures go when it is exported as source. Answers the open question in
[#528](https://github.com/yschimke/compose-preview-server/issues/528), which was raised against the
inlining [#524](https://github.com/yschimke/compose-preview-server/pull/524) landed
([#523](https://github.com/yschimke/compose-preview-server/issues/523)), and settles the content-image
half of [#508](https://github.com/yschimke/compose-preview-server/issues/508) for the lane that can
carry bytes.

## The correction that starts it

#524 justified inlining a widget background with "a widget is drawn by the system host, so anything
resolved at draw time is not there to resolve". The first half is true; the conclusion was too
strong, and `RemoteContentEmitter` and `WidgetAssetBytes` both said it in prose that is now fixed.

`provideWidgetData` runs in the **application's** process. That is where the brush chain is built and
where the bitmap is serialised into the `WearWidgetDocument`, before anything reaches the launcher.
An app can load a picture there — from its resources, from its assets, from its cache — and pass it
in; the pixels are baked into the document at build time and the host resolves no name. What is
genuinely ruled out is a name the **drawing** side resolves. Loading in `provideWidgetData` is not
that.

So inlining is a good default and not the only thing that works, and the cost it pays is real:

| Asset | Inlined source |
| --- | --- |
| The 432×248 test cover (27 KB) | ~37 KB, ~390 lines of base64 |
| A real album cover at 1:1 | hundreds of KB, thousands of lines |

About 1.37× the PNG, in a file a person is supposed to read. It is not images only: `remote-m3/lottie`
inlines a few thousand columns of minified JSON, and the emitter apologises for it by putting the
declaration last in the file so a reader can stop before it.

## The decision: a second format, not a threshold

#528 put two shapes:

**A.** A second export format. `compose` keeps inlining and stays self-contained; a new `bundle`
format returns an archive.

**B.** Bundle replaces inlining above a size threshold. One format, self-contained while it is small.

**A.** The self-contained file is not a fallback for small designs — it is the product for two lanes
that will never want an archive:

- **`ui_builder_export` over MCP** returns into a conversation. Source is the useful thing there; an
  archive is base64 an agent cannot read, and the file it wants to write is the one the export
  already hands it.
- **"Paste this into your project"** is what makes the export work with no build wiring at all. A
  bundle asks the caller to unpack into a source set and get an asset path right; that is a fair
  price for real artwork and a bad one for a widget with two gradient stops.

B loses both by making the answer's *kind* depend on how large a picture is. A caller of an export
API branches on the format it asked for; it cannot branch on a number the server picked. A design
that gains 3 KB of artwork would silently change what comes back, and the threshold itself is a
number nobody can defend — it is the sort of thing that is discovered in a bug report rather than
read in a doc.

The cost of A, stated: one member on a released wire enum, and a second path through the emitter.
The second is smaller than it looks — `RemoteContentEmitter` already separates the picture whose
bytes the file must carry (`inlineAssets`) from the one the app supplies (`imageAssets`), and the
bundle lane is a different answer at exactly that seam.

## What the archive is

A project fragment, not "the source minus the pictures":

```
spotify-now-playing.zip
├── README.md                                       # where to drop it, what the app passes
├── SpotifyNowPlayingWidget.kt                      # readable; loads by path
└── assets/uibuilder/spotify-now-playing/
    ├── cover-wide.png
    └── album-art.jpg
```

### `assets/`, not `res/drawable-nodpi/`

#528 assumed `res/drawable-nodpi/` and `context.resources`, and named the cost that follows: a
mapping from asset keys to Android resource names, with collision handling, "since Android resource
names are a narrower alphabet than asset keys". Putting the bytes in `assets/` instead deletes that
cost rather than paying it, and deletes three more:

- **No `R`.** `R` is generated into the *application's* namespace, not into the package the exported
  file declares. Source that says `R.drawable.cover_wide` compiles only when the two agree, so the
  export would need the app's namespace as a second parameter — a thing the design does not know and
  the caller would have to be asked for. `context.assets.open("uibuilder/…/cover-wide.png")` needs
  nothing generated. (`resources.getIdentifier` is the other way to avoid the namespace and is
  refused: a reflective lookup R8 cannot see, which resource shrinking breaks and which fails at
  draw time rather than at compile time.)
- **No name mapping, no collisions.** A design id and an asset key share one alphabet — 1–64
  characters of `[A-Za-z0-9][A-Za-z0-9._-]*` (`ServeUiBuilderDesignLibrary.DESIGN_ID`, and the key
  rule in [`UI_BUILDER_ASSETS.md`](UI_BUILDER_ASSETS.md)) — which is already a safe path segment.
  `assets/uibuilder/<designId>/<assetKey>.<ext>` is therefore injective with no renaming, and
  scoping by design id means two designs unpacked into the same app do not collide either. A
  resource name would have had to fold `.` and `-` away and then disambiguate what folded together.
- **No density.** A drawable is scaled by the density bucket its directory names; `-nodpi` is how
  #528's shape says "do not". An asset is not scaled at all, which is what a widget background wants:
  the exact pixels the design was authored against, at the size the document already fixes.

What `assets/` gives up is resource qualifiers — no `-night`, no per-density variant, no shrinking or
lint awareness. A design carries one binding per key and no configuration axis, so there is nothing
to qualify; when a design grows per-configuration artwork, that is the point to revisit this, not
before.

### A stored zip, not a `tar.gz`

The archive is a container, not a compressor: a PNG and a minified JSON are already compressed, and
gzip over them buys a rounding error. What the choice actually decides is who can write it and who
can open it.

- A **stored** (uncompressed) zip is a few dozen lines of writer with no dependency, on the JVM and
  in Wasm alike — which matters because the editor's Code pane runs in the browser and should be able
  to hand the same bundle down as the server does, rather than being the surface that cannot.
- It opens by double-click everywhere, including the Windows machines where `tar.gz` is a command
  rather than a gesture.

`tar.gz` would need a gzip implementation on both floors to buy nothing. Recorded because #528 named
it, not because it is wrong.

## What the source looks like

The readable half of the same widget: no base64, and the picture loaded where the app's process
already is.

```kotlin
class SpotifyNowPlayingWidget(
    // The design's `album-art` asset. Null draws the design's own artwork, which is what the
    // @Preview below shows; an application passes the album art it is actually playing.
    private val albumArt: RemoteImageBitmap? = null,
) : GlanceWearWidget() {
  override suspend fun provideWidgetData(
      context: Context,
      params: WearWidgetParams,
  ): WearWidgetData {
    val coverWide = context.bundledBitmap("uibuilder/spotify-now-playing/cover-wide.png")
    val albumArt = albumArt ?: context.bundledBitmap("uibuilder/spotify-now-playing/album-art.jpg")
    return WearWidgetDocument(background = WearWidgetBrush.image(coverWide)) {
      SpotifyNowPlayingWidgetContent(albumArt = albumArt)
    }
  }
}

private fun Context.bundledBitmap(path: String): RemoteImageBitmap =
    assets.open(path).use { BitmapFactory.decodeStream(it) }
        .asImageBitmap()
        .rb
```

Three things are load-bearing there.

**The parameter survives.** A content picture is application data — that is what #508 settled, and a
bundle does not take it back. What changes is the default.

**The default moves into `provideWidgetData`.** It has to: `GlanceWearWidget`'s constructor has no
`Context`, so a parameter default cannot decode anything. The parameter becomes nullable, `null`
means "the artwork the design was drawn with", and the fallback is resolved where the `Context`
exists. Today that default is `ImageBitmap(1, 1)` — a deliberate hole, because in the inlining lane
there is nowhere for the bytes to be. In the bundle lane there is, so **the generated `@Preview`
shows the design instead of a hole**, which #528 called the thing to look at first and which is the
strongest argument for the format existing at all. A key the archive cannot carry keeps the hole —
its local falls back to the same blank bitmap — because a content picture is the application's and
the parameter stands whether or not there is a stand-in behind it.

**The background is a local, not a `val` below the preview.** The inlining lane hoists a picture to
the file's foot so a reader can stop before the base64; a bundle has nothing to scroll past, so the
load sits where it is used.

The `compose` lane is unchanged by all of this, `ImageBitmap(1, 1)` included. Giving it a real
default would mean inlining a second picture into a file already carrying one, which is the cost this
format exists to avoid.

## Where it goes on the wire

Smaller than #528 estimated. An archive is not a third artifact shape: `ExportArtifactV1` already
carries binary as `ExportEncodingV1.BASE64` — that is how `png` travels — so a bundle is
`mediaType = "application/zip"` with the same encoding, and `ExportEncodingV1` does not change.

What does change is one enum member, and it is **not in this repository**. `ExportFormatV1` and
`ExportCapabilitiesV1` are published from `compose-preview-contracts`, which is why the widget
export reuses `composeCode` today rather than having a format of its own (`ServeRunner` says so at
the `composeExportFor` wiring). So the work is staged, and the first step is not here:

1. **compose-preview-contracts** — `ExportFormatV1.BUNDLE`, and `ExportCapabilitiesV1.bundle`
   defaulting to `false` so an older server deserialises a newer client's capability block unchanged.
   Release `ui-builder-protocol`.
2. **Here, `:ui-builder-export`** — the emitter's second path: the same walk, answering at the
   `inlineAssets` seam with a path instead of a base64 literal, and returning the file set rather
   than an archive. That is `WearWidgetCodeExporter.exportBundle`, `WidgetAssetContents` (the
   registry seam, which answers a media type as well as bytes because a file has a name) and
   `WidgetBundle` (the source, the `README.md`, and one `WidgetBundleFile` per picture, its bytes
   still base64). The module is KMP with no I/O and stays that way; framing bytes into a zip is a
   host's job.
3. **Here, `:server` and `:ui-builder-runtime`** — `ProductionUiBuilderExportExecutor` grows the
   `BUNDLE` arm, reading bytes from `UiBuilderAssetStore` exactly as the daemon-render inlining does
   today; the download route learns the `.zip` extension; the capability is advertised per catalog
   beside `composeCode`.
4. **Here, `:ui-builder`** — the editor's Code pane offers the archive beside the source, writing the
   same zip in the browser from its own asset copies.

Until step 1 ships there is nothing to wire, and a bundle emitter with no format to return it under
would be API with no caller. That ordering is the reason this document lands before any of it.

## What a caller gets told

- **The MCP tool keeps `compose` as its default** and describes `bundle` as the format for a design
  with artwork, saying plainly that it answers base64 rather than text. An agent that wants to read
  the code asks for `compose`; one that is writing files asks for `bundle` and unpacks it.
- **The `ASSET_PLACEHOLDER` warning does not appear on a bundle.** It exists because the Compose
  export stands `ColorPainter` in for a picture it cannot carry
  ([`UI_BUILDER_ASSETS.md`](UI_BUILDER_ASSETS.md)); a bundle carries it, so the diagnostic would be
  false. A bundle whose asset bytes are *missing* from the store still refuses by name, as the
  inlining lane does — an export that quietly ships a broken path is the failure this repository's
  emitters are written to avoid.
- **The `README.md` in the archive names the source set.** `assets/` is `src/main/assets/` in a
  conventional Android module and the path inside it is what the source already says; the file states
  both, plus which parameters the application is expected to pass and what happens if it passes none.

## What is deliberately not here

- **The Lottie animation.** A minified animation is still a few thousand columns of constant, and
  the archive is the obvious place for it — but `LottieAnimation(json = …)` is called inside the
  `@RemoteComposable` content function, which has no `Context` and cannot open a file. A bundled
  animation therefore has to travel as a parameter the widget resolves in `provideWidgetData`, the
  same shape a content picture takes, and that is a change to what the content function's signature
  means rather than a second answer at the asset seam. Worth doing; not this change. Until then the
  64 KiB refusal a large animation gets is unchanged in both lanes.
- **The screen exporters.** `ScreenDocumentProjection` writes `ColorPainter(...)` for an
  `asset/image` on an ordinary screen, and the same archive would let it write a real painter. It is
  the obvious generalisation and it is a second change: the widget lane is where the inlining that
  motivated #528 actually lives, and the screen lane's placeholder is a call-site argument rather
  than a document brush.
- **A threshold anywhere.** Not as a switch (that is B, refused above) and not as a warning either —
  a `compose` export of a large picture is a large file on purpose, and the caller who wanted the
  other thing asks for the other thing.
- **Fetching anything.** A bundle carries the bytes the host already stores under the design's
  bindings. External URLs stay refused, for the reason
  [`UI_BUILDER_ASSETS.md`](UI_BUILDER_ASSETS.md) gives.
- **Round-tripping.** The archive is an export, not a document format: nothing reads a bundle back
  into a design. The design is the state on the server, and it stays the only thing that is.
