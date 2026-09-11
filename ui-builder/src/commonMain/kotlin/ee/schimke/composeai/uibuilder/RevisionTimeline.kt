package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog

/**
 * The design's own revisions, drawn as a strip of pictures — and what changed between two of them.
 *
 * This is the same history the History panel lists in words, asked the other question. A list of
 * revision numbers, actors and summaries answers "what has been done"; it cannot answer "which one
 * looked like what", and only pixels can. The viewer's render-history menu settled that argument
 * for published renders already (`serve-web/src/viewer/historyModel.ts`), and this is that shape on
 * the history the editor keeps.
 *
 * ### Not the viewer's versions
 *
 * The two timelines are different axes and are deliberately kept apart. A **version** in the viewer
 * is one *published render* of a preview — an immutable, content-addressed PNG on the delivery
 * branch, one entry per publish that moved the bytes, diffed by content id. A **revision** here is
 * one *committed edit* to a design document — live, pre-publish, minted by the reducer, and drawn
 * by the same renderer that is drawing the canvas. Versions outlive the session and the design;
 * revisions are what the session has done. What they share is the shape: a picture per entry,
 * identical neighbours collapsed, one entry marked current, and any two of them comparable. Where a
 * design is exported into the render pipeline, its published renders appear in the viewer's
 * versions timeline — a revision is what a version is made of, not a smaller kind of one.
 *
 * ### Where the pictures come from
 *
 * Not from stored thumbnails. [CollaborationState.documentsBackTo] rebuilds the document at each
 * revision from the compensating changes the reducer already recorded, and the bar draws each one
 * through the renderer that draws the canvas — the same choice the component palette makes for its
 * rows. So no PNG is committed, no render is commissioned, a thumbnail cannot disagree with what
 * the canvas would show at that revision, and a design nobody has baked artwork for still gets
 * pictures.
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
   * The oldest row: where this editor's record begins.
   *
   * Revision 0 for a design built in this session. For one reopened from a stored snapshot it is
   * the revision that snapshot carried — the client holds the mutations made since it connected and
   * none of the ones that built the document it was handed, so the strip starts there and says so
   * rather than drawing rows it cannot picture.
   */
  val origin: Boolean,
  /** How many revisions this row covers, when identical neighbours collapsed into it. */
  val span: Int,
  /**
   * The design at this revision, or null where it could not be rebuilt.
   *
   * Null is drawn as a row without a picture, never as a guess: a thumbnail that is not what that
   * revision looked like is worse than no thumbnail at all.
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
 * Two revisions of one design, compared.
 *
 * Computed from the two **documents**, not from the operations between them. A log diff reads
 * plausibly until an undo is in the range, at which point it lists a change and its reversal and
 * calls that the difference; the documents cannot lie about it. The operations are still the right
 * account of *what was done*, and the History panel is where they are read.
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
 * How many revisions back the strip reaches.
 *
 * A bound rather than the whole session, for the reason the History panel has none: every row here
 * costs a rebuilt document held in memory and a composed thumbnail on screen, and a strip long
 * enough to need its own scrollbar has stopped being a glance. Older revisions are still in the
 * record and still undoable — this is the length of the picture strip, not the length of the
 * history.
 */
const val REVISION_TIMELINE_LIMIT = 24

/**
 * The revisions this editor can picture, oldest first, ending at the one on the canvas.
 *
 * Built from [operationHistory][UiBuilderEditorReducer.operationHistory] and the collaboration
 * record together: the history says what each revision *was*, and the record is what rebuilds what
 * it *looked like*. Undo and redo commit revisions of their own and have no entry in the history —
 * they are named after the change they took back or put back, because "Undo" on its own is the one
 * thing about a history row a reader already knows.
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
 * Adjacent rows holding the same design, folded into one marked `×N`.
 *
 * The rule the viewer's versions timeline already applies to identical render bytes, for the same
 * reason: a strip is a list of *states*, and two pictures that cannot be told apart are one state
 * shown twice. The newer row survives, so the current revision stays current and a row still names
 * a revision the editor can go back to. Rows whose document could not be rebuilt never fold —
 * "unknown" is not evidence of sameness.
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
 * What changed between two revisions of this design, or null where either end cannot be rebuilt.
 *
 * [from] and [to] are ordered by the caller's numbers rather than by the order they were picked, so
 * comparing a revision with an older one reads forwards either way round.
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
 * Two documents compared node by node.
 *
 * Only the nodes each document actually holds, so a tombstoned node the reducer keeps for undo is
 * absent from both ends and is not reported as a difference. Properties are compared per key rather
 * than as one blob, because "the properties changed" is not something anyone can act on.
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
