@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class UiBuilderLayer {
  Design,
  EditorOverlay,
}

fun uiBuilderLayers(editorOverlay: Boolean): List<UiBuilderLayer> =
  if (editorOverlay) listOf(UiBuilderLayer.Design, UiBuilderLayer.EditorOverlay)
  else listOf(UiBuilderLayer.Design)

/**
 * Renders only native Compose components. Measurement is attached to each component's existing
 * modifier chain; editor decoration is painted by a sibling layer and cannot affect design layout.
 */
@Composable
fun UiBuilderSurface(document: UiBuilderDocument, editorOverlay: Boolean = false) {
  val bounds = remember { mutableStateMapOf<String, Rect>() }
  var selectedTrack by remember(document.id) { mutableStateOf<String?>(null) }
  MaterialTheme {
    Box(Modifier.fillMaxSize()) {
      document.roots.forEach { root ->
        RenderNode(
          document = document,
          nodeId = root,
          selectedTrack = selectedTrack,
          onSelectedTrack = { selectedTrack = it },
          onBounds = { id, rect -> bounds[id] = rect },
        )
      }
      if (editorOverlay) {
        Canvas(Modifier.fillMaxSize()) {
          bounds.values.forEach { rect ->
            drawRect(
              color = Color(0xff6750a4),
              topLeft = rect.topLeft,
              size = rect.size,
              style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun RenderNode(
  document: UiBuilderDocument,
  nodeId: String,
  selectedTrack: String?,
  onSelectedTrack: (String?) -> Unit,
  onBounds: (String, Rect) -> Unit,
  modifier: Modifier = Modifier,
) {
  val node = requireNotNull(document.nodes[nodeId]) { "unknown node: $nodeId" }
  val measured =
    node.modifiers
      .fold(modifier) { result, item -> result.applyModifier(item.jsonObject) }
      .onGloballyPositioned { onBounds(node.id, it.boundsInRoot()) }
  fun slot(name: String): List<String> = node.slots[name].orEmpty()

  when (node.componentId) {
    "layout/scaffold" ->
      Scaffold(
        modifier = measured,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
          slot("topBar").forEach {
            RenderNode(document, it, selectedTrack, onSelectedTrack, onBounds)
          }
        },
      ) { contentPadding ->
        slot("content").forEach {
          RenderNode(
            document,
            it,
            selectedTrack,
            onSelectedTrack,
            onBounds,
            Modifier.padding(contentPadding),
          )
        }
      }
    "m3/center-aligned-top-app-bar" ->
      CenterAlignedTopAppBar(
        modifier = measured,
        colors =
          TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
          ),
        title = {
          slot("title").forEach {
            RenderNode(document, it, selectedTrack, onSelectedTrack, onBounds)
          }
        },
      )
    "layout/column" ->
      Column(measured) {
        slot("children").forEach { childId ->
          val childModifier =
            if (document.nodes.getValue(childId).componentId == "layout/lazy-column") {
              Modifier.fillMaxWidth().weight(1f)
            } else {
              Modifier
            }
          RenderNode(document, childId, selectedTrack, onSelectedTrack, onBounds, childModifier)
        }
      }
    "layout/row" ->
      Row(
        modifier = measured,
        horizontalArrangement =
          Arrangement.spacedBy(node.propertyFloat("horizontalSpacingDp", 0f).dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
      ) {
        slot("children").forEach {
          RenderNode(document, it, selectedTrack, onSelectedTrack, onBounds)
        }
      }
    "layout/lazy-row" -> {
      val spacing = node.propertyFloat("horizontalSpacingDp", 0f)
      val padding = node.propertyObject("contentPadding")
      LazyRow(
        modifier = measured,
        horizontalArrangement = Arrangement.spacedBy(spacing.dp),
        contentPadding =
          PaddingValues(
            start = padding.float("startDp").dp,
            top = padding.float("topDp").dp,
            end = padding.float("endDp").dp,
            bottom = padding.float("bottomDp").dp,
          ),
      ) {
        items(slot("items"), key = { it }) { id ->
          RenderNode(document, id, selectedTrack, onSelectedTrack, onBounds)
        }
      }
    }
    "m3/filter-chip" -> {
      val selected = node.stateEquals("selected", selectedTrack)
      FilterChip(
        modifier = measured,
        selected = selected,
        onClick = { onSelectedTrack(node.eventValue("click", selectedTrack)) },
        label = {
          slot("label").forEach {
            RenderNode(document, it, selectedTrack, onSelectedTrack, onBounds)
          }
        },
        leadingIcon =
          slot("leadingIcon")
            .takeIf { it.isNotEmpty() }
            ?.let { ids ->
              { ids.forEach { RenderNode(document, it, selectedTrack, onSelectedTrack, onBounds) } }
            },
      )
    }
    "m3/primary-tab-row" ->
      PrimaryTabRow(selectedTabIndex = node.propertyInt("selectedIndex", 0), modifier = measured) {
        slot("tabs").forEach { RenderNode(document, it, selectedTrack, onSelectedTrack, onBounds) }
      }
    "m3/tab" ->
      Tab(
        selected = node.propertyBool("selected", false),
        onClick = {},
        modifier = measured,
        text = {
          slot("text").forEach {
            RenderNode(document, it, selectedTrack, onSelectedTrack, onBounds)
          }
        },
      )
    "layout/lazy-column" ->
      LazyColumn(modifier = measured) {
        items(slot("items"), key = { it }) { id ->
          RenderNode(document, id, selectedTrack, onSelectedTrack, onBounds)
        }
      }
    "m3/list-item" -> {
      val accent = parseArgb(node.propertyString("startAccentColor"))
      ListItem(
        modifier =
          measured.drawBehind {
            if (node.propertyString("startAccentColor").isNotEmpty()) {
              drawRect(
                Color(accent),
                size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height),
              )
            }
          },
        headlineContent = {
          slot("headline").forEach {
            RenderNode(document, it, selectedTrack, onSelectedTrack, onBounds)
          }
        },
        supportingContent =
          slot("supporting")
            .takeIf { it.isNotEmpty() }
            ?.let { ids ->
              { ids.forEach { RenderNode(document, it, selectedTrack, onSelectedTrack, onBounds) } }
            },
        trailingContent =
          slot("trailing")
            .takeIf { it.isNotEmpty() }
            ?.let { ids ->
              { ids.forEach { RenderNode(document, it, selectedTrack, onSelectedTrack, onBounds) } }
            },
      )
    }
    "m3/surface" ->
      Surface(
        modifier = measured,
        color = node.propertyColorToken("containerColor"),
        shape = RoundedCornerShape(node.propertyFloat("shapeDp", 0f).dp),
      ) {
        slot("content").forEach {
          RenderNode(document, it, selectedTrack, onSelectedTrack, onBounds)
        }
      }
    "m3/horizontal-divider" -> HorizontalDivider(modifier = measured)
    "m3/icon" ->
      Icon(
        imageVector =
          when (node.propertyString("iconKey")) {
            "accessTime" -> Icons.Filled.AccessTime
            "bookmark" -> Icons.Filled.Bookmark
            "bookmarkBorder" -> Icons.Outlined.BookmarkBorder
            "coffee" -> Icons.Filled.Coffee
            else -> error("unsupported icon: ${node.propertyString("iconKey")}")
          },
        contentDescription = null,
        modifier = measured.size(node.propertyFloat("sizeDp", 24f).dp),
        tint = node.propertyTextColor(),
      )
    "m3/text" ->
      Text(
        text = node.propertyString("text"),
        modifier = measured,
        style = node.textStyle(),
        fontWeight =
          when (node.propertyString("fontWeight")) {
            "bold" -> FontWeight.Bold
            "semiBold" -> FontWeight.SemiBold
            else -> null
          },
        color = node.propertyTextColor(),
        maxLines = node.propertyInt("maxLines", Int.MAX_VALUE),
        textAlign =
          if (node.propertyString("textAlign") == "center") TextAlign.Center else TextAlign.Start,
      )
    "shape/colour-dot" ->
      Box(
        measured
          .size(node.propertyFloat("diameterDp", 8f).dp)
          .clip(CircleShape)
          .background(Color(parseArgb(node.propertyString("color"))))
      )
    else -> error("unsupported component: ${node.componentId}")
  }
}

private fun Modifier.applyModifier(value: kotlinx.serialization.json.JsonObject): Modifier =
  when (value.optionalString("type")) {
    "fillMaxSize" -> fillMaxSize()
    "fillMaxWidth" -> fillMaxWidth()
    "padding" ->
      padding(
        start = value.float("startDp").dp,
        top = value.float("topDp").dp,
        end = value.float("endDp").dp,
        bottom = value.float("bottomDp").dp,
      )
    else -> this
  }

private fun UiBuilderNode.propertyObject(name: String) =
  properties[name]?.jsonObject ?: kotlinx.serialization.json.JsonObject(emptyMap())

private fun UiBuilderNode.propertyString(name: String): String =
  propertyObject(name)["value"]?.jsonPrimitive?.contentOrNull.orEmpty()

private fun UiBuilderNode.propertyFloat(name: String, fallback: Float): Float =
  propertyObject(name)["value"]?.jsonPrimitive?.floatOrNull ?: fallback

private fun UiBuilderNode.propertyInt(name: String, fallback: Int): Int =
  propertyObject(name)["value"]?.jsonPrimitive?.intOrNull ?: fallback

private fun UiBuilderNode.propertyBool(name: String, fallback: Boolean): Boolean =
  propertyObject(name)["value"]?.jsonPrimitive?.booleanOrNull ?: fallback

@Composable
private fun UiBuilderNode.textStyle() =
  when (propertyString("style")) {
    "titleLarge" -> MaterialTheme.typography.titleLarge
    "titleMedium" -> MaterialTheme.typography.titleMedium
    "titleSmall" -> MaterialTheme.typography.titleSmall
    "bodyMedium" -> MaterialTheme.typography.bodyMedium
    "labelSmall" -> MaterialTheme.typography.labelSmall
    else -> LocalTextStyle.current
  }

@Composable
private fun UiBuilderNode.propertyTextColor(): Color =
  when (propertyString("color")) {
    "primary" -> MaterialTheme.colorScheme.primary
    "onSurfaceVariant" -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> Color.Unspecified
  }

@Composable
private fun UiBuilderNode.propertyColorToken(name: String): Color =
  when (propertyString(name)) {
    "surfaceContainer" -> MaterialTheme.colorScheme.surfaceContainer
    "surfaceContainerLow" -> MaterialTheme.colorScheme.surfaceContainerLow
    else -> Color.Transparent
  }

private fun UiBuilderNode.stateEquals(name: String, actual: String?): Boolean {
  val expected = propertyObject(name)["value"]
  return if (expected == null || expected is JsonNull) actual == null
  else expected.jsonPrimitive.contentOrNull == actual
}

private fun UiBuilderNode.eventValue(name: String, current: String?): String? {
  val action =
    eventBindings[name]?.let { it as? kotlinx.serialization.json.JsonArray }?.firstOrNull()
      ?: return current
  val objectValue = action.jsonObject
  val value = objectValue["value"]
  val selected = value?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull
  return if (objectValue.optionalString("type") == "selectOrClear" && current == selected) null
  else selected
}

private fun kotlinx.serialization.json.JsonObject.float(name: String): Float =
  this[name]?.jsonPrimitive?.floatOrNull ?: 0f

private fun parseArgb(value: String): Long =
  value.removePrefix("#").toLongOrNull(16)?.let { if (value.length == 7) it or 0xff000000 else it }
    ?: 0xff000000
