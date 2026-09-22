package ee.schimke.composeai.uibuilder.intellij

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.UiBuilderChrome
import ee.schimke.composeai.uibuilder.UiBuilderMenuEntry
import ee.schimke.composeai.uibuilder.UiBuilderMenuIcon
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.ActionButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.MenuScope
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.separator
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** IntelliJ-native implementation of the editor chrome that has crossed the host boundary. */
@OptIn(ExperimentalJewelApi::class)
internal object JewelUiBuilderChrome : UiBuilderChrome {
  @Composable
  override fun NavigatorSurface(modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier.background(JewelTheme.globalColors.toolwindowBackground)) { content() }
  }

  @Composable
  override fun DockHeading(title: String, supporting: String?, onClose: (() -> Unit)?) {
    Row(
      Modifier.fillMaxWidth().height(40.dp).padding(start = 12.dp, end = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f)) {
        Text(
          title,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (supporting != null) {
          Text(supporting, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      }
      if (onClose != null) {
        ActionButton(
          onClick = onClose,
          modifier = Modifier.semantics { contentDescription = "Close $title" },
        ) {
          Icon(
            AllIconsKeys.Actions.Close,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
          )
        }
      }
    }
    Divider(orientation = Orientation.Horizontal)
  }

  @Composable
  override fun SearchField(
    value: String,
    placeholder: String,
    searchLabel: String,
    onFocusChanged: (Boolean) -> Unit,
    onValueChange: (String) -> Unit,
  ) {
    var fieldValue by remember { mutableStateOf(TextFieldValue(value)) }
    LaunchedEffect(value) { if (value != fieldValue.text) fieldValue = TextFieldValue(value) }
    TextField(
      value = fieldValue,
      onValueChange = {
        fieldValue = it
        onValueChange(it.text)
      },
      modifier =
        Modifier.fillMaxWidth()
          .padding(horizontal = 8.dp, vertical = 6.dp)
          .onFocusChanged { onFocusChanged(it.isFocused) }
          .semantics { contentDescription = searchLabel },
      placeholder = { Text(placeholder) },
      leadingIcon = {
        Icon(
          AllIconsKeys.Actions.Search,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
        )
      },
    )
  }

  @Composable
  override fun PanelHeading(title: String, supporting: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
      Text(title, fontWeight = FontWeight.SemiBold)
      Text(supporting)
    }
  }

  @Composable
  override fun GroupHeading(title: String) {
    Text(
      title,
      Modifier.fillMaxWidth()
        .background(JewelTheme.globalColors.panelBackground)
        .padding(horizontal = 12.dp, vertical = 4.dp),
      fontWeight = FontWeight.SemiBold,
    )
  }

  @Composable
  override fun PopupMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    entries: List<UiBuilderMenuEntry>,
    offset: DpOffset,
  ) {
    if (!expanded) return
    PopupMenu(
      onDismissRequest = {
        onDismissRequest()
        true
      },
      horizontalAlignment = Alignment.Start,
      modifier = Modifier.offset(offset.x, offset.y),
      adContent = null,
    ) {
      jewelEntries(entries)
    }
  }
}

private fun MenuScope.jewelEntries(entries: List<UiBuilderMenuEntry>) {
  entries.forEach { entry ->
    when (entry) {
      UiBuilderMenuEntry.Divider -> separator()
      is UiBuilderMenuEntry.Action ->
        if (entry.children.isEmpty()) {
          selectableItem(
            selected = false,
            iconKey = if (entry.selected) AllIconsKeys.Actions.Checked else entry.icon?.jewelIcon(),
            keybinding = entry.shortcut?.let(::setOf),
            onClick = entry.onClick,
            enabled = entry.enabled,
          ) {
            JewelMenuLabel(entry)
          }
        } else {
          submenu(
            enabled = entry.enabled,
            iconKey = entry.icon?.jewelIcon(),
            submenu = { jewelEntries(entry.children) },
          ) {
            Text(entry.label)
          }
        }
    }
  }
}

@Composable
private fun JewelMenuLabel(entry: UiBuilderMenuEntry.Action) {
  Column {
    Text(entry.label)
    entry.detail?.let { Text(it) }
  }
}

private fun UiBuilderMenuIcon.jewelIcon(): IconKey =
  when (this) {
    UiBuilderMenuIcon.Folder -> AllIconsKeys.Nodes.Folder
    UiBuilderMenuIcon.Keyboard -> AllIconsKeys.General.Keyboard
    UiBuilderMenuIcon.Tidy -> AllIconsKeys.Actions.ReformatCode
    UiBuilderMenuIcon.Refresh -> AllIconsKeys.Actions.Refresh
    UiBuilderMenuIcon.Components -> AllIconsKeys.Nodes.Plugin
    UiBuilderMenuIcon.Help -> AllIconsKeys.Actions.Help
    UiBuilderMenuIcon.Copy -> AllIconsKeys.Actions.Copy
    UiBuilderMenuIcon.Link -> AllIconsKeys.Actions.MenuOpen
    UiBuilderMenuIcon.Download -> AllIconsKeys.Actions.Download
    UiBuilderMenuIcon.Properties -> AllIconsKeys.Actions.Properties
    UiBuilderMenuIcon.Duplicate -> AllIconsKeys.Actions.Copy
    UiBuilderMenuIcon.Cut -> AllIconsKeys.Actions.MenuCut
    UiBuilderMenuIcon.Paste -> AllIconsKeys.Actions.MenuPaste
    UiBuilderMenuIcon.Delete -> AllIconsKeys.Actions.DeleteTag
    UiBuilderMenuIcon.Wrap -> AllIconsKeys.Actions.GroupBy
  }
