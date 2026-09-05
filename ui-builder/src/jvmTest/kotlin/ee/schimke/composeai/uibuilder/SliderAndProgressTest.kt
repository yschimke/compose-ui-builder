package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `m3/slider` and `m3/progress-indicator`: a value you drag and a value you watch.
 *
 * The pair is the decimal half of what the selection controls did for flags. A slider writes a
 * declared decimal variable and a progress indicator reads one, so between them they cover both
 * directions of the same seam — and the indicator adds the one thing neither of the others has, an
 * *indeterminate* state that is a second Material overload rather than a value.
 */
class SliderAndProgressTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  @Test
  fun `both arrive showing a value rather than an empty track`() {
    val (slider, sliderId) = insert("m3/slider")
    val (progress, progressId) = insert("m3/progress-indicator")

    assertEquals("0.5", slider.document.nodes.getValue(sliderId).stringProperty("value"))
    assertEquals("0.6", progress.document.nodes.getValue(progressId).stringProperty("progress"))
    assertEquals("linear", progress.document.nodes.getValue(progressId).stringProperty("variant"))
    assertEquals(emptyList(), CapabilityValidator(catalog).validate(slider.document).issues)
    assertEquals(emptyList(), CapabilityValidator(catalog).validate(progress.document).issues)
  }

  /**
   * The inspector has to be able to author these, which is not automatic: the catalog gives a
   * number an editor from a `…Dp` name, and none of a slider's numbers is a dimension. Without the
   * overrides they were all `Unsupported` — a slider whose ends nobody can set.
   */
  @Test
  fun `the inspector offers bounded fields for the range, the steps and the fraction`() {
    val (slider, _) = insert("m3/slider")
    val sliderFields = reducer.propertyFields(slider).associateBy { it.name }
    val (progress, _) = insert("m3/progress-indicator")
    val progressFields = reducer.propertyFields(progress).associateBy { it.name }

    listOf("value", "valueFrom", "valueTo", "steps").forEach {
      assertEquals(EditorPropertyControl.Number, assertNotNull(sliderFields[it]).control, it)
    }
    assertEquals(0.0, assertNotNull(sliderFields["steps"]).numberBounds?.minimum)
    assertEquals(
      EditorPropertyControl.Number,
      assertNotNull(progressFields["progress"]).control,
    )
    assertEquals(1.0, assertNotNull(progressFields["progress"]).numberBounds?.maximum)
  }

  @Test
  fun `a slider exports its range, its steps and the value it holds`() {
    val source = exportOf("m3/slider", "valueTo" to "10", "steps" to "4")

    assertTrue("Slider(" in source, source)
    assertTrue("value = 0.5f," in source, source)
    assertTrue("valueRange = 0f..10f," in source, source)
    assertTrue("steps = 4," in source, source)
    // Unbound, so the handler writes nowhere — the same rule a text field follows, and for the same
    // reason: a generated slider that moved its own private value would not be this design.
    assertTrue("onValueChange = { Unit }," in source, source)
  }

  @Test
  fun `the variant picks between the linear and the circular indicator`() {
    assertTrue("LinearProgressIndicator(progress = { 0.6f }" in exportOf("m3/progress-indicator"))
    assertTrue(
      "CircularProgressIndicator(progress = { 0.6f }" in
        exportOf("m3/progress-indicator", "variant" to "circular")
    )
  }

  /**
   * Material's indeterminate indicator is the overload you call *without* a progress lambda, so the
   * flag has to pick the call rather than a value. Passing `progress = { … }` and animating anyway
   * is not a thing the API offers.
   */
  @Test
  fun `an indeterminate indicator exports as the overload that takes no progress`() {
    val source = exportOf("m3/progress-indicator", "indeterminate" to "true")

    assertTrue("LinearProgressIndicator(modifier = Modifier)" in source, source)
    assertTrue("progress = {" !in source, source)
  }

  private fun exportOf(componentId: String, vararg properties: Pair<String, String>): String {
    val (state, nodeId) = insert(componentId)
    val edited =
      properties.fold(state) { acc, (name, value) ->
        reducer.reduce(acc, UiBuilderEditorEvent.CommitProperty(nodeId, name, value))
      }
    return CapabilityComposeCodeExporter.export(edited.document, catalog).requireSource()
  }

  private fun insert(componentId: String): Pair<UiBuilderEditorState, String> {
    val initial = reducer.initial(document, selectedNodeId = "main-background")
    val target = requireNotNull(reducer.dropTarget(initial, componentId)) { componentId }
    val inserted =
      reducer.reduce(initial, UiBuilderEditorEvent.InsertComponent(componentId, target))
    assertIs<CommandOutcome.Accepted>(inserted.lastOutcome, "$componentId: ${inserted.lastOutcome}")
    return inserted to assertNotNull(inserted.selectedNodeId)
  }

  private fun UiBuilderNode.stringProperty(name: String): String =
    (properties.getValue(name) as JsonObject).getValue("value").jsonPrimitive.content

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
