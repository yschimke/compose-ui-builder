@file:OptIn(
  androidx.compose.material3.ExperimentalMaterial3Api::class,
  androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The comments panel: read what has been said about this design, say something, close a thread.
 *
 * Its own tab rather than a section under Screen — where the reference lives — because a discussion
 * is about the design as a whole as often as it is about one part of it, and because the panel has
 * to be readable while somebody is editing rather than only while they are aligning a mock.
 *
 * ### Its link to markup
 *
 * A new thread is pinned to one of three things, offered as chips: nothing (the design), the
 * selected layer, or the last mark drawn on the reference. That third one is the point of putting
 * comments and markup in the same product: circle the thing that is wrong, then say why. The pin is
 * drawn on the canvas by [CommentPinOverlay], so the sentence and the stroke are one gesture apart.
 *
 * ### Why it holds no cache
 *
 * Everything here is read from [board], which the host replaces whenever the server says the
 * discussion moved — over a socket while the page is open. There is deliberately no local list that
 * a post appends to: a comment is not shown until the host has stored it and told everybody, which
 * is the same rule the editor already applies to a design edit, and it means the panel cannot show
 * a reply that an agent never received.
 */
@Composable
internal fun CommentsInspector(
  board: DesignCommentBoard,
  reference: ReferenceOverlayState,
  /** The layer a new thread can be pinned to, and the one a pinned thread selects when opened. */
  selectedNodeId: String?,
  /** The name to show against the node anchor; the id is not what a designer calls it. */
  nodeLabel: (String) -> String,
  selectedThreadId: String?,
  onSelectThread: (String?) -> Unit,
  onPost: ((DesignCommentDraft) -> Unit)?,
  onResolve: ((String, Boolean) -> Unit)?,
  /** A sentence from the host — a refusal, a socket that dropped. */
  hostStatus: String?,
  onTextInputFocusChanged: (Boolean) -> Unit,
) {
  var draft by remember { mutableStateOf("") }
  var reply by remember(selectedThreadId) { mutableStateOf("") }
  var showResolved by remember { mutableStateOf(false) }
  val lastMark = reference.marks.lastOrNull()
  var anchorChoice by remember { mutableStateOf(CommentAnchorChoice.Design) }
  // A chip that no longer applies must not stay selected: rubbing out the last mark, or clearing
  // the selection, would otherwise pin the next thread to something that is not there any more.
  if (anchorChoice == CommentAnchorChoice.LastMark && lastMark == null) {
    anchorChoice = CommentAnchorChoice.Design
  }
  if (anchorChoice == CommentAnchorChoice.Layer && selectedNodeId == null) {
    anchorChoice = CommentAnchorChoice.Design
  }

  // No heading of its own: the dock's own heading already names this panel and counts what is open
  // in it, and a second title under the first is the thing the rail was meant to remove.
  Text(
    "Kept beside the design and never part of it: no node holds a comment and no export sees " +
      "one. Everybody watching sees a reply the moment it lands.",
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.bodySmall,
  )
  HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline)

  if (onPost == null) {
    Text(
      "This host keeps no discussion for a design.",
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodySmall,
    )
    return
  }

  Text(
    "Pin the next thread to",
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  FlowRow(
    Modifier.fillMaxWidth().padding(top = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    CommentAnchorChoice.entries.forEach { choice ->
      val available =
        when (choice) {
          CommentAnchorChoice.Design -> true
          CommentAnchorChoice.Layer -> selectedNodeId != null
          CommentAnchorChoice.LastMark -> lastMark != null
        }
      if (!available) return@forEach
      val label =
        when (choice) {
          CommentAnchorChoice.Design -> "This design"
          CommentAnchorChoice.Layer -> nodeLabel(selectedNodeId.orEmpty())
          CommentAnchorChoice.LastMark -> "Last ${lastMark?.kind?.label?.lowercase()}"
        }
      FilterChip(
        selected = anchorChoice == choice,
        onClick = { anchorChoice = choice },
        label = { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
        modifier = Modifier.semantics { contentDescription = "Pin comment to $label" },
      )
    }
  }

  CommentField(
    value = draft,
    placeholder = "Start a thread…",
    label = "New comment",
    onChange = { draft = it },
    onFocusChanged = onTextInputFocusChanged,
  )
  Row(
    Modifier.fillMaxWidth().padding(top = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Button(
      onClick = {
        onPost(
          DesignCommentDraft(
            anchor =
              when (anchorChoice) {
                CommentAnchorChoice.Design -> null
                CommentAnchorChoice.Layer -> DesignCommentAnchor(nodeId = selectedNodeId)
                CommentAnchorChoice.LastMark ->
                  lastMark?.let { mark ->
                    // Both the mark and the point it resolves to today. The mark is the link a
                    // reader follows; the point is what keeps the pin on the frame after the
                    // reference is replaced and the stroke is gone.
                    val centre = DesignCommentAnchor(markId = mark.id).pointOn(reference.marks)
                    DesignCommentAnchor(
                      markId = mark.id,
                      x = centre?.first,
                      y = centre?.second,
                    )
                  }
              },
            body = draft,
          )
        )
        draft = ""
      },
      enabled = draft.isNotBlank(),
      modifier = Modifier.semantics { contentDescription = "Post comment" },
    ) {
      Text("Post")
    }
    val resolvedCount = board.threads.count { it.resolved }
    if (resolvedCount > 0) {
      TextButton(onClick = { showResolved = !showResolved }) {
        Text(if (showResolved) "Hide resolved" else "Show resolved ($resolvedCount)")
      }
    }
  }
  hostStatus?.let {
    SelectionContainer {
      Text(
        it,
        Modifier.padding(top = 6.dp),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.labelSmall,
      )
    }
  }

  HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline)

  val shown = if (showResolved) board.threads else board.openThreads
  if (shown.isEmpty()) {
    Text(
      if (board.threads.isEmpty()) "Nothing has been said about this design yet."
      else "Every thread on this design is resolved.",
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodySmall,
    )
    return
  }

  // Newest conversation first: a thread somebody just replied to is the one being talked about.
  shown
    .sortedByDescending { it.updatedAtEpochMillis }
    .forEach { thread ->
      CommentThreadCard(
        thread = thread,
        marks = reference.marks,
        nodeLabel = nodeLabel,
        expanded = thread.id == selectedThreadId,
        onToggle = { onSelectThread(if (thread.id == selectedThreadId) null else thread.id) },
        reply = reply,
        onReplyChanged = { reply = it },
        onReply = {
          onPost(DesignCommentDraft(threadId = thread.id, body = reply))
          reply = ""
        },
        onResolve = onResolve?.let { resolve -> { resolve(thread.id, !thread.resolved) } },
        onTextInputFocusChanged = onTextInputFocusChanged,
      )
    }
}

/** What a new thread is pinned to. Three, because there are three things worth pointing at. */
private enum class CommentAnchorChoice {
  Design,
  Layer,
  LastMark,
}

@Composable
private fun CommentThreadCard(
  thread: DesignCommentThread,
  marks: List<ReferenceMark>,
  nodeLabel: (String) -> String,
  expanded: Boolean,
  onToggle: () -> Unit,
  reply: String,
  onReplyChanged: (String) -> Unit,
  onReply: () -> Unit,
  onResolve: (() -> Unit)?,
  onTextInputFocusChanged: (Boolean) -> Unit,
) {
  val anchorLabel = thread.anchorLabel(marks, nodeLabel)
  Column(
    Modifier.fillMaxWidth()
      .padding(top = 8.dp)
      .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
      .padding(10.dp)
  ) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(
        anchorLabel,
        Modifier.weight(1f).clickable(onClick = onToggle).semantics {
          contentDescription = "Comment thread on $anchorLabel"
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (thread.resolved) {
        Text(
          "Resolved",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
        )
      }
    }
    // Collapsed shows the question and how many answers it drew; expanded shows the conversation.
    // The opening line is the title on purpose — the last word on a thread is rarely the point of
    // it, and a panel that showed the most recent reply would make every thread read the same.
    val visible = if (expanded) thread.comments else listOfNotNull(thread.opening)
    visible.forEach { comment ->
      Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            comment.author,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
          )
          if (comment.kind == DesignCommentAuthorKind.Agent) {
            Text(
              " · agent",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }
        // The one piece of prose in this editor somebody else wrote, so it is the one that most
        // wants copying out — into a commit message, a reply, an issue.
        SelectionContainer { Text(comment.body, style = MaterialTheme.typography.bodySmall) }
      }
    }
    Row(
      Modifier.fillMaxWidth().padding(top = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      val replies = thread.comments.size - 1
      Text(
        if (expanded) "" else if (replies > 0) "$replies more" else "",
        Modifier.weight(1f),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      TextButton(onClick = onToggle) { Text(if (expanded) "Collapse" else "Open") }
      onResolve?.let {
        TextButton(onClick = it) { Text(if (thread.resolved) "Reopen" else "Resolve") }
      }
    }
    if (expanded) {
      CommentField(
        value = reply,
        placeholder = "Reply…",
        label = "Reply to this thread",
        onChange = onReplyChanged,
        onFocusChanged = onTextInputFocusChanged,
      )
      Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Button(
          onClick = onReply,
          enabled = reply.isNotBlank(),
          modifier = Modifier.semantics { contentDescription = "Send reply" },
        ) {
          Text("Reply")
        }
      }
    }
  }
}

/** What a thread says it is about, in the words the panel and the pin both use. */
internal fun DesignCommentThread.anchorLabel(
  marks: List<ReferenceMark>,
  nodeLabel: (String) -> String,
): String {
  val anchor = anchor ?: return "This design"
  anchor.nodeId?.let {
    return nodeLabel(it)
  }
  anchor.markId?.let { markId ->
    val mark = marks.firstOrNull { it.id == markId }
    // A stroke that has been rubbed out since keeps its thread readable rather than pretending the
    // mark is still there: the words somebody wrote outlive the shape they wrote them about.
    return if (mark == null) "A mark that is gone" else "The ${mark.kind.label.lowercase()}"
  }
  return if (anchor.x != null && anchor.y != null) "A point on the frame" else "This design"
}

/**
 * Comment pins, over the frame, in the same coordinate space the marks are drawn in.
 *
 * Real composables rather than a [androidx.compose.foundation.Canvas] pass, because a pin is
 * something a person clicks and a screen reader announces — the presence overlay beside it draws
 * rectangles nobody interacts with, which is why that one is a canvas and this one is not.
 */
@Composable
internal fun CommentPinOverlay(
  threads: List<DesignCommentThread>,
  marks: List<ReferenceMark>,
  selectedThreadId: String?,
  onSelect: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  if (threads.isEmpty()) return
  var size by remember { mutableStateOf(IntSize.Zero) }
  val density = LocalDensity.current
  Box(modifier.fillMaxSize().onSizeChanged { size = it }) {
    if (size.width == 0 || size.height == 0) return@Box
    threads.forEachIndexed { index, thread ->
      val point = thread.anchor?.pointOn(marks) ?: return@forEachIndexed
      val selected = thread.id == selectedThreadId
      val label = "${index + 1}"
      val x = with(density) { (point.first * size.width).roundToInt().toDp() }
      val y = with(density) { (point.second * size.height).roundToInt().toDp() }
      Box(
        Modifier.offset(x - PIN_RADIUS, y - PIN_RADIUS)
          .size(PIN_RADIUS * 2)
          .background(
            if (thread.resolved) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.primary,
            CircleShape,
          )
          .border(
            width = if (selected) 3.dp else 1.dp,
            color =
              if (selected) MaterialTheme.colorScheme.onSurface
              else MaterialTheme.colorScheme.outline,
            shape = CircleShape,
          )
          .clickable { onSelect(thread.id) }
          .semantics {
            contentDescription = "Comment $label by ${thread.opening?.author.orEmpty()}".trimEnd()
          },
        contentAlignment = Alignment.Center,
      ) {
        Text(
          label,
          style = MaterialTheme.typography.labelSmall,
          color =
            if (thread.resolved) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onPrimary,
        )
      }
    }
  }
}

private val PIN_RADIUS = 11.dp

@Composable
private fun CommentField(
  value: String,
  placeholder: String,
  label: String,
  onChange: (String) -> Unit,
  onFocusChanged: (Boolean) -> Unit,
) {
  Box(
    Modifier.fillMaxWidth()
      .padding(top = 8.dp)
      .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
      .padding(horizontal = 8.dp, vertical = 7.dp)
  ) {
    if (value.isEmpty()) {
      Text(
        placeholder,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    BasicTextField(
      value = value,
      onValueChange = onChange,
      modifier =
        Modifier.fillMaxWidth()
          .semantics { contentDescription = label }
          // The editor's own keyboard shortcuts have to stand down while somebody is typing a
          // sentence; Delete in a comment is a character, not a node.
          .onFocusChanged { onFocusChanged(it.isFocused) },
      textStyle =
        MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
      cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
      maxLines = 6,
    )
  }
}
