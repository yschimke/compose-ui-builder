package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Wear's numbers (Slider, Stepper, progress ring, Text size) are editable where their Material 3
 * counterparts are, though the editor overrides are keyed on `m3/…` ids.
 */
class WearNumberEditorsTest {
  private val catalog = SyntheticCatalogs.wear

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

  @Test
  fun `a Wear slider's steps are a count, so a fraction is refused`() {
    val reducer = UiBuilderEditorReducer(catalog)
    val document = assertNotNull(reducer.previewDocument("wear-m3/slider"))
    val slider = document.nodes.values.single { it.componentId == "wear-m3/slider" }
    val state = reducer.initial(document, selectedNodeId = slider.id)

    val refused =
      reducer.reduce(state, UiBuilderEditorEvent.CommitProperty(slider.id, "steps", "3.5"))
    assertEquals(state.document, refused.document, "3.5 steps was accepted")

    val counted =
      reducer.reduce(state, UiBuilderEditorEvent.CommitProperty(slider.id, "steps", "3"))
    val steps = counted.document.nodes.getValue(slider.id).properties["steps"] as? JsonObject
    assertEquals("3", (steps?.get("value") as? JsonPrimitive)?.content)
  }
}
