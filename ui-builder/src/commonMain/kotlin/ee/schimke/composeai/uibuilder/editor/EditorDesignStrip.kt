package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.UidDesignCollection

/** One of the top-level designs an open file holds. */
data class UiBuilderFileDesign(val id: String, val title: String)

/**
 * The top-level designs the open file holds, for the strip above the canvas.
 *
 * The editor edits [active] and nothing else; the others are the host's, and so is moving between
 * them, because switching is a write to the file (it changes which design opens and previews) and
 * the host owns the file. Each action is null where the host cannot do it, which hides its control:
 * a read-only file, say, lists its designs without offering to switch.
 */
data class UiBuilderFileDesigns(
  val designs: List<UiBuilderFileDesign>,
  val active: String,
  val onSelect: ((String) -> Unit)? = null,
  val onAdd: (() -> Unit)? = null,
  val onRemove: (() -> Unit)? = null,
) {
  /** Whether the strip has anything to say: another design to show, or a way to add one. */
  val shown: Boolean
    get() = designs.size > 1 || onAdd != null
}

/** [UidDesignCollection]'s designs, as the strip lists them. */
fun UidDesignCollection.fileDesigns(
  onSelect: ((String) -> Unit)? = null,
  onAdd: (() -> Unit)? = null,
  onRemove: (() -> Unit)? = null,
): UiBuilderFileDesigns =
  UiBuilderFileDesigns(
    designs = designs.map { UiBuilderFileDesign(it.id, it.title) },
    active = active,
    onSelect = onSelect,
    onAdd = onAdd,
    onRemove = onRemove,
  )

/** How the strip names a design: its title, or its id when it has none. */
internal fun UiBuilderFileDesign.label(): String = title.ifBlank { id }

/**
 * The strip above the canvas listing the file's designs, the active one selected.
 *
 * [activeTitle] is the active design's title as the editor has it now, so renaming it in the Screen
 * inspector renames its chip at once rather than at the next open.
 */
@Composable
internal fun EditorDesignStrip(
  designs: UiBuilderFileDesigns,
  activeTitle: String,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier.fillMaxWidth(),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 1.dp,
  ) {
    Row(
      Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        "Designs",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(
        Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        designs.designs.forEach { design ->
          val active = design.id == designs.active
          val select = designs.onSelect
          FilterChip(
            selected = active,
            onClick = { if (!active) select?.invoke(design.id) },
            // The active chip stays enabled so it reads as selected rather than greyed out.
            enabled = active || select != null,
            label = {
              Text(
                if (active) activeTitle.ifBlank { design.id } else design.label(),
                Modifier.widthIn(max = 200.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            },
          )
        }
      }
      designs.onAdd?.let { add -> TextButton(onClick = add) { Text("Add design") } }
      designs.onRemove
        ?.takeIf { designs.designs.size > 1 }
        ?.let { remove -> TextButton(onClick = remove) { Text("Remove design") } }
    }
  }
}
