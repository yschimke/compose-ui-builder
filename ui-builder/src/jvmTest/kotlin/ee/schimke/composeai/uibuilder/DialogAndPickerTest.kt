package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import ee.schimke.composeai.uibuilder.capability.PropertyEditorControl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The dialog and the two pickers: what they arrive holding, and what they generate.
 *
 * The three share one property the rest of the catalog does not have to think about — **a render
 * must not depend on when it happens**. A date picker opens on the current month and rings today's
 * cell, and a time picker's state defaults to the current time, so all three of the golden lanes
 * (the committed renders, the visual diff, the SVG export) would see a component that changed by
 * itself. Every test here that pins a date or an hour is testing that, not a preference about which
 * day to show.
 */
class DialogAndPickerTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  @Test
  fun `a dialog arrives with a title, supporting text and both actions`() {
    val inserted = insert("m3/dialog")
    val dialog = inserted.first.document.nodes.getValue(inserted.second)

    assertEquals(
      "Dialog title",
      inserted.text(dialog.slots.getValue("title").single()),
    )
    assertEquals(
      "Supporting text explaining what this dialog is asking.",
      inserted.text(dialog.slots.getValue("text").single()),
    )
    listOf("dismissButton" to "Cancel", "confirmButton" to "OK").forEach { (slot, label) ->
      val button = inserted.first.document.nodes.getValue(dialog.slots.getValue(slot).single())
      assertEquals("m3/button", button.componentId)
      // Material's dialog actions are text buttons, and a filled OK beside a filled Cancel is the
      // one shape the spec is explicit about not being a dialog.
      assertEquals("text", button.stringProperty("style"))
      assertEquals(label, inserted.text(button.slots.getValue("content").single()))
    }
    assertTrue(CapabilityValidator(catalog).validate(inserted.first.document).issues.isEmpty())
  }

  @Test
  fun `a picker arrives on a pinned day and time rather than on today`() {
    val date = insert("m3/date-picker")
    val time = insert("m3/time-picker")

    assertEquals(
      DEFAULT_SELECTED_DATE,
      date.first.document.nodes.getValue(date.second).stringProperty("selectedDate"),
    )
    val clock = time.first.document.nodes.getValue(time.second)
    assertEquals(DEFAULT_PICKED_HOUR.toString(), clock.stringProperty("hour"))
    assertEquals(DEFAULT_PICKED_MINUTE.toString(), clock.stringProperty("minute"))
    assertEquals("dial", clock.stringProperty("mode"))
  }

  /** Two numbers with no editor is a time picker nobody can set the time on. */
  @Test
  fun `the inspector offers bounded number fields for the hour and the minute`() {
    val inserted = insert("m3/time-picker")
    val fields = reducer.propertyFields(inserted.first).associateBy { it.name }

    assertEquals(EditorPropertyControl.Number, assertNotNull(fields["hour"]).control)
    assertEquals(0.0, assertNotNull(fields["hour"]).numberBounds?.minimum)
    assertEquals(23.0, assertNotNull(fields["hour"]).numberBounds?.maximum)
    assertEquals(59.0, assertNotNull(fields["minute"]).numberBounds?.maximum)
    assertEquals(EditorPropertyControl.Enum, assertNotNull(fields["mode"]).control)
    assertEquals(
      PropertyEditorControl.NUMBER,
      catalog.componentsById
        .getValue("m3/time-picker")
        .propertiesByName
        .getValue("hour")
        .editor
        ?.control,
    )
  }

  @Test
  fun `a dialog exports as the surface the canvas draws, with both buttons in Material order`() {
    val source = exportOf("m3/dialog")

    assertTrue("BuilderDialogSurface(" in source, source)
    assertTrue("hasTitle = true" in source, source)
    // Dismiss before confirm, which is the order both the canvas and the Material spec put them in.
    assertTrue(source.indexOf("\"Cancel\"") < source.indexOf("\"OK\""), source)
    // The helper block is a declared export diagnostic rather than a claim of API parity, and the
    // reason it exists at all is that `AlertDialog` needs an `onDismissRequest` a design has no way
    // to write. Emitting the real dialog would put an undismissable modal over the exported screen.
    assertTrue("AlertDialog(" !in source, source)
  }

  @Test
  fun `an exported picker carries the document's own date and time, never the clock`() {
    val date = exportOf("m3/date-picker")
    val time = exportOf("m3/time-picker")

    val millis = assertNotNull(isoDateToEpochMillis(DEFAULT_SELECTED_DATE))
    assertTrue("initialSelectedDateMillis = ${millis}L" in date, date)
    // The month the calendar opens on is the other half: left out, Material reads the system clock
    // and the generated screen drifts from the design's render the day the month turns over.
    assertTrue("initialDisplayedMonthMillis = ${millis}L" in date, date)
    assertTrue("initialDisplayMode = DisplayMode.Picker" in date, date)
    assertTrue(
      "rememberTimePickerState(initialHour = $DEFAULT_PICKED_HOUR, initialMinute = $DEFAULT_PICKED_MINUTE" in
        time,
      time,
    )
    assertTrue("TimePicker(" in time, time)
  }

  @Test
  fun `the typed modes export as Material's own input components`() {
    val date = exportOf("m3/date-picker", "mode" to "input")
    val time = exportOf("m3/time-picker", "mode" to "input")

    assertTrue("initialDisplayMode = DisplayMode.Input" in date, date)
    // `TimeInput`, not `TimePicker` with a flag: they are two composables in Material and the
    // canvas picks between them the same way.
    assertTrue("TimeInput(" in time, time)
    assertTrue("TimePicker(" !in time, time)
  }

  @Test
  fun `an ISO date becomes the epoch millisecond the calendar opens on`() {
    // Epoch itself, a leap day, and a date after one — the three cases a civil-date conversion gets
    // wrong when its month arithmetic special-cases February.
    assertEquals(0L, isoDateToEpochMillis("1970-01-01"))
    assertEquals(951_782_400_000L, isoDateToEpochMillis("2000-02-29"))
    assertEquals(1_709_164_800_000L, isoDateToEpochMillis("2024-02-29"))
    assertEquals(1_715_817_600_000L, isoDateToEpochMillis("2024-05-16"))
    assertNull(isoDateToEpochMillis("16/05/2024"))
    assertNull(isoDateToEpochMillis("2024-13-01"))
    assertNull(isoDateToEpochMillis(""))
  }

  private fun exportOf(componentId: String, vararg properties: Pair<String, String>): String {
    val inserted = insert(componentId)
    val edited =
      properties.fold(inserted.first) { state, (name, value) ->
        reducer.reduce(state, UiBuilderEditorEvent.CommitProperty(inserted.second, name, value))
      }
    // The whole design, with the new node inside it, rather than the node re-rooted: the export
    // validates the graph, and a document whose root is a node that is also somebody's child is a
    // document it correctly refuses.
    return CapabilityComposeCodeExporter.export(edited.document, catalog).requireSource()
  }

  private fun Pair<UiBuilderEditorState, String>.text(nodeId: String): String =
    first.document.nodes.getValue(nodeId).stringProperty("text")

  private fun insert(componentId: String): Pair<UiBuilderEditorState, String> {
    val initial = reducer.initial(document, selectedNodeId = "main-background")
    val target = requireNotNull(reducer.dropTarget(initial, componentId)) { componentId }
    val inserted =
      reducer.reduce(initial, UiBuilderEditorEvent.InsertComponent(componentId, target))
    assertIs<CommandOutcome.Accepted>(inserted.lastOutcome, inserted.lastOutcome.toString())
    return inserted to assertNotNull(inserted.selectedNodeId)
  }

  private fun UiBuilderNode.stringProperty(name: String): String =
    (properties.getValue(name) as JsonObject).getValue("value").jsonPrimitive.content

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
