package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Undo and redo walk a whole history, not one step of it.
 *
 * Undo used to work exactly once. The second press returned the state unchanged with
 * `UNSAFE_COMPENSATION` — while `canUndo` kept reporting true, so the toolbar button stayed lit and
 * did nothing. The cause: an undo stamped its own revision onto every address it restored, so the
 * next undo compared that stamp against the older command's `committedRevision`, saw a mismatch,
 * and read its own predecessor as a foreign write.
 */
class MultiLevelUndoTest {
  private val catalog =
    CapabilityCatalogParser.parse(resource("/jetcaster-discover-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  private val titleId = "main-episode-title"
  private val originalTitle = "Episode 140: Lorem ipsum dolor"

  @Test
  fun `three property edits undo one at a time, all the way back`() {
    var state = reducer.initial(document, selectedNodeId = titleId)
    assertEquals(originalTitle, state.title())

    listOf("one", "two", "three").forEach { value ->
      state = reducer.reduce(state, UiBuilderEditorEvent.CommitProperty(titleId, "text", value))
    }
    assertEquals("three", state.title())

    listOf("two", "one", originalTitle).forEach { expected ->
      state = reducer.reduce(state, UiBuilderEditorEvent.Undo)
      assertIs<CommandOutcome.Accepted>(state.lastOutcome, "undo back to '$expected'")
      assertEquals(expected, state.title())
    }
    // The history is spent, and the editor now says so rather than offering a button that no-ops.
    assertFalse(reducer.canUndo(state))
  }

  @Test
  fun `redo walks the same history forward, and undo can walk it back again`() {
    var state = reducer.initial(document, selectedNodeId = titleId)
    listOf("one", "two", "three").forEach { value ->
      state = reducer.reduce(state, UiBuilderEditorEvent.CommitProperty(titleId, "text", value))
    }
    repeat(3) { state = reducer.reduce(state, UiBuilderEditorEvent.Undo) }
    assertEquals(originalTitle, state.title())

    listOf("one", "two", "three").forEach { expected ->
      state = reducer.reduce(state, UiBuilderEditorEvent.Redo)
      assertIs<CommandOutcome.Accepted>(state.lastOutcome, "redo forward to '$expected'")
      assertEquals(expected, state.title())
    }

    // And back down again — a redone operation is undoable like any other.
    listOf("two", "one", originalTitle).forEach { expected ->
      state = reducer.reduce(state, UiBuilderEditorEvent.Undo)
      assertIs<CommandOutcome.Accepted>(state.lastOutcome, "second pass back to '$expected'")
      assertEquals(expected, state.title())
    }
  }

  @Test
  fun `switching phone to foldable to tablet leaves one undo per device`() {
    // The environment lane, and the reason this was worth chasing: the device-preset menu is three
    // clicks and an undo, and the undo used to strand a phone-width tablet on the canvas.
    val phone = UiBuilderDevicePreset("id:pixel_7", "Pixel 7", "Phones", 411, 914, 2.625)
    val foldable =
      UiBuilderDevicePreset("id:pixel_fold", "Pixel Fold", "Foldables", 841, 701, 2.625)
    val tablet = UiBuilderDevicePreset("id:pixel_tablet", "Pixel Tablet", "Tablets", 1280, 800, 2.0)

    var state = reducer.initial(document, selectedNodeId = titleId)
    val original = state.document.screenEnvironmentSettings()
    listOf(phone, foldable, tablet).forEach { preset ->
      state =
        reducer.reduce(
          state,
          UiBuilderEditorEvent.UpdateEnvironment(
            state.document.screenEnvironmentSettings().withDevicePreset(preset)
          ),
        )
    }
    assertEquals(tablet.frame(), state.frame())

    listOf(foldable.frame(), phone.frame(), original.frame()).forEach { expected ->
      state = reducer.reduce(state, UiBuilderEditorEvent.Undo)
      assertIs<CommandOutcome.Accepted>(state.lastOutcome, "undo back to $expected")
      // All three fields together, every step — including density, which two of the presets share
      // and one does not.
      assertEquals(expected, state.frame())
    }
  }

  @Test
  fun `a collaborator's write still blocks the undo that would clobber it`() {
    // The check the fix must not have loosened. `CollaborationConvergenceTest` covers this at the
    // reducer; asserted here too because the rewrite moved the revision this guard reads.
    val mine = UiBuilderEditorReducer(catalog, actorId = "github:alice", clientId = "tab-a")
    val theirs = UiBuilderEditorReducer(catalog, actorId = "github:bob", clientId = "tab-b")

    var state = mine.initial(document, selectedNodeId = titleId)
    state = mine.reduce(state, UiBuilderEditorEvent.CommitProperty(titleId, "text", "mine"))
    state = theirs.reduce(state, UiBuilderEditorEvent.CommitProperty(titleId, "text", "theirs"))

    val refused = mine.reduce(state, UiBuilderEditorEvent.Undo)
    val outcome = assertIs<CommandOutcome.Rejected>(refused.lastOutcome)
    assertEquals(RejectionCode.UNSAFE_COMPENSATION, outcome.code)
    assertEquals("theirs", refused.title())
  }

  @Test
  fun `undoing does not disturb the nodes an edit never touched`() {
    var state = reducer.initial(document, selectedNodeId = titleId)
    val originalNodes = state.document.nodes
    listOf("one", "two").forEach { value ->
      state = reducer.reduce(state, UiBuilderEditorEvent.CommitProperty(titleId, "text", value))
    }
    repeat(2) { state = reducer.reduce(state, UiBuilderEditorEvent.Undo) }

    assertEquals(originalNodes, state.document.nodes)
    assertTrue(state.document.revision > document.revision, "history still advances the revision")
  }

  private fun UiBuilderEditorState.title(): String =
    document.nodes
      .getValue(titleId)
      .properties
      .getValue("text")
      .jsonObject
      .getValue("value")
      .jsonPrimitive
      .content

  private fun UiBuilderEditorState.frame(): Triple<Int, Int, Double> =
    document.screenEnvironmentSettings().frame()

  private fun ScreenEnvironmentSettings.frame(): Triple<Int, Int, Double> =
    Triple(widthDp, heightDp, density)

  private fun UiBuilderDevicePreset.frame(): Triple<Int, Int, Double> =
    Triple(widthDp, heightDp, density)

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
