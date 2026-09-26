package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Wear's numbers are editable where their Material 3 counterparts are.
 *
 * The inspector's number editors are catalog metadata keyed on component ids, and every one was
 * keyed on an `m3/…` id. So a Wear Slider showed its value and refused every edit to it with
 * "cannot be safely edited from its catalog metadata", and so did its Stepper, its progress ring
 * and the size of its Text.
 */
class WearNumberEditorsTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/wear-m3-capabilities-v1.json"))

  @Test
  fun `every Wear slider, stepper, progress and text number has an editor`() {
    val properties =
      mapOf(
        "wear-m3/slider" to listOf("value", "valueFrom", "valueTo", "steps"),
        "wear-m3/stepper" to listOf("value", "valueFrom", "valueTo", "steps"),
        "wear-m3/progress-indicator" to listOf("progress", "segments"),
        "wear-m3/text" to
          listOf("fontSizeSp", "lineHeightSp", "letterSpacingSp", "minLines", "maxLines"),
      )
    val missing = properties.flatMap { (componentId, names) ->
      val component = assertNotNull(catalog.componentsById[componentId], componentId)
      names.filter { component.propertiesByName[it]?.editor == null }.map { "$componentId.$it" }
    }

    assertEquals(emptyList(), missing, "Wear numbers with no editor")
  }

  @Test
  fun `a Wear slider's range can be set`() {
    val reducer = UiBuilderEditorReducer(catalog)
    val document = assertNotNull(reducer.previewDocument("wear-m3/slider"))
    val slider = document.nodes.values.single { it.componentId == "wear-m3/slider" }
    val state = reducer.initial(document, selectedNodeId = slider.id)

    val edited =
      reducer.reduce(state, UiBuilderEditorEvent.CommitProperty(slider.id, "valueTo", "2"))

    val valueTo = edited.document.nodes.getValue(slider.id).properties["valueTo"] as? JsonObject
    assertEquals(2.0, (valueTo?.get("value") as? JsonPrimitive)?.content?.toDouble())
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
