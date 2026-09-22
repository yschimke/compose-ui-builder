package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CodeOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * Host-rendered editor chrome at the boundary between UI Builder and its container.
 *
 * The browser keeps [MaterialUiBuilderChrome]. IntelliJ supplies a Jewel implementation for these
 * controls while the document canvas and component thumbnails continue to use the catalog's real
 * Compose renderer. Navigation and menu primitives move behind this boundary without putting an IDE
 * dependency in common code.
 */
interface UiBuilderChrome {
  @Composable fun NavigatorSurface(modifier: Modifier, content: @Composable () -> Unit)

  @Composable fun DockHeading(title: String, supporting: String?, onClose: (() -> Unit)?)

  @Composable
  fun SearchField(
    value: String,
    placeholder: String,
    searchLabel: String,
    onFocusChanged: (Boolean) -> Unit,
    onValueChange: (String) -> Unit,
  )

  @Composable fun PanelHeading(title: String, supporting: String)

  @Composable fun GroupHeading(title: String)

  @Composable fun ComponentBrowserDestination(text: String, available: Boolean)

  @Composable fun ComponentBrowserAddBeside(checked: Boolean, onToggle: () -> Unit)

  @Composable fun ComponentBrowserPacksSummary(label: String, onManage: () -> Unit)

  @Composable fun ComponentBrowserAllRow(total: Int, onShowAll: () -> Unit)

  @Composable
  fun ComponentBrowserGroupRow(
    name: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
  )

  @Composable
  fun ComponentBrowserTile(
    model: UiBuilderCatalogTileModel,
    thumbnail: @Composable () -> Unit,
  )

  /** A host-native popup over a semantic list shared by browser and IDE chrome. */
  @Composable
  fun PopupMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    entries: List<UiBuilderMenuEntry>,
    offset: DpOffset = DpOffset.Zero,
  )
}

/** The chrome around one shared, catalog-rendered component thumbnail. */
data class UiBuilderCatalogTileModel(
  val title: String,
  val supporting: String?,
  val supportingIsError: Boolean = false,
  val variant: Boolean = false,
  val defaultVariant: Boolean = false,
  val unexportable: Boolean = false,
  val pinned: Boolean? = null,
  val variantCount: Int = 0,
  val variantsExpanded: Boolean = false,
  val canAdd: Boolean,
  val addContentDescription: String,
  val onAdd: () -> Unit,
  val onTogglePinned: (() -> Unit)? = null,
  val onToggleVariants: (() -> Unit)? = null,
)

/** A popup row or separator with no UI-toolkit types in its model. */
sealed interface UiBuilderMenuEntry {
  data class Action(
    val label: String,
    val detail: String? = null,
    val detailStyle: UiBuilderMenuDetailStyle = UiBuilderMenuDetailStyle.Label,
    val icon: UiBuilderMenuIcon? = null,
    val selected: Boolean = false,
    val reserveIconSpace: Boolean = false,
    val compactLeadingIcon: Boolean = false,
    val enabled: Boolean = true,
    val shortcut: String? = null,
    val contentDescription: String = label,
    val children: List<Action> = emptyList(),
    val onClick: () -> Unit,
  ) : UiBuilderMenuEntry

  data object Divider : UiBuilderMenuEntry
}

/** The two supporting-text treatments used by the existing Material menus. */
enum class UiBuilderMenuDetailStyle {
  Label,
  Body,
}

/** Toolkit-neutral icon names used by editor menus. */
enum class UiBuilderMenuIcon {
  Folder,
  Keyboard,
  Tidy,
  Refresh,
  Components,
  Help,
  Copy,
  Link,
  Download,
  Properties,
  Duplicate,
  Cut,
  Paste,
  Delete,
  Wrap,
}

/** Existing browser and desktop chrome. Kept as the default for source and visual compatibility. */
object MaterialUiBuilderChrome : UiBuilderChrome {
  @Composable
  override fun NavigatorSurface(modifier: Modifier, content: @Composable () -> Unit) {
    Surface(modifier, color = MaterialTheme.colorScheme.surface) { content() }
  }

  @Composable
  override fun DockHeading(title: String, supporting: String?, onClose: (() -> Unit)?) {
    Row(
      Modifier.fillMaxWidth().height(44.dp).padding(start = 14.dp, end = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f)) {
        Text(
          title,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (supporting != null) {
          Text(
            supporting,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      if (onClose != null) {
        ToolbarIconAction("Close $title", "", Icons.Filled.Close, true, onClose)
      }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
  }

  @Composable
  override fun SearchField(
    value: String,
    placeholder: String,
    searchLabel: String,
    onFocusChanged: (Boolean) -> Unit,
    onValueChange: (String) -> Unit,
  ) {
    Surface(
      Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp, vertical = 5.dp),
      shape = RoundedCornerShape(10.dp),
      color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
      Row(
        Modifier.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Icon(Icons.Filled.Search, contentDescription = null, Modifier.size(18.dp))
        Box(Modifier.weight(1f)) {
          if (value.isEmpty()) {
            Text(
              placeholder,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodyMedium,
            )
          }
          BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier =
              Modifier.fillMaxWidth()
                .onFocusChanged { onFocusChanged(it.isFocused) }
                .semantics { contentDescription = searchLabel },
            textStyle =
              MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            singleLine = true,
          )
        }
      }
    }
  }

  @Composable
  override fun PanelHeading(title: String, supporting: String) {
    Row(
      Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
          supporting,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }

  @Composable
  override fun GroupHeading(title: String) {
    Text(
      title,
      Modifier.fillMaxWidth()
        .background(Color(0xff202126))
        .padding(horizontal = 14.dp, vertical = 5.dp),
      color = MaterialTheme.colorScheme.primary,
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.Bold,
    )
  }

  @Composable
  override fun ComponentBrowserDestination(text: String, available: Boolean) {
    Text(
      text,
      Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
      color =
        if (available) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
  }

  @Composable
  override fun ComponentBrowserAddBeside(checked: Boolean, onToggle: () -> Unit) {
    Row(
      Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, bottom = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f)) {
        Text("Add beside", style = MaterialTheme.typography.labelMedium)
        Text(
          "Place items side by side instead of inside the selection",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.labelSmall,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Switch(
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
      Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        label,
        Modifier.weight(1f),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      TextButton(
        onClick = onManage,
        modifier = Modifier.semantics { contentDescription = "Manage component packs" },
      ) {
        Text("Packs…", style = MaterialTheme.typography.labelMedium)
      }
    }
  }

  @Composable
  override fun ComponentBrowserAllRow(total: Int, onShowAll: () -> Unit) {
    Surface(
      Modifier.fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 4.dp)
        .clip(RoundedCornerShape(20.dp))
        .clickable(onClick = onShowAll),
      shape = RoundedCornerShape(20.dp),
      color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
      Row(
        Modifier.padding(start = 16.dp, end = 8.dp).height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          "All",
          Modifier.weight(1f).semantics { contentDescription = "Show all $total components" },
          style = MaterialTheme.typography.bodyLarge,
        )
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = MaterialTheme.colorScheme.primaryContainer,
        ) {
          Text(
            total.toString(),
            Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.labelMedium,
          )
        }
      }
    }
  }

  @Composable
  override fun ComponentBrowserGroupRow(
    name: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
  ) {
    val accent =
      if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(
      Modifier.fillMaxWidth().height(40.dp).clickable(onClick = onToggle).semantics {
        contentDescription = "${if (expanded) "Collapse" else "Expand"} $name"
      },
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        Modifier.padding(start = 8.dp)
          .width(3.dp)
          .height(24.dp)
          .background(
            if (expanded) MaterialTheme.colorScheme.primary else Color.Transparent,
            RoundedCornerShape(2.dp),
          )
      )
      Icon(
        Icons.Filled.ArrowDropDown,
        contentDescription = null,
        modifier = Modifier.padding(start = 5.dp).size(20.dp).rotate(if (expanded) 0f else -90f),
        tint = accent,
      )
      Text(
        name,
        Modifier.padding(start = 2.dp).weight(1f),
        color = accent,
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Surface(
        Modifier.padding(end = 12.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
      ) {
        Text(
          count.toString(),
          Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.labelSmall,
        )
      }
    }
  }

  @Composable
  override fun ComponentBrowserTile(
    model: UiBuilderCatalogTileModel,
    thumbnail: @Composable () -> Unit,
  ) {
    Column(
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(if (model.variant) 8.dp else 10.dp))
        .background(
          if (model.variant) MaterialTheme.colorScheme.surfaceContainerHigh
          else MaterialTheme.colorScheme.surfaceVariant
        )
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
          style = MaterialTheme.typography.labelMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Text(
            model.title,
            Modifier.weight(1f).alpha(if (model.unexportable) 0.45f else 1f),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          if (model.unexportable) MaterialUnexportableBadge(model.title)
          model.onTogglePinned?.let { onToggle ->
            MaterialPinnedStar(model.title, model.pinned == true, onToggle)
          }
        }
      }
      if (model.variant) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          if (model.defaultVariant) {
            Text(
              "default",
              Modifier.weight(1f),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.labelSmall,
            )
          } else {
            Spacer(Modifier.weight(1f))
          }
          MaterialCatalogAddButton(model)
        }
      } else {
        model.supporting?.let {
          Text(
            it,
            color =
              if (model.supportingIsError) MaterialTheme.colorScheme.error
              else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            maxLines = if (model.supportingIsError) 2 else 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          if (model.variantCount > 0 && model.onToggleVariants != null) {
            TextButton(
              onClick = model.onToggleVariants,
              modifier =
                Modifier.weight(1f).semantics {
                  contentDescription =
                    "${if (model.variantsExpanded) "Hide" else "Show"} ${model.title} variants"
                },
              contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            ) {
              Text(
                if (model.variantsExpanded) "Hide variants" else "${model.variantCount} variants"
              )
            }
          } else {
            Spacer(Modifier.weight(1f))
          }
          MaterialCatalogAddButton(model)
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
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, offset = offset) {
      entries.forEach { entry ->
        when (entry) {
          UiBuilderMenuEntry.Divider -> HorizontalDivider()
          is UiBuilderMenuEntry.Action -> MaterialMenuAction(entry)
        }
      }
    }
  }
}

@Composable
private fun MaterialPinnedStar(componentName: String, pinned: Boolean, onToggle: () -> Unit) {
  Box(
    Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onToggle).semantics(
      mergeDescendants = true
    ) {
      contentDescription = if (pinned) "Unpin $componentName" else "Pin $componentName"
    },
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      if (pinned) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
      contentDescription = null,
      modifier = Modifier.size(16.dp),
      tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
    )
  }
}

@Composable
private fun MaterialUnexportableBadge(componentName: String) {
  Icon(
    Icons.Filled.CodeOff,
    contentDescription =
      "$componentName renders on the canvas, but the Compose export cannot write it yet",
    modifier = Modifier.padding(end = 6.dp).size(16.dp),
    tint = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

@Composable
private fun MaterialCatalogAddButton(model: UiBuilderCatalogTileModel) {
  TextButton(
    onClick = model.onAdd,
    enabled = model.canAdd,
    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
  ) {
    Text(
      "Add",
      Modifier.semantics { contentDescription = model.addContentDescription },
    )
  }
}

@Composable
private fun MaterialMenuAction(entry: UiBuilderMenuEntry.Action) {
  var submenuOpen by remember(entry.label) { mutableStateOf(false) }
  val iconModifier = if (entry.compactLeadingIcon) Modifier.size(18.dp) else Modifier
  val leadingIcon: (@Composable () -> Unit)? =
    when {
      entry.selected -> {
        { Icon(Icons.Filled.Check, contentDescription = null, modifier = iconModifier) }
      }
      entry.icon != null -> {
        { Icon(entry.icon.materialIcon(), contentDescription = null, modifier = iconModifier) }
      }
      entry.reserveIconSpace -> {
        { Spacer(Modifier.size(24.dp)) }
      }
      else -> null
    }
  val trailingIcon: (@Composable () -> Unit)? =
    when {
      entry.children.isNotEmpty() -> {
        { Text("›") }
      }
      entry.shortcut != null -> {
        {
          Text(
            entry.shortcut,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
          )
        }
      }
      else -> null
    }
  Box {
    DropdownMenuItem(
      text = {
        Column {
          Text(entry.label)
          entry.detail?.let {
            Text(
              it,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style =
                when (entry.detailStyle) {
                  UiBuilderMenuDetailStyle.Label -> MaterialTheme.typography.labelSmall
                  UiBuilderMenuDetailStyle.Body -> MaterialTheme.typography.bodySmall
                },
            )
          }
        }
      },
      enabled = entry.enabled,
      leadingIcon = leadingIcon,
      trailingIcon = trailingIcon,
      modifier = Modifier.semantics { contentDescription = entry.contentDescription },
      onClick = { if (entry.children.isEmpty()) entry.onClick() else submenuOpen = true },
    )
    DropdownMenu(expanded = submenuOpen, onDismissRequest = { submenuOpen = false }) {
      entry.children.forEach { child -> MaterialMenuAction(child) }
    }
  }
}

private fun UiBuilderMenuIcon.materialIcon(): ImageVector =
  when (this) {
    UiBuilderMenuIcon.Folder -> Icons.Filled.FolderOpen
    UiBuilderMenuIcon.Keyboard -> Icons.Filled.Keyboard
    UiBuilderMenuIcon.Tidy -> Icons.Filled.Tune
    UiBuilderMenuIcon.Refresh -> Icons.Filled.Refresh
    UiBuilderMenuIcon.Components -> Icons.Filled.Widgets
    UiBuilderMenuIcon.Help -> Icons.AutoMirrored.Filled.HelpOutline
    UiBuilderMenuIcon.Copy -> Icons.Filled.ContentCopy
    UiBuilderMenuIcon.Link -> Icons.Filled.Link
    UiBuilderMenuIcon.Download -> Icons.Filled.Download
    UiBuilderMenuIcon.Properties -> Icons.Filled.Tune
    UiBuilderMenuIcon.Duplicate -> Icons.Filled.LibraryAdd
    UiBuilderMenuIcon.Cut -> Icons.Filled.ContentCut
    UiBuilderMenuIcon.Paste -> Icons.Filled.ContentPaste
    UiBuilderMenuIcon.Delete -> Icons.Filled.DeleteOutline
    UiBuilderMenuIcon.Wrap -> Icons.Filled.Widgets
  }

internal val LocalUiBuilderChrome = compositionLocalOf<UiBuilderChrome> { MaterialUiBuilderChrome }
