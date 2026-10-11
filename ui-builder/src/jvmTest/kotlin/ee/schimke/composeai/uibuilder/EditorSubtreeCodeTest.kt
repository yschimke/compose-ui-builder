package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.EditorGeneratedCode
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.editor.UiBuilderMenuEntry
import ee.schimke.composeai.uibuilder.editor.copyCodeSentence
import ee.schimke.composeai.uibuilder.editor.editorSelectionMenuEntries
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import ee.schimke.composeai.uibuilder.export.blankUiBuilderDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * "Copy as Compose code" on one layer: the code pane's export run on the design re-rooted at the
 * layer, so what it copies is the part of the whole design's Kotlin that the layer wrote — not a
 * second generator's idea of it.
 */
class EditorSubtreeCodeTest {

  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)

  private val jetcaster =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  private fun text(id: String, value: String) =
    UiBuilderNode(
      id = id,
      componentId = "m3/text",
      properties =
        JsonObject(
          mapOf(
            "text" to
              JsonObject(mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive(value)))
          )
        ),
    )

  /** A new design whose content box holds a headline and a card of two lines. */
  private fun design(): UiBuilderDocument {
    val blank =
      blankUiBuilderDocument(
        designId = "subtree-code",
        catalogPin = jetcaster.catalogPin,
        environment = jetcaster.environment,
      )
    val box = blank.nodes.getValue("screen-content")
    return blank.copy(
      nodes =
        blank.nodes +
          mapOf(
            box.id to box.copy(slots = mapOf("children" to listOf("headline", "episode"))),
            "headline" to text("headline", "Discover"),
            "episode" to
              UiBuilderNode(
                id = "episode",
                componentId = "m3/card",
                slots = mapOf("content" to listOf("episode-copy")),
              ),
            "episode-copy" to
              UiBuilderNode(
                id = "episode-copy",
                componentId = "layout/column",
                slots = mapOf("children" to listOf("episode-title", "episode-summary")),
              ),
            "episode-title" to text("episode-title", "Episode 140"),
            "episode-summary" to text("episode-summary", "A summary"),
          )
    )
  }

  /** The body of the screen function, dedented to column 0. */
  private fun screenBody(kotlin: String): List<String> {
    val lines = kotlin.lines()
    val start = lines.indexOfFirst { it.startsWith("fun ") } + 1
    val end = (start until lines.size).first { lines[it] == "}" }
    return lines.subList(start, end).map { it.removePrefix("    ") }
  }

  /**
   * Whether [part] appears in [whole] as a contiguous run of lines at some single extra indent: the
   * layer's call where the full screen writes it.
   */
  private fun containsIndented(whole: String, part: List<String>): Boolean {
    val lines = whole.lines()
    return lines.indices.any { at ->
      val indent = lines[at].removeSuffix(part.first())
      indent != lines[at] &&
        indent.isBlank() &&
        at + part.size <= lines.size &&
        part.indices.all { i ->
          val line = lines[at + i]
          if (part[i].isEmpty()) line.isBlank() else line == indent + part[i]
        }
    }
  }

  private fun source(code: EditorGeneratedCode?): String =
    assertIs<EditorGeneratedCode.Source>(code, "$code").kotlin

  @Test
  fun `a layer copies exactly what the whole design's export writes for it`() {
    val document = design()
    val full = source(reducer.generatedCode(document))
    val layer = source(reducer.subtreeCode(document, "episode"))

    val body = screenBody(layer)
    assertEquals("Card {", body.first(), layer)
    assertTrue(containsIndented(full, body), "the layer's call is not in the full export:\n$full")
    // Only the layer: its sibling and the scaffold around it are the design's, not the layer's.
    assertFalse("Discover" in layer, layer)
    assertFalse("Scaffold" in layer, layer)
    // A whole file still, so it pastes: the export's own imports, trimmed to what the layer calls.
    assertTrue("import androidx.compose.material3.Card" in layer, layer)
    assertFalse("import androidx.compose.material3.Scaffold" in layer, layer)
  }

  @Test
  fun `the root layer copies the whole design's code`() {
    val document = design()
    assertEquals(
      reducer.generatedCode(document),
      reducer.subtreeCode(document, document.roots.single()),
    )
  }

  @Test
  fun `a layer placing a local component brings the component's function with it`() {
    val made =
      reducer
        .reduce(
          reducer.initial(design(), "episode-copy"),
          UiBuilderEditorEvent.MakeComponent("EpisodeCopy"),
        )
        .document
    val full = source(reducer.generatedCode(made))
    val layer = source(reducer.subtreeCode(made, "episode"))
    assertTrue(containsIndented(full, screenBody(layer)), layer)
    val function = layer.substring(layer.indexOf("@Composable\nprivate fun EpisodeCopy"))
    assertTrue(full.endsWith(function), "the component's function differs:\n$layer")

    // A layer that places no component is not handed one it never calls.
    val headline = source(reducer.subtreeCode(made, "headline"))
    assertFalse("EpisodeCopy" in headline, headline)
    assertTrue("""Text(text = "Discover")""" in headline, headline)
  }

  @Test
  fun `an unexportable layer is refused in the code pane's words, and nothing is copied`() {
    val refused =
      assertIs<EditorGeneratedCode.Refused>(reducer.subtreeCode(jetcaster, "category-row"))
    // Only the layer's own problems: the rest of Jetcaster refuses for reasons this layer has none
    // of.
    assertTrue(refused.reasons.all { "category-row" in it || "chip-" in it }, "$refused")
    assertNull(reducer.subtreeCode(jetcaster, "no-such-node"))

    var copied: String? = null
    val sentence = runBlocking { copyCodeSentence(refused, "Categories") { copied = it } }
    assertNull(copied)
    assertEquals(
      "No Compose source · the export would refuse Categories: ${refused.reasons.first()} " +
        "(and ${refused.reasons.size - 1} more)",
      sentence,
    )
  }

  @Test
  fun `a copy writes the source and says so, and a failed write is a sentence too`() {
    var copied: String? = null
    val code = EditorGeneratedCode.Source("fun Screen() {}\n")
    assertEquals(
      "Copied Episode as Compose code",
      runBlocking { copyCodeSentence(code, "Episode") { copied = it } },
    )
    assertEquals(code.kotlin, copied)
    assertEquals(
      "Could not copy: denied",
      runBlocking { copyCodeSentence(code, "Episode") { error("denied") } },
    )
  }

  @Test
  fun `the selection menu offers the copy beside Copy link only when given one`() {
    fun labels(onCopyCode: (() -> Unit)?) =
      editorSelectionMenuEntries(
          modifierToggles = emptyList(),
          onToggleModifier = {},
          canDuplicate = true,
          canCopy = true,
          canCut = true,
          canPaste = true,
          canDelete = true,
          wrapCandidates = emptyList(),
          canUnwrap = false,
          onOpenProperties = null,
          onCopyLink = {},
          onCopyCode = onCopyCode,
          onDismiss = {},
          dispatch = {},
        )
        .filterIsInstance<UiBuilderMenuEntry.Action>()
        .map { it.label }

    val offered = labels {}
    assertEquals(offered.indexOf("Copy link") + 1, offered.indexOf("Copy as Compose code"))
    assertFalse("Copy as Compose code" in labels(null))
  }

  @Test
  fun `a weighted layer leaves its weight to the parent it is pasted into`() {
    // Every message row's body takes the row's remaining width with `Modifier.weight(1f)`, which
    // only a `RowScope` supplies. Copied out of the row, it has none.
    val gmail = Json {
      ignoreUnknownKeys = true
    }
      .decodeFromString<UiBuilderDocument>(
        java.io
          .File(
            System.getProperty("uiBuilderProjectDir"),
            "../site/designs/google-gmail-tablet.uid",
          )
          .readText()
      )
    val weighted = gmail.nodes.getValue("msg-figma-body")
    assertTrue("weight" in weighted.modifiers.toString(), "${weighted.modifiers}")
    val full = source(reducer.generatedCode(gmail))
    val body = screenBody(source(reducer.subtreeCode(gmail, weighted.id)))

    assertFalse(".weight(" in body.first(), body.first())
    // Everything under the layer's own call is the full export's, line for line.
    assertTrue(containsIndented(full, body.drop(1)), body.joinToString("\n"))
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
