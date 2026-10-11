package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import ee.schimke.composeai.uibuilder.codegen.CapabilityComposeCodeExporter
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The cross-language contract fixture (the Confetti design) checks out against the catalog it pins,
 * so it validates and can export:
 * - every flag a state variable can drive (`m3/filter-chip.selected`, `m3/button.selected`,
 *   `m3/checkbox.checked`, `m3/radio-button.selected`, `m3/switch.checked`) admits an `object` (a
 *   `stateEquals` binding), like the other bindables;
 * - the `layout/lazy-column` carries its required `scrollStateKey`;
 * - undeclared properties nothing reads (`initialIndex`, `sticky`) were removed rather than
 *   declared.
 *
 * Editing the fixture moves its `expectedDocumentHash`, which both the Kotlin reducer and the JS
 * one in `scripts/ui-builder` recompute.
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
   * A `stateEquals` binding is admissible on every flag a state variable can drive, not just the
   * one the fixture uses.
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
