package ee.schimke.composeai.uibuilder.intellij

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.UiBuilderCatalogTileModel
import ee.schimke.composeai.uibuilder.UiBuilderChrome
import ee.schimke.composeai.uibuilder.UiBuilderMenuEntry
import ee.schimke.composeai.uibuilder.UiBuilderMenuIcon
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.ActionButton
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.DefaultSlimButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.MenuScope
import org.jetbrains.jewel.ui.component.OutlinedSlimButton
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
  override fun ComponentBrowserDestination(text: String, available: Boolean) {
    Text(
      text,
      Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
      color =
        if (available) JewelTheme.globalColors.text.info else JewelTheme.globalColors.text.disabled,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
  }

  @Composable
  override fun ComponentBrowserAddBeside(checked: Boolean, onToggle: () -> Unit) {
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f)) {
        Text("Add beside")
        Text(
          "Place items side by side instead of inside the selection",
          color = JewelTheme.globalColors.text.info,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Checkbox(
        checked = checked,
        onCheckedChange = { onToggle() },
        modifier =
          Modifier.semantics {
            contentDescription =
              if (checked) "Add into the selected layer instead"
              else "Add beside the design instead"
          },
      )
    }
  }

  @Composable
  override fun ComponentBrowserPacksSummary(label: String, onManage: () -> Unit) {
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        label,
        Modifier.weight(1f),
        color = JewelTheme.globalColors.text.info,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      OutlinedSlimButton(
        onClick = onManage,
        modifier = Modifier.semantics { contentDescription = "Manage component packs" },
      ) {
        Text("Packs…")
      }
    }
  }

  @Composable
  override fun ComponentBrowserAllRow(total: Int, onShowAll: () -> Unit) {
    Row(
      Modifier.fillMaxWidth()
        .padding(horizontal = 8.dp, vertical = 3.dp)
        .clip(RoundedCornerShape(4.dp))
        .background(JewelTheme.globalColors.panelBackground)
        .clickable(onClick = onShowAll)
        .padding(horizontal = 8.dp)
        .height(30.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        "All",
        Modifier.weight(1f).semantics { contentDescription = "Show all $total components" },
      )
      Text(total.toString(), fontWeight = FontWeight.SemiBold)
    }
  }

  @Composable
  override fun ComponentBrowserGroupRow(
    name: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
  ) {
    Row(
      Modifier.fillMaxWidth()
        .height(32.dp)
        .clickable(onClick = onToggle)
        .semantics { contentDescription = "${if (expanded) "Collapse" else "Expand"} $name" }
        .padding(horizontal = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        if (expanded) AllIconsKeys.General.ChevronDown else AllIconsKeys.General.ChevronRight,
        contentDescription = null,
        modifier = Modifier.size(16.dp),
      )
      Text(
        name,
        Modifier.padding(start = 4.dp).weight(1f),
        color =
          if (expanded) JewelTheme.globalColors.text.info else JewelTheme.globalColors.text.normal,
        fontWeight = if (expanded) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(count.toString(), color = JewelTheme.globalColors.text.info)
    }
  }

  @Composable
  override fun ComponentBrowserTile(
    model: UiBuilderCatalogTileModel,
    thumbnail: @Composable () -> Unit,
  ) {
    Column(
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(4.dp))
        .background(JewelTheme.globalColors.panelBackground)
        .padding(6.dp)
    ) {
      Box(
        Modifier.fillMaxWidth().alpha(if (model.unexportable) 0.45f else 1f),
        contentAlignment = Alignment.Center,
      ) {
        thumbnail()
      }
      if (model.variant) {
        Text(
          model.title,
          Modifier.alpha(if (model.unexportable) 0.45f else 1f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Text(
            model.title,
            Modifier.weight(1f).alpha(if (model.unexportable) 0.45f else 1f),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          if (model.unexportable) {
            Icon(
              AllIconsKeys.General.Warning,
              contentDescription =
                "${model.title} renders on the canvas, but the Compose export cannot write it yet",
              modifier = Modifier.padding(end = 4.dp).size(16.dp),
            )
          }
          model.onTogglePinned?.let { onToggle ->
            ActionButton(
              onClick = onToggle,
              modifier =
                Modifier.semantics {
                  contentDescription =
                    if (model.pinned == true) "Unpin ${model.title}" else "Pin ${model.title}"
                },
            ) {
              Icon(
                if (model.pinned == true) AllIconsKeys.General.PinSelected
                else AllIconsKeys.General.Pin,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
              )
            }
          }
        }
      }
      if (!model.variant) {
        model.supporting?.let {
          Text(
            it,
            color =
              if (model.supportingIsError) JewelTheme.globalColors.text.error
              else JewelTheme.globalColors.text.info,
            maxLines = if (model.supportingIsError) 2 else 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      val onToggleVariants = model.onToggleVariants
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        when {
          model.variant && model.defaultVariant ->
            Text(
              "default",
              Modifier.weight(1f),
              color = JewelTheme.globalColors.text.info,
            )
          !model.variant && model.variantCount > 0 && onToggleVariants != null ->
            OutlinedSlimButton(
              onClick = onToggleVariants,
              modifier =
                Modifier.weight(1f).semantics {
                  contentDescription =
                    "${if (model.variantsExpanded) "Hide" else "Show"} ${model.title} variants"
                },
            ) {
              Text(
                if (model.variantsExpanded) "Hide variants" else "${model.variantCount} variants",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          else -> Spacer(Modifier.weight(1f))
        }
        DefaultSlimButton(onClick = model.onAdd, enabled = model.canAdd) {
          Text(
            "Add",
            Modifier.semantics { contentDescription = model.addContentDescription },
          )
        }
      }
    }
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
