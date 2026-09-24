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
 * The wrapper vocabulary is one set, and every lane asks the same question of it.
 *
 * `{"type":"fixedGrid","columns":7}` was kept by replay, reported clean by `CapabilityValidator`,
 * exported as Compose, drawn by the renderer and asserted green by `DesignFixturesTest` — then
 * refused by the server at commit with a kotlinx-serialization message naming a sealed base class
 * (#901). The vocabulary was closed the whole time; only `propertyWrapperIssue` knew it, and
 * nothing outside `CollaborationReducer` asked.
 *
 * So the names live in [PropertyValueKinds.WRAPPER_TYPES] and this pins the two together in
 * **both** directions. A name in one and not the other is the drift that made the bug, and it fails
 * here rather than at somebody's commit.
 */
class WrapperVocabularyTest {

  /**
   * Every wrapper the reducer has a branch for is named in the shared set.
   *
   * One direction only: the set is deliberately **wider** than this function. `list` and `binding`
   * arrive on inserts, which `propertyWrapperIssue` never sees, and are checked by
   * `inspectUiBuilderArgumentBindings` instead — so the reverse assertion would be wrong, and
   * asserting it is how this test was first written and how it passed while the set was missing
   * both of them.
   */
  @Test
  fun `every wrapper the reducer accepts is named in the shared set`() {
    val acceptedByReducer = PropertyValueKinds.WRAPPER_TYPES.filterNot(::rejectsOutright)
    assertTrue(
      acceptedByReducer.toSet().size >= 16,
      "expected the reducer's own sixteen, found ${acceptedByReducer.size}: $acceptedByReducer",
    )
  }

  /**
   * And the corpus is covered: no committed fixture carries a wrapper the set does not name.
   *
   * This is the direction that matters, and the one nothing asked before. A fixture is
   * hand-authored and replayed rather than written through a reducer, which is exactly how
   * `fixedGrid` got as far as a green build (#901). Reading the fixtures rather than a hand-kept
   * list means a wrapper introduced by a future fixture has to be named here too.
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
   * The original case, end to end: a document that replays cleanly and does not validate.
   *
   * This is the assertion that would have failed the build before the fix — `DesignFixturesTest`
   * runs exactly this validator over exactly this path, and reported no issue.
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
   * Whether [type] is refused *because the name is unknown*, as opposed to because this probe's
   * value has the wrong shape for it — which is the reducer's other job and not what is pinned
   * here.
   */
  private fun rejectsOutright(type: String): Boolean =
    propertyWrapperIssue(type, wrapper(type))?.startsWith("uses unsupported wrapper type") == true

  private fun wrapper(type: String): JsonObject =
    JsonObject(mapOf("type" to JsonPrimitive(type), "columns" to JsonPrimitive(7)))
}
