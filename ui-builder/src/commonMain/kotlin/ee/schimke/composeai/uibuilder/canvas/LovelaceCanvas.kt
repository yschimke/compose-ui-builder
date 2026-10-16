package ee.schimke.composeai.uibuilder.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.export.*
import kotlin.math.ceil
import kotlinx.serialization.json.*

/** Session-only card documents and named values supplied by the embedding host, never saved. */
val LocalLovelaceCardPreviews = staticCompositionLocalOf<JsonObject> { JsonObject(emptyMap()) }

/** Dashboard-specific structure; cards are played from host documents, never imitated. */
@Composable
internal fun LovelaceCanvas(
  document: UiBuilderDocument,
  node: UiBuilderNode,
  modifier: Modifier,
  child: @Composable (String, Modifier) -> Unit,
  reportFallback: @Composable () -> Unit = {},
) {
  when (node.componentId) {
    LovelaceConfigExporter.DASHBOARD -> {
      val views = node.slots["views"].orEmpty()
      var chosen by remember(node.id) { mutableStateOf<String?>(null) }
      val active = chosen?.takeIf { it in views } ?: views.firstOrNull()
      val chain = LocalUiBuilderRevealChain.current
      LaunchedEffect(chain, views) { views.firstOrNull { it in chain }?.let { chosen = it } }
      Column(modifier.background(MaterialTheme.colorScheme.background)) {
        Row(
          Modifier.horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          views.forEachIndexed { index, id ->
            FilterChip(
              active == id,
              { chosen = id },
              label = {
                Text(document.nodes[id]?.lovelaceTitle().orEmpty().ifBlank { "View ${index + 1}" })
              },
            )
          }
        }
        active?.let { id ->
          key(id) {
            child(id, Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()))
          }
        }
        if (views.isEmpty())
          Text("Add a view to this dashboard", color = MaterialTheme.colorScheme.onSurface)
      }
    }
    LovelaceConfigExporter.VIEW -> {
      val sections = node.slots["sections"].orEmpty()
      LovelaceGrid(
        document,
        sections,
        node.number("max_columns", 4).coerceIn(1, 12),
        "column_span",
        null,
        modifier,
        child,
      )
    }
    LovelaceConfigExporter.SECTION ->
      Column(modifier.padding(8.dp)) {
        node
          .string("title")
          .takeIf { it.isNotBlank() }
          ?.let {
            Text(
              it,
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurface,
            )
          }
        LovelaceGrid(
          document,
          node.slots["cards"].orEmpty(),
          12,
          "grid_columns",
          "grid_rows",
          Modifier.fillMaxWidth(),
          child,
        )
      }
    LovelaceConfigExporter.OPAQUE_VIEW ->
      NativeOnlyPlaceholder(node, modifier, "Preserved view") {
        reportFallback()
        Text(
          "This view keeps its original layout and configuration. Export includes it unchanged.",
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
    else -> {
      val preview = LocalLovelaceCardPreviews.current[node.id] as? JsonObject
      val current =
        LovelaceConfigExporter.exportCard(document, node.id)
          as? LovelaceConfigExporter.Result.Emitted
      // Replies can arrive after an edit. Only draw a render of this exact card configuration.
      if (
        preview != null &&
          preview["config"] == current?.config &&
          preview["properties"] is JsonObject
      ) {
        RemoteComposeDocument(
          document,
          node.copy(properties = preview.getValue("properties").jsonObject),
          modifier,
          emptyMap(),
          onEvent = {},
          slotContent = { _, _ -> },
        )
      } else
        NativeOnlyPlaceholder(
          node,
          modifier,
          if (preview?.get("config") == current?.config && preview?.get("error") != null)
            "Preview unavailable"
          else "Loading preview",
        ) {
          reportFallback()
          Text(
            node.string("name").ifBlank { node.string("entity").ifBlank { node.string("title") } },
            color = MaterialTheme.colorScheme.onSurface,
          )
        }
    }
  }
}

internal fun UiBuilderNode.lovelaceTitle(): String =
  string("title").ifBlank {
    runCatching {
        Json.parseToJsonElement(string(LovelaceConfigExporter.RAW_CONFIG))
          .jsonObject["title"]
          ?.jsonPrimitive
          ?.contentOrNull
          .orEmpty()
      }
      .getOrDefault("")
  }

private fun UiBuilderNode.number(name: String, default: Int): Int =
  ((properties[name] as? JsonObject)?.get("value") as? JsonPrimitive)?.intOrNull ?: default

/** First-fit sections grid. One row is 56dp; automatic cards keep their measured content height. */
@Composable
private fun LovelaceGrid(
  document: UiBuilderDocument,
  ids: List<String>,
  columns: Int,
  spanProperty: String,
  rowsProperty: String?,
  modifier: Modifier,
  child: @Composable (String, Modifier) -> Unit,
) {
  Layout(content = { ids.forEach { child(it, Modifier.padding(4.dp)) } }, modifier = modifier) {
    measurables,
    constraints ->
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else 1200
    val cardGrid = spanProperty == "grid_columns"
    val gridColumns = if (cardGrid) 12 else (width / 320.dp.roundToPx()).coerceIn(1, columns)
    val cellWidth = (width / gridColumns).coerceAtLeast(1)
    val rowHeight = 56.dp.roundToPx().coerceAtLeast(1)
    val occupied = mutableSetOf<Pair<Int, Int>>()
    data class Placed(val placeable: androidx.compose.ui.layout.Placeable, val x: Int, val y: Int)
    val placed = measurables.mapIndexed { index, measurable ->
      val item = document.nodes[ids[index]]
      val span =
        item?.number(spanProperty, if (cardGrid) 12 else 1)?.coerceIn(1, gridColumns) ?: gridColumns
      val fixedRows =
        rowsProperty?.let { item?.number(it, 0) }?.takeIf { it > 0 }?.coerceAtMost(256)
      val itemWidth = span * cellWidth
      val fixedHeight = fixedRows?.times(rowHeight)
      val size =
        measurable.measure(
          if (fixedHeight == null) Constraints(maxWidth = itemWidth, minWidth = itemWidth)
          else Constraints.fixed(itemWidth, fixedHeight)
        )
      val rows = fixedRows ?: ceil(size.height.toDouble() / rowHeight).toInt().coerceAtLeast(1)
      var y = 0
      var x = 0
      fun fits(): Boolean =
        (y until y + rows).all { r -> (x until x + span).all { c -> (c to r) !in occupied } }
      while (!fits()) {
        x++
        if (x + span > gridColumns) {
          x = 0
          y++
        }
      }
      for (r in y until y + rows) for (c in x until x + span) occupied += c to r
      Placed(size, x * cellWidth, y * rowHeight)
    }
    val height = placed.maxOfOrNull { it.y + it.placeable.height } ?: 0
    layout(width, constraints.constrainHeight(height)) {
      placed.forEach { it.placeable.placeRelative(it.x, it.y) }
    }
  }
}
