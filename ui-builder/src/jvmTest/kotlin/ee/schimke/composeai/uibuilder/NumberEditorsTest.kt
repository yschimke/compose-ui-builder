package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.THEME_PROPERTIES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Every number a catalog declares can be edited in the inspector.
 *
 * A number with no editor is shown and then refused with "cannot be safely edited from its catalog
 * metadata" — a Wear slider nobody could move, a Column whose weight nobody could set. Editors are
 * catalog metadata the builder adds by rule and by override, and the next number a catalog declares
 * should fail here rather than wait for someone to try editing it.
 *
 * `a2ui-catalog` is left out: its numbers are dynamic values the A2UI projection owns. The theme
 * properties are edited in the Theme panel and hidden from the property list on purpose.
 */
class NumberEditorsTest {
  @Test
  fun `every number in the builder's catalogs has an editor`() {
    val missing =
      listOf("m3-catalog", "wear-m3", "remote-m3").flatMap { systemId ->
        val catalog =
          CapabilityCatalogParser.parse(
            checkNotNull(javaClass.getResource("/$systemId-capabilities-v1.json")).readText()
          )
        catalog.components.flatMap { component ->
          component.properties
            .filter { it.name !in THEME_PROPERTIES && it.isNumber() && it.editor == null }
            .map { "${component.componentId}.${it.name}" }
            .filter { it !in NOT_YET_DRAWN }
            .map { "$systemId $it" }
        }
      }

    assertEquals(emptyList(), missing, "numbers with no editor")
  }

  private companion object {
    /**
     * Numbers nothing on the canvas or in the Compose export reads yet, so an editor for them would
     * accept values the preview and the exported source both discard. Material Symbols' variable
     * axes feed only the runtime's outline registry: `BuilderIcon` and the emitter draw from
     * `iconKey`. They get editors in the change that teaches those two to use the outline.
     */
    val NOT_YET_DRAWN =
      setOf(
        "m3/icon.iconFill",
        "m3/icon.iconWeight",
        "m3/icon.iconGrade",
        "m3/icon.iconOpticalSize",
      )
  }

  private fun ee.schimke.composeai.uibuilder.capability.PropertyCapability.isNumber(): Boolean {
    val types =
      when (val type = jsonType) {
        is JsonPrimitive -> setOfNotNull(type.contentOrNull)
        is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
        else -> emptySet()
      } - "object" - "null"
    return types.isNotEmpty() && types.all { it == "number" || it == "integer" }
  }
}
