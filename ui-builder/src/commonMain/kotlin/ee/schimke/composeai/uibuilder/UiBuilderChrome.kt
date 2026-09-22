package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Host-rendered editor chrome at the boundary between UI Builder and its container.
 *
 * The browser keeps [MaterialUiBuilderChrome]. IntelliJ supplies a Jewel implementation for these
 * controls while the document canvas and component thumbnails continue to use the catalog's real
 * Compose renderer. This interface intentionally starts with the shared navigator frame; more
 * chrome can move behind it without putting an IDE dependency in common code.
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
}

internal val LocalUiBuilderChrome = compositionLocalOf<UiBuilderChrome> { MaterialUiBuilderChrome }
