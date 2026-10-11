package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.accepts
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorState
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import kotlin.random.Random

/**
 * A seeded generator of documents the editor could have produced, built by select, pick a
 * component, press Add, so every document is reachable.
 *
 * The catalog is the grammar (components are nonterminals, slots productions), so new components
 * are generated automatically. Placement uses [UiBuilderEditorReducer.dropTarget], the palette's
 * own call, and inserts exactly there (`InsertComponent` refuses a target the selection doesn't
 * resolve to).
 */
class UiBuilderTreeGenerator(
  private val catalog: CapabilityCatalog,
  private val reducer: UiBuilderEditorReducer,
) {
  data class Attempt(
    val selectedNodeId: String,
    val componentId: String,
    val target: ParentSlot,
    val outcome: CommandOutcome?,
  ) {
    val accepted: Boolean
      get() = outcome is CommandOutcome.Accepted
  }

  data class Generated(val state: UiBuilderEditorState, val attempts: List<Attempt>) {
    val document: UiBuilderDocument
      get() = state.document

    val rejected: List<Attempt>
      get() = attempts.filterNot(Attempt::accepted)
  }

  /**
   * [inserts] rounds of "select a node at random, pick a component at random, press Add". A round
   * with nothing offered is skipped; a refused round is recorded and the walk continues from the
   * prior state.
   */
  fun generate(seed: Long, inserts: Int, start: UiBuilderDocument): Generated {
    val random = Random(seed)
    var state = reducer.initial(start, start.roots.firstOrNull())
    val attempts = mutableListOf<Attempt>()
    repeat(inserts) {
      val selected = state.document.nodes.keys.random(random)
      val componentId = catalog.components.random(random).componentId
      val selectedState = reducer.reduce(state, UiBuilderEditorEvent.SelectNode(selected))
      val target = reducer.dropTarget(selectedState, componentId) ?: return@repeat
      val next =
        reducer.reduce(selectedState, UiBuilderEditorEvent.InsertComponent(componentId, target))
      attempts += Attempt(selected, componentId, target, next.lastOutcome)
      if (next.lastOutcome is CommandOutcome.Accepted) state = next
    }
    return Generated(state, attempts)
  }

  /**
   * The slots of [parentComponentId] that still have room after every catalog component has been
   * offered [rounds] times, and that nothing was routed into. Repeating lets bounded slots fill and
   * hand on to the next. Full slots aren't reported.
   */
  fun slotsAddCannotReach(
    parentComponentId: String,
    start: UiBuilderDocument,
    rounds: Int = 4,
  ): List<String> {
    val seeded = insertParent(parentComponentId, start) ?: return emptyList()
    val parentId = seeded.selectedNodeId ?: return emptyList()
    val capability = catalog.componentsById[parentComponentId] ?: return emptyList()
    val filled = mutableSetOf<String>()
    var state = seeded
    catalog.components.forEach { component ->
      repeat(rounds) {
        val selected = reducer.reduce(state, UiBuilderEditorEvent.SelectNode(parentId))
        val target = reducer.dropTarget(selected, component.componentId) ?: return@repeat
        if (target.nodeId != parentId) return@repeat
        val next =
          reducer.reduce(
            selected,
            UiBuilderEditorEvent.InsertComponent(component.componentId, target),
          )
        if (next.lastOutcome !is CommandOutcome.Accepted) return@repeat
        filled += target.slot
        state = next
      }
    }
    val parent = state.document.nodes[parentId] ?: return emptyList()
    return capability.slots
      .filterNot { it.name in filled }
      .filter { slot ->
        slot.cardinality.max?.let { parent.slots[slot.name].orEmpty().size < it } ?: true
      }
      .filter { slot -> catalog.components.any(slot::accepts) }
      .map { "$parentComponentId.${it.name}" }
  }

  /** [parentComponentId] inserted into [start] wherever the palette will take it. */
  private fun insertParent(
    parentComponentId: String,
    start: UiBuilderDocument,
  ): UiBuilderEditorState? {
    val roots = start.roots.firstOrNull() ?: return null
    return start.nodes.keys.firstNotNullOfOrNull { nodeId ->
      val state =
        reducer.reduce(reducer.initial(start, roots), UiBuilderEditorEvent.SelectNode(nodeId))
      val target = reducer.dropTarget(state, parentComponentId) ?: return@firstNotNullOfOrNull null
      reducer
        .reduce(state, UiBuilderEditorEvent.InsertComponent(parentComponentId, target))
        .takeIf { it.lastOutcome is CommandOutcome.Accepted }
    }
  }
}
