package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import ee.schimke.composeai.uibuilder.codegen.validateDocumentForExport
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorState
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import ee.schimke.composeai.uibuilder.export.blankUiBuilderDocument
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Validity survives editing, not just inserting. A seeded random walk of move, delete, duplicate,
 * wrap, unwrap, copy, cut, paste, property writes, undo and redo, since ordinary steps can combine
 * into an invalid state.
 *
 * Two invariants after every event:
 * 1. **The document only changes through an accepted command** (a refused operation must leave
 * nothing behind).
 * 2. **Every document the editor passes through is valid**, against the catalog and the export
 * graph rules.
 *
 * Events ignore the `can…` guards on purpose: those govern what the toolbar offers, but the reducer
 * must hold when asked anyway (stale panels, shortcuts, racing collaborators).
 */
class EditSequenceTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val validator = CapabilityValidator(catalog)
  private val generator = UiBuilderTreeGenerator(catalog, reducer)
  private val jetcaster =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document
  private val blank = blankUiBuilderDocument("edited", jetcaster.catalogPin, jetcaster.environment)

  @Test
  fun `a random edit sequence never leaves the document invalid`() {
    var changed = 0
    var refused = 0
    SEEDS.forEach { seed ->
      val random = Random(seed)
      var state = reducer.initial(generator.generate(seed, 12, blank).document, null)
      assertValid(state.document, "seed $seed: the starting document")

      repeat(EVENTS) { step ->
        val before = state.document
        val event = randomEvent(state, random) ?: return@repeat
        val after = reducer.reduce(state, event)
        val case = "seed $seed step $step: $event"

        if (after.document != before) {
          changed++
          assertTrue(
            after.lastOutcome is CommandOutcome.Accepted,
            "$case changed the document without being accepted: ${after.lastOutcome}",
          )
          assertValid(after.document, case)
        }
        if (after.lastOutcome is CommandOutcome.Rejected) refused++
        state = after
      }
    }

    // A walk that mostly no-ops proves nothing, and would go on passing while the reducer rotted.
    // Both floors matter: edits that land exercise the invariant, refusals exercise the rollback.
    assertTrue(changed > SEEDS.size * 4, "only $changed of ${SEEDS.size * EVENTS} events edited")
    assertTrue(refused > SEEDS.size, "only $refused events were refused")
  }

  /**
   * Undo puts the document's nodes back exactly, whatever the accepted command was. The revision
   * moves on: undo is a new operation.
   */
  @Test
  fun `undo restores the document an accepted command changed`() {
    SEEDS.forEach { seed ->
      val random = Random(seed)
      var state = reducer.initial(generator.generate(seed, 12, blank).document, null)

      repeat(EVENTS) { step ->
        val before = state.document
        val event = randomEvent(state, random) ?: return@repeat
        val applied = reducer.reduce(state, event)
        state = applied
        if (applied.lastOutcome !is CommandOutcome.Accepted || applied.document == before) {
          return@repeat
        }
        if (!reducer.canUndo(applied)) return@repeat

        val undone = reducer.reduce(applied, UiBuilderEditorEvent.Undo)
        if (undone.lastOutcome !is CommandOutcome.Accepted) return@repeat
        assertEquals(
          before.nodes,
          undone.document.nodes,
          "seed $seed step $step: undo of $event did not restore the document",
        )
        assertEquals(before.roots, undone.document.roots, "seed $seed step $step: roots")
        assertValid(undone.document, "seed $seed step $step: after undo")
        state = undone
      }
    }
  }

  /** One event, drawn from what a person can actually do to the current selection. */
  private fun randomEvent(state: UiBuilderEditorState, random: Random): UiBuilderEditorEvent? {
    val document = state.document
    val nodeId = document.nodes.keys.random(random)
    val selected = state.selection.lastOrNull()
    return when (random.nextInt(12)) {
      0 -> UiBuilderEditorEvent.SelectNode(nodeId)
      1 -> {
        val componentId = catalog.components.random(random).componentId
        val target = reducer.dropTarget(state, componentId) ?: return null
        UiBuilderEditorEvent.InsertComponent(componentId, target)
      }
      2 -> UiBuilderEditorEvent.DeleteSelected
      3 -> UiBuilderEditorEvent.DuplicateSelected
      4 -> {
        // A move to a real slot, chosen without asking whether it is a legal one: half the point is
        // that an illegal destination is refused rather than half-applied.
        val parent = document.nodes.values.random(random)
        val slot = parent.slots.keys.randomOrNull(random) ?: return null
        UiBuilderEditorEvent.MoveNodeInto(nodeId, ParentSlot(parent.id, slot))
      }
      5 -> {
        val field = reducer.propertyFields(state).randomOrNull(random) ?: return null
        UiBuilderEditorEvent.CommitProperty(field.nodeId, field.name, DRAFTS.random(random))
      }
      6 -> {
        val container = reducer.wrapCandidates(state).randomOrNull(random) ?: return null
        UiBuilderEditorEvent.WrapSelection(container.componentId)
      }
      7 -> UiBuilderEditorEvent.UnwrapSelection
      8 -> UiBuilderEditorEvent.CopySelected
      9 ->
        if (random.nextBoolean()) UiBuilderEditorEvent.CutSelected else UiBuilderEditorEvent.Paste
      10 -> if (random.nextBoolean()) UiBuilderEditorEvent.Undo else UiBuilderEditorEvent.Redo
      else -> {
        val toggled = selected ?: nodeId
        val type = MODIFIERS.random(random)
        UiBuilderEditorEvent.ToggleModifier(toggled, type)
      }
    }
  }

  private fun assertValid(document: UiBuilderDocument, case: String) {
    assertEquals(
      emptyList(),
      validator.validate(document).issues.map { "${it.code} ${it.nodeId} ${it.message}" },
      "$case: catalog validation",
    )
    assertEquals(
      emptyList(),
      validateDocumentForExport(document, catalog).map { "${it.code} ${it.nodeId} ${it.message}" },
      "$case: export graph",
    )
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  private companion object {
    val SEEDS = (1L..15L).toList()
    const val EVENTS = 40

    /** Property drafts: some plausible, some not, because both have to be handled. */
    val DRAFTS = listOf("Hello", "", "42", "-1", "true", "notAValueAnyCatalogDeclares", "#FF00FF")

    /** A mix a component may and may not declare, for the same reason. */
    val MODIFIERS = listOf("fillMaxSize", "fillMaxWidth", "clip", "weight", "zIndex", "testTag")
  }
}
