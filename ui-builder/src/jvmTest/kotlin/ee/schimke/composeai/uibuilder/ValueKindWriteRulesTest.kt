package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.artwork.ANDROID_DEVELOPERS_BACKSTAGE_ARTWORK_KEY
import ee.schimke.composeai.uibuilder.artwork.GOOGLE_DEVELOPERS_PODCAST_ARTWORK_KEY
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import ee.schimke.composeai.uibuilder.export.PropertyValueKinds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A committed document is one the canvas can draw, and the author is told at the door.
 *
 * The reducer's refusals were shape — is the property declared, is the scalar the right JSON type,
 * is the value in `allowedValues` — and every expensive failure passed them: a colour written as
 * `string` committed and was refused one export later with a sentence about `Text` (#476); an
 * `asset/image` whose key nothing resolved committed and then failed every render of the design
 * (#484). The decision that closes the pattern (#487) is in [PropertyValueKinds]; these tests hold
 * the behaviour at the editor's reducer, against the real m3 catalog.
 */
class ValueKindWriteRulesTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val validator = CapabilityPropertyWriteValidator(CapabilityValidator(catalog))

  @Test
  fun `a colour written as a string wrapper is refused, naming the node, the field and the wrapper`() {
    val rejected = rejected(set("text", "color", typed("string", "#5F6368")))

    assertEquals(RejectionCode.INVALID_PROPERTY, rejected.code)
    assertEquals("text", rejected.nodeId)
    assertEquals("color", rejected.field)
    assertTrue(rejected.message.contains("`color` or `colorToken`"), rejected.message)
    assertTrue(rejected.message.contains("`string`"), rejected.message)
  }

  @Test
  fun `a colour the canvas draws is accepted under either colour wrapper`() {
    accepted(set("text", "color", typed("color", "#5F6368")))
    accepted(set("text", "color", typed("color", "#805F6368")))
    accepted(set("text", "color", typed("colorToken", "primary")))
    // Empty is the component's own default — `startAccentColor` documents it as "draws none".
    accepted(set("item", "startAccentColor", typed("color", "")))
  }

  @Test
  fun `a theme role the canvas does not draw is refused rather than committed to throw`() {
    // The export's table has `primaryContainer`; the canvas's does not, and the renderer used to
    // throw on the difference, taking the frame with it.
    val rejected = rejected(set("text", "color", typed("colorToken", "primaryContainer")))

    assertEquals("color", rejected.field)
    assertTrue(rejected.message.contains("`primaryContainer`"), rejected.message)
    assertTrue(rejected.message.contains("primary"), "the refusal lists the roles it takes")
  }

  @Test
  fun `an insert carrying an asset key nothing resolves is refused at the insert`() {
    val rejected =
      rejected(
        DesignOperation.InsertNode(
          image("assetKey" to typed("string", "avatar-lain")),
          parent = ParentSlot("column", "children"),
        )
      )

    assertEquals(RejectionCode.INVALID_PROPERTY, rejected.code)
    assertEquals("photo", rejected.nodeId)
    assertEquals("assetKey", rejected.field)
    assertTrue(rejected.message.contains("`avatar-lain`"), rejected.message)
    assertTrue(
      rejected.message.contains(ANDROID_DEVELOPERS_BACKSTAGE_ARTWORK_KEY),
      rejected.message,
    )
  }

  @Test
  fun `an asset key the catalog's registry lists is accepted, and so is a later write of one`() {
    accepted(
      DesignOperation.InsertNode(
        image("assetKey" to typed("assetKey", GOOGLE_DEVELOPERS_PODCAST_ARTWORK_KEY)),
        parent = ParentSlot("column", "children"),
      )
    )
    val withImage =
      document(image("assetKey" to typed("assetKey", GOOGLE_DEVELOPERS_PODCAST_ARTWORK_KEY)))
    val rewritten =
      CollaborationReducer.apply(
        CollaborationState(withImage),
        command(set("photo", "assetKey", typed("assetKey", "avatar-lain"))),
        validator,
      )
    assertEquals("assetKey", assertIs<CommandOutcome.Rejected>(rewritten.outcome).field)
  }

  @Test
  fun `the catalog tells a reader the same roles and keys the validators refuse against`() {
    // `statusSemantics.colorTokens` and `statusSemantics.assetRegistry` exist so an agent reading
    // the capability document can get a value right the first time; they are pinned to the code's
    // own lists so the document cannot promise what the canvas will not draw.
    assertEquals(
      PropertyValueKinds.CANVAS_COLOR_TOKENS,
      assertNotNull(PropertyValueKinds.declaredColorTokens(catalog.statusSemantics)),
    )
    val registry = assertNotNull(PropertyValueKinds.declaredAssetKeys(catalog.statusSemantics))
    assertEquals(
      setOf(
        ANDROID_DEVELOPERS_BACKSTAGE_ARTWORK_KEY,
        GOOGLE_DEVELOPERS_PODCAST_ARTWORK_KEY,
        "ui-builder.gate0.cover",
        "editor.placeholder",
      ),
      registry,
    )
    // Every colour property in the catalog says so in its notes, so the rule is visible where an
    // author reads the property, not only where the refusal lands.
    catalog.components
      .flatMap { component -> component.properties.map { component.componentId to it } }
      .filter { (_, property) -> PropertyValueKinds.isColour(property.name) }
      .forEach { (componentId, property) ->
        assertTrue(
          property.notes?.contains("colorToken") == true,
          "$componentId.${property.name} says it is a colour: ${property.notes}",
        )
      }
  }

  private fun set(nodeId: String, property: String, value: JsonObject) =
    DesignOperation.SetProperty(nodeId, property, value)

  private fun accepted(operation: DesignOperation) {
    val application =
      CollaborationReducer.apply(CollaborationState(document()), command(operation), validator)
    assertIs<CommandOutcome.Accepted>(application.outcome, application.outcome.toString())
  }

  private fun rejected(operation: DesignOperation): CommandOutcome.Rejected {
    val application =
      CollaborationReducer.apply(CollaborationState(document()), command(operation), validator)
    return assertIs<CommandOutcome.Rejected>(application.outcome)
  }

  private fun typed(type: String, value: String): JsonObject = buildJsonObject {
    put("type", type)
    put("value", value)
  }

  private fun image(vararg properties: Pair<String, JsonObject>) =
    UiBuilderNode(
      id = "photo",
      componentId = "asset/image",
      properties = JsonObject(properties.toMap()),
    )

  private fun command(vararg operations: DesignOperation): DesignCommand =
    DesignCommand(
      designId = "design",
      operationId = "value-kind",
      actorId = "actor-a",
      clientId = "browser-a",
      baseRevision = 0,
      operations = operations.toList(),
    )

  private fun document(vararg extra: UiBuilderNode): UiBuilderDocument {
    val text =
      UiBuilderNode(
        id = "text",
        componentId = "m3/text",
        properties = JsonObject(mapOf("text" to typed("string", "Hello"))),
      )
    val item = UiBuilderNode(id = "item", componentId = "m3/list-item")
    val column =
      UiBuilderNode(
        id = "column",
        componentId = "layout/column",
        slots = mapOf("children" to listOf(text.id, item.id) + extra.map { it.id }),
      )
    return UiBuilderDocument(
      schema = "compose-ui-builder-document/v1",
      id = "design",
      title = "Value kinds",
      revision = 0,
      catalogPin = JsonObject(emptyMap()),
      environment = JsonObject(emptyMap()),
      stateVariables = JsonObject(emptyMap()),
      roots = listOf(column.id),
      nodes = (listOf(column, text, item) + extra).associateBy { it.id },
    )
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
