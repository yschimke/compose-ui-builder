package ee.schimke.composeai.uibuilder

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a widget body may be written in: every modifier `remote-m3` advertises, and the picture.
 *
 * The gap this closes is not a missing case, it is two lists nobody compared. The catalog offered
 * 28 modifiers on a widget node and the canvas drew all 28; `RemoteContentEmitter` wrote three, and
 * the other 25 — `size`, `background`, `weight`, `align`, which is how anyone builds a coloured
 * button in a corner beside a column of text that truncates — refused *after* the design was drawn
 * (yschimke/compose-preview-server#508). [REMOTE_CONTENT_MODIFIERS] is now the one list, the
 * catalog is narrowed to it in `:ui-builder-runtime`, and this walks every name in it through a
 * real export so the set cannot claim something the emitter refuses.
 */
class RemoteContentVocabularyTest {
  private val pin = JsonObject(emptyMap())
  private val environment = JsonObject(emptyMap())

  @Test
  fun `every modifier the vocabulary claims is written rather than refused`() {
    REMOTE_CONTENT_MODIFIERS.forEach { type ->
      val result = WearWidgetCodeExporter.export(widgetWith(modifier(type)))
      assertIs<WearWidgetCodeExporter.Result.Emitted>(
        result,
        "$type: ${(result as? WearWidgetCodeExporter.Result.Refused)?.reasons}",
      )
    }
  }

  /**
   * The calls, spot-checked against the upstream signatures they are written for.
   *
   * A refusal-free export only proves the emitter had a case; these prove the case writes the call
   * `remote-creation-compose` 1.0.0-alpha18 actually publishes — `size(width, height)` in dp,
   * `background` taking a colour with the shape it does not have written as the `clip` before it,
   * and a weight that is a member of the column's scope rather than of `RemoteModifier`.
   */
  @Test
  fun `the calls match the RemoteModifier surface`() {
    val sized = emitted(widgetWith(modifier("size")))
    assertTrue("size(24.rdp, 24.rdp)" in sized, sized)
    assertTrue("import androidx.compose.remote.creation.compose.modifier.size" in sized, sized)

    // A shape is the `clip` before it: `RemoteModifier.background` takes a colour and no shape.
    val painted = emitted(widgetWith(modifier("background")))
    assertTrue("clip(RemoteRoundedCornerShape(12.rdp))" in painted, painted)
    assertTrue("background(Color(0xFF1DB954).rc)" in painted, painted)

    val weighted = emitted(widgetWith(modifier("weight")))
    assertTrue("RemoteModifier.weight(1.rf)" in weighted, weighted)

    val aligned = emitted(widgetWith(modifier("align")))
    assertTrue("contentAlignment = RemoteAlignment.BottomEnd" in aligned, aligned)
    // Consumed by the box above it, so it writes no call of its own.
    assertTrue(".align(" !in aligned, aligned)

    val scrolled = emitted(widgetWith(modifier("verticalScroll")))
    assertTrue("verticalScroll(rememberRemoteScrollState())" in scrolled, scrolled)
  }

  /**
   * The four with no counterpart, refused the way the good refusals in this generator read.
   *
   * Each names the node, says why, and hands over the route that does work. That is the shape the
   * `asset/image` catch-all did not have, and it is the floor for anything this cannot write.
   */
  @Test
  fun `a modifier with no counterpart is refused by name with the way round it`() {
    mapOf(
        "matchParentSize" to "fillMaxSize",
        "aspectRatio" to "size",
        "shadow" to "border",
        "testTag" to "content description",
      )
      .forEach { (type, route) ->
        val refused =
          assertIs<WearWidgetCodeExporter.Result.Refused>(
            WearWidgetCodeExporter.export(widgetWith(modifier(type)))
          )
        val reason = refused.reasons.single()
        assertTrue("`$type`" in reason, reason)
        assertTrue("frame" in reason || "stack" in reason, reason)
        assertTrue(route in reason, reason)
      }
  }

  /**
   * `weight` outside a row or column does not compile upstream, and is named rather than dropped.
   */
  @Test
  fun `a weight outside a row or column is refused`() {
    val refused =
      assertIs<WearWidgetCodeExporter.Result.Refused>(
        WearWidgetCodeExporter.export(widgetWith(modifier("weight"), on = "frame"))
      )

    assertTrue("weight" in refused.reasons.single(), refused.reasons.single())
    assertTrue("content slot" in refused.reasons.single(), refused.reasons.single())
  }

  /**
   * A weight of zero or less is refused, not emitted.
   *
   * The editor's numeric field stores whatever is typed, and `UiBuilderRenderer` drops a
   * non-positive weight (`takeIf { it > 0f }`) because Compose throws on one — so the canvas draws
   * the child unweighted and looks fine. Emitting it anyway produced `weight(0f)`, which fails
   * while the Remote Compose document is being built, long after this export reported success. The
   * two readers now agree that it is not a weight, and this one says so.
   */
  @Test
  fun `a weight of zero or less is refused rather than generating a document that fails to build`() {
    listOf(0, -1).forEach { value ->
      val refused =
        assertIs<WearWidgetCodeExporter.Result.Refused>(
          WearWidgetCodeExporter.export(
            widgetWith(
              JsonObject(mapOf("type" to JsonPrimitive("weight"), "weight" to JsonPrimitive(value)))
            )
          )
        )
      val reason = refused.reasons.single()
      assertTrue("`weight`" in reason, reason)
      assertTrue("greater than zero" in reason, reason)
    }
  }

  /**
   * A picture in the content slot: the call, and the bitmap the application supplies.
   *
   * The key stays in the design and the bytes stay out of the source, which is the whole shape of
   * the answer — album art is data an application has, not a constant a generator can bake in. So
   * the content function takes the bitmap, the widget class takes it, and the `@Preview` still
   * compiles because the class defaults it to a blank one.
   */
  @Test
  fun `an image in the content slot is written as a RemoteImage the application feeds`() {
    val source = emitted(widgetWith(image("album-art", description = "Album art")))

    assertTrue("RemoteImage(" in source, source)
    assertTrue("remoteBitmap = albumArt" in source, source)
    assertTrue("contentDescription = \"Album art\".rs" in source, source)
    assertTrue("contentScale = ContentScale.Crop" in source, source)
    assertTrue("fun HelloWidgetContent(albumArt: RemoteImageBitmap)" in source, source)
    assertTrue("private val albumArt: RemoteImageBitmap = ImageBitmap(1, 1).rb," in source, source)
    assertTrue("HelloWidgetContent(albumArt = albumArt)" in source, source)
    assertTrue(
      "import androidx.compose.remote.creation.compose.layout.RemoteImage" in source,
      source,
    )
    assertTrue("import androidx.compose.ui.layout.ContentScale" in source, source)
  }

  /** Two nodes drawing one asset are one picture, so they are one parameter. */
  @Test
  fun `one asset drawn twice is one parameter`() {
    val source = emitted(widgetWith(image("album-art"), image("album-art"), image("logo")))

    assertTrue(
      "fun HelloWidgetContent(albumArt: RemoteImageBitmap, logo: RemoteImageBitmap)" in source,
      source,
    )
  }

  /** An image with no key is a picture nothing can resolve, and it is said rather than written. */
  @Test
  fun `an image naming no asset is refused by name`() {
    val refused =
      assertIs<WearWidgetCodeExporter.Result.Refused>(
        WearWidgetCodeExporter.export(widgetWith(image("")))
      )

    assertEquals(1, refused.reasons.size)
    assertTrue("names no asset" in refused.reasons.single(), refused.reasons.single())
  }

  /**
   * The two `remote-m3` components a widget body has no counterpart for, refused with the reason.
   *
   * Both used to reach the catch-all `else`, which is what the issue calls out: an author who put a
   * surface in a widget was told the component "has no Remote Compose counterpart this generator
   * can write" — the same sentence a typo in a component id gets.
   */
  @Test
  fun `a surface and a content-slot gradient are refused with what to reach for instead`() {
    val surface =
      assertIs<WearWidgetCodeExporter.Result.Refused>(
        WearWidgetCodeExporter.export(widgetWith(node("card", "m3/surface")))
      )
    assertTrue("`layout/box`" in surface.reasons.single(), surface.reasons.single())
    assertTrue("background" in surface.reasons.single(), surface.reasons.single())

    val gradient =
      assertIs<WearWidgetCodeExporter.Result.Refused>(
        WearWidgetCodeExporter.export(widgetWith(node("sky", "shape/linear-gradient")))
      )
    assertTrue("background slot" in gradient.reasons.single(), gradient.reasons.single())
  }

  /**
   * The widget from the issue, end to end: album art, a weighted text column, a round play button.
   *
   * Every part of it used to refuse — the picture through the catch-all, and `size`, `background`,
   * `weight` and `align` through the modifier `else`. It is here as one artifact because that is
   * how the gap was found: not by reading the emitter, but by building an ordinary widget and being
   * told four times that ordinary was unexpressible.
   */
  @Test
  fun `the widget the issue was filed about exports`() {
    val art =
      image("album-art", description = "Album art")
        .withModifiers(modifier("size"), modifier("clip"))
    val title = node("title", "m3/text", text = "Skinny Love")
    val artist = node("artist", "m3/text", text = "Bon Iver")
    val stack =
      node("stack", "layout/column", children = listOf(title.id, artist.id))
        .withModifiers(modifier("weight"))
    val button =
      node("play-button", "layout/box").withModifiers(modifier("size"), modifier("background"))
    val frame =
      node("frame", "layout/row", children = listOf(art.id, stack.id, button.id))
        .withModifiers(modifier("fillMaxSize"))

    val source =
      emitted(
        widget(
          listOf(art, title, artist, stack, button, frame).associateBy { it.id },
          root = "frame",
        )
      )

    Files.createDirectories(GENERATED)
    Files.writeString(GENERATED.resolve("NowPlayingWidget.kt"), source)
    assertTrue("RemoteImage(" in source, source)
    assertTrue("RemoteModifier.size(24.rdp, 24.rdp)" in source, source)
    assertTrue("RemoteModifier.weight(1.rf)" in source, source)
    assertTrue("background(Color(0xFF1DB954).rc)" in source, source)
    // The budget the generator keeps so a pasted file survives ktfmt unchanged. A chain of three
    // modifiers is past it on one line, which is why the emitter breaks one at its dots.
    assertEquals(emptyList(), source.lines().filter { it.length > 100 })
  }

  private val GENERATED = Path.of("build", "generated-widget-source")

  private fun UiBuilderNode.withModifiers(vararg values: JsonObject): UiBuilderNode =
    copy(modifiers = JsonArray(values.toList()))

  private fun emitted(document: UiBuilderDocument): String {
    val result = WearWidgetCodeExporter.export(document)
    return assertIs<WearWidgetCodeExporter.Result.Emitted>(
        result,
        (result as? WearWidgetCodeExporter.Result.Refused)?.reasons?.toString(),
      )
      .source
  }

  /**
   * A widget whose content is `frame` (a box) → `stack` (a column) → `line` (a row) → `label`.
   *
   * Three nested containers because the scoped modifiers each need a different one above them: an
   * `align` is a box's, an `alignHorizontal` and a `weight` a column's, an `alignVertical` a row's.
   * [on] places the modifier under test on the node whose parent makes it legal.
   */
  private fun widgetWith(vararg leaves: UiBuilderNode): UiBuilderDocument = widget(leaves.toList())

  private fun widgetWith(
    modifier: JsonObject,
    on: String = scopeFor(modifier["type"]?.let { (it as JsonPrimitive).content }.orEmpty()),
  ): UiBuilderDocument =
    widget(listOf(node("label", "m3/text", text = "Track")), modifierOn = on to modifier)

  private fun scopeFor(type: String): String =
    when (type) {
      "align" -> "stack"
      "alignHorizontal",
      "weight" -> "line"
      "alignVertical" -> "label"
      else -> "frame"
    }

  /** A widget whose content slot holds [root], with [nodes] as the subtree under it. */
  private fun widget(nodes: Map<String, UiBuilderNode>, root: String): UiBuilderDocument {
    val base = helloWidgetUiBuilderDocument("widget", pin, environment)
    val scaffold =
      base.nodes.values.first { it.componentId.startsWith("remote-m3/widget-container") }
    // The template's own `hello-content` box and text are replaced wholesale: this design is the
    // subtree above, and leaving the sample's nodes in would export both.
    return base.copy(
      nodes =
        mapOf(scaffold.id to scaffold.copy(slots = scaffold.slots + ("content" to listOf(root)))) +
          nodes
    )
  }

  private fun widget(
    leaves: List<UiBuilderNode>,
    modifierOn: Pair<String, JsonObject>? = null,
  ): UiBuilderDocument {
    val label = leaves.ifEmpty { listOf(node("label", "m3/text", text = "Track")) }
    val line = node("line", "layout/row", children = label.map { it.id })
    val stack = node("stack", "layout/column", children = listOf(line.id))
    val frame = node("frame", "layout/box", children = listOf(stack.id))
    return widget(
      (label + listOf(line, stack, frame))
        .map { candidate ->
          if (modifierOn?.first == candidate.id)
            candidate.copy(modifiers = JsonArray(listOf(modifierOn.second)))
          else candidate
        }
        .associateBy { it.id },
      root = "frame",
    )
  }

  private fun node(
    id: String,
    componentId: String,
    children: List<String> = emptyList(),
    text: String? = null,
  ): UiBuilderNode =
    UiBuilderNode(
      id = id,
      componentId = componentId,
      properties =
        JsonObject(
          buildMap {
            if (text != null) put("text", literal("string", text))
            if (componentId == "m3/surface") put("containerColor", literal("color", "#FF1DB954"))
          }
        ),
      slots = if (children.isEmpty()) emptyMap() else mapOf("children" to children),
    )

  private fun image(
    assetKey: String,
    description: String? = null,
    id: String = "art-${assetKey.ifEmpty { "none" }}-${imageIds++}",
  ): UiBuilderNode =
    UiBuilderNode(
      id = id,
      componentId = "asset/image",
      properties =
        JsonObject(
          buildMap {
            put("assetKey", literal("assetKey", assetKey))
            if (description != null) put("contentDescription", literal("string", description))
          }
        ),
    )

  private var imageIds = 0

  /** A plausible value for each modifier, in the shape the inspector writes it. */
  private fun modifier(type: String): JsonObject =
    JsonObject(
      buildMap {
        put("type", JsonPrimitive(type))
        when (type) {
          "size" -> {
            put("widthDp", JsonPrimitive(24))
            put("heightDp", JsonPrimitive(24))
          }
          "width" -> put("widthDp", JsonPrimitive(120))
          "height" -> put("heightDp", JsonPrimitive(40))
          "widthIn",
          "heightIn" -> {
            put("minDp", JsonPrimitive(8))
            put("maxDp", JsonPrimitive(120))
          }
          "padding" -> {
            put("startDp", JsonPrimitive(8))
            put("topDp", JsonPrimitive(4))
            put("endDp", JsonPrimitive(8))
            put("bottomDp", JsonPrimitive(4))
          }
          "offset" -> {
            put("xDp", JsonPrimitive(4))
            put("yDp", JsonPrimitive(-2))
          }
          "background",
          "border" -> {
            put("color", literal("color", "#FF1DB954"))
            put("shape", JsonPrimitive("medium"))
            put("widthDp", JsonPrimitive(1))
          }
          "clip" -> put("shape", JsonPrimitive("small"))
          "alpha" -> put("alpha", JsonPrimitive(0.5))
          "rotate" -> put("degrees", JsonPrimitive(90))
          "scale" -> {
            put("scaleX", JsonPrimitive(2))
            put("scaleY", JsonPrimitive(2))
          }
          "zIndex" -> put("zIndex", JsonPrimitive(1))
          "aspectRatio" -> put("ratio", JsonPrimitive(1.5))
          "testTag" -> put("tag", JsonPrimitive("play"))
          "shadow" -> put("elevationDp", JsonPrimitive(4))
          "weight" -> put("weight", JsonPrimitive(1))
          "align" -> put("alignment", JsonPrimitive("bottomEnd"))
          "alignHorizontal" -> put("alignment", JsonPrimitive("centerHorizontally"))
          "alignVertical" -> put("alignment", JsonPrimitive("centerVertically"))
        }
      }
    )

  private fun literal(type: String, value: String): JsonObject =
    JsonObject(mapOf("type" to JsonPrimitive(type), "value" to JsonPrimitive(value)))
}
