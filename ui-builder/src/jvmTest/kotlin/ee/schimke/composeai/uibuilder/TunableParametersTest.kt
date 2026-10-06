package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.DesignTunable
import ee.schimke.composeai.uibuilder.editor.EditorNumberBounds
import ee.schimke.composeai.uibuilder.editor.MAX_DESIGN_TUNABLES
import ee.schimke.composeai.uibuilder.editor.TunableTarget
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorState
import ee.schimke.composeai.uibuilder.editor.edited
import ee.schimke.composeai.uibuilder.editor.formatTunableValue
import ee.schimke.composeai.uibuilder.editor.heldValue
import ee.schimke.composeai.uibuilder.editor.isTuned
import ee.schimke.composeai.uibuilder.editor.suggestedTunableRange
import ee.schimke.composeai.uibuilder.editor.tuned
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Tunables: named numbers with a range that drive numeric properties while their slider moves.
 *
 * The rule every test here leans on is that dragging is a way of *looking* — the document, its
 * revision and the undo log stay exactly where they were until Apply, and Apply is one edit.
 */
class TunableParametersTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  private val spacing = TunableTarget.Property("main-episode-column", "verticalSpacingDp")
  private val androidCardWidth =
    TunableTarget.Modifier("podcast-card-android", 0, "size", "widthDp")
  private val googleCardWidth = TunableTarget.Modifier("podcast-card-google", 0, "size", "widthDp")

  private fun UiBuilderEditorState.on(vararg events: UiBuilderEditorEvent): UiBuilderEditorState =
    events.fold(this, reducer::reduce)

  @Test
  fun `tuning a property seeds a tunable from its value and a range around it`() {
    val state = reducer.initial(document).on(UiBuilderEditorEvent.TuneTarget(spacing))

    val tunable = state.tunables.single()
    assertTrue(tunable.name.isNotBlank(), "named after the field")
    assertEquals(8.0, tunable.default)
    assertTrue(tunable.minimum <= 8.0 && tunable.maximum > 8.0, "range $tunable holds the value")
    assertEquals(listOf<TunableTarget>(spacing), tunable.targets)
    assertSame(document, state.document, "making a tunable is not an edit")
  }

  @Test
  fun `dragging redraws the tuned document and leaves the stored one alone`() {
    val state =
      reducer.initial(document).on(UiBuilderEditorEvent.TuneTarget(spacing)).let {
        it.on(UiBuilderEditorEvent.SetTunedValue(it.tunables.single().name, 20.0))
      }

    assertEquals(document.revision, state.document.revision)
    assertEquals(8.0, state.document.heldValue(spacing))
    val drawn = state.document.tuned(state.tunables, state.tunedValues)
    assertEquals(20.0, drawn.heldValue(spacing))
    assertEquals(
      JsonPrimitive("float"),
      (drawn.nodes.getValue("main-episode-column").properties["verticalSpacingDp"] as JsonObject)[
        "type"],
      "the target keeps its own wrapper",
    )
    assertTrue(state.document.isTuned(state.tunables, state.tunedValues))
    assertNull(reducer.acceptedSubmission(reducer.initial(document), state), "nothing to send")
  }

  @Test
  fun `one tunable drives several targets, including modifier fields`() {
    var state = reducer.initial(document).on(UiBuilderEditorEvent.TuneTarget(androidCardWidth))
    val name = state.tunables.single().name
    state =
      state.on(
        UiBuilderEditorEvent.TuneTarget(googleCardWidth, into = name),
        UiBuilderEditorEvent.SetTunedValue(name, 160.0),
      )

    val drawn = state.document.tuned(state.tunables, state.tunedValues)
    assertEquals(160.0, drawn.heldValue(androidCardWidth))
    assertEquals(160.0, drawn.heldValue(googleCardWidth))
    assertEquals(
      """{"type":"size","widthDp":160,"heightDp":128}""",
      drawn.nodes.getValue("podcast-card-google").modifiers[0].toString(),
      "whole numbers stay whole, and the rest of the modifier is carried",
    )
  }

  @Test
  fun `a slider is held inside its range`() {
    val state =
      reducer.initial(document).on(UiBuilderEditorEvent.TuneTarget(spacing)).let {
        it.on(UiBuilderEditorEvent.SetTunedValue(it.tunables.single().name, 1e9))
      }

    assertEquals(state.tunables.single().maximum, state.tunedValues.values.single())
  }

  @Test
  fun `apply writes every tuned value, one edit per lane, and makes it the default`() {
    var state = reducer.initial(document).on(UiBuilderEditorEvent.TuneTarget(spacing))
    val spacingName = state.tunables.single().name
    state = state.on(UiBuilderEditorEvent.TuneTarget(androidCardWidth))
    val widthName = state.tunables.last().name
    state =
      state.on(
        UiBuilderEditorEvent.TuneTarget(googleCardWidth, into = widthName),
        UiBuilderEditorEvent.SetTunedValue(spacingName, 12.0),
        UiBuilderEditorEvent.SetTunedValue(widthName, 150.0),
        UiBuilderEditorEvent.ApplyTunables,
      )

    assertIs<CommandOutcome.Accepted>(state.lastOutcome)
    // Undo compensates property writes and modifier writes in separate lanes, and refuses a batch
    // that mixes them, so a configuration touching both is two commands.
    assertEquals(document.revision + 2, state.document.revision)
    assertEquals(12.0, state.document.heldValue(spacing))
    assertEquals(150.0, state.document.heldValue(androidCardWidth))
    assertEquals(150.0, state.document.heldValue(googleCardWidth))
    assertEquals(emptyMap(), state.tunedValues)
    assertEquals(listOf(12.0, 150.0), state.tunables.map(DesignTunable::default))
    assertFalse(state.document.isTuned(state.tunables, state.tunedValues))

    val undone = state.on(UiBuilderEditorEvent.Undo, UiBuilderEditorEvent.Undo)
    assertIs<CommandOutcome.Accepted>(undone.lastOutcome, "${undone.lastOutcome}")
    assertEquals(8.0, undone.document.heldValue(spacing))
    assertEquals(128.0, undone.document.heldValue(androidCardWidth))
    assertEquals(128.0, undone.document.heldValue(googleCardWidth))
  }

  @Test
  fun `applying only properties is a single undoable edit`() {
    val state =
      reducer.initial(document).on(UiBuilderEditorEvent.TuneTarget(spacing)).let {
        it.on(
          UiBuilderEditorEvent.SetTunedValue(it.tunables.single().name, 14.0),
          UiBuilderEditorEvent.ApplyTunables,
        )
      }
    assertEquals(document.revision + 1, state.document.revision)
    val undone = state.on(UiBuilderEditorEvent.Undo)
    assertIs<CommandOutcome.Accepted>(undone.lastOutcome, "${undone.lastOutcome}")
    assertEquals(8.0, undone.document.heldValue(spacing))
  }

  @Test
  fun `a target follows one tunable at a time`() {
    var state = reducer.initial(document).on(UiBuilderEditorEvent.TuneTarget(androidCardWidth))
    state = state.on(UiBuilderEditorEvent.TuneTarget(googleCardWidth))
    val first = state.tunables.first().name
    state = state.on(UiBuilderEditorEvent.TuneTarget(googleCardWidth, into = first))

    assertEquals(listOf(androidCardWidth, googleCardWidth), state.tunables.first().targets)
    assertEquals(emptyList(), state.tunables.last().targets)
  }

  @Test
  fun `what is not a plain number is refused`() {
    val bound =
      reducer
        .initial(document)
        .on(
          UiBuilderEditorEvent.TuneTarget(
            TunableTarget.Modifier("podcast-card-android", 1, "clip", "shape")
          )
        )
    assertIs<CommandOutcome.Rejected>(bound.lastOutcome)
    assertEquals(emptyList(), bound.tunables)

    val moved =
      reducer
        .initial(document)
        .on(
          UiBuilderEditorEvent.TuneTarget(
            TunableTarget.Modifier("podcast-card-android", 1, "size", "widthDp")
          )
        )
    assertIs<CommandOutcome.Rejected>(moved.lastOutcome, "index 1 is the clip, not a size")
  }

  @Test
  fun `a design is offered a handful of tunables`() {
    val fields =
      document.nodes.values
        .flatMap { node ->
          node.properties.entries
            .filter { (_, value) -> (value as? JsonObject)?.get("type") == JsonPrimitive("float") }
            .map { TunableTarget.Property(node.id, it.key) }
        }
        .take(MAX_DESIGN_TUNABLES + 1)
    val state =
      fields.fold(reducer.initial(document)) { acc, target ->
        reducer.reduce(acc, UiBuilderEditorEvent.TuneTarget(target))
      }
    assertEquals(MAX_DESIGN_TUNABLES, state.tunables.size)
    assertIs<CommandOutcome.Rejected>(state.lastOutcome)
  }

  @Test
  fun `tunables survive a document arriving, minus targets it no longer holds`() {
    val state =
      reducer
        .initial(document)
        .on(
          UiBuilderEditorEvent.TuneTarget(spacing),
          UiBuilderEditorEvent.TuneTarget(androidCardWidth),
        )
    val withoutCard =
      document.copy(
        revision = document.revision + 1,
        nodes = document.nodes - "podcast-card-android",
      )

    val reconciled = reducer.reconciled(state, withoutCard)
    assertEquals(
      listOf(listOf<TunableTarget>(spacing), emptyList()),
      reconciled.tunables.map { it.targets },
    )

    val elsewhere = reducer.reconciled(state, document.copy(id = "another-design"))
    assertEquals(emptyList(), elsewhere.tunables, "a node id in another design is another node")
  }

  @Test
  fun `editing a tunable renames its slider and keeps it in range`() {
    var state = reducer.initial(document).on(UiBuilderEditorEvent.TuneTarget(spacing))
    val tunable = state.tunables.single()
    state = state.on(UiBuilderEditorEvent.SetTunedValue(tunable.name, 20.0))
    val edited = tunable.edited("Gap", "0", "16", "4", taken = emptyList()).getOrThrow()
    state = state.on(UiBuilderEditorEvent.EditTunable(tunable.name, edited))

    assertEquals(listOf("Gap"), state.tunables.map(DesignTunable::name))
    assertEquals(mapOf("Gap" to 16.0), state.tunedValues)
    assertTrue(tunable.edited("Gap", "10", "4", "5", emptyList()).isFailure, "min below max")
    assertTrue(tunable.edited("Gap", "0", "4", "5", emptyList()).isFailure, "default in range")
    assertTrue(tunable.edited("Other", "0", "4", "2", listOf("Other")).isFailure, "unique names")
  }

  @Test
  fun `no tunables draws the very same document`() {
    assertSame(document, document.tuned(emptyList(), emptyMap()))
  }

  @Test
  fun `a slider lands on a step that suits its range`() {
    val dp = DesignTunable("Width", minimum = 0.0, maximum = 384.0, default = 128.0)
    assertEquals(254.0, dp.coerce(254.11764526367188))
    val weight = DesignTunable("Weight", minimum = 0.0, maximum = 1.0, default = 0.5)
    assertEquals(0.33, weight.coerce(0.3333333))
    val small = DesignTunable("Ratio", minimum = 0.5, maximum = 4.0, default = 1.0)
    assertEquals(1.3, small.coerce(1.2999999523))
    assertEquals(small.default, small.coerce(Double.NaN))
  }

  @Test
  fun `a float property is written as a float`() {
    val state =
      reducer.initial(document).on(UiBuilderEditorEvent.TuneTarget(spacing)).let {
        it.on(
          UiBuilderEditorEvent.SetTunedValue(it.tunables.single().name, 12.4),
          UiBuilderEditorEvent.ApplyTunables,
        )
      }
    assertEquals(
      """{"type":"float","value":12.0}""",
      state.document.nodes
        .getValue("main-episode-column")
        .properties["verticalSpacingDp"]
        .toString(),
    )
  }

  @Test
  fun `a suggested range has whole ends around the value, inside the catalog's bounds`() {
    val bounds = EditorNumberBounds(minimum = 0.0, maximum = 4096.0, step = 1.0, integer = false)
    assertEquals(0.0 to 763.0, suggestedTunableRange(254.1, bounds))
    assertEquals(0.0 to 48.0, suggestedTunableRange(16.0, bounds))
    assertEquals(0.0 to 24.0, suggestedTunableRange(0.0, bounds), "zero still gets room")
    val tight = EditorNumberBounds(minimum = 0.0, maximum = 1.0, step = 0.01, integer = false)
    assertEquals(0.0 to 1.0, suggestedTunableRange(0.15, tight))
  }

  @Test
  fun `values read as short labels`() {
    assertEquals("16", formatTunableValue(16.0))
    assertEquals("0.5", formatTunableValue(0.5))
    assertEquals("-1.25", formatTunableValue(-1.25))
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
