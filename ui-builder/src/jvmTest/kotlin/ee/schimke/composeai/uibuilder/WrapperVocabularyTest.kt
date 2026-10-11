package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.export.PropertyValueKinds
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The wrapper vocabulary is one set ([PropertyValueKinds.WRAPPER_TYPES]), and every lane asks the
 * same question of it. Pinned against the reducer and the fixture corpus so an invented wrapper
 * (like `{"type":"fixedGrid","columns":7}`, see #901) fails here rather than at the server's
 * deserializer.
 */
class WrapperVocabularyTest {

  /**
   * Every wrapper the reducer has a branch for is named in the shared set. One direction only: the
   * set is wider, since `list` and `binding` arrive on inserts and are checked by
   * `inspectUiBuilderArgumentBindings`.
   */
  @Test
  fun `every wrapper the reducer accepts is named in the shared set`() {
    val acceptedByReducer = PropertyValueKinds.WRAPPER_TYPES.filterNot(::rejectsOutright)
    assertTrue(
      acceptedByReducer.toSet().size >= 17,
      "expected the reducer's own seventeen, found ${acceptedByReducer.size}: $acceptedByReducer",
    )
  }

  /**
   * No committed fixture carries a wrapper the set doesn't name. Fixtures are hand-authored and
   * replayed rather than written through a reducer, so they're read directly.
   */
  @Test
  fun `every wrapper in a committed fixture is named in the shared set`() {
    val unknown =
      fixtureWrapperTypes().filterNot { it in PropertyValueKinds.WRAPPER_TYPES }.sorted()
    assertEquals(emptyList(), unknown, "fixtures carry wrappers WRAPPER_TYPES does not name")
  }

  /** The other direction, by sampling: an invented name is refused, and says so plainly. */
  @Test
  fun `an invented wrapper is refused by the reducer with a message naming it`() {
    val issue = propertyWrapperIssue("fixedGrid", wrapper("fixedGrid"))
    assertEquals("uses unsupported wrapper type fixedGrid", issue)
  }

  /** A catalog string resource is admitted by its shape, and a keyless one is refused. */
  @Test
  fun `a resource wrapper is accepted and a keyless one refused`() {
    assertNull(
      propertyWrapperIssue(
        "resource",
        JsonObject(
          mapOf("type" to JsonPrimitive("resource"), "resourceKey" to JsonPrimitive("card_title"))
        ),
      )
    )
    assertTrue(
      propertyWrapperIssue("resource", JsonObject(mapOf("type" to JsonPrimitive("resource"))))
        ?.startsWith("resource wrapper") == true
    )
  }

  /** A well-formed literal still passes, so the pin above is not just rejecting everything. */
  @Test
  fun `a well formed literal is accepted`() {
    assertNull(
      propertyWrapperIssue(
        "string",
        JsonObject(mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive("Inbox"))),
      )
    )
  }

  /**
   * Every `{"type": …}` a committed fixture writes as a property value, nested objects included.
   */
  private fun fixtureWrapperTypes(): Set<String> {
    val directory =
      java.io.File(
        System.getProperty("uiBuilderDesignFixturesDir")
          ?: "../docs/design/fixtures/ui-builder/designs"
      )
    val found = mutableSetOf<String>()
    fun walk(properties: JsonObject) {
      properties.values.forEach { value ->
        val encoded = value as? JsonObject ?: return@forEach
        (encoded["type"] as? JsonPrimitive)?.takeIf { it.isString }?.let { found += it.content }
        (encoded["fields"] as? JsonObject)?.let(::walk)
      }
    }
    directory
      .listFiles { file -> file.extension == "json" }
      ?.forEach { file ->
        val fixture =
          kotlinx.serialization.json.Json.parseToJsonElement(file.readText()) as JsonObject
        (fixture["operations"] as? kotlinx.serialization.json.JsonArray)?.forEach { operation ->
          val node = (operation as? JsonObject)?.get("node") as? JsonObject ?: return@forEach
          (node["properties"] as? JsonObject)?.let(::walk)
        }
      }
    return found
  }

  /**
   * The original case, end to end: a document that replays cleanly and does not validate (the path
   * `DesignFixturesTest` runs).
   */
  @Test
  fun `a replayed document carrying an invented wrapper is reported by the validator`() {
    val catalog =
      ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser.parse(
        checkNotNull(javaClass.getResource("/m3-catalog-capabilities-v1.json")).readText()
      )
    val grid =
      UiBuilderNode(
        id = "grid",
        componentId = "layout/lazy-grid",
        properties =
          JsonObject(
            mapOf(
              "columns" to
                JsonObject(
                  mapOf("type" to JsonPrimitive("fixedGrid"), "columns" to JsonPrimitive(7))
                ),
              "scrollStateKey" to
                JsonObject(
                  mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive("month"))
                ),
            )
          ),
      )
    val document =
      UiBuilderDocument(
        schema = "compose-ui-builder-document/v1-candidate",
        id = "month",
        title = "Month",
        revision = 1,
        catalogPin = JsonObject(mapOf("catalogId" to JsonPrimitive("m3-catalog"))),
        environment = JsonObject(emptyMap()),
        stateVariables = JsonObject(emptyMap()),
        roots = listOf("grid"),
        nodes = mapOf("grid" to grid),
      )

    val issues =
      ee.schimke.composeai.uibuilder.capability
        .CapabilityValidator(catalog)
        .validate(document)
        .issues

    val wrapper = issues.single { it.field == "columns" }
    assertEquals("grid", wrapper.nodeId)
    assertTrue("fixedGrid" in wrapper.message, wrapper.message)
  }

  /**
   * Whether [type] is refused because the name is unknown, rather than because the probe's value
   * has the wrong shape.
   */
  private fun rejectsOutright(type: String): Boolean =
    propertyWrapperIssue(type, wrapper(type))?.startsWith("uses unsupported wrapper type") == true

  private fun wrapper(type: String): JsonObject =
    JsonObject(mapOf("type" to JsonPrimitive(type), "columns" to JsonPrimitive(7)))
}
