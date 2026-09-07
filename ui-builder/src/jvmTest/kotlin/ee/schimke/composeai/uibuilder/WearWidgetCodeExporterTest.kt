package ee.schimke.composeai.uibuilder

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class WearWidgetCodeExporterTest {
  private val pin = JsonObject(emptyMap())
  private val environment = JsonObject(emptyMap())

  @Test
  fun `hello generates the sample's own shape`() {
    val source =
      assertIs<WearWidgetCodeExporter.Result.Emitted>(
          WearWidgetCodeExporter.export(helloWidgetUiBuilderDocument("hello", pin, environment))
        )
        .source

    write("HelloWidget.kt", source)
    // The host's container appears nowhere: on-device the launcher draws it.
    assertTrue("WidgetContainer" !in source, source)
    assertTrue("widget-container" !in source, source)
    assertTrue("class HelloWidget : GlanceWearWidget()" in source, source)
    assertTrue(
      "WearWidgetDocument(background = WearWidgetBrush.color(colorScheme.primary))" in source,
      source,
    )
    assertTrue("fun HelloWidgetContent()" in source, source)
    assertTrue("SquircleSmallWidgetPreviewParams().values.maxBy { it.widthDp }" in source, source)
  }

  @Test
  fun `weather generates its literal colours and its column`() {
    val source =
      assertIs<WearWidgetCodeExporter.Result.Emitted>(
          WearWidgetCodeExporter.export(weatherWidgetUiBuilderDocument("weather", pin, environment))
        )
        .source

    write("WeatherWidget.kt", source)
    assertTrue("WearWidgetBrush.color(Color(0xFF2196F3).rc)" in source, source)
    assertTrue("RemoteColumn(" in source, source)
    assertTrue("SquircleLargeWidgetPreviewParams().values.maxBy { it.widthDp }" in source, source)
  }

  /**
   * A gradient in the background slot becomes the `WearWidgetBrush` chain the container takes.
   *
   * `RemoteContentEmitter` has written this since the slot existed, but nothing could author it:
   * the reviewed `remote-m3` subset carried no component with a `DrawLayer` trait, so the slot was
   * unfillable from the palette and from any document the catalog validator would accept
   * (yschimke/compose-preview-server#428). `shape/linear-gradient` is in that subset now, and this
   * is the export end of it.
   */
  @Test
  fun `a linear gradient in the background slot is written as a brush chain`() {
    val base = weatherWidgetUiBuilderDocument("weather", pin, environment)
    val scaffold = base.nodes.values.first { it.componentId.startsWith("remote-m3/") }
    val gradient =
      UiBuilderNode(
        id = "sky",
        componentId = "shape/linear-gradient",
        properties =
          JsonObject(
            mapOf(
              "startColor" to literal("color", "#FF2196F3"),
              "endColor" to literal("color", "#FF0D47A1"),
              "direction" to literal("enum", "leftToRight"),
            )
          ),
      )
    val document =
      base.copy(
        nodes =
          base.nodes +
            mapOf(
              gradient.id to gradient,
              scaffold.id to
                scaffold.copy(slots = scaffold.slots + ("background" to listOf(gradient.id))),
            )
      )

    val source =
      assertIs<WearWidgetCodeExporter.Result.Emitted>(WearWidgetCodeExporter.export(document))
        .source

    write("GradientWidget.kt", source)
    // Hoisted and broken between the chain's calls: two literal stops already spend past the
    // column budget, so the chain is asserted call by call rather than as one line.
    assertTrue("WearWidgetBrush.color(Color(0xFF2196F3).rc)" in source, source)
    assertTrue(
      ".horizontalGradient(listOf(Color(0xFF2196F3).rc, Color(0xFF0D47A1).rc))" in source,
      source,
    )
    assertTrue("import androidx.glance.wear.horizontalGradient" in source, source)
  }

  /**
   * A Lottie element compiles into the document, and the animation lands in a constant.
   *
   * The three assertions are the three halves of the promise (the call, the import, the bytes): the
   * body calls Horologist's `LottieAnimation`, the file imports it from the module
   * `yschimke/rc-players` vendors, and the animation itself is a top-level constant rather than a
   * few thousand columns inside the design.
   */
  @Test
  fun `a lottie element is compiled into the widget's document`() {
    val source =
      assertIs<WearWidgetCodeExporter.Result.Emitted>(
          WearWidgetCodeExporter.export(lottieWidget(LOTTIE_JSON))
        )
        .source

    write("LottieWidget.kt", source)
    assertTrue("LottieAnimation(json = LOTTIE_ANIMATION" in source, source)
    assertTrue(
      "import com.google.android.horologist.remotecompose.lottie.LottieAnimation" in source,
      source,
    )
    assertTrue("private const val LOTTIE_ANIMATION = \"{" in source, source)
    // Reparsed and reprinted: the indentation the author's file carried is most of its bytes, and
    // all of them wasted inside a string literal.
    assertTrue("\\n" !in source, source)
    // Unset progress is what makes the compiled document run the animation off its own clock.
    assertTrue("progress" !in source, source)
  }

  /** A pinned frame is an argument; the absence of one is what makes the animation loop. */
  @Test
  fun `a pinned progress is emitted as a remote float`() {
    val base = lottieWidget(LOTTIE_JSON)
    val lottie = base.nodes.values.first { it.componentId == LOTTIE_COMPONENT_ID }
    val document =
      base.copy(
        nodes =
          base.nodes +
            (lottie.id to
              lottie.copy(
                properties =
                  JsonObject(lottie.properties + ("progress" to literalNumber("number", 0f)))
              ))
      )

    val source =
      assertIs<WearWidgetCodeExporter.Result.Emitted>(WearWidgetCodeExporter.export(document))
        .source

    assertTrue("progress = 0.rf" in source, source)
    assertTrue("import androidx.compose.remote.creation.compose.state.rf" in source, source)
  }

  /**
   * A URL alone is refused, because the generated widget has no network at the moment it needs the
   * animation. The builder resolves a URL into the JSON while the design is open; an element that
   * arrived here without one is unfinished, and saying so beats writing a file that cannot fetch.
   */
  @Test
  fun `a lottie element carrying only a url is refused by name`() {
    val refused =
      assertIs<WearWidgetCodeExporter.Result.Refused>(
        WearWidgetCodeExporter.export(
          lottieWidget(json = "", url = "https://example.test/spin.json")
        )
      )

    assertEquals(1, refused.reasons.size)
    assertTrue(
      "https://example.test/spin.json" in refused.reasons.single(),
      refused.reasons.single(),
    )
  }

  /** Not JSON is caught here rather than by the compiler of whoever pasted the file. */
  @Test
  fun `a lottie element holding something other than json is refused`() {
    val refused =
      assertIs<WearWidgetCodeExporter.Result.Refused>(
        WearWidgetCodeExporter.export(lottieWidget("not an animation"))
      )

    assertTrue("valid JSON" in refused.reasons.single(), refused.reasons.single())
  }

  /** A small, real Lottie: one solid layer, which is enough for the compiler to have something. */
  private val LOTTIE_JSON =
    """
    {
      "v": "5.9.6",
      "fr": 30,
      "ip": 0,
      "op": 30,
      "w": 64,
      "h": 64,
      "layers": [
        { "ty": 1, "ind": 1, "sc": "#2196f3", "sw": 64, "sh": 64, "ip": 0, "op": 30, "st": 0 }
      ]
    }
    """
      .trimIndent()

  private fun lottieWidget(json: String, url: String = ""): UiBuilderDocument {
    val base = helloWidgetUiBuilderDocument("lottie", pin, environment)
    val scaffold =
      base.nodes.values.first { it.componentId.startsWith("remote-m3/widget-container") }
    val lottie =
      UiBuilderNode(
        id = "spinner",
        componentId = LOTTIE_COMPONENT_ID,
        properties =
          JsonObject(
            buildMap {
              if (json.isNotEmpty()) put("json", literal("string", json))
              if (url.isNotEmpty()) put("url", literal("string", url))
            }
          ),
      )
    return base.copy(
      nodes =
        base.nodes +
          mapOf(
            lottie.id to lottie,
            scaffold.id to scaffold.copy(slots = scaffold.slots + ("content" to listOf(lottie.id))),
          )
    )
  }

  private fun literalNumber(type: String, value: Float): JsonObject =
    JsonObject(mapOf("type" to JsonPrimitive(type), "value" to JsonPrimitive(value)))

  private fun literal(type: String, value: String): JsonObject =
    JsonObject(mapOf("type" to JsonPrimitive(type), "value" to JsonPrimitive(value)))

  /** A screen is the Compose exporter's job, and saying so beats emitting something plausible. */
  @Test
  fun `a design that is not a widget is refused by name`() {
    val blank = blankUiBuilderDocument("screen", pin, environment)

    val refused =
      assertIs<WearWidgetCodeExporter.Result.Refused>(WearWidgetCodeExporter.export(blank))

    assertEquals(1, refused.reasons.size)
    assertTrue("layout/scaffold" in refused.reasons.single(), refused.reasons.single())
  }

  /**
   * The Code pane routes a widget design here rather than to the Compose gate.
   *
   * Without the branch a widget shows the gate's refusal — "no component record for remote-m3" —
   * which is true and useless: the design has generated code, just not that generator's.
   */
  @Test
  fun `the editor's code pane generates the widget, not a compose refusal`() {
    val catalog =
      ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser.parse(
        checkNotNull(javaClass.getResource("/m3-catalog-capabilities-v1.json")).readText()
      )
    val reducer = UiBuilderEditorReducer(catalog)

    val generated = reducer.generatedCode(helloWidgetUiBuilderDocument("hello", pin, environment))

    val source = assertIs<EditorGeneratedCode.Source>(generated).kotlin
    assertTrue("class HelloWidget : GlanceWearWidget()" in source, source)
  }

  private fun write(name: String, source: String) {
    val directory = Path.of("build", "generated-widget-source")
    Files.createDirectories(directory)
    Files.writeString(directory.resolve(name), source)
  }

  /**
   * A picture in the background slot is **inlined**, not named.
   *
   * What a widget rules out is a name the drawing side resolves: the launcher draws the document
   * out of the app's process and without its resources, so an `R.drawable` in the brush chain is
   * not there to resolve. The pixels travel inside the document — and source that has to stand
   * alone has nowhere to load them from, so it carries them (yschimke/compose-preview-server#523).
   * Before this, every widget with an image background refused outright and told the author to
   * write `WearWidgetBrush.image(bitmap)` by hand. The bundle lane below is the other way to answer
   * the same question.
   */
  @Test
  fun `an image background inlines its bytes and builds the brush`() {
    val document = imageBackgroundDocument()

    val source =
      assertIs<WearWidgetCodeExporter.Result.Emitted>(
          WearWidgetCodeExporter.export(
            document,
            assets = { key -> if (key == "cover") "QUJD" else null },
          )
        )
        .source

    assertTrue(".image(cover)" in source, source)
    assertTrue("private val cover: RemoteImageBitmap =" in source, source)
    assertTrue("decodeInlineBitmap(COVER_PNG)" in source, source)
    assertTrue("\"QUJD\"," in source, source)
    assertTrue("Base64.decode(encoded, Base64.NO_WRAP)" in source, source)
    assertTrue("import androidx.glance.wear.image" in source, source)
    assertTrue("import android.graphics.BitmapFactory" in source, source)
    // The bytes are declared above the decode that reads them: a top-level `val` is initialised in
    // declaration order, and the other way round does not compile.
    assertTrue(
      source.indexOf("private val COVER_PNG") < source.indexOf("private val cover:"),
      source,
    )
  }

  /** A key the registry cannot answer refuses by name rather than emitting a picture. */
  @Test
  fun `an image background with no bytes is refused by name`() {
    val refused =
      assertIs<WearWidgetCodeExporter.Result.Refused>(
        WearWidgetCodeExporter.export(imageBackgroundDocument())
      )

    assertTrue(refused.reasons.any { "bg-art" in it }, refused.reasons.toString())
  }

  /**
   * The same background, as a **bundle**: a file beside the source, and a path the source opens.
   *
   * The design is `docs/design/UI_BUILDER_EXPORT_BUNDLE.md`. What this pins is the shape of the
   * answer — the picture is opened where the `Context` is, the archive carries it under a path
   * scoped by design, and none of the base64 machinery the inlining lane needs appears at all.
   */
  @Test
  fun `a bundled image background opens the file the archive carries`() {
    val bundle =
      assertIs<WearWidgetCodeExporter.BundleResult.Emitted>(
          WearWidgetCodeExporter.exportBundle(
            imageBackgroundDocument(),
            assets = { key ->
              if (key == "cover") WidgetAssetContent("image/png", "QUJD") else null
            },
          )
        )
        .bundle

    val source = bundle.source
    write("CoverWidgetBundle.kt", source)
    assertTrue(".image(cover)" in source, source)
    assertTrue(
      "val cover = context.bundledBitmap(\"uibuilder/cover-widget/cover.png\")" in source,
      source,
    )
    assertTrue("private fun Context.bundledBitmap(path: String): RemoteImageBitmap =" in source)
    assertTrue("assets.open(path).use { BitmapFactory.decodeStream(it) }" in source, source)
    // The whole point: no bytes in the file, and none of what decoding them needs.
    assertTrue("QUJD" !in source, source)
    assertTrue("Base64" !in source, source)
    assertTrue("import android.graphics.BitmapFactory" in source, source)

    assertEquals(
      listOf(WidgetBundleFile("assets/uibuilder/cover-widget/cover.png", "image/png", "QUJD")),
      bundle.files,
    )
    assertEquals("WeatherWidget.kt", bundle.sourceFileName)
    assertTrue("assets/uibuilder/cover-widget/cover.png" in bundle.readme, bundle.readme)
  }

  /**
   * A **content** picture keeps its parameter and gains the design's artwork as its default.
   *
   * The parameter is what #508 settled and a bundle does not take it back — an application's album
   * art is not the design's. What changes is the default: nullable, resolved in `provideWidgetData`
   * where the `Context` is, so the generated `@Preview` draws the design instead of the
   * `ImageBitmap(1, 1)` hole the inlining lane has to leave.
   */
  @Test
  fun `a bundled content picture defaults its parameter to the design's artwork`() {
    val bundle =
      assertIs<WearWidgetCodeExporter.BundleResult.Emitted>(
          WearWidgetCodeExporter.exportBundle(
            contentImageDocument(),
            assets = { key ->
              if (key == "album-art") WidgetAssetContent("image/jpeg", "QUJD") else null
            },
          )
        )
        .bundle

    val source = bundle.source
    write("AlbumWidgetBundle.kt", source)
    assertTrue("private val albumArt: RemoteImageBitmap? = null," in source, source)
    assertTrue(
      "val albumArt = albumArt ?: context.bundledBitmap(\"uibuilder/album-widget/album-art.jpg\")" in
        source,
      source,
    )
    assertTrue("WeatherWidgetContent(albumArt = albumArt)" in source, source)
    assertEquals(
      listOf(WidgetBundleFile("assets/uibuilder/album-widget/album-art.jpg", "image/jpeg", "QUJD")),
      bundle.files,
    )
    assertTrue("albumArt" in bundle.readme, bundle.readme)
  }

  /**
   * A content picture the archive cannot carry keeps the blank default rather than failing.
   *
   * The asymmetry with a background is deliberate and is the lanes' shared rule: a background is
   * the design's own picture, so a key nothing can answer is a refusal; a content picture is the
   * application's, so the parameter stands on its own and the fallback is the hole.
   */
  @Test
  fun `a content picture with no bytes still exports as a parameter`() {
    val bundle =
      assertIs<WearWidgetCodeExporter.BundleResult.Emitted>(
          WearWidgetCodeExporter.exportBundle(contentImageDocument(), assets = { null })
        )
        .bundle

    assertTrue("val albumArt = albumArt ?: ImageBitmap(1, 1).rb" in bundle.source, bundle.source)
    assertEquals(emptyList(), bundle.files)
  }

  /** A background key the registry cannot answer refuses in this lane too, and by name. */
  @Test
  fun `a bundled image background with no bytes is refused by name`() {
    val refused =
      assertIs<WearWidgetCodeExporter.BundleResult.Refused>(
        WearWidgetCodeExporter.exportBundle(imageBackgroundDocument(), assets = { null })
      )

    assertTrue(refused.reasons.any { "bg-art" in it }, refused.reasons.toString())
  }

  /** The weather widget with an `asset/image` as its whole body. */
  private fun contentImageDocument(): UiBuilderDocument {
    val base = weatherWidgetUiBuilderDocument("album-widget", pin, environment)
    val scaffold = base.nodes.values.first { it.componentId.startsWith("remote-m3/") }
    val art =
      UiBuilderNode(
        id = "art",
        componentId = "asset/image",
        properties = JsonObject(mapOf("assetKey" to literal("string", "album-art"))),
      )
    return base.copy(
      nodes =
        base.nodes +
          mapOf(
            art.id to art,
            scaffold.id to scaffold.copy(slots = scaffold.slots + ("content" to listOf(art.id))),
          )
    )
  }

  /** The weather widget with an `asset/image` in its background slot instead of a colour. */
  private fun imageBackgroundDocument(): UiBuilderDocument {
    val base = weatherWidgetUiBuilderDocument("cover-widget", pin, environment)
    val scaffold = base.nodes.values.first { it.componentId.startsWith("remote-m3/") }
    val art =
      UiBuilderNode(
        id = "bg-art",
        componentId = "asset/image",
        properties = JsonObject(mapOf("assetKey" to literal("string", "cover"))),
      )
    return base.copy(
      nodes =
        base.nodes +
          mapOf(
            art.id to art,
            scaffold.id to scaffold.copy(slots = scaffold.slots + ("background" to listOf(art.id))),
          )
    )
  }
}
