package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.DTCG_EXTENSION
import ee.schimke.composeai.uibuilder.editor.DesignTokenValue
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorState
import ee.schimke.composeai.uibuilder.editor.exportDesignTokens
import ee.schimke.composeai.uibuilder.editor.importDesignTokens
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.WearScreenCodeExporter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Design tokens out as W3C DTCG and in from DTCG or a Material Theme Builder export.
 *
 * The point of a standard format is that what goes out comes back: a design's tokens exported and
 * imported into a fresh design read the same, and a file another tool wrote applies whatever this
 * design system declares and says plainly what it did not.
 */
class DesignTokenInterchangeTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/wear-m3-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)

  private val screen =
    UiBuilderNewDesignSeed.document(
        designId = "wear-token-exchange",
        catalogSystemId = "wear-m3",
        templateId = "wear-screen",
        catalogRevision = "wear-screen-scaffold-v1",
        nativeRuntimeId = "candidate",
        fixture =
          Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject,
      )
      .copy(
        roots = listOf("screen"),
        nodes =
          mapOf(
            "screen" to
              UiBuilderNode(
                id = "screen",
                componentId = WearScreenCodeExporter.SCAFFOLD,
                slots = mapOf("content" to listOf("list")),
              ),
            "list" to
              UiBuilderNode(
                id = "list",
                componentId = "wear-m3/transforming-lazy-column",
                slots = mapOf("items" to listOf("cell-0")),
              ),
          ) + button("cell-0"),
      )

  private fun UiBuilderEditorState.on(vararg events: UiBuilderEditorEvent): UiBuilderEditorState =
    events.fold(this, reducer::reduce)

  private fun values(state: UiBuilderEditorState): Map<String, DesignTokenValue> =
    reducer.designTokenRows(state).associate { it.token.id to it.value }

  private val tokens = catalog.designTokens

  private val styled =
    reducer
      .initial(screen)
      .on(
        UiBuilderEditorEvent.ApplyDesignToken("color.primary", "#FF8800"),
        UiBuilderEditorEvent.ApplyDesignToken("color.buttonContainer", "primary"),
        UiBuilderEditorEvent.ApplyDesignToken("space.list", "6"),
      )

  @Test
  fun `the export is DTCG, with what the format has no field for under its extension`() {
    val export = exportDesignTokens(reducer.designTokenRows(styled))
    assertEquals(listOf("color.primary", "space.list", "color.buttonContainer"), export.written)
    assertTrue("color.secondary" in export.skipped, "an unset token has no DTCG value to write")

    val json = export.json
    val primary = json.path("color", "primary")
    assertEquals(JsonPrimitive("color"), primary["\$type"])
    val colour = primary["\$value"] as JsonObject
    assertEquals(JsonPrimitive("srgb"), colour["colorSpace"])
    assertEquals(JsonPrimitive("#ff8800"), colour["hex"])
    assertEquals("[1,0.5333,0]", colour["components"].toString())

    assertEquals(
      JsonPrimitive("{color.primary}"),
      json.path("color", "buttonContainer")["\$value"],
      "a theme role is an alias to the role's token",
    )

    val spacing = json.path("space", "list")
    assertEquals(JsonPrimitive("dimension"), spacing["\$type"])
    assertEquals("""{"value":6,"unit":"px"}""", spacing["\$value"].toString())
    val extension = (spacing["\$extensions"] as JsonObject)[DTCG_EXTENSION] as JsonObject
    assertEquals("null", extension["default"].toString(), "the nullable default survives")
    assertEquals(JsonPrimitive("dp"), extension["unit"])
    assertEquals(JsonPrimitive(24), extension["maximum"])
  }

  @Test
  fun `what goes out comes back`() {
    val text = exportDesignTokens(reducer.designTokenRows(styled)).text()
    val read = importDesignTokens(text, tokens).getOrThrow()
    assertEquals("DTCG", read.format)
    assertEquals(emptyList(), read.unknown)
    assertEquals(emptyList(), read.invalid)

    val imported = reducer.initial(screen).on(UiBuilderEditorEvent.ImportDesignTokens(read.values))
    assertIs<CommandOutcome.Accepted>(imported.lastOutcome, "${imported.lastOutcome}")
    assertEquals(screen.revision + 1, imported.document.revision, "one edit")
    assertEquals(values(styled), values(imported))

    val undone = imported.on(UiBuilderEditorEvent.Undo)
    assertTrue(values(undone).values.all { it == DesignTokenValue.Unset }, "one undo")
  }

  @Test
  fun `a file another tool wrote applies what this design system declares`() {
    val foreign =
      """
      {
        "color": {
          "${'$'}type": "color",
          "brand": { "${'$'}value": "#336699" },
          "primary": { "${'$'}value": "{color.brand}" },
          "secondary": { "${'$'}value": { "colorSpace": "srgb", "components": [0, 0.5, 1] } },
          "tertiary": { "${'$'}value": { "colorSpace": "display-p3", "components": [1, 0, 0] } }
        },
        "space": {
          "list": { "${'$'}type": "dimension", "${'$'}value": "8px" },
          "row": { "${'$'}type": "dimension", "${'$'}value": { "value": 1, "unit": "rem" } }
        }
      }
      """
    val read = importDesignTokens(foreign, tokens).getOrThrow()

    assertEquals(
      mapOf("color.primary" to "#336699", "color.secondary" to "#0080FF", "space.list" to "8"),
      read.values,
      "an alias resolves through the file; a group's type is inherited; px is read as a number",
    )
    assertEquals(listOf("color.brand"), read.unknown, "not a wear-m3 token")
    assertEquals(
      listOf("color.tertiary", "space.row"),
      read.invalid.map { it.first },
      "a colour space and a unit this canvas cannot draw are refused, by name",
    )
  }

  @Test
  fun `a Material Theme Builder export fills the theme colours from the scheme asked for`() {
    val export =
      """
      {
        "description": "TYPE: CUSTOM",
        "seed": "#6750A4",
        "coreColors": { "primary": "#6750A4" },
        "schemes": {
          "light": { "primary": "#65558F", "onPrimary": "#FFFFFF", "background": "#FEF7FF" },
          "dark": { "primary": "#CFBDFE", "onPrimary": "#36265D", "background": "#141218",
                    "surfaceTint": "#CFBDFE" }
        }
      }
      """
    val dark = importDesignTokens(export, tokens, scheme = "dark").getOrThrow()
    assertEquals("Material Theme Builder (dark)", dark.format)
    assertEquals(
      mapOf(
        "color.primary" to "#CFBDFE",
        "color.onPrimary" to "#36265D",
        "color.background" to "#141218",
      ),
      dark.values,
    )
    assertEquals(listOf("color.surfaceTint"), dark.unknown)

    val applied = reducer.initial(screen).on(UiBuilderEditorEvent.ImportDesignTokens(dark.values))
    assertEquals(
      """{"type":"color","value":"#CFBDFE"}""",
      applied.document.nodes.getValue("screen").properties["themePrimaryColor"].toString(),
    )

    val light = importDesignTokens(export, tokens, scheme = "light").getOrThrow()
    assertEquals("#65558F", light.values["color.primary"])
  }

  @Test
  fun `an import with a value a token cannot take is refused whole`() {
    val refused =
      reducer
        .initial(screen)
        .on(
          UiBuilderEditorEvent.ImportDesignTokens(
            mapOf("color.primary" to "#123456", "space.list" to "999")
          )
        )
    assertIs<CommandOutcome.Rejected>(refused.lastOutcome)
    assertEquals(screen, refused.document, "the half that came first was not applied")
  }

  @Test
  fun `what is not a token file says so`() {
    assertTrue(importDesignTokens("[1, 2]", tokens).isFailure)
    assertTrue(importDesignTokens("""{"color": {}}""", tokens).isFailure)
    assertTrue(importDesignTokens("not json", tokens).isFailure)
  }

  private fun JsonObject.path(vararg keys: String): JsonObject =
    keys.fold(this) { group, key -> group.getValue(key) as JsonObject }

  private fun button(id: String) =
    mapOf(
      id to
        UiBuilderNode(
          id = id,
          componentId = "wear-m3/button",
          slots = mapOf("content" to listOf("$id-label")),
        ),
      "$id-label" to
        UiBuilderNode(
          id = "$id-label",
          componentId = "wear-m3/text",
          properties =
            JsonObject(
              mapOf(
                "text" to
                  JsonObject(
                    mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive("Start"))
                  )
              )
            ),
        ),
    )

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
