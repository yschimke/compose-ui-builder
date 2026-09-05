package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The cross-language contract fixture now checks out against the catalog it pins.
 *
 * The Confetti design is this repository's oldest committed document and it predates the capability
 * catalog, so four things in it had never been reconciled with the catalog it names. The export
 * refuses a document the validator rejects, which meant the one design both languages replay could
 * not be turned into Compose at all.
 *
 * Three were gaps in the document and one was a gap in the catalog:
 * - `m3/filter-chip.selected` carried a `stateEquals` binding — an **object** — against a property
 *   declaring `["boolean", "string"]`. That was the catalog's mistake and it was not the fixture's
 *   alone: every flag a state variable can drive (`m3/button.selected`, `m3/checkbox.checked`,
 *   `m3/radio-button.selected`, `m3/switch.checked`) declared the same too-narrow types, so *any*
 *   design binding one was invalid. The newer bindables (`m3/slider.value`, `m3/text-field.value`,
 *   `m3/progress-indicator.progress`) already declared `object`; these five now match them.
 * - `layout/lazy-column` required a `scrollStateKey` and the design had none. It has one now: a
 *   list that keeps its scroll position across recomposition is what the component is for.
 * - `initialIndex` on that same list, and `sticky` on three time-header surfaces, were properties
 *   **no catalog declares and no renderer, inspector or exporter reads** — spike leftovers, in the
 *   old `{"type": "int"}` / `{"type": "bool"}` wrappers. Declaring them to make the document pass
 *   would have advertised behaviour that does not exist, so they are gone from the document
 *   instead. Sticky time headers are a real thing to want; wanting it is not the same as the
 *   catalog claiming it.
 *
 * Editing the fixture moves its `expectedDocumentHash`, which is the point of the field: the Kotlin
 * reducer and the JS one in `scripts/ui-builder` both recompute it, so a change either replays or
 * it does not.
 */
class ConfettiFixtureValidatesTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val confetti =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/confetti-schedule-operations-v1.json")).jsonObject
      )
      .document

  @Test
  fun `the whole design validates against the catalog it pins`() {
    assertEquals(emptyList(), CapabilityValidator(catalog).validate(confetti).issues)
  }

  @Test
  fun `and therefore exports as Compose`() {
    val source = CapabilityComposeCodeExporter.export(confetti, catalog).requireSource()

    assertTrue("LazyColumn(" in source, source)
    assertTrue("FilterChip(" in source, source)
    // The chips read the state variable rather than a literal, which is the binding that could not
    // be declared before.
    assertTrue("selectedTrack" in source, source)
  }

  /**
   * A `stateEquals` binding is admissible on every flag a state variable can drive, not only on the
   * one the fixture happens to use.
   */
  @Test
  fun `every state-driven flag admits the binding shape`() {
    listOf(
        "m3/button" to "selected",
        "m3/checkbox" to "checked",
        "m3/filter-chip" to "selected",
        "m3/radio-button" to "selected",
        "m3/switch" to "checked",
      )
      .forEach { (componentId, property) ->
        val declared =
          catalog.componentsById
            .getValue(componentId)
            .propertiesByName
            .getValue(property)
            .jsonType
            .toString()

        assertTrue("object" in declared, "$componentId.$property: $declared")
      }
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
