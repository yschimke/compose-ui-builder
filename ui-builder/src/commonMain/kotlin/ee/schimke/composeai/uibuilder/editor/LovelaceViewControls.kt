package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ee.schimke.composeai.uibuilder.canvas.lovelaceTitle
import ee.schimke.composeai.uibuilder.canvas.selectionChain
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument

/** View navigation lives outside the design surface's selection and drag gestures. */
@Composable
internal fun LovelaceViewControls(
  document: UiBuilderDocument,
  selectedNodeId: String?,
  hostChrome: UiBuilderHostChrome?,
  onSelect: (String) -> Unit,
) {
  val views = document.roots.flatMap { document.nodes[it]?.slots?.get("views").orEmpty() }
  val chain = document.selectionChain(selectedNodeId)
  val selected = views.firstOrNull { it in chain } ?: views.firstOrNull()
  val entries = views.mapIndexed { index, id ->
    HostChromeEntry(
      UiBuilderHostAction(
        id = "lovelace.view.$id",
        label = document.nodes[id]?.lovelaceTitle().orEmpty().ifBlank { "View ${index + 1}" },
        group = "views",
        icon = "Layers",
        checked = selected == id,
      ),
      { onSelect(id) },
    )
  }
  if (hostChrome != null) PublishHostChrome(hostChrome, "lovelace.views", entries)
  else
    Row(Modifier.horizontalScroll(rememberScrollState())) {
      entries.forEach { entry ->
        FilterChip(
          entry.action.checked == true,
          entry.onInvoke,
          label = { Text(entry.action.label) },
        )
      }
    }
}
