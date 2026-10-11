package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The search `/` opens beside the selection: type to narrow the catalog, Up and Down to move, Enter
 * to insert, Esc to close. Placed like the quick editor and the Comment field, so it appears where
 * the eye already is rather than in a panel across the window.
 *
 * [search] is [UiBuilderEditorReducer.quickInsertItems] for the current state, so the rows rank as
 * the insert panel's do and every row is one the selection can take. [onInsert] is the panel's own
 * Add, which is what records the pick on the "Recently used" shelf.
 */
@Composable
internal fun QuickInsertCard(
  label: String,
  search: (String) -> List<EditorCatalogItem>,
  recentComponents: List<String>,
  onInsert: (EditorCatalogItem) -> Unit,
  onDismiss: () -> Unit,
  onTextInputFocusChanged: (Boolean) -> Unit,
  dragHandle: Modifier = Modifier,
) {
  var query by remember { mutableStateOf("") }
  // Reset to the top on every keystroke: the best match is the first row, and Enter should take it
  // without a glance at the list.
  var highlighted by remember { mutableStateOf(0) }
  val items = remember(query, search) { search(query) }
  val index = highlighted.coerceIn(0, (items.size - 1).coerceAtLeast(0))
  // The shelf heading only while browsing, as in the insert panel: a search ranks recents among
  // everything else.
  val recentCount =
    if (query.isBlank()) items.takeWhile { it.componentId in recentComponents }.size else 0
  val requester = remember { FocusRequester() }
  val listState = rememberLazyListState()
  // The key that asked for this was typed at the canvas, so the caret goes straight to the search.
  LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
  // Arrowing past the visible rows scrolls them along, so the highlight is never off-screen.
  LaunchedEffect(index, items) {
    val visible = listState.layoutInfo.visibleItemsInfo
    if (visible.isNotEmpty() && visible.none { it.index == index && it.offset >= 0 }) {
      listState.scrollToItem(index)
    }
  }
  fun insert() {
    items.getOrNull(index)?.let(onInsert)
  }
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 4.dp,
    shadowElevation = 8.dp,
    modifier = Modifier.semantics { contentDescription = "Quick insert" },
  ) {
    Column(
      Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(
        "Insert into $label",
        dragHandle.fillMaxWidth(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Box(
        Modifier.fillMaxWidth()
          .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
          .padding(horizontal = 8.dp, vertical = 6.dp)
      ) {
        if (query.isEmpty()) {
          Text(
            "Search components…",
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodyMedium,
          )
        }
        BasicTextField(
          value = query,
          onValueChange = {
            query = it
            highlighted = 0
          },
          singleLine = true,
          textStyle =
            MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
          cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
          modifier =
            Modifier.fillMaxWidth()
              .focusRequester(requester)
              .onFocusChanged { onTextInputFocusChanged(it.isFocused) }
              // The preview pass, ahead of the field's own handling: Up and Down would otherwise
              // move the caret, and Enter would do nothing in a single line.
              .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                  Key.DirectionDown -> {
                    highlighted = (index + 1).coerceAtMost((items.size - 1).coerceAtLeast(0))
                    true
                  }
                  Key.DirectionUp -> {
                    highlighted = (index - 1).coerceAtLeast(0)
                    true
                  }
                  Key.Enter,
                  Key.NumPadEnter -> {
                    insert()
                    true
                  }
                  Key.Escape -> {
                    onDismiss()
                    true
                  }
                  else -> false
                }
              }
              .semantics { contentDescription = "Quick insert search" },
        )
      }
      if (items.isEmpty()) {
        Text(
          if (query.isBlank()) "Nothing in the catalog fits here"
          else "No component matches “${query.trim()}”",
          Modifier.padding(vertical = 6.dp),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
        )
      } else {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 264.dp), state = listState) {
          itemsIndexed(items, key = { _, item -> item.componentId }) { position, item ->
            // One item with its heading rather than a heading item, so the highlight's index and
            // the list's index stay the same number.
            Column {
              if (recentCount > 0 && (position == 0 || position == recentCount)) {
                QuickInsertHeading(if (position == 0) RECENT_SHELF else "All components")
              }
              QuickInsertRow(item, highlighted = position == index, onClick = { onInsert(item) })
            }
          }
        }
      }
    }
  }
}

@Composable
private fun QuickInsertHeading(text: String) {
  Text(
    text,
    Modifier.padding(start = 8.dp, top = 6.dp, bottom = 2.dp),
    color = MaterialTheme.colorScheme.primary,
    style = MaterialTheme.typography.labelSmall,
  )
}

@Composable
private fun QuickInsertRow(item: EditorCatalogItem, highlighted: Boolean, onClick: () -> Unit) {
  Row(
    Modifier.fillMaxWidth()
      .background(
        if (highlighted) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surface,
        RoundedCornerShape(6.dp),
      )
      .clickable(onClick = onClick)
      .padding(horizontal = 8.dp, vertical = 6.dp)
      .semantics {
        contentDescription = "Insert ${item.displayName}"
        selected = highlighted
      },
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      item.displayName,
      Modifier.weight(1f),
      color =
        if (highlighted) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurface,
      style = MaterialTheme.typography.bodyMedium,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    // The family, so two components with one name (a Material and a Wear button) can be told apart.
    Text(
      item.group,
      Modifier.padding(start = 8.dp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}
