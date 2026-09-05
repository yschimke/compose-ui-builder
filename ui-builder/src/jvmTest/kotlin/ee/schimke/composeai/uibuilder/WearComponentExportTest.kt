package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The Wear components that have no Material 3 counterpart, and what they generate.
 *
 * ## Why these are worth a test file of their own
 *
 * Every one of them is a component the canvas deliberately does not draw
 * (`docs/design/UI_BUILDER_WEAR_SCREEN.md`: *do not fabricate a component in the Wasm canvas to
 * stand in for a library the canvas cannot link*). So the generated source is not a second opinion
 * about how they look — it is the **only** description of them this repository holds, and the
 * Android render lane's input. A branch that emits a `CheckboxButton` with the wrong argument name
 * fails as a compile error on somebody's host, minutes later, with the design named as the culprit.
 *
 * What is asserted is therefore the call shape rather than the whole file: the composable upstream
 * publishes, the arguments it actually declares, and the import that resolves it.
 */
class WearComponentExportTest {
  private val pin = JsonObject(emptyMap())
  private val environment = JsonObject(emptyMap())

  private fun screenWith(vararg nodes: UiBuilderNode): UiBuilderDocument {
    val base = wearScreenUiBuilderDocument("activity", pin, environment)
    val list = base.nodes.getValue("wear-list")
    return base.copy(
      nodes =
        base.nodes +
          ("wear-list" to list.copy(slots = mapOf("items" to nodes.map { it.id }))) +
          nodes.associateBy { it.id }
    )
  }

  /** The wire's typed-value wrappers, which is how every property reaches an emitter. */
  private fun properties(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
    pairs.forEach { (name, value) ->
      putJsonObject(name) {
        put(
          "type",
          when (value) {
            is Boolean -> "boolean"
            is Number -> "number"
            else -> "string"
          },
        )
        put(
          "value",
          when (value) {
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
          },
        )
      }
    }
  }

  private fun export(document: UiBuilderDocument): String =
    assertIs<WearScreenCodeExporter.Result.Emitted>(WearScreenCodeExporter.export(document)).source

  /**
   * The three selection controls, which are one labelled row with three different controls in it.
   *
   * Also the reason a borrowed `m3/checkbox` was never an option: what upstream publishes is a
   * full-width row with a `label` slot, not a 20dp square, so there was nothing on the mobile side
   * to rename.
   */
  @Test
  fun `a checkbox button generates a labelled CheckboxButton with hoisted state`() {
    val source =
      export(
        screenWith(
          UiBuilderNode(
            id = "notify",
            componentId = WearScreenCodeExporter.CHECKBOX_BUTTON,
            properties =
              properties("label" to "Notifications", "secondaryLabel" to "On", "checked" to true),
          )
        )
      )

    assertTrue("import androidx.wear.compose.material3.CheckboxButton" in source, source)
    assertTrue("CheckboxButton(" in source, source)
    assertTrue("""label = { Text(text = "Notifications") },""" in source, source)
    assertTrue("""secondaryLabel = { Text(text = "On") },""" in source, source)
    // Controlled, not decorative. A generated screen whose checkbox cannot be ticked is a picture,
    // and the hoisted `var` is what the author would have written by hand.
    assertTrue("var notify by remember { mutableStateOf(true) }" in source, source)
    assertTrue("checked = notify," in source, source)
    assertTrue("onCheckedChange = { notify = it }," in source, source)
  }

  /** `RadioButton`'s callback takes no argument, because a radio row selects itself. */
  @Test
  fun `a radio button generates onSelect rather than onCheckedChange`() {
    val source =
      export(
        screenWith(
          UiBuilderNode(
            id = "daily",
            componentId = WearScreenCodeExporter.RADIO_BUTTON,
            properties = properties("label" to "Daily", "selected" to false),
          )
        )
      )

    assertTrue("RadioButton(" in source, source)
    assertTrue("selected = daily," in source, source)
    assertTrue("onSelect = { daily = true }," in source, source)
    assertTrue("onCheckedChange" !in source, source)
  }

  /** A switch is the same row again, and differs only in the composable it names. */
  @Test
  fun `a switch button generates SwitchButton`() {
    val source =
      export(
        screenWith(
          UiBuilderNode(
            id = "wifi",
            componentId = WearScreenCodeExporter.SWITCH_BUTTON,
            properties = properties("label" to "Wi-Fi", "checked" to false),
          )
        )
      )

    assertTrue("import androidx.wear.compose.material3.SwitchButton" in source, source)
    assertTrue("SwitchButton(" in source, source)
    // No `secondaryLabel` argument at all where the design declares none — an empty lambda would
    // draw an empty second line and change the row's height.
    assertTrue("secondaryLabel" !in source, source)
  }

  @Test
  fun `a slider generates a range, its steps and the state it is driven by`() {
    val source =
      export(
        screenWith(
          UiBuilderNode(
            id = "volume",
            componentId = WearScreenCodeExporter.SLIDER,
            properties =
              properties(
                "value" to 3,
                "valueFrom" to 0,
                "valueTo" to 10,
                "steps" to 9,
                "segmented" to "segmented",
              ),
          )
        )
      )

    assertTrue("Slider(" in source, source)
    assertTrue("value = volume," in source, source)
    assertTrue("valueRange = 0f..10f," in source, source)
    assertTrue("steps = 9," in source, source)
    assertTrue("segmented = true," in source, source)
  }

  /** The variant selects the composable, exactly as `m3/button`'s style does on the mobile side. */
  @Test
  fun `an icon button's variant names the composable rather than tinting one`() {
    val source =
      export(
        screenWith(
          UiBuilderNode(
            id = "play",
            componentId = WearScreenCodeExporter.ICON_BUTTON,
            properties = properties("variant" to "filled-tonal"),
            slots = mapOf("content" to listOf("play-icon")),
          ),
          UiBuilderNode(
            id = "play-icon",
            componentId = WearScreenCodeExporter.ICON,
            properties = properties("iconKey" to "playCircle"),
          ),
        )
      )

    assertTrue("import androidx.wear.compose.material3.FilledTonalIconButton" in source, source)
    assertTrue("FilledTonalIconButton(" in source, source)
    // The vector is `androidx.compose.material.icons`, which both platforms share, and it resolves
    // through an *extension property* import rather than a longer qualified path.
    assertTrue("import androidx.compose.material.icons.filled.PlayCircle" in source, source)
    assertTrue("imageVector = Icons.Filled.PlayCircle," in source, source)
  }

  /** An unknown icon key is refused by name, and lists what it could have been. */
  @Test
  fun `an unknown icon key is refused rather than emitted`() {
    val refused =
      assertIs<WearScreenCodeExporter.Result.Refused>(
        WearScreenCodeExporter.export(
          screenWith(
            UiBuilderNode(
              id = "mystery",
              componentId = WearScreenCodeExporter.ICON,
              properties = properties("iconKey" to "notAnIcon"),
            )
          )
        )
      )

    assertTrue("notAnIcon" in refused.reasons.single(), refused.reasons.single())
  }

  /**
   * A picker owns the round display, so it goes in the scaffold's content slot and not in the list.
   *
   * The generated body has to be the picker itself rather than an `item { … }`: there is no
   * `TransformingLazyColumnScope` in `ScreenScaffold`'s content lambda, so the wrapped form never
   * compiled.
   */
  @Test
  fun `a full-screen picker replaces the list rather than becoming a row of it`() {
    val base = wearScreenUiBuilderDocument("activity", pin, environment)
    val root = base.nodes.getValue(base.roots.single())
    val document =
      base.copy(
        nodes =
          base.nodes +
            (root.id to root.copy(slots = root.slots + ("content" to listOf("when")))) +
            ("when" to
              UiBuilderNode(
                id = "when",
                componentId = WearScreenCodeExporter.TIME_PICKER,
                properties = properties("initialTime" to "07:30", "type" to "hours-minutes-24h"),
              ))
      )

    val source = export(document)

    assertTrue("TimePicker(" in source, source)
    assertTrue("""initialTime = LocalTime.parse("07:30"),""" in source, source)
    assertTrue("timePickerType = TimePickerType.HoursMinutes24H," in source, source)
    assertTrue("item {" !in source, source)
  }

  /**
   * A dialog is a screen state, and lands beside the scaffold rather than inside its content.
   *
   * Put in a list item it would be a full-screen dialog inside a scrolling row, which is why the
   * scaffold grew an `overlays` slot and why the content path refuses one by name.
   */
  @Test
  fun `an alert dialog generates beside the ScreenScaffold, from the overlays slot`() {
    val base = wearScreenUiBuilderDocument("activity", pin, environment)
    val root = base.nodes.getValue(base.roots.single())
    val document =
      base.copy(
        nodes =
          base.nodes +
            (root.id to root.copy(slots = root.slots + ("overlays" to listOf("confirm")))) +
            ("confirm" to
              UiBuilderNode(
                id = "confirm",
                componentId = WearScreenCodeExporter.ALERT_DIALOG,
                properties =
                  properties("title" to "Delete run?", "text" to "This cannot be undone"),
              ))
      )

    val source = export(document)

    assertTrue("import androidx.wear.compose.material3.AlertDialog" in source, source)
    assertTrue("AlertDialog(" in source, source)
    assertTrue("visible = confirm," in source, source)
    assertTrue("""title = { Text(text = "Delete run?") },""" in source, source)
    // Beside the scaffold, inside `AppScaffold`: the dialog's call must start after the
    // `ScreenScaffold` block has closed.
    assertTrue(source.indexOf("AlertDialog(") > source.indexOf("ScreenScaffold("), source)
  }

  @Test
  fun `a dialog placed in the list is refused, and says where it belongs`() {
    val refused =
      assertIs<WearScreenCodeExporter.Result.Refused>(
        WearScreenCodeExporter.export(
          screenWith(
            UiBuilderNode(
              id = "confirm",
              componentId = WearScreenCodeExporter.ALERT_DIALOG,
              properties = properties("title" to "Delete run?"),
            )
          )
        )
      )

    assertTrue("overlays" in refused.reasons.single(), refused.reasons.single())
  }

  /** The edge button is its own component now, and can choose the size upstream publishes. */
  @Test
  fun `an edge button generates its size from the enum rather than in dp`() {
    val base = wearScreenUiBuilderDocument("activity", pin, environment)
    val root = base.nodes.getValue(base.roots.single())
    val document =
      base.copy(
        nodes =
          base.nodes +
            (root.id to root.copy(slots = root.slots + ("edgeButton" to listOf("done")))) +
            ("done" to
              UiBuilderNode(
                id = "done",
                componentId = WearScreenCodeExporter.EDGE_BUTTON,
                properties = properties("size" to "medium"),
                slots = mapOf("content" to listOf("done-label")),
              )) +
            ("done-label" to
              UiBuilderNode(
                id = "done-label",
                componentId = WearScreenCodeExporter.TEXT,
                properties = properties("text" to "Done"),
              ))
      )

    val source = export(document)

    assertTrue("EdgeButton(onClick = {}, buttonSize = EdgeButtonSize.Medium)" in source, source)
    assertTrue("import androidx.wear.compose.material3.EdgeButtonSize" in source, source)
  }

  /**
   * Every id the catalog offers as native-only generates something, or the palette is lying.
   *
   * The pairing this guards is the one that goes wrong silently: a component is added to the
   * catalog, an author drops it in, and the export refuses it by name — a palette entry that cannot
   * be exported. Driven off `NATIVE_ONLY_COMPONENT_IDS` so a new id is in this test the moment it
   * is in the palette.
   */
  @Test
  fun `every native-only component the catalog advertises generates a call site`() {
    val required =
      mapOf(
        WearScreenCodeExporter.ICON to properties("iconKey" to "check"),
        WearScreenCodeExporter.CHECKBOX_BUTTON to properties("label" to "L"),
        WearScreenCodeExporter.SWITCH_BUTTON to properties("label" to "L"),
        WearScreenCodeExporter.RADIO_BUTTON to properties("label" to "L"),
        WearScreenCodeExporter.LIST_SUB_HEADER to properties("text" to "L"),
        WearScreenCodeExporter.ALERT_DIALOG to properties("title" to "L"),
        WearScreenCodeExporter.CONFIRMATION_DIALOG to properties("text" to "L"),
      )
    val base = wearScreenUiBuilderDocument("activity", pin, environment)
    val root = base.nodes.getValue(base.roots.single())
    val list = base.nodes.getValue("wear-list")

    WearScreenCodeExporter.NATIVE_ONLY_COMPONENT_IDS.forEach { componentId ->
      val node =
        UiBuilderNode(
          id = "node",
          componentId = componentId,
          properties = required[componentId] ?: JsonObject(emptyMap()),
        )
      // Three places a component can legally sit, and which one it is is part of what the
      // catalog declares: a dialog is an overlay, the edge button hugs the bottom curve, and
      // everything else is a row of the list.
      val slot =
        when {
          componentId in WearScreenCodeExporter.OVERLAYS -> "overlays"
          componentId == WearScreenCodeExporter.EDGE_BUTTON -> "edgeButton"
          else -> null
        }
      val document =
        base.copy(
          nodes =
            base.nodes +
              (root.id to
                root.copy(
                  slots = root.slots + (slot?.let { mapOf(it to listOf("node")) } ?: emptyMap())
                )) +
              ("wear-list" to
                list.copy(
                  slots = mapOf("items" to if (slot == null) listOf("node") else emptyList())
                )) +
              ("node" to node)
        )

      val result = WearScreenCodeExporter.export(document)
      assertIs<WearScreenCodeExporter.Result.Emitted>(result, "$componentId did not generate")
    }
  }

  /**
   * The generator and the canvas read one list, so neither can grow a component the other lacks.
   */
  @Test
  fun `the native-only set covers every id the branches handle`() {
    assertEquals(
      17,
      WearScreenCodeExporter.NATIVE_ONLY_COMPONENT_IDS.size,
      WearScreenCodeExporter.NATIVE_ONLY_COMPONENT_IDS.sorted().toString(),
    )
  }
}
