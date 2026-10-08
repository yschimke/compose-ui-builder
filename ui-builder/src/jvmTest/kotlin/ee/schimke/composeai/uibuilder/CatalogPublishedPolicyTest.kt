package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.capability.CatalogPublishedPolicy
import ee.schimke.composeai.uibuilder.capability.CatalogScrollers
import ee.schimke.composeai.uibuilder.capability.PropertyEditorControl
import ee.schimke.composeai.uibuilder.capability.insertContent
import ee.schimke.composeai.uibuilder.capability.scrollers
import ee.schimke.composeai.uibuilder.capability.textComponentFor
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.editor.isVerticalScroller
import ee.schimke.composeai.uibuilder.editor.scrollingContainerOf
import ee.schimke.composeai.uibuilder.export.CatalogBuilderRoles
import ee.schimke.composeai.uibuilder.export.FontSettings
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The catalog-published editor policy: role traits, editor ranges and insert content, each read
 * from the catalog's own `statusSemantics` before the Kotlin table it replaces.
 *
 * Two halves. A catalog that publishes none of it — every packaged catalog, and every catalog
 * served today — is edited exactly as before. A catalog that does publish it is honoured, and a
 * declaration this build cannot honour falls back to the table rather than breaking the property or
 * the insert. Together they are what lets a catalog take each table over before the table is
 * deleted.
 */
class CatalogPublishedPolicyTest {

  private val base: JsonObject =
    Json.parseToJsonElement(resource("/m3-catalog-capabilities-v1.json")).jsonObject
  private val catalog = CapabilityCatalogParser.parse(base)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  @Test
  fun `no packaged catalog publishes any of it, so every table still answers`() {
    for (path in
      listOf(
        "/m3-catalog-capabilities-v1.json",
        "/remote-m3-capabilities-v1.json",
        "/a2ui-catalog-capabilities-v1.json",
      )) {
      val parsed = CapabilityCatalogParser.parse(resource(path))
      parsed.components.forEach { component ->
        assertNull(parsed.insertContent(component.componentId), "$path ${component.componentId}")
        component.properties.forEach { property ->
          assertNull(
            CatalogPublishedPolicy.editorFor(
              parsed.statusSemantics,
              component.componentId,
              property.name,
            ),
            "$path ${component.componentId}.${property.name}",
          )
        }
        assertTrue(CatalogBuilderRoles.ALL.none { it in component.traits }, component.componentId)
      }
      assertEquals(CatalogScrollers.NONE, parsed.scrollers, path)
    }
  }

  @Test
  fun `a published editor range wins over the builder's own`() {
    val builtIn = catalog.editorOf("m3/slider", "steps")
    assertEquals(100.0, builtIn.maximum)

    val published =
      publishing(
        "m3/slider" to
          policy(
            propertyEditor(
              "steps",
              """{"control":"number","minimum":0,"maximum":10,"step":1,"integer":true}""",
            )
          )
      )
    val editor = published.editorOf("m3/slider", "steps")
    assertEquals(PropertyEditorControl.NUMBER, editor.control)
    assertEquals(10.0, editor.maximum)
    assertTrue(editor.integer)
  }

  @Test
  fun `an editor range this build cannot honour falls back to the builder's own`() {
    for (bad in
      listOf(
        """{"control":"number","minimum":10,"maximum":0}""",
        """{"control":"number","minimum":0}""",
        """{"control":"number","minimum":0,"maximum":10,"step":0}""",
        """{"control":"dial"}""",
        """{}""",
      )) {
      val published = publishing("m3/slider" to policy(propertyEditor("steps", bad)))
      assertEquals(catalog.editorOf("m3/slider", "steps"), published.editorOf("m3/slider", "steps"))
    }
  }

  @Test
  fun `published insert content is what the component arrives holding`() {
    val published =
      publishing(
        "m3/button" to
          JsonObject(
            mapOf(
              "insertContent" to
                Json.parseToJsonElement(
                  """
                  {"slots": {"content": [
                    {"componentId": "m3/text",
                     "properties": {"text": {"type": "string", "value": "Go"}}}
                  ]}}
                  """
                )
            )
          )
      )

    assertEquals("Button", insertedLabel(catalog))
    assertEquals("Go", insertedLabel(published))
  }

  @Test
  fun `insert content that does not decode or fit falls back to the starter table`() {
    for (bad in
      listOf(
        """{"slots": {"content": "m3/text"}}""",
        """{"slots": {"content": [{"properties": {}}]}}""",
        """{"slots": {"content": [{"componentId": "m3/not-a-component"}]}}""",
      )) {
      val published =
        publishing(
          "m3/button" to JsonObject(mapOf("insertContent" to Json.parseToJsonElement(bad)))
        )
      assertEquals("Button", insertedLabel(published), bad)
    }
  }

  @Test
  fun `the text role gives a component the font settings its id never did`() {
    val leaf = catalog.components.first { it.slots.isEmpty() && it.componentId != "m3/text" }
    assertFalse(leaf.properties.any { it.name == FontSettings.VARIATION_PROPERTY })

    val roled = withTraits(leaf.componentId to CatalogBuilderRoles.TEXT)
    assertTrue(
      roled.componentsById.getValue(leaf.componentId).properties.any {
        it.name == FontSettings.VARIATION_PROPERTY
      }
    )
  }

  @Test
  fun `the text role chooses what fills a text slot, and is absent today`() {
    val (owner, slot) =
      catalog.components.firstNotNullOf { component ->
        component.slots.firstOrNull { "TextContent" in it.acceptedTraits }?.let { component to it }
      }
    assertNull(catalog.textComponentFor(slot, owner.componentId))

    val roled = withTraits("m3/text" to CatalogBuilderRoles.TEXT)
    assertEquals("m3/text", roled.textComponentFor(slot, owner.componentId)?.componentId)
  }

  @Test
  fun `the scroller role makes a container pop out, beside the ids the canvas knew`() {
    val (nodeId, node) =
      document.nodes.entries.first { (_, node) ->
        !document.isVerticalScroller(node.id) && node.componentId.startsWith("m3/")
      }
    val roled = withTraits(node.componentId to CatalogBuilderRoles.VERTICAL_SCROLLER)

    assertFalse(document.isVerticalScroller(nodeId))
    assertTrue(document.isVerticalScroller(nodeId, roled.scrollers))
    assertEquals(nodeId, document.scrollingContainerOf(nodeId, roled.scrollers)?.nodeId)
  }

  private fun CapabilityCatalog.editorOf(componentId: String, property: String) =
    assertNotNull(
      componentsById.getValue(componentId).properties.single { it.name == property }.editor
    )

  private fun insertedLabel(catalog: CapabilityCatalog): String {
    val reducer = UiBuilderEditorReducer(catalog)
    val initial = reducer.initial(document, selectedNodeId = "main-background")
    val target = requireNotNull(reducer.dropTarget(initial, "m3/button"))
    val inserted =
      reducer.reduce(initial, UiBuilderEditorEvent.InsertComponent("m3/button", target))
    assertIs<CommandOutcome.Accepted>(inserted.lastOutcome, inserted.lastOutcome.toString())
    val root = inserted.document.nodes.getValue(assertNotNull(inserted.selectedNodeId))
    val label = inserted.document.nodes.getValue(root.slots.getValue("content").single())
    return label.properties.getValue("text").jsonObject.getValue("value").jsonPrimitive.content
  }

  /** The packaged catalog, with [policies] published under `statusSemantics.components`. */
  private fun publishing(vararg policies: Pair<String, JsonObject>): CapabilityCatalog {
    val semantics = base["statusSemantics"]?.jsonObject.orEmpty()
    return CapabilityCatalogParser.parse(
      JsonObject(
        base +
          ("statusSemantics" to
            JsonObject(semantics + ("components" to JsonObject(policies.toMap()))))
      )
    )
  }

  /** The packaged catalog, with each component given one more trait. */
  private fun withTraits(vararg traits: Pair<String, String>): CapabilityCatalog {
    val added = traits.groupBy({ it.first }, { it.second })
    val components =
      (base.getValue("components") as JsonArray).map { element ->
        val component = element.jsonObject
        val id = component.getValue("componentId").jsonPrimitive.content
        val extra = added[id] ?: return@map component
        val existing = (component["traits"] as? JsonArray).orEmpty()
        JsonObject(component + ("traits" to JsonArray(existing + extra.map(::JsonPrimitive))))
      }
    return CapabilityCatalogParser.parse(JsonObject(base + ("components" to JsonArray(components))))
  }

  private fun policy(vararg properties: JsonElement): JsonObject =
    JsonObject(mapOf("propertyCapabilities" to JsonArray(properties.toList())))

  private fun propertyEditor(name: String, editor: String): JsonElement =
    JsonObject(mapOf("name" to JsonPrimitive(name), "editor" to Json.parseToJsonElement(editor)))

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
