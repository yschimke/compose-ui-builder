package ee.schimke.composeai.uibuilder.editor

import ee.schimke.composeai.uibuilder.CollaborationState
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.documentsBackTo
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.reconstructableFromRevision

/**
 * One row of the revision strip: the design's committed edits drawn as pictures, the visual
 * counterpart of the History panel.
 *
 * A revision is a live committed edit to the document, not a viewer "version" (a published render).
 * Pictures are rebuilt with [CollaborationState.documentsBackTo] and drawn by the canvas renderer,
 * so no thumbnail is stored and none can disagree with the canvas.
 */
data class EditorRevisionEntry(
  /** The revision this row stands for — the newest of a collapsed run. */
  val revision: Int,
  /** The command that committed it, or null on the origin row and on an undo or redo. */
  val operationId: String?,
  /** What happened, in the History panel's own words. */
  val summary: String,
  val actorId: String?,
  val mine: Boolean,
  /** How the History panel would mark this row, or null where it has no operation to mark. */
  val standing: EditorOperationStanding?,
  /** The revision the canvas is showing — the newest row, and only ever one. */
  val current: Boolean,
  /**
   * The oldest row: revision 0, or the revision a reopened snapshot carried, since earlier
   * mutations are not held by this client.
   */
  val origin: Boolean,
  /** How many revisions this row covers, when identical neighbours collapsed into it. */
  val span: Int,
  /**
   * The design at this revision, or null where it could not be rebuilt (drawn without a picture,
   * never a guess).
   */
  val document: UiBuilderDocument?,
  /** What the committing command moved, for the row's own tooltip. */
  val changes: List<EditorOperationChange>,
)

/** One node's differences between two revisions. */
data class EditorNodeDiff(
  val nodeId: String,
  /** What the layers panel calls this node, at whichever end of the diff still has it. */
  val label: String,
  val kind: EditorNodeDiffKind,
  /** The values that moved, empty for a node that was only added or removed. */
  val changes: List<EditorOperationChange>,
)

enum class EditorNodeDiffKind {
  Added,
  Removed,
  Changed,
}

/**
 * Two revisions compared by their documents rather than the operations between them, so an undo in
 * the range cannot show up as a change and its reversal.
 */
data class EditorRevisionDiff(
  /** The older revision. */
  val from: Int,
  /** The newer revision. */
  val to: Int,
  val before: UiBuilderDocument?,
  val after: UiBuilderDocument?,
  val nodes: List<EditorNodeDiff>,
  /** Screen-level fields: theme, viewport, locale — everything not on a node. */
  val environment: List<EditorOperationChange>,
) {
  val added: Int
    get() = nodes.count { it.kind == EditorNodeDiffKind.Added }

  val removed: Int
    get() = nodes.count { it.kind == EditorNodeDiffKind.Removed }

  val changed: Int
    get() = nodes.count { it.kind == EditorNodeDiffKind.Changed }

  /** True when the two revisions hold the same design, whatever was done between them. */
  val identical: Boolean
    get() = nodes.isEmpty() && environment.isEmpty()
}

/**
 * How many revisions back the strip reaches. Each row costs a rebuilt document and a thumbnail;
 * older revisions remain in the history and undoable.
 */
const val REVISION_TIMELINE_LIMIT = 24

/**
 * The revisions this editor can picture, oldest first, ending at the canvas. Undo and redo rows are
 * named after the change they took back or put back.
 */
fun revisionTimeline(
  state: UiBuilderEditorState,
  history: List<EditorOperationEntry>,
  limit: Int = REVISION_TIMELINE_LIMIT,
): List<EditorRevisionEntry> {
  val collaboration = state.collaboration
  val current = state.document.revision
  val floor = collaboration.reconstructableFromRevision()
  val oldest = maxOf(floor, current - limit)
  if (current < oldest) return emptyList()
  val documents = collaboration.documentsBackTo(oldest)
  val byRevision = history.associateBy(EditorOperationEntry::revision)
  val undoneAt = collaboration.undoRecords.values.associateBy { it.committedRevision }
  val redoneAt = collaboration.redoRecords.values.associateBy { it.committedRevision }

  val rows =
    (oldest..current).map { revision ->
      val entry = byRevision[revision]
      val undo = undoneAt[revision]
      val redo = redoneAt[revision]?.let { collaboration.undoRecords[it.targetUndoOperationId] }
      val subject = (undo ?: redo)?.target?.command
      val subjectSummary = history.firstOrNull { it.operationId == subject?.operationId }?.summary
      EditorRevisionEntry(
        revision = revision,
        operationId = entry?.operationId,
        summary =
          when {
            // Two different oldest rows. Where the record itself starts, the row is the design as
            // this editor opened on it. Where the strip merely ran out of room, older revisions are
            // still in the record and the row says so rather than claiming to be the beginning.
            revision == oldest && revision == floor -> "Opened"
            revision == oldest -> "Earlier work"
            entry != null -> entry.summary
            undo != null -> "Took back: ${subjectSummary ?: "an earlier change"}"
            redo != null -> "Put back: ${subjectSummary ?: "an earlier change"}"
            else -> "Revision $revision"
          },
        actorId = entry?.actorId ?: undo?.command?.actorId ?: redoneAt[revision]?.command?.actorId,
        mine = entry?.mine ?: false,
        standing = entry?.standing,
        current = revision == current,
        origin = revision == oldest,
        span = 1,
        document = documents[revision],
        changes = entry?.changes.orEmpty(),
      )
    }
  return rows.collapseIdenticalNeighbours()
}

/**
 * Adjacent rows holding the same design, folded into the newer one and marked `×N`. Rows without a
 * rebuilt document never fold.
 */
private fun List<EditorRevisionEntry>.collapseIdenticalNeighbours(): List<EditorRevisionEntry> {
  val folded = mutableListOf<EditorRevisionEntry>()
  forEach { row ->
    val previous = folded.lastOrNull()
    val same =
      previous?.document != null &&
        row.document != null &&
        previous.document.copy(revision = 0) == row.document.copy(revision = 0)
    if (same) {
      folded[folded.lastIndex] =
        row.copy(span = previous.span + 1, origin = previous.origin, summary = previous.summary)
    } else {
      folded += row
    }
  }
  return folded
}

/**
 * What changed between two revisions, or null where either end cannot be rebuilt. Ends are ordered
 * by number, not pick order.
 */
fun revisionDiff(
  state: UiBuilderEditorState,
  catalog: CapabilityCatalog,
  from: Int,
  to: Int,
): EditorRevisionDiff? {
  val older = minOf(from, to)
  val newer = maxOf(from, to)
  val documents = state.collaboration.documentsBackTo(older)
  val before = documents[older] ?: return null
  val after = documents[newer] ?: return null
  return documentDiff(before, after, catalog)
}

/**
 * Two documents compared node by node and property by property. Tombstoned nodes are absent from
 * both ends, so they never show as differences.
 */
fun documentDiff(
  before: UiBuilderDocument,
  after: UiBuilderDocument,
  catalog: CapabilityCatalog,
): EditorRevisionDiff {
  fun label(document: UiBuilderDocument, nodeId: String): String {
    val node = document.nodes[nodeId] ?: return nodeId
    val capability = catalog.componentsById[node.componentId] ?: return nodeId
    return node.contentLabel(capability) ?: capability.displayName
  }

  val nodes = mutableListOf<EditorNodeDiff>()
  (before.nodes.keys - after.nodes.keys).sorted().forEach { nodeId ->
    nodes += EditorNodeDiff(nodeId, label(before, nodeId), EditorNodeDiffKind.Removed, emptyList())
  }
  (after.nodes.keys - before.nodes.keys).sorted().forEach { nodeId ->
    nodes += EditorNodeDiff(nodeId, label(after, nodeId), EditorNodeDiffKind.Added, emptyList())
  }
  (before.nodes.keys intersect after.nodes.keys).sorted().forEach { nodeId ->
    val old = before.nodes.getValue(nodeId)
    val new = after.nodes.getValue(nodeId)
    val changes = mutableListOf<EditorOperationChange>()
    (old.properties.keys + new.properties.keys).sorted().forEach { property ->
      val oldValue = old.properties[property]
      val newValue = new.properties[property]
      if (oldValue != newValue) {
        changes +=
          EditorOperationChange(property, oldValue?.displayValue(), newValue?.displayValue())
      }
    }
    if (old.modifiers != new.modifiers) {
      changes +=
        EditorOperationChange(
          "layout",
          old.modifiers.modifierSummary(),
          new.modifiers.modifierSummary(),
        )
    }
    if (old.eventBindings != new.eventBindings) {
      changes +=
        EditorOperationChange(
          "actions",
          old.eventBindings.size.toString(),
          new.eventBindings.size.toString(),
        )
    }
    (old.slots.keys + new.slots.keys).sorted().forEach { slot ->
      val oldChildren = old.slots[slot].orEmpty()
      val newChildren = new.slots[slot].orEmpty()
      if (oldChildren != newChildren) {
        changes +=
          EditorOperationChange(
            slot,
            itemCount(oldChildren.size),
            itemCount(newChildren.size),
          )
      }
    }
    if (changes.isNotEmpty()) {
      nodes += EditorNodeDiff(nodeId, label(after, nodeId), EditorNodeDiffKind.Changed, changes)
    }
  }

  val environment =
    (before.environment.keys + after.environment.keys).sorted().mapNotNull { field ->
      val oldValue = before.environment[field]
      val newValue = after.environment[field]
      if (oldValue == newValue) null
      else EditorOperationChange(field, oldValue?.displayValue(), newValue?.displayValue())
    }
  return EditorRevisionDiff(
    from = before.revision,
    to = after.revision,
    before = before,
    after = after,
    nodes = nodes,
    environment =
      environment +
        (before.stateVariables.keys + after.stateVariables.keys).sorted().mapNotNull { name ->
          val oldValue = before.stateVariables[name]
          val newValue = after.stateVariables[name]
          if (oldValue == newValue) null
          else
            EditorOperationChange("State $name", oldValue?.displayValue(), newValue?.displayValue())
        },
  )
}

private fun itemCount(count: Int): String = if (count == 1) "1 item" else "$count items"
