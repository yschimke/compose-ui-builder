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
  override fun InspectorProperty(
    model: UiBuilderInspectorPropertyModel,
    content: @Composable () -> Unit,
  ) {
    Box(Modifier.padding(1.dp)) { MaterialUiBuilderChrome.InspectorProperty(model, content) }
  }

  @Composable
  override fun InspectorAddPropertyRow(label: String, type: String, onAdd: () -> Unit) {
    Box(Modifier.padding(1.dp)) {
      MaterialUiBuilderChrome.InspectorAddPropertyRow(label, type, onAdd)
    }
  }

  @Composable
  override fun InspectorBooleanProperty(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
  ) {
    Box(Modifier.padding(1.dp)) {
      MaterialUiBuilderChrome.InspectorBooleanProperty(label, checked, onCheckedChange)
    }
  }

  @Composable
  override fun InspectorTextField(model: UiBuilderInspectorTextFieldModel) {
    MaterialUiBuilderChrome.InspectorTextField(model.copy(modifier = model.modifier.padding(1.dp)))
  }

  @Composable
  override fun InspectorValueField(model: UiBuilderInspectorValueFieldModel) {
    MaterialUiBuilderChrome.InspectorValueField(model.copy(modifier = model.modifier.padding(1.dp)))
  }

  @Composable
  override fun InspectorFormHeader(title: String, supporting: String) {
    Box(Modifier.padding(1.dp)) { MaterialUiBuilderChrome.InspectorFormHeader(title, supporting) }
  }

  @Composable
  override fun InspectorChoiceRow(
    label: String,
    choices: List<UiBuilderInspectorChoiceModel>,
  ) {
    Box(Modifier.padding(1.dp)) { MaterialUiBuilderChrome.InspectorChoiceRow(label, choices) }
  }

  @Composable
  override fun InspectorAction(model: UiBuilderInspectorActionModel) {
    MaterialUiBuilderChrome.InspectorAction(model.copy(modifier = model.modifier.padding(1.dp)))
  }

  @Composable
  override fun InspectorBinding(variable: String, onUnbind: () -> Unit) {
    Box(Modifier.padding(1.dp)) { MaterialUiBuilderChrome.InspectorBinding(variable, onUnbind) }
  }

  @Composable
  override fun ComponentBrowserTile(
    model: UiBuilderCatalogTileModel,
    thumbnail: @Composable () -> Unit,
  ) {
    Box(Modifier.padding(1.dp)) { MaterialUiBuilderChrome.ComponentBrowserTile(model, thumbnail) }
  }
}
