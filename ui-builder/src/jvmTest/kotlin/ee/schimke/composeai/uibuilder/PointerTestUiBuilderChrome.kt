package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Proves pointer gestures survive an embedding host's tile wrapper, not only the default chrome.
 */
internal object PointerTestUiBuilderChrome : UiBuilderChrome by MaterialUiBuilderChrome {
  @Composable
  override fun ToolbarAction(model: UiBuilderToolbarActionModel) {
    Box(Modifier.padding(1.dp)) { MaterialUiBuilderChrome.ToolbarAction(model) }
  }

  @Composable
  override fun EditorRail(items: List<UiBuilderRailItemModel>, modifier: Modifier) {
    MaterialUiBuilderChrome.EditorRail(items, modifier.padding(1.dp))
  }

  @Composable
  override fun ComponentBrowserTile(
    model: UiBuilderCatalogTileModel,
    thumbnail: @Composable () -> Unit,
  ) {
    Box(Modifier.padding(1.dp)) { MaterialUiBuilderChrome.ComponentBrowserTile(model, thumbnail) }
  }
}
