@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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

/** Native Compose design pixels plus an optional sibling-only editor overlay. */
@Composable
fun UiBuilderSurface(document: UiBuilderDocument, editorOverlay: Boolean = false) {
  val bounds = remember { mutableStateMapOf<String, Rect>() }
  val state =
    remember(document.id) {
      mutableStateMapOf<String, String?>().also { target ->
        document.stateVariables.forEach { (name, declaration) ->
          target[name] =
            declaration
              .objectOrEmpty()["initialValue"]
              ?.takeUnless { it is JsonNull }
              ?.jsonPrimitive
              ?.contentOrNull
        }
      }
    }
  val dark = document.environment["theme"]?.jsonPrimitive?.contentOrNull == "dark"
  MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
    Box(Modifier.fillMaxSize()) {
      document.roots.forEach { root ->
        RenderNode(
          document = document,
          nodeId = root,
          state = state,
          onState = { key, value -> state[key] = value },
          onBounds = { id, rect -> bounds[id] = rect },
        )
      }
      if (editorOverlay) {
        Canvas(Modifier.fillMaxSize()) {
          bounds.values.forEach { rect ->
            drawRect(
              Color(0xff6750a4),
              rect.topLeft,
              rect.size,
              style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()),
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
  state: Map<String, String?>,
  onState: (String, String?) -> Unit,
  onBounds: (String, Rect) -> Unit,
  modifier: Modifier = Modifier,
) {
  val node = requireNotNull(document.nodes[nodeId]) { "unknown node: $nodeId" }
  val measured =
    node.modifiers
      .fold(modifier) { result, value -> result.applyModifier(value.jsonObject, node.id) }
      .then(node.actionModifier(state, onState))
      .onGloballyPositioned { onBounds(node.id, it.boundsInRoot()) }
  fun slot(name: String) = node.slots[name].orEmpty()
  val child: @Composable (String, Modifier) -> Unit = { id, next ->
    RenderNode(document, id, state, onState, onBounds, next)
  }

  when (node.componentId) {
    "layout/supporting-pane-scaffold" ->
      DeterministicSupportingPaneScaffold(
        node,
        measured,
        { next -> slot("mainPane").forEach { child(it, next) } },
        { next -> slot("supportingPane").forEach { child(it, next) } },
      )
    "layout/scaffold" ->
      Scaffold(
        modifier = measured,
        containerColor = node.color("containerColor", MaterialTheme.colorScheme.background),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { slot("topBar").forEach { child(it, Modifier) } },
        snackbarHost = { slot("snackbarHost").forEach { child(it, Modifier) } },
      ) { padding ->
        slot("content").forEach { child(it, Modifier.padding(padding)) }
      }
    "layout/box" ->
      Box(measured) {
        slot("children").forEach { id ->
          val item = document.nodes.getValue(id)
          val parentSizing =
            if (item.hasModifier("matchParentSize")) Modifier.matchParentSize() else Modifier
          child(
            id,
            parentSizing.align(item.childAlignment()).zIndex(item.float("zIndex")),
          )
        }
      }
    "layout/column" ->
      Column(
        measured,
        verticalArrangement = Arrangement.spacedBy(node.float("verticalSpacingDp").dp),
      ) {
        slot("children").forEach { id ->
          val item = document.nodes.getValue(id)
          val weight = item.float("weight")
          val next =
            when {
              weight > 0f -> Modifier.weight(weight)
              item.componentId == "layout/lazy-column" -> Modifier.fillMaxWidth().weight(1f)
              else -> Modifier
            }
          child(id, next)
        }
      }
    "layout/row" ->
      Row(
        measured,
        horizontalArrangement = Arrangement.spacedBy(node.float("horizontalSpacingDp").dp),
        verticalAlignment = node.verticalAlignment(),
      ) {
        slot("children").forEach { id ->
          val weight = document.nodes.getValue(id).float("weight")
          child(id, if (weight > 0f) Modifier.weight(weight) else Modifier)
        }
      }
    "layout/lazy-row" ->
      LazyRow(
        measured,
        contentPadding = node.obj("contentPadding").paddingValues(),
        horizontalArrangement = Arrangement.spacedBy(node.float("horizontalSpacingDp").dp),
      ) {
        items(slot("items"), key = { it }) { child(it, Modifier) }
      }
    "layout/lazy-column" ->
      LazyColumn(
        measured,
        contentPadding = node.obj("contentPadding").paddingValues(),
        verticalArrangement = Arrangement.spacedBy(node.float("verticalSpacingDp").dp),
      ) {
        items(slot("items"), key = { it }) { child(it, Modifier) }
      }
    "layout/lazy-grid" -> {
      val minimum = node.obj("columns").number("minimumCellWidthDp", 362f).coerceAtLeast(1f)
      LazyVerticalGrid(
        columns = GridCells.Adaptive(minimum.dp),
        modifier = measured,
        contentPadding = node.obj("contentPadding").paddingValues(),
      ) {
        items(
          items = slot("items"),
          key = { it },
          span = { id ->
            if (document.nodes.getValue(id).string("span") == "full") GridItemSpan(maxLineSpan)
            else GridItemSpan(1)
          },
        ) {
          child(it, Modifier)
        }
      }
    }
    "layout/horizontal-carousel" ->
      CompatibleHorizontalCarousel(node, measured, slot("items")) { id, next -> child(id, next) }
    "m3/center-aligned-top-app-bar" ->
      CenterAlignedTopAppBar(
        modifier = measured,
        colors =
          TopAppBarDefaults.topAppBarColors(
            containerColor = node.color("containerColor", Color.Transparent),
            scrolledContainerColor = node.color("scrolledContainerColor", Color.Transparent),
          ),
        title = { slot("title").forEach { child(it, Modifier) } },
      )
    "m3/search-bar" ->
      Surface(
        measured,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = node.float("tonalElevationDp").dp,
      ) {
        Column { slot("inputField").forEach { child(it, Modifier) } }
      }
    "m3/search-input-field" -> {
      val variable = node.obj("value")["variable"]?.jsonPrimitive?.contentOrNull
      val value = variable?.let(state::get).orEmpty()
      Row(
        measured.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        slot("leadingIcon").forEach { child(it, Modifier) }
        Box(Modifier.weight(1f)) {
          if (value.isEmpty()) slot("placeholder").forEach { child(it, Modifier) }
          BasicTextField(
            value,
            { if (variable != null) onState(variable, it) },
            Modifier.fillMaxWidth(),
            enabled = node.bool("enabled", true),
            textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
            singleLine = true,
          )
        }
        slot("trailingIcon").forEach { child(it, Modifier) }
      }
    }
    "m3/snackbar-host" ->
      if (node.bool("visible")) Snackbar(measured) { Text(node.string("message")) }
      else Box(measured)
    "m3/filter-chip" ->
      FilterChip(
        selected = node.resolvedBool("selected", state),
        onClick = { node.dispatch("click", state, onState) },
        modifier = measured,
        enabled = node.bool("enabled", true),
        label = { slot("label").forEach { child(it, Modifier) } },
        leadingIcon =
          slot("leadingIcon").takeIf(List<String>::isNotEmpty)?.let { ids ->
            { ids.forEach { child(it, Modifier) } }
          },
      )
    "m3/primary-tab-row" ->
      PrimaryTabRow(node.integer("selectedIndex"), measured) {
        slot("tabs").forEach { child(it, Modifier) }
      }
    "m3/tab" ->
      Tab(
        node.bool("selected"),
        { node.dispatch("click", state, onState) },
        measured,
        text = { slot("text").forEach { child(it, Modifier) } },
      )
    "m3/list-item" ->
      LegacyListItem(node, measured, slot("headline"), slot("supporting"), slot("trailing"), child)
    "m3/surface" ->
      Surface(
        measured,
        shape = node.shape(),
        color = node.color("containerColor", Color.Transparent),
      ) {
        slot("content").forEach { child(it, Modifier) }
      }
    "m3/card" ->
      Card(
        measured,
        shape = node.shape(),
        colors =
          CardDefaults.cardColors(
            node.color("containerColor", MaterialTheme.colorScheme.surfaceContainer)
          ),
      ) {
        Box(Modifier.fillMaxSize()) {
          slot("content").forEach { id ->
            val item = document.nodes.getValue(id)
            val parentSizing =
              if (item.hasModifier("matchParentSize")) Modifier.matchParentSize() else Modifier
            child(
              id,
              parentSizing.align(item.childAlignment()).zIndex(item.float("zIndex")),
            )
          }
        }
      }
    "m3/icon-button" ->
      IconButton(
        { node.dispatch("click", state, onState) },
        measured
          .size(node.float("sizeDp", 48f).dp)
          .then(
            if ("selected" in node.properties) {
              Modifier.background(Color.Black.copy(alpha = 0.46f), CircleShape)
            } else {
              Modifier
            }
          ),
      ) {
        slot("content").forEach { child(it, Modifier) }
      }
    "m3/button" ->
      BuilderButton(node, measured, state, onState) {
        slot("content").forEach { child(it, Modifier) }
      }
    "m3/horizontal-floating-toolbar" ->
      CompatibleFloatingToolbar(node, measured) { slot("content").forEach { child(it, Modifier) } }
    "m3/horizontal-divider" ->
      HorizontalDivider(
        measured,
        color = node.color("color", MaterialTheme.colorScheme.outlineVariant),
      )
    "m3/icon" ->
      Icon(
        node.icon(),
        node.string("contentDescription").ifEmpty { null },
        measured.size(node.float("sizeDp", 24f).dp),
        tint = node.color("color", MaterialTheme.colorScheme.onSurface),
      )
    "m3/text" ->
      Text(
        node.string("text"),
        measured,
        color = node.color("color", Color.Unspecified),
        style = node.textStyle(),
        fontWeight = node.fontWeight(),
        maxLines = node.integer("maxLines", Int.MAX_VALUE),
        overflow =
          if (node.string("overflow") == "ellipsis") TextOverflow.Ellipsis else TextOverflow.Clip,
        textAlign = if (node.string("textAlign") == "center") TextAlign.Center else TextAlign.Start,
      )
    "asset/image" -> AssetPlaceholder(node, measured)
    "shape/linear-gradient" ->
      Box(
        measured.background(
          Brush.verticalGradient(
            listOf(
              node.color("startColor", Color.Transparent),
              node.color("endColor", Color.Transparent),
            )
          )
        )
      )
    "shape/radial-gradient" ->
      Box(
        measured.background(
          Brush.radialGradient(
            listOf(
              node
                .color("innerColor", MaterialTheme.colorScheme.primary)
                .copy(alpha = node.float("innerAlpha", 1f)),
              node.color("outerColor", Color.Transparent),
            )
          )
        )
      )
    "shape/colour-dot" ->
      Box(
        measured
          .size(node.float("diameterDp", 8f).dp)
          .clip(CircleShape)
          .background(Color(parseArgb(node.string("color"))))
      )
    else -> UnsupportedComponentDiagnostic(node.componentId, measured)
  }
}

/**
 * Material adaptive is not on this module's dependency floor. This explicit compatibility layout
 * implements only deterministic expanded two-pane sizing and single-pane fallback; it does not
 * claim posture, motion, navigation, or predictive-back behaviour.
 */
@Composable
private fun DeterministicSupportingPaneScaffold(
  node: UiBuilderNode,
  modifier: Modifier,
  mainPane: @Composable (Modifier) -> Unit,
  supportingPane: @Composable (Modifier) -> Unit,
) {
  val mainVisible = node.bool("mainPaneVisible", true)
  val supportingVisible = node.bool("supportingPaneVisible", true)
  val mainWidth = node.float("mainPanePreferredWidthDp", 744f).coerceAtLeast(1f)
  val supportWidth = node.float("supportingPanePreferredWidthDp", 512f).coerceAtLeast(1f)
  val spacing = node.float("paneSpacingDp").coerceAtLeast(0f)
  BoxWithConstraints(modifier) {
    val expanded =
      node.string("layoutMode") in setOf("expandedTwoPane", "twoPane") &&
        mainVisible &&
        supportingVisible &&
        maxWidth >= (mainWidth + supportWidth + spacing).dp
    if (expanded) {
      Row(Modifier.fillMaxSize()) {
        mainPane(Modifier.width(mainWidth.dp).fillMaxSize())
        Spacer(Modifier.width(spacing.dp))
        supportingPane(Modifier.weight(1f).fillMaxSize())
      }
    } else if (mainVisible) mainPane(Modifier.fillMaxSize())
    else if (supportingVisible) supportingPane(Modifier.fillMaxSize())
    else Box(Modifier.fillMaxSize())
  }
}

/**
 * Material's uncontained carousel is not on the dependency floor; this preserves its data/layout
 * contract.
 */
@Composable
private fun CompatibleHorizontalCarousel(
  node: UiBuilderNode,
  modifier: Modifier,
  ids: List<String>,
  child: @Composable (String, Modifier) -> Unit,
) {
  LazyRow(
    modifier,
    contentPadding = PaddingValues(start = node.float("contentPaddingStartDp").dp),
    horizontalArrangement = Arrangement.spacedBy(node.float("itemSpacingDp").dp),
  ) {
    items(ids, key = { it }) { child(it, Modifier.width(node.float("itemWidthDp", 128f).dp)) }
  }
}

/** Experimental floating-toolbar identity with deterministic Material surface/row semantics. */
@Composable
private fun CompatibleFloatingToolbar(
  node: UiBuilderNode,
  modifier: Modifier,
  content: @Composable () -> Unit,
) {
  Surface(
    modifier,
    shape = RoundedCornerShape(32.dp),
    color = node.color("containerColor", MaterialTheme.colorScheme.surfaceContainerHighest),
    shadowElevation = 6.dp,
  ) {
    Row(
      Modifier.padding(4.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      content()
    }
  }
}

@Composable
private fun BuilderButton(
  node: UiBuilderNode,
  modifier: Modifier,
  state: Map<String, String?>,
  onState: (String, String?) -> Unit,
  content: @Composable () -> Unit,
) {
  val click = { node.dispatch("click", state, onState) }
  when (node.string("style")) {
    "text" -> TextButton(click, modifier) { content() }
    "filledTonal" -> FilledTonalButton(click, modifier) { content() }
    "fab" ->
      FloatingActionButton(
        click,
        modifier,
        containerColor = node.color("containerColor", MaterialTheme.colorScheme.primary),
      ) {
        content()
      }
    else ->
      Button(
        click,
        modifier,
        colors =
          ButtonDefaults.buttonColors(
            node.color("containerColor", MaterialTheme.colorScheme.primary)
          ),
      ) {
        content()
      }
  }
}

@Composable
private fun LegacyListItem(
  node: UiBuilderNode,
  modifier: Modifier,
  headline: List<String>,
  supporting: List<String>,
  trailing: List<String>,
  child: @Composable (String, Modifier) -> Unit,
) {
  val accent = parseArgb(node.string("startAccentColor"))
  ListItem(
    headlineContent = { headline.forEach { child(it, Modifier) } },
    modifier =
      modifier.drawBehind {
        if (node.string("startAccentColor").isNotEmpty()) {
          drawRect(
            Color(accent),
            size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height),
          )
        }
      },
    supportingContent =
      supporting.takeIf(List<String>::isNotEmpty)?.let {
        { it.forEach { id -> child(id, Modifier) } }
      },
    trailingContent =
      trailing.takeIf(List<String>::isNotEmpty)?.let {
        { it.forEach { id -> child(id, Modifier) } }
      },
  )
}

@Composable
private fun AssetPlaceholder(node: UiBuilderNode, modifier: Modifier) {
  val key = node.string("assetKey")
  val palette =
    when (key) {
      "jetcaster.cover.android-developers-backstage" ->
        listOf(Color(0xFF0B57D0), Color(0xFF00A896), Color(0xFF101828))
      "jetcaster.cover.google-developers-podcast" ->
        listOf(Color(0xFFEA4335), Color(0xFFFBBC04), Color(0xFF174EA6))
      else -> error("unsupported asset '$key' on ${node.id}")
    }
  Canvas(modifier) {
    drawRect(Brush.linearGradient(palette, Offset.Zero, Offset(size.width, size.height)))
    drawCircle(
      Color.White.copy(alpha = 0.18f),
      size.minDimension * 0.34f,
      Offset(size.width * 0.76f, size.height * 0.24f),
    )
    drawCircle(
      Color.Black.copy(alpha = 0.18f),
      size.minDimension * 0.22f,
      Offset(size.width * 0.22f, size.height * 0.72f),
    )
    drawPath(
      Path().apply {
        moveTo(size.width * 0.19f, size.height * 0.32f)
        lineTo(size.width * 0.48f, size.height * 0.18f)
        lineTo(size.width * 0.82f, size.height * 0.58f)
        lineTo(size.width * 0.48f, size.height * 0.78f)
        close()
      },
      Color.White.copy(alpha = 0.27f),
    )
    listOf(
        Triple(0.30f, 0.39f, 0.28f),
        Triple(0.47f, 0.30f, 0.38f),
        Triple(0.64f, 0.43f, 0.24f),
      )
      .forEach { (x, y, height) ->
        drawRect(
          Color.White.copy(alpha = 0.72f),
          Offset(size.width * x, size.height * y),
          androidx.compose.ui.geometry.Size(size.width * 0.10f, size.height * height),
        )
      }
    drawCircle(
      Color.White.copy(alpha = 0.72f),
      size.minDimension * 0.28f,
      style = Stroke(size.minDimension * 0.035f),
    )
  }
}

@Composable
private fun UnsupportedComponentDiagnostic(componentId: String, modifier: Modifier) {
  Surface(modifier, color = MaterialTheme.colorScheme.errorContainer) {
    Text(
      "Unsupported component: $componentId",
      Modifier.padding(8.dp),
      color = MaterialTheme.colorScheme.onErrorContainer,
    )
  }
}

private fun Modifier.applyModifier(value: JsonObject, nodeId: String): Modifier =
  when (val type = value.optionalString("type")) {
    "fillMaxSize" -> fillMaxSize()
    "fillMaxWidth" -> fillMaxWidth()
    "padding" -> padding(value.paddingValues())
    "size" -> {
      val width = value.numberOrNull("widthDp")
      val height = value.numberOrNull("heightDp")
      when {
        width != null && height != null -> size(width.dp, height.dp)
        width != null -> width(width.dp)
        height != null -> height(height.dp)
        else ->
          throw IllegalArgumentException("size modifier on $nodeId requires widthDp or heightDp")
      }
    }
    "clip" -> clip(shapeFor(value.optionalString("shape")))
    // Applied by the owning BoxScope so it does not contribute to the parent's measurement.
    "matchParentSize" -> this
    null -> throw IllegalArgumentException("modifier on $nodeId requires a type")
    else -> throw IllegalArgumentException("unsupported modifier '$type' on $nodeId")
  }

private fun UiBuilderNode.actionModifier(
  state: Map<String, String?>,
  onState: (String, String?) -> Unit,
): Modifier =
  if (eventBindings["click"] == null || componentId in INTERACTIVE_COMPONENTS) Modifier
  else Modifier.clickable { dispatch("click", state, onState) }

private fun UiBuilderNode.childAlignment(): Alignment =
  when (string("alignment")) {
    "topCenter" -> Alignment.TopCenter
    "topEnd" -> Alignment.TopEnd
    "centerStart" -> Alignment.CenterStart
    "center" -> Alignment.Center
    "centerEnd" -> Alignment.CenterEnd
    "bottomStart" -> Alignment.BottomStart
    "bottomCenter" -> Alignment.BottomCenter
    "bottomEnd" -> Alignment.BottomEnd
    else -> Alignment.TopStart
  }

private fun UiBuilderNode.dispatch(
  event: String,
  state: Map<String, String?>,
  onState: (String, String?) -> Unit,
) {
  val actions = eventBindings[event] as? JsonArray ?: return
  actions.forEach { element ->
    val action = element.jsonObject
    val variable = action.optionalString("variable") ?: return@forEach
    val value = action["value"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull
    when (action.optionalString("type")) {
      "select",
      "setText" -> onState(variable, value)
      "selectOrClear" -> onState(variable, if (state[variable] == value) null else value)
      else -> error("unsupported action '${action.optionalString("type")}' on $id")
    }
  }
}

private fun UiBuilderNode.resolvedBool(name: String, state: Map<String, String?>): Boolean {
  val value = obj(name)
  return if (value.optionalString("type") == "stateEquals") {
    state[value.optionalString("variable")] == value.optionalString("value")
  } else value["value"]?.jsonPrimitive?.booleanOrNull ?: false
}

private fun UiBuilderNode.obj(name: String): JsonObject =
  properties[name]?.objectOrEmpty() ?: JsonObject(emptyMap())

private fun UiBuilderNode.hasModifier(type: String): Boolean = modifiers.any {
  it.objectOrEmpty().optionalString("type") == type
}

private fun UiBuilderNode.string(name: String): String =
  obj(name)["value"]?.jsonPrimitive?.contentOrNull.orEmpty()

private fun UiBuilderNode.float(name: String, fallback: Float = 0f): Float =
  obj(name)["value"]?.jsonPrimitive?.floatOrNull ?: fallback

private fun UiBuilderNode.integer(name: String, fallback: Int = 0): Int =
  obj(name)["value"]?.jsonPrimitive?.intOrNull ?: fallback

private fun UiBuilderNode.bool(name: String, fallback: Boolean = false): Boolean =
  obj(name)["value"]?.jsonPrimitive?.booleanOrNull ?: fallback

@Composable
private fun UiBuilderNode.textStyle() =
  when (string("style")) {
    "headlineSmall" -> MaterialTheme.typography.headlineSmall
    "titleLarge" -> MaterialTheme.typography.titleLarge
    "titleMedium" -> MaterialTheme.typography.titleMedium
    "titleSmall" -> MaterialTheme.typography.titleSmall
    "bodyLarge" -> MaterialTheme.typography.bodyLarge
    "bodyMedium" -> MaterialTheme.typography.bodyMedium
    "bodySmall" -> MaterialTheme.typography.bodySmall
    "labelLarge" -> MaterialTheme.typography.labelLarge
    "labelSmall" -> MaterialTheme.typography.labelSmall
    "" -> LocalTextStyle.current
    else -> error("unsupported text style '${string("style")}' on $id")
  }

private fun UiBuilderNode.fontWeight() =
  when (string("fontWeight")) {
    "bold" -> FontWeight.Bold
    "semiBold" -> FontWeight.SemiBold
    "medium" -> FontWeight.Medium
    else -> null
  }

@Composable
private fun UiBuilderNode.color(name: String, fallback: Color): Color {
  val value = string(name)
  if (value.startsWith("#")) return Color(parseArgb(value))
  return when (value) {
    "background" -> MaterialTheme.colorScheme.background
    "surface" -> MaterialTheme.colorScheme.surface
    "surfaceContainer" -> MaterialTheme.colorScheme.surfaceContainer
    "surfaceContainerLow" -> MaterialTheme.colorScheme.surfaceContainerLow
    "surfaceContainerHigh" -> MaterialTheme.colorScheme.surfaceContainerHigh
    "surfaceContainerHighest" -> MaterialTheme.colorScheme.surfaceContainerHighest
    "primary" -> MaterialTheme.colorScheme.primary
    "tertiary" -> MaterialTheme.colorScheme.tertiary
    "onTertiary" -> MaterialTheme.colorScheme.onTertiary
    "onSurface" -> MaterialTheme.colorScheme.onSurface
    "onSurfaceVariant" -> MaterialTheme.colorScheme.onSurfaceVariant
    "outlineVariant" -> MaterialTheme.colorScheme.outlineVariant
    "transparent" -> Color.Transparent
    "" -> fallback
    else -> error("unsupported color token '$value' for $name on $id")
  }
}

private fun UiBuilderNode.shape() =
  if (string("shape").isNotEmpty()) shapeFor(string("shape"), id)
  else RoundedCornerShape(float("shapeDp").dp)

private fun shapeFor(value: String?, nodeId: String? = null) =
  RoundedCornerShape(
    when (value) {
      "large" -> 16.dp
      "medium" -> 12.dp
      "small" -> 8.dp
      "",
      null -> 0.dp
      else ->
        value.toFloatOrNull()?.dp
          ?: error("unsupported shape '$value'${nodeId?.let { " on $it" }.orEmpty()}")
    }
  )

private fun UiBuilderNode.verticalAlignment() =
  when (string("verticalAlignment")) {
    "top" -> Alignment.Top
    "bottom" -> Alignment.Bottom
    else -> Alignment.CenterVertically
  }

private fun UiBuilderNode.icon(): ImageVector =
  when (val key = string("iconKey")) {
    "accessTime" -> Icons.Filled.AccessTime
    "accountCircle" -> Icons.Filled.AccountCircle
    "addCircle" -> Icons.Filled.AddCircle
    "bookmark" -> Icons.Filled.Bookmark
    "bookmarkBorder" -> Icons.Outlined.BookmarkBorder
    "check" -> Icons.Filled.Check
    "checkCircle" -> Icons.Filled.CheckCircle
    "coffee" -> Icons.Filled.Coffee
    "genres" -> Icons.Filled.Category
    "moreVert" -> Icons.Filled.MoreVert
    "playCircle" -> Icons.Filled.PlayCircle
    "playlistAdd" -> Icons.AutoMirrored.Filled.PlaylistAdd
    "search" -> Icons.Filled.Search
    "videoLibrary" -> Icons.Filled.VideoLibrary
    else -> error("unsupported icon '$key' on $id")
  }

private fun JsonObject.paddingValues() =
  PaddingValues(
    start = number("startDp").dp,
    top = number("topDp").dp,
    end = number("endDp").dp,
    bottom = number("bottomDp").dp,
  )

private fun JsonObject.number(name: String, fallback: Float = 0f) =
  this[name]?.jsonPrimitive?.floatOrNull ?: fallback

private fun JsonObject.numberOrNull(name: String) = this[name]?.jsonPrimitive?.floatOrNull

private fun JsonElement.objectOrEmpty() = this as? JsonObject ?: JsonObject(emptyMap())

private fun parseArgb(value: String): Long =
  value.removePrefix("#").toLongOrNull(16)?.let { if (value.length == 7) it or 0xff000000 else it }
    ?: 0xff000000

private val INTERACTIVE_COMPONENTS =
  setOf("m3/button", "m3/filter-chip", "m3/icon-button", "m3/tab")
