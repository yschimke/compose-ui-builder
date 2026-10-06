package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.capability.DesignTokenKind
import ee.schimke.composeai.uibuilder.capability.DesignTokens
import ee.schimke.composeai.uibuilder.editor.DesignTokenValue
import ee.schimke.composeai.uibuilder.editor.TunableTarget
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorState
import ee.schimke.composeai.uibuilder.editor.heldValue
import ee.schimke.composeai.uibuilder.editor.tuned
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.WearScreenCodeExporter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Design tokens: the values a design system lets a design re-skin, declared by the catalog with a
 * nullable default and applied by writing them into the theme host or into every node of the
 * components they bind.
 */
class DesignTokensTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/wear-m3-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)

  private val seed =
    UiBuilderNewDesignSeed.document(
      designId = "wear-tokens",
      catalogSystemId = "wear-m3",
      templateId = "wear-screen",
      catalogRevision = "wear-screen-scaffold-v1",
      nativeRuntimeId = "candidate",
      fixture =
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject,
    )

  /** A scaffold over a list of two buttons, with a column of one more inside the second. */
  private val screen =
    seed.copy(
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
              slots = mapOf("items" to listOf("cell-0", "cell-1")),
            ),
        ) + button("cell-0") + button("cell-1"),
    )

  private fun UiBuilderEditorState.on(vararg events: UiBuilderEditorEvent): UiBuilderEditorState =
    events.fold(this, reducer::reduce)

  private fun token(id: String) = catalog.designTokens.single { it.id == id }

  private fun row(state: UiBuilderEditorState, id: String) =
    reducer.designTokenRows(state).single { it.token.id == id }

  @Test
  fun `wear-m3 declares theme and component tokens, every default null`() {
    val tokens = catalog.designTokens
    assertTrue(tokens.any { it.id == "color.primary" && it.themed })
    assertTrue(tokens.any { it.id == "space.list" && !it.themed })
    assertEquals(emptyList(), tokens.mapNotNull { it.default }, "unset keeps Wear's own")
    assertEquals(
      emptyList(),
      tokens
        .flatMap { it.bindings }
        .filterNot { binding ->
          catalog.componentsById[binding.componentId]
            ?.propertiesByName
            ?.containsKey(binding.property) == true
        },
      "every token binds a property the catalog declares",
    )
  }

  @Test
  fun `an unset token reads as the design system default`() {
    val state = reducer.initial(screen)
    assertEquals(DesignTokenValue.Unset, row(state, "color.primary").value)
    assertEquals(1, row(state, "color.primary").targetCount, "the scaffold is the theme host")
    assertEquals(2, row(state, "color.buttonContainer").targetCount, "both buttons")
    assertEquals(0, row(state, "size.icon").targetCount, "no icons yet")
  }

  @Test
  fun `a theme token lands on the theme host`() {
    val state =
      reducer.initial(screen).on(UiBuilderEditorEvent.ApplyDesignToken("color.primary", "#FF8800"))

    assertIs<CommandOutcome.Accepted>(state.lastOutcome, "${state.lastOutcome}")
    assertEquals(
      """{"type":"color","value":"#FF8800"}""",
      state.document.nodes.getValue("screen").properties["themePrimaryColor"].toString(),
    )
    assertEquals(
      DesignTokenValue.Set(JsonPrimitive("#FF8800")),
      row(state, "color.primary").value,
    )
  }

  @Test
  fun `a component token lands on every node of its components, as one edit`() {
    val state =
      reducer
        .initial(screen)
        .on(UiBuilderEditorEvent.ApplyDesignToken("color.buttonContainer", "primary"))

    assertIs<CommandOutcome.Accepted>(state.lastOutcome, "${state.lastOutcome}")
    assertEquals(screen.revision + 1, state.document.revision)
    listOf("cell-0", "cell-1").forEach { id ->
      assertEquals(
        """{"type":"colorToken","value":"primary"}""",
        state.document.nodes.getValue(id).properties["containerColor"].toString(),
        "a theme role is written as a colorToken",
      )
    }
    val undone = state.on(UiBuilderEditorEvent.Undo)
    assertEquals(DesignTokenValue.Unset, row(undone, "color.buttonContainer").value)
  }

  @Test
  fun `resetting a token with a null default unsets what it wrote`() {
    val reset =
      reducer
        .initial(screen)
        .on(
          UiBuilderEditorEvent.ApplyDesignToken("space.list", "6"),
          UiBuilderEditorEvent.ApplyDesignToken("space.list", null),
        )

    assertIs<CommandOutcome.Accepted>(reset.lastOutcome, "${reset.lastOutcome}")
    assertNull(reset.document.nodes.getValue("list").properties["verticalSpacingDp"])
    assertEquals(DesignTokenValue.Unset, row(reset, "space.list").value)
  }

  @Test
  fun `a property edited by hand after the token makes it read mixed`() {
    val state =
      reducer
        .initial(screen)
        .on(
          UiBuilderEditorEvent.ApplyDesignToken("color.buttonContainer", "#112233"),
          UiBuilderEditorEvent.CommitProperty("cell-1", "containerColor", "#445566"),
        )
    assertEquals(DesignTokenValue.Mixed, row(state, "color.buttonContainer").value)
  }

  @Test
  fun `a value outside the token's range or kind is refused by name`() {
    val state = reducer.initial(screen)
    val tooFar = state.on(UiBuilderEditorEvent.ApplyDesignToken("space.list", "99"))
    assertIs<CommandOutcome.Rejected>(tooFar.lastOutcome)
    assertEquals(screen, tooFar.document)

    val notAColour = state.on(UiBuilderEditorEvent.ApplyDesignToken("color.primary", "orange"))
    assertIs<CommandOutcome.Rejected>(notAColour.lastOutcome)

    val nowhere = state.on(UiBuilderEditorEvent.ApplyDesignToken("size.icon", "24"))
    val refusal = assertIs<CommandOutcome.Rejected>(nowhere.lastOutcome)
    assertTrue("icon" in refusal.message, refusal.message)
  }

  @Test
  fun `a number token is tunable across every node it binds, including ones added later`() {
    var state = reducer.initial(screen).on(UiBuilderEditorEvent.TuneDesignToken("space.list"))
    val tunable = state.tunables.single()
    assertEquals("space.list", tunable.token)
    assertEquals(12.0, tunable.default, "an unset token with no default starts mid-range")
    assertEquals(listOf(TunableTarget.Property("list", "verticalSpacingDp")), tunable.targets)

    // A column appears inside the first button: the token binds `layout/column` too.
    state =
      state.on(
        UiBuilderEditorEvent.InsertComponent("layout/column", ParentSlot("cell-0", "content"))
      )
    assertIs<CommandOutcome.Accepted>(state.lastOutcome, "${state.lastOutcome}")
    val column = state.document.nodes.values.single { it.componentId == "layout/column" }
    assertEquals(2, state.tunables.single().targets.size, "the new column is tuned too")

    state = state.on(UiBuilderEditorEvent.SetTunedValue(tunable.name, 4.0))
    val drawn = state.document.tuned(state.tunables, state.tunedValues)
    assertEquals(4.0, drawn.heldValue(TunableTarget.Property("list", "verticalSpacingDp")))
    assertEquals(4.0, drawn.heldValue(TunableTarget.Property(column.id, "verticalSpacingDp")))

    val applied = state.on(UiBuilderEditorEvent.ApplyTunables)
    assertEquals(
      DesignTokenValue.Set(JsonPrimitive(4.0)),
      row(applied, "space.list").value,
      "applying the slider is applying the token",
    )
  }

  @Test
  fun `a colour token has no slider`() {
    val state = reducer.initial(screen).on(UiBuilderEditorEvent.TuneDesignToken("color.primary"))
    assertIs<CommandOutcome.Rejected>(state.lastOutcome)
    assertEquals(emptyList(), state.tunables)
  }

  @Test
  fun `the parser skips what is not a token`() {
    val parsed =
      DesignTokens.from(
        Json.parseToJsonElement(
            """
            {"designTokens": {"tokens": [
              {"id": "ok", "kind": "number", "minimum": 0, "maximum": 4, "default": 2,
               "components": [{"component": "layout/row", "property": "horizontalSpacingDp"}]},
              {"id": "ok", "kind": "color", "theme": [{"component": "a", "property": "b"}]},
              {"id": "noKind", "theme": [{"component": "a", "property": "b"}]},
              {"id": "unbound", "kind": "color"},
              {"id": "backwards", "kind": "number", "minimum": 4, "maximum": 0,
               "components": [{"component": "a", "property": "b"}]}
            ]}}
            """
          )
          .jsonObject
      )
    assertEquals(listOf("ok"), parsed.map { it.id }, "first id wins; the rest are not tokens")
    assertEquals(DesignTokenKind.Number, parsed.single().kind)
    assertEquals(JsonPrimitive(2), parsed.single().default)
    assertEquals(emptyList(), DesignTokens.from(JsonObject(emptyMap())))
  }

  @Test
  fun `the token's own target list matches the catalog binding`() {
    assertEquals(
      listOf("wear-m3/transforming-lazy-column", "layout/column"),
      token("space.list").bindings.map { it.componentId },
    )
  }

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
