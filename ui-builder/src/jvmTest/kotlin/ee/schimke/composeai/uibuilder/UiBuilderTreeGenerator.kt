package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.accepts
import kotlin.random.Random

/**
 * A seeded generator of documents the editor could have produced.
 *
 * It builds trees the only way a person can — select a node, pick a component, press Add — so what
 * comes out is not a hand-assembled JSON blob but a document with an authoring history. That is the
 * point: a generator that wrote nodes directly could produce shapes no sequence of editor
 * operations reaches, and every failure it found would need triaging for whether the document was
 * reachable at all before it meant anything.
 *
 * The catalog is the grammar — components are the nonterminals, slots the productions — so a
 * component or slot added to the catalog is generated the day it is declared, with nothing to
 * update here. Where the child *lands* is not this generator's decision either: it asks
 * [UiBuilderEditorReducer.dropTarget], which is the same call the palette's Add makes, and inserts
 * at exactly what comes back. Anything else would be testing a door the product does not have —
 * `InsertComponent` refuses a target the current selection does not resolve to, which is what keeps
 * a stale panel from writing into a slot that has since moved.
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
   * [inserts] rounds of "select a node at random, pick a component at random, press Add".
   *
   * A round where the palette offers nothing for that pair is not an insert and not a failure — it
   * is the ordinary case of a component that does not belong under the selection. A round the
   * reducer *refuses* is recorded, and the walk carries on from the state before it, so one refusal
   * does not truncate the tree and the caller can assert there were none.
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
   * The slots of [parentComponentId] that still have room after every component in the catalog has
   * been offered to it, and that nothing was ever routed into.
   *
   * The parent is inserted the ordinary way — so it arrives holding its starter content and
   * whatever its required slots were filled with — and then each component is offered to it
   * [rounds] times over. Repeating matters: a bounded slot fills and hands the next component to
   * the slot behind it, which is how a text field's label, placeholder and supporting text are
   * reached in turn.
   *
   * A slot that ends full is not reported: it holds what it can hold, however it got there. What is
   * left is a slot with space in it that no amount of pressing Add can put anything into.
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
