@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

// The strip of revision thumbnails under the canvas, and the read-only surfaces it opens.
//
// Why a strip of pictures beside a panel of words: the History panel answers "what has been done"
// and cannot answer "which one looked like what". The viewer's render-history menu made that
// argument for published renders and drew thumbnails on its rows because of it; this is the same
// answer for the design's own revisions. [EditorRevisionEntry] carries how the two timelines differ
// and why they stay separate surfaces.
//
// The panel and the strip are one control, not two: the History rail opens both. They are the same
// history, and a second switch would only let somebody have half of it.

// Big enough that two revisions of the same screen can be told apart, small enough that a dozen of
// them fit above the status bar without the strip becoming a second canvas.
private val THUMBNAIL_WIDTH = 92.dp
private val THUMBNAIL_HEIGHT = 64.dp

/**
 * One row per revision, oldest on the left, the design as it is now on the right.
 *
 * Left to right in time, unlike the History panel's newest-first list, because a strip is read as a
 * timeline and a timeline that runs backwards has to be relabelled in the reader's head. The newest
 * entry is the one on the canvas and it is scrolled to, so the end that matters is the end that
 * shows.
 */
@Composable
internal fun RevisionHistoryBar(
  entries: List<EditorRevisionEntry>,
  /** The revision being looked at, or null while the canvas is showing the living design. */
  peeked: Int?,
  /** The other end of a comparison, or null. */
  compared: Int?,
  onPeek: (Int?) -> Unit,
  onCompare: (Int) -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  if (entries.isEmpty()) return
  val scroll = rememberScrollState()
  Surface(
    modifier.fillMaxWidth(),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    tonalElevation = 2.dp,
  ) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
      Row(
        Modifier.fillMaxWidth().padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          "History",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          // The way the viewer's menu states its own size: how much history there is, said without
          // making anybody count the pictures.
          if (entries.size == 1) "  1 revision" else "  ${entries.size} revisions",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(Modifier.weight(1f))
        // No "Back to now" here: whenever there is one to press, the review pane above is on screen
        // carrying it, and two of the same button in one column is a reader wondering what the
        // difference is.
        TextButton(onClick = onClose) {
          Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
          Text("  Hide")
        }
      }
      Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        entries.forEach { entry ->
          RevisionThumbnail(
            entry = entry,
            // Nothing picked means the canvas is live, and the canvas is the newest revision — so
            // the newest row is the one marked, rather than no row being marked at all.
            peeked = peeked?.let { it == entry.revision } ?: entry.current,
            compared = compared == entry.revision,
            onPeek = { onPeek(entry.revision) },
            onCompare = { onCompare(entry.revision) },
            comparable = peeked != null && peeked != entry.revision,
          )
        }
      }
    }
  }
}

/**
 * One revision as a picture of itself, with what happened under it.
 *
 * **Rendered, not baked**, exactly as the component palette's rows are: the document rebuilt for
 * this revision is drawn through the renderer that is drawing the canvas, laid out at the frame's
 * own size and shrunk by a draw-time transform. So no PNG is stored per revision, the picture
 * cannot disagree with what going back would show, and a revision of a design nobody has baked
 * artwork for still has a picture.
 *
 * A row whose document could not be rebuilt keeps its words and loses its picture. That is the
 * fail-closed half of [CollaborationState.documentsBackTo]: a thumbnail that is not what that
 * revision looked like is worse than a blank one.
 */
@Composable
private fun RevisionThumbnail(
  entry: EditorRevisionEntry,
  peeked: Boolean,
  compared: Boolean,
  comparable: Boolean,
  onPeek: () -> Unit,
  onCompare: () -> Unit,
) {
  val width = THUMBNAIL_WIDTH
  val height = THUMBNAIL_HEIGHT
  val outline =
    when {
      compared -> MaterialTheme.colorScheme.tertiary
      peeked -> MaterialTheme.colorScheme.primary
      else -> Color.Transparent
    }
  Column(
    Modifier.width(width)
      .clip(RoundedCornerShape(6.dp))
      .clickable(onClick = onPeek)
      .semantics {
        // What the row is *for*, not what it draws: "revision 12, Set text on Episode title".
        contentDescription =
          "Revision ${entry.revision}, ${entry.summary}" +
            (if (entry.span > 1) ", ${entry.span} revisions" else "") +
            (if (entry.document == null) ", no picture" else "")
        selected = peeked
      }
      .padding(2.dp)
  ) {
    Box(
      Modifier.size(width, height)
        .clip(RoundedCornerShape(4.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        .border(2.dp, outline, RoundedCornerShape(4.dp)),
      contentAlignment = Alignment.Center,
    ) {
      val document = entry.document
      if (document == null) {
        Text(
          "—",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        RevisionPicture(document, Modifier.fillMaxSize())
      }
      if (entry.span > 1) {
        Text(
          "×${entry.span}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp),
        )
      }
    }
    Text(
      if (entry.current) "now" else "r${entry.revision}",
      style = MaterialTheme.typography.labelSmall,
      color =
        if (entry.standing == EditorOperationStanding.Undone) {
          MaterialTheme.colorScheme.onSurfaceVariant
        } else {
          MaterialTheme.colorScheme.onSurface
        },
      maxLines = 1,
    )
    Text(
      entry.summary,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    // The second end of a comparison is offered only once there is a first, and never on the row
    // that already is it: "compare this with itself" is not a question anybody is asking.
    if (comparable) {
      TextButton(
        onClick = onCompare,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
      ) {
        Icon(
          Icons.Filled.CompareArrows,
          contentDescription = null,
          modifier = Modifier.size(14.dp),
        )
        // Said as well as drawn: two arrows are not a word, and this row already has three lines of
        // text under it that the icon alone would be read as a fourth of.
        Text(" Compare", style = MaterialTheme.typography.labelSmall, maxLines = 1)
      }
    }
  }
}

/**
 * A design drawn at whatever size it is given: laid out at its own frame, shrunk to fit.
 *
 * The same technique as the palette's thumbnail, at whatever scale the caller has room for —
 * `graphicsLayer` over a subtree that laid itself out at a sensible size, rather than a layout
 * squeezed until its text wraps to nothing. The design's own density is divided back out for the
 * reason the canvas does it: a design measured at 2.625 and a workspace measured at 1.0 do not
 * agree about what a dp is, and sizing the frame in the workspace's clamps everything authored
 * wider than the number.
 *
 * Semantics are cleared. A picture of a Switch is not a Switch, and a strip of forty revisions
 * would otherwise publish forty screens' worth of controls to a screen reader.
 */
@Composable
internal fun RevisionPicture(document: UiBuilderDocument, modifier: Modifier = Modifier) {
  val frameWidth =
    document.environment["widthDp"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 1280f
  val frameHeight =
    document.environment["heightDp"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 800f
  val density = LocalDensity.current
  val densityRatio = document.renderDensity(density).density / density.density
  BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
    val scale =
      minOf(maxWidth.value / frameWidth, maxHeight.value / frameHeight).coerceAtLeast(0.01f)
    Box(
      Modifier.requiredSize((frameWidth * densityRatio).dp, (frameHeight * densityRatio).dp)
        .graphicsLayer {
          scaleX = scale / densityRatio
          scaleY = scale / densityRatio
        }
        .clearAndSetSemantics {}
    ) {
      UiBuilderSurface(document = document, editorOverlay = false)
    }
  }
}

/**
 * The canvas's stand-in while an old revision is being looked at, or two are being compared.
 *
 * It **replaces** the editing canvas rather than drawing over it. A canvas that took a drop at
 * revision 12 of a design that is at revision 40 would be editing a picture, and hiding that behind
 * a disabled overlay is a rule somebody eventually finds a way around. Not composing the editing
 * surface at all is the rule holding itself.
 */
@Composable
internal fun RevisionReviewPane(
  /** The revision picked first — the one being looked at. */
  peeked: EditorRevisionEntry,
  /** The other end, or null while one revision is being looked at on its own. */
  compared: EditorRevisionEntry?,
  diff: EditorRevisionDiff?,
  onBackToNow: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier.background(Color(0xff0d0e11))) {
    Surface(
      Modifier.fillMaxWidth(),
      color = MaterialTheme.colorScheme.secondaryContainer,
      tonalElevation = 2.dp,
    ) {
      Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(Modifier.weight(1f)) {
          Text(
            if (compared == null) {
              "Looking at revision ${peeked.revision}"
            } else {
              "Comparing r${minOf(peeked.revision, compared.revision)} with " +
                "r${maxOf(peeked.revision, compared.revision)}"
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
          )
          Text(
            // What a reader has to know before they trust anything on this pane: it is a picture,
            // their edits are still landing on the design, and the design has not moved.
            diff?.summaryLine() ?: "${peeked.summary} · read-only, the design is unchanged",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
          )
        }
        TextButton(onClick = onBackToNow) { Text("Back to now") }
      }
    }
    Row(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
      if (compared == null) {
        RevisionPane(peeked, "r${peeked.revision}", Modifier.weight(1f).fillMaxSize())
      } else {
        val older = if (peeked.revision <= compared.revision) peeked else compared
        val newer = if (peeked.revision <= compared.revision) compared else peeked
        RevisionPane(older, "r${older.revision}", Modifier.weight(1f).fillMaxSize())
        RevisionPane(newer, "r${newer.revision}", Modifier.weight(1f).fillMaxSize())
      }
      if (diff != null) {
        RevisionDiffList(diff, Modifier.width(260.dp).fillMaxSize().padding(start = 16.dp))
      }
    }
  }
}

@Composable
private fun RevisionPane(entry: EditorRevisionEntry, label: String, modifier: Modifier) {
  Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Text(
      "$label · ${entry.summary}",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(bottom = 6.dp),
    )
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
      val document = entry.document
      if (document == null) {
        Text(
          "This revision cannot be redrawn from what this session holds.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        RevisionPicture(document, Modifier.fillMaxSize())
      }
    }
  }
}

/**
 * The change list beside the two pictures: what moved, node by node.
 *
 * Computed from the two documents rather than from the operations between them — see
 * [EditorRevisionDiff]. Two pictures say *that* something changed; a list of the properties, the
 * layouts and the nodes says what, and is the half somebody can act on.
 */
@Composable
private fun RevisionDiffList(diff: EditorRevisionDiff, modifier: Modifier) {
  Column(modifier) {
    Text(
      diff.summaryLine(),
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.padding(bottom = 8.dp),
    )
    if (diff.identical) {
      Text(
        "The two revisions hold the same design.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      return@Column
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      if (diff.environment.isNotEmpty()) {
        item {
          RevisionDiffRow(
            label = "The screen",
            kind = "changed",
            changes = diff.environment,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      items(diff.nodes, key = { "${it.kind}:${it.nodeId}" }) { node ->
        RevisionDiffRow(
          label = node.label,
          kind =
            when (node.kind) {
              EditorNodeDiffKind.Added -> "added"
              EditorNodeDiffKind.Removed -> "removed"
              EditorNodeDiffKind.Changed -> "changed"
            },
          changes = node.changes,
          tint =
            when (node.kind) {
              EditorNodeDiffKind.Added -> MaterialTheme.colorScheme.primary
              EditorNodeDiffKind.Removed -> MaterialTheme.colorScheme.error
              EditorNodeDiffKind.Changed -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
      }
    }
  }
}

@Composable
private fun RevisionDiffRow(
  label: String,
  kind: String,
  changes: List<EditorOperationChange>,
  tint: Color,
) {
  Column(Modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(kind, style = MaterialTheme.typography.labelSmall, color = tint)
      Text(
        "  $label",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    changes.forEach { change ->
      Text(
        // The same before → after line the History panel writes, so one account of a change reads
        // the same wherever it is met. An absent end is stated rather than left blank.
        "${change.label}: ${change.before ?: "none"} → ${change.after ?: "none"}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 8.dp),
      )
    }
  }
}

/** The diff in one line: what a reader needs before deciding whether to read the rest. */
private fun EditorRevisionDiff.summaryLine(): String {
  if (identical) return "No difference"
  val parts = buildList {
    if (added > 0) add("$added added")
    if (removed > 0) add("$removed removed")
    if (changed > 0) add("$changed changed")
    if (environment.isNotEmpty()) add("${environment.size} screen")
  }
  return parts.joinToString(" · ")
}
