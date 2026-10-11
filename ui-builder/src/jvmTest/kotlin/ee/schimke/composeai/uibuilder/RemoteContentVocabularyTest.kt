package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.export.REMOTE_CONTENT_MODIFIERS
import ee.schimke.composeai.uibuilder.export.UiBuilderBuildFeatures
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.WearWidgetCodeExporter
import ee.schimke.composeai.uibuilder.export.helloWidgetUiBuilderDocument
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * What a widget body may be written in: every modifier `remote-m3` advertises, and the picture.
 * [REMOTE_CONTENT_MODIFIERS] is the one list (the catalog is narrowed to it in
 * `:ui-builder-runtime`), and this walks every name through a real export so it can't advertise
 * what the emitter refuses.
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
   * The calls, spot-checked against the `remote-creation-compose` 1.0.0-alpha18 signatures:
   * `size(width, height)` in dp, `background` with its shape as a preceding `clip`, and `weight` as
   * a column-scope member.
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
   * The four with no counterpart are refused naming the node, the reason, and the route that works.
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
   * A weight of zero or less is refused: the renderer drops it (Compose throws), but an emitted
   * `weight(0f)` fails while building the Remote Compose document.
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
   * A picture in the content slot: the key stays in the design and the bitmap comes from the
   * application, through the content function and widget class; the `@Preview` compiles with a
   * blank default.
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
   * A gradient is a widget-frame brush, not body content; a malformed stored document that puts it
   * in the body is diagnosed, not silently dropped.
   */
  @Test
  fun `a content-slot gradient is refused with what to reach for instead`() {
    val gradient =
      assertIs<WearWidgetCodeExporter.Result.Refused>(
        WearWidgetCodeExporter.export(widgetWith(node("sky", "shape/linear-gradient")))
      )
    assertTrue("background slot" in gradient.reasons.single(), gradient.reasons.single())
  }

  /** An ordinary widget end to end: album art, a weighted text column, a round play button. */
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

  /**
   * `RemoteFitBox` and a shared element, written with their own calls. A fit box writes only the
   * requested edge (it centres by default); a shared element in a "Show by state" box
   * (`RemoteStateLayout`) uses the `animationSpec(Int, Boolean)` overload.
   */
  @Test
  fun `a fit box and a shared element across a state switch are written`() {
    fun literal(kind: String, value: Any) =
      JsonObject(mapOf("type" to JsonPrimitive(kind), "value" to JsonPrimitive(value.toString())))
    val wide = node("wide", "m3/text", text = "A long label")
    val narrow = node("narrow", "m3/text", text = "Short")
    val fit =
      node("fit", "layout/fit-box", children = listOf(wide.id, narrow.id)).let {
        it.copy(properties = JsonObject(mapOf("verticalArrangement" to literal("enum", "top"))))
      }
    val collapsed =
      node("collapsed", "m3/text", text = "Collapsed").withModifiers(modifier("sharedElement"))
    val expanded =
      node("expanded", "m3/text", text = "Expanded").withModifiers(modifier("sharedElement"))
    // "Show by state" is Remote Compose authoring, which a default build withholds; the flagged
    // build (`-PuiBuilderRemoteCompose=true`, a CI job of its own) writes it as a state layout.
    val stateLayout = UiBuilderBuildFeatures.remoteCompose
    val selection =
      Json.parseToJsonElement(
          """{"type":"object","fields":{"selector":{"type":"state","variable":"expanded"},""" +
            """"cases":{"type":"object","fields":{"collapsed":{"type":"bool","value":false},""" +
            """"expanded":{"type":"bool","value":true}}}}}"""
        )
        .jsonObject
    val mode =
      node("mode", "layout/box", children = listOf(collapsed.id, expanded.id)).let {
        if (stateLayout) it.copy(properties = JsonObject(mapOf("showByState" to selection))) else it
      }
    val frame = node("frame", "layout/column", children = listOf(fit.id, mode.id))
    val document =
      widget(
          listOf(wide, narrow, fit, collapsed, expanded, mode, frame).associateBy { it.id },
          "frame",
        )
        .let {
          it.copy(
            stateVariables =
              Json.parseToJsonElement("""{"expanded":{"initialValue":true,"valueType":"bool"}}""")
                .jsonObject
          )
        }

    val source = emitted(document)

    Files.createDirectories(GENERATED)
    Files.writeString(GENERATED.resolve("FitBoxStateWidget.kt"), source)
    assertTrue("RemoteFitBox(verticalArrangement = RemoteArrangement.Top)" in source, source)
    assertTrue(
      "import androidx.compose.remote.creation.compose.layout.RemoteFitBox" in source,
      source,
    )
    assertEquals(stateLayout, "RemoteStateLayout(" in source, source)
    assertEquals(
      2,
      Regex("animationSpec\\(3, true\\)").findAll(source).count(),
      source,
    )
    assertTrue(
      "import androidx.compose.remote.creation.compose.modifier.animationSpec" in source,
      source,
    )
    assertEquals(emptyList(), source.lines().filter { it.length > 100 })
  }

  /**
   * A flow row and the collapsibles are written as their own calls with their scope members. None
   * is in the Glance Wear widget profile, so each call carries a comment explaining a native-render
   * failure.
   */
  @Test
  fun `a flow row and the collapsibles are written, marked as outside the widget profile`() {
    fun literal(kind: String, value: Any) =
      JsonObject(
        mapOf(
          "type" to JsonPrimitive(kind),
          "value" to if (value is Number) JsonPrimitive(value) else JsonPrimitive("$value"),
        )
      )
    fun UiBuilderNode.with(vararg values: Pair<String, JsonObject>) =
      copy(properties = JsonObject(properties + values))
    val chips = (1..3).map { node("chip$it", "m3/text", text = "Chip $it") }
    val flow =
      node("flow", "layout/flow-row", children = chips.map { it.id })
        .with(
          "horizontalSpacingDp" to literal("number", 4),
          "verticalSpacingDp" to literal("number", 2),
          "maxItemsInEachRow" to literal("number", 2),
        )
    val headline = node("headline", "m3/text", text = "Headline")
    val supporting =
      node("supporting", "m3/text", text = "Supporting")
        .withModifiers(modifier("collapsiblePriority"))
    val column =
      node("column", "layout/collapsible-column", children = listOf(headline.id, supporting.id))
        .with("verticalArrangement" to literal("enum", "spaceBetween"))
    val grow = node("grow", "m3/text", text = "Grow").withModifiers(modifier("weight"))
    val row = node("row", "layout/collapsible-row", children = listOf(grow.id))
    val frame = node("frame", "layout/column", children = listOf(flow.id, column.id, row.id))

    val source =
      emitted(
        widget(
          (chips + listOf(flow, headline, supporting, column, grow, row, frame)).associateBy {
            it.id
          },
          root = "frame",
        )
      )

    Files.createDirectories(GENERATED)
    Files.writeString(GENERATED.resolve("OutsideWidgetProfileWidget.kt"), source)
    assertTrue("RemoteFlowRow(" in source, source)
    assertTrue("horizontalArrangement = RemoteArrangement.spacedBy(4.rdp)" in source, source)
    assertTrue("verticalArrangement = RemoteArrangement.spacedBy(2.rdp)" in source, source)
    assertTrue("maxItemsInEachRow = 2" in source, source)
    assertTrue("RemoteCollapsibleColumn(" in source, source)
    assertTrue("verticalArrangement = RemoteArrangement.SpaceBetween" in source, source)
    assertTrue("RemoteModifier.collapsiblePriority(2f)" in source, source)
    assertTrue("RemoteCollapsibleRow(" in source, source)
    assertTrue("RemoteModifier.weight(1.rf)" in source, source)
    listOf("LAYOUT_FLOW", "LAYOUT_COLLAPSIBLE_COLUMN", "LAYOUT_COLLAPSIBLE_ROW").forEach {
      assertTrue("// $it is outside the Glance Wear widget profile." in source, source)
    }
    listOf("RemoteFlowRow", "RemoteCollapsibleColumn", "RemoteCollapsibleRow").forEach {
      assertTrue("import androidx.compose.remote.creation.compose.layout.$it" in source, it)
    }
    // Scope members resolve through the lambda's receiver, so nothing imports them.
    assertTrue("modifier.collapsiblePriority" !in source, source)
    assertEquals(emptyList(), source.lines().filter { it.length > 100 })
  }

  /** A priority means nothing outside a collapsible, and does not compile there upstream. */
  @Test
  fun `a collapsible priority outside a collapsible column or row is refused`() {
    val refused =
      assertIs<WearWidgetCodeExporter.Result.Refused>(
        WearWidgetCodeExporter.export(widgetWith(modifier("collapsiblePriority"), on = "frame"))
      )
    assertTrue("layout/collapsible-column" in refused.reasons.single(), refused.reasons.single())
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
   * A widget whose content is `frame` (a box) → `stack` (a column) → `line` (a row) → `label`, so
   * each scoped modifier has a legal parent; [on] places the modifier under test accordingly.
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
      "alignVertical",
      "collapsiblePriority" -> "label"
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
    // A priority is a member of the collapsible scopes only, so the row it is tested in is one.
    val collapsible = modifierOn?.second?.get("type") == JsonPrimitive("collapsiblePriority")
    val line =
      node(
        "line",
        if (collapsible) "layout/collapsible-row" else "layout/row",
        children = label.map { it.id },
      )
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
      properties = JsonObject(buildMap { if (text != null) put("text", literal("string", text)) }),
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
          "collapsiblePriority" -> put("priority", JsonPrimitive(2))
          "sharedElement" -> put("key", JsonPrimitive(3))
          // One call from the generated vocabulary, with its arguments as values.
          "remoteCall" -> {
            put("name", JsonPrimitive("border"))
            put(
              "args",
              Json.parseToJsonElement(
                """{"width":{"type":"float","value":2},"color":{"type":"color","value":"#FF1DB954"}}"""
              ),
            )
          }
          "align" -> put("alignment", JsonPrimitive("bottomEnd"))
          "alignHorizontal" -> put("alignment", JsonPrimitive("centerHorizontally"))
          "alignVertical" -> put("alignment", JsonPrimitive("centerVertically"))
        }
      }
    )

  private fun literal(type: String, value: String): JsonObject =
    JsonObject(mapOf("type" to JsonPrimitive(type), "value" to JsonPrimitive(value)))
}
