# UI Builder target: Wear OS watch faces (Watch Face Format)

**Status: plan, 2026-11-17.** Parked on this branch, not merged; later phases are tracked below.

## Direction update (2026-11-17)

Decisions taken after this plan was first drafted. Where they conflict with the sections below,
these win:

- **Agents first, collaborating with humans.** Editor features are chosen for that; core
  capabilities (XML in and out, validation, the two previews, APK publishing) come before editor
  breadth.
- **XML in, UI Builder model inside.** Agents and people can submit WFF XML directly. The host
  imports it into the design document, where incremental edits, undo and collaboration continue.
  Import must be lossless, keeping unmodelled elements as passthrough.
- **Libraries live in [yschimke/watchface-tools](https://github.com/yschimke/watchface-tools):**
  `wff-core` (model, writer, parser, a schema generated from Google's XSDs for format versions
  1–5, validator, bundle layout), `wff-packager` (aapt2, signing, Google's validators, the Watch
  Face Push token) and `push-app`, a Wear OS 6 marketplace app that installs designs published on
  the site. Pushed APKs are named `ee.schimke.watchface.push.watchfacepush.<face>`.
- **Format version is an option up to v5**, sourced from `wff-core`'s generated schema.
- **Previews:** Remote Compose for several devices in the editor tab (via compose-ai-tools'
  `dwf-remote-compose` translator), and a separate tab streamed from the Android DWF runtime daemon
  (compose-preview-server's `android-dwf` surface).

UI Builder gets a fourth built-in target next to `m3-catalog`, `wear-m3` and `remote-m3`: a
Wear OS watch face written in the [Watch Face Format](https://developer.android.com/training/wearables/wff)
(WFF, also called the declarative watch face, DWF). Like the [Lovelace target](UI_BUILDER_LOVELACE_TARGET.md)
its output is a data document another system owns, not Compose source: WFF XML plus the
resources it names.

A user should be able to:

1. Start an analog or digital face from a template, or import an existing `watchface.xml` / WFF APK.
2. Edit it on a round canvas: groups, parts, clock hands, time text, complication slots, the
   ambient variant, user configurations and flavors.
3. See it **fast**, in the browser, on several devices at once, through Remote Compose.
4. See it **for real**, in a second web tab streamed from the Android DWF runtime daemon.
5. Read the XML at any time, and export the XML, a DWF resource bundle, or a resource-only APK.

## What already exists

All of this is in [`yschimke/compose-ai-tools`](https://github.com/yschimke/compose-ai-tools):

| Piece | Where |
| --- | --- |
| WFF → Remote Compose translator (scenes, clock hands, time text, expressions, ambient, complications, flavors, the face's own fonts) | `rc-render-jvm/.../rccreation/dwf/` (`DwfParser`, `DwfExpressionCompiler`, `DwfRemoteWatchFace`) |
| Fast lane daemon `dwf-rc`: renders from source dirs with live reload, named-value overrides | `rc-render-jvm/.../rcjvm/DwfDaemonMain.kt` |
| Faithful lane `dwf-apk`: Google's `DeclarativeWatchFaceRuntime.apk` on Robolectric via `:simulator-host`, with `watchface push`, `clock`, `ambient`, `complication`, `config`, `health`, `battery`, `screenshot` | `render-host/.../serve/DwfApkDaemonBridge.kt` |
| Resource-only WFF APK packaging (manifest, `watch_face_info.xml`, `aapt2 compile` + `link`) | `ensureWffApk()` in the same file |
| Parity evidence and the diff tool between the two lanes | `docs/evidence/dwf-apk-vs-rc/`, `scripts/dwf-diff.py` |

Nothing writes WFF XML from a model yet; only parsers exist.

## Architecture

### The design document is the source of truth; XML is a projection

The `wff-watchface` catalog's components mirror WFF elements one to one (`Scene`, `Group`,
`PartDraw`, `PartImage`, `PartText`, `AnalogClock`, `ClockHand`, `DigitalClock`, `TimeText`,
`ComplicationSlot`, `Variant`, and later `UserConfigurations`, `Flavors`, `Transform`,
`Condition`). Properties carry WFF attribute names. Images and fonts are design assets.

WFF positions every element absolutely in the face's pixel space, so canvas selection, drag and
resize overlays come straight from the document; no renderer has to report bounds back.

Catalog `statusSemantics`: platform `watchface`, frame adapter `frame/round-screen`, a round
seed device, `previewSurfaces.fast` = Remote Compose (Wasm player), native backend `android-dwf`.
No Compose source export.

### A shared WFF library: `yschimke/watchface-tools`

Kotlin Multiplatform (JVM + wasmJs), consumed from Maven by this repository and by
compose-ai-tools, so neither depends on the other:

- `wff-core` (common): a lossless WFF tree, a deterministic XML writer and a parser, the element
  schema with each element's and attribute's `sinceVersion`, a validator that reports node paths,
  and the bundle layout. Common code lets the browser editor emit XML on every edit.
- `wff-packager` (JVM): `ensureWffApk`'s manifest + `aapt2` recipe, lifted out of render-host.
- `wff-validator` (JVM): Google's open-source WFF XSD validator, as an export gate.

compose-ai-tools moves its `DwfParser` onto `wff-core` and publishes the translator
(`dwf-remote-compose`) as an artifact the hosts call.

### Format version is an option

Each design carries a target WFF version, 1–4, **default 2** (what `ensureWffApk` declares and
the widest-supported version on current devices). The palette and inspector hide elements and
attributes newer than the target; the validator refuses them on export with the node path. Raising
the version is a document edit, so it is undoable and visible in history.

### Preview: two surfaces

**Fast (this tab).** On each debounced revision the editor writes the XML locally; the host turns
it into a `.rc` document with the translator and the existing `RcComposePlayer` plays it, on
several round and square devices side by side. Preview controls (time, ambient, complication
data, flavor, user configuration, heart rate, steps, battery) drive the translator's named values
(`dwf.is_ambient`, `dwf.complication.<n>.*`, `metrics.*`), so changing them does not recompile.
Later option: compile the translator for Wasm too, so the fast preview works fully offline.

**Android (a second tab).** The native surface `android-dwf` is a separate page streamed from the
daemon. Per revision the host packages a resource-only APK, sends `watchface push` to the
`dwf-apk` daemon and streams its frames over the existing `/{session}/ws/{preview}` socket, with
the same controls. A "compare with fast" toggle runs the dwf-diff metrics and flags where Remote
Compose disagrees with the real runtime.

The runtime APK is proprietary and never redistributed. Hosts find it the way compose-ai-tools
does: `compose-preview dwf-runtime pull` from the user's own Wear emulator or device, or an
explicit path. Without it, the Android tab says so and the fast surface keeps working.

### Export

| Output | What it is | Hosts |
| --- | --- | --- |
| WFF XML | live, read-only XML view in the inspector, copy and download; validator diagnostics link back to nodes | all |
| DWF bundle (zip) | `AndroidManifest.xml` (with `com.google.wear.watchface.format.version`), `res/raw/watchface.xml`, `res/xml/watch_face_info.xml`, `res/drawable*/`, `res/font/`, `res/values/strings.xml`, `preview.png`, optional `build.gradle.kts` so it opens as an Android Studio module | all |
| WFF APK | the aapt2-linked resource-only APK, debug-signed, ready for `adb install` or Watch Face Push | local and desktop |

Anything the target version or the validator rejects is refused by name with its location, the
same 422 contract as the other exporters. `ExportFormatV1` lives in compose-preview-contracts, so
the new formats need a contracts change before the server can route them.

### Import

`watchface.xml` (or an APK through compose-ai-tools' `ApkDwfExtractor`) → a `.uid` design. Unknown
elements are kept as opaque passthrough nodes rather than refused. The wear-os-samples faces are
the round-trip corpus.

## Prior art: Samsung Watch Face Studio and Google's Watch Face Designer

Researched 2026-11-17, mostly from search extracts; Samsung's own pages could not be fetched from
the sandbox, so treat the WFS details as indicative.

[Watch Face Studio](https://developer.samsung.com/watch-face-studio) (WFS) is the incumbent
desktop tool. Google's [Watch Face Designer](https://developer.android.com/training/wearables/watch-face-designer/basics)
is a Figma plugin that assigns a behavior to each layer (second hand, digital time, complication
slot, photo) and exports an AAB, APK, raw `res/`, an Android Studio project or a WFS project.

**Worth copying**, roughly in priority order:

1. A layers tree and a properties panel that map one to one onto WFF elements.
2. A Run panel: a time scrubber with reset, and simulated battery, steps, heart rate, weather,
   date, 12/24h, locale and filled complication data per type.
3. An ambient toggle with a burn-in on-pixel-ratio readout (Samsung recommends under 15%) and
   per-layer ambient visibility.
4. A tag/expression editor with source search; ours adds autocomplete, type checking and the live
   evaluated value.
5. A complication slot editor: supported types, default provider, bounds, edge-slot shapes.
6. Theme palettes (WFS: up to 3 colours per palette applied by slot) and user configuration with
   a customization preview; in WFF terms `ColorConfiguration`, `ListConfiguration`, `Flavors`.
7. A component palette: analog hands, digital clock, index and ticks, progress arc and line,
   text and curved text, image sequences, photo slot, weather.
8. A memory-footprint budget and validation on every edit, not only at build time.
9. Device presets (round 384/450/480 px, square) and push to an emulator or device.

**What WFS users complain about, and our answer:**

| WFS pain point | This design |
| --- | --- |
| No XML import or export; "extract the bundle and continue in an IDE" | lossless XML round-trip both ways; the XML is always visible |
| Stuck on the WFF version a WFS release supports; newer features lag | per-design target version, newer features shown but gated with "requires vN" |
| Preview differs from the watch, especially in ambient | the Android tab runs the real runtime; compare-with-fast flags drift |
| Memory check is "guidance only" and disagrees with Play | run Google's memory footprint tool on the exported bundle |
| Windows and macOS only; binary project files; no collaboration | web-based, `.uid` and XML are diffable text, the existing collaborative op log |
| Visibility is faked with opacity or off-screen positions | first-class `Condition` editing in the inspector |

WFF has moved past version 4, so the target-version option is an open list sourced from
`wff-core`'s schema rather than a fixed 1–4.

## Phases

| # | Delivers | Repository |
| --- | --- | --- |
| 0 | `wff-core`: model, writer, parser, schema, versions, validator, bundle layout; round-trip tests on the wear-os-samples | watchface-tools |
| 1 | `WATCH_FACE` platform, `wff-watchface` catalog and route, templates, round frame with placeholder drawing, in-repo XML exporter | this repository |
| 2 | Fast Remote Compose preview on several devices, preview controls; `wff-core` replaces the in-repo writer | this repository, compose-ai-tools, compose-preview-server |
| 3 | Android tab streamed from the `dwf-apk` daemon; compare-with-fast toggle | compose-preview-server, compose-ai-tools |
| 4 | Bundle and APK export, validator gate, `preview.png`; `ExportFormatV1` additions | watchface-tools, compose-preview-contracts, this repository |
| 5 | Import, user configuration and flavor editing, expression editor | this repository |
