@file:OptIn(
  androidx.compose.material3.ExperimentalMaterial3Api::class,
  androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * What the host does when the operator asks for a picture, and what came back.
 *
 * Three outcomes rather than a nullable image, because the three mean different things to the
 * panel: a cancelled picker says nothing, a refusal has a sentence the operator needs to read, and
 * an import replaces what is attached. The host owns every part of this that is not portable —
 * opening a file picker, reading the clipboard, rendering a snapshot, sniffing the bytes, minting
 * the identity — because none of that exists in common Compose.
 */
sealed interface ReferenceImportOutcome {
  data class Imported(val image: ReferenceImage) : ReferenceImportOutcome

  /** A sentence for the operator: too big, wrong type, an SVG carrying something it may not. */
  data class Refused(val reason: String) : ReferenceImportOutcome

  /** The picker was dismissed, or the clipboard held nothing. Nothing to say. */
  data object Cancelled : ReferenceImportOutcome
}

/**
 * The reference panel: attach a picture, compare against it, mark it up, and fold the result back
 * into the next round.
 *
 * Lives under the screen frame rather than in a tab of its own. A reference is fitted to the frame
 * and judged against it, so the two controls belong to one question; and the tab row is already at
 * the width its four labels fit in.
 */
@Composable
internal fun ReferenceInspector(
  reference: ReferenceOverlayState,
  /** The design's own colours, so the erase brush can be the screen's background exactly. */
  themeSettings: EditorThemeSettings,
  onPickReference: (suspend () -> ReferenceImportOutcome)?,
  onSnapshotDesign: (suspend () -> ReferenceImportOutcome)?,
  onFlatten: () -> Unit,
  /** The catalog, for the component picker: a piece can be a picture of any of these. */
  catalogItems: List<EditorCatalogItem>,
  /** Capture a component and place the picture. The capture itself happens in the editor. */
  onPlaceComponent: (String) -> Unit,
  /** Build a piece's component for real, where the layout under it accepts one. */
  onPromotePiece: (ReferencePiece) -> Unit,
  canPromotePiece: (ReferencePiece) -> Boolean,
  /** A sentence the host wants shown — a refused paste, a store that would not keep it. */
  hostStatus: String?,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  val scope = rememberCoroutineScope()
  var status by remember(reference.image?.id) { mutableStateOf<String?>(null) }
  var busy by remember { mutableStateOf(false) }

  /** Runs one host import and turns its outcome into either an event or a sentence. */
  fun import(source: (suspend () -> ReferenceImportOutcome)?, onImage: (ReferenceImage) -> Unit) {
    val picker = source ?: return
    busy = true
    scope.launch {
      status =
        when (val outcome = picker()) {
          is ReferenceImportOutcome.Imported -> {
            onImage(outcome.image)
            null
          }
          is ReferenceImportOutcome.Refused -> outcome.reason
          ReferenceImportOutcome.Cancelled -> null
        }
      busy = false
    }
  }

  Text("Reference", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
  Text(
    "A picture to build against — a Figma export, a screenshot, a snapshot of this design. Kept " +
      "with the design and never part of it: no node holds it and no export sees it.",
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.bodySmall,
  )
  HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline)

  if (onPickReference == null && onSnapshotDesign == null && !reference.hasContent) {
    Text(
      "This host cannot import reference pictures.",
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodySmall,
    )
    return
  }

  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Button(
      onClick = { import(onPickReference) { dispatch(UiBuilderEditorEvent.AttachReference(it)) } },
      enabled = onPickReference != null && !busy,
      modifier = Modifier.weight(1f).semantics { contentDescription = "Import reference" },
    ) {
      Text(if (reference.attached) "Replace…" else "Import…")
    }
    OutlinedButton(
      onClick = { import(onSnapshotDesign) { dispatch(UiBuilderEditorEvent.AttachReference(it)) } },
      enabled = onSnapshotDesign != null && !busy,
      modifier = Modifier.weight(1f).semantics { contentDescription = "Snapshot design" },
    ) {
      Text("Snapshot")
    }
  }
  Text(
    "PNG, JPEG, WebP or SVG. Snapshot renders this design and builds the reference from it.",
    Modifier.padding(top = 6.dp),
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.labelSmall,
  )

  if (!reference.hasContent) {
    (status ?: hostStatus)?.let { ReferenceStatusText(it) }
    return
  }

  val settings = reference.settings
  fun update(block: ReferenceOverlaySettings.() -> ReferenceOverlaySettings) =
    dispatch(UiBuilderEditorEvent.UpdateReferenceSettings(settings.block()))

  reference.image?.let { image ->
    Text(
      image.name,
      Modifier.padding(top = 8.dp),
      style = MaterialTheme.typography.bodyMedium,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Text(
      listOfNotNull(
          image.mediaType,
          "${image.widthPx}×${image.heightPx}".takeIf { image.widthPx > 0 && image.heightPx > 0 },
          "${reference.layoutBoxes.size} boxes".takeIf { reference.layoutBoxes.isNotEmpty() },
          image.sourceUrl,
        )
        .joinToString(" · "),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
  }

  Row(
    Modifier.fillMaxWidth().padding(top = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text("Show overlay", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    Switch(
      checked = settings.visible,
      onCheckedChange = { dispatch(UiBuilderEditorEvent.ToggleReference) },
      modifier = Modifier.semantics { contentDescription = "Show reference overlay" },
    )
  }

  if (reference.attached) {
    ReferenceSectionHeading("Compare")
    Row(
      Modifier.fillMaxWidth().padding(top = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      reference.availableModes.forEach { mode ->
        FilterChip(
          selected = settings.mode == mode,
          onClick = { update { copy(mode = mode, visible = true) } },
          label = { Text(mode.label, style = MaterialTheme.typography.labelSmall) },
          modifier = Modifier.semantics { contentDescription = "${mode.label} reference mode" },
        )
      }
    }
    Text(
      settings.mode.explanation(),
      Modifier.padding(top = 4.dp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
    )

    if (settings.mode == ReferenceDiffMode.Overlay) {
      ReferencePercentSlider("Opacity", settings.opacityPercent, 0..100) {
        update { copy(opacityPercent = it) }
      }
    }
    if (settings.mode == ReferenceDiffMode.Split) {
      ReferencePercentSlider("Split", settings.splitPercent, 0..100) {
        update { copy(splitPercent = it) }
      }
    }
    if (reference.layoutBoxes.isNotEmpty() && settings.mode != ReferenceDiffMode.Boxes) {
      ReferenceSwitchRow("Also draw boxes", settings.alwaysShowBoxes) {
        update { copy(alwaysShowBoxes = it) }
      }
    }
    ReferencePercentSlider(
      "Scale",
      settings.scalePercent,
      ReferenceOverlaySettings.MIN_SCALE_PERCENT..ReferenceOverlaySettings.MAX_SCALE_PERCENT,
    ) {
      update { copy(scalePercent = it) }
    }
    ReferenceSectionHeading("Nudge (dp)")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      ReferenceNudgeAxis("X", settings.offsetXDp, Modifier.weight(1f)) {
        update { copy(offsetXDp = it) }
      }
      ReferenceNudgeAxis("Y", settings.offsetYDp, Modifier.weight(1f)) {
        update { copy(offsetYDp = it) }
      }
    }
  }

  ReferenceMarkupControls(
    reference = reference,
    themeSettings = themeSettings,
    busy = busy,
    catalogItems = catalogItems,
    onPlaceComponent = onPlaceComponent,
    onPromotePiece = onPromotePiece,
    canPromotePiece = canPromotePiece,
    onPlacePiece = {
      import(onPickReference) { dispatch(UiBuilderEditorEvent.PlaceReferencePiece(it)) }
    },
    onFlatten = onFlatten,
    dispatch = dispatch,
  )

  Row(
    Modifier.fillMaxWidth().padding(top = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    TextButton(
      onClick = { update { ReferenceOverlaySettings(mode = mode, visible = visible) } },
      modifier = Modifier.semantics { contentDescription = "Reset reference alignment" },
    ) {
      Text("Reset")
    }
    TextButton(onClick = { dispatch(UiBuilderEditorEvent.ClearReference) }) {
      Text("Remove all", color = MaterialTheme.colorScheme.error)
    }
  }
  (status ?: hostStatus)?.let { ReferenceStatusText(it) }
}

/**
 * The markup half: a tool, a colour, the pieces and marks that are on the frame, and the flatten
 * that turns all of it into the next reference.
 */
@Composable
private fun ReferenceMarkupControls(
  reference: ReferenceOverlayState,
  themeSettings: EditorThemeSettings,
  busy: Boolean,
  catalogItems: List<EditorCatalogItem>,
  onPlaceComponent: (String) -> Unit,
  onPromotePiece: (ReferencePiece) -> Unit,
  canPromotePiece: (ReferencePiece) -> Boolean,
  onPlacePiece: () -> Unit,
  onFlatten: () -> Unit,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  ReferenceSectionHeading("Markup")
  Text(
    "A tool takes the pointer: while one is chosen, dragging on the canvas draws instead of " +
      "selecting, and every shape but the pen is dragged out to the size you want. Every mark " +
      "can be rubbed out on its own.",
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.labelSmall,
  )
  // Wrapped, because there are nine tools and the inspector is 360 dp wide. A row that scrolled
  // sideways would hide half the toolbox behind a gesture nobody would think to try.
  FlowRow(
    Modifier.fillMaxWidth().padding(top = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    ReferenceTool.entries
      .filter { it != ReferenceTool.MovePiece || reference.pieces.isNotEmpty() }
      .forEach { tool ->
        FilterChip(
          selected = reference.tool == tool,
          onClick = { dispatch(UiBuilderEditorEvent.SelectReferenceTool(tool)) },
          label = { Text(tool.label, style = MaterialTheme.typography.labelSmall) },
          modifier = Modifier.semantics { contentDescription = "${tool.label} reference tool" },
        )
      }
  }
  if (reference.tool.markupKind.carriesText()) {
    ReferenceLabelField(reference.markupText) { dispatch(UiBuilderEditorEvent.SetMarkupText(it)) }
  }
  // The design's own background and surface sit in the palette beside the marker colours, because
  // the erase brush is only an erase when it is painted in the colour the screen already is —
  // and making the operator find that hex by hand is how a hole ends up nearly, visibly, wrong.
  val swatches =
    REFERENCE_MARKUP_COLORS.map { it to null as String? } +
      listOfNotNull(
        themeSettings.backgroundColor.markupArgbOrNull()?.let { it to "screen background" },
        themeSettings.surfaceColor.markupArgbOrNull()?.let { it to "surface" },
      )
  FlowRow(
    Modifier.fillMaxWidth().padding(top = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    swatches.forEach { (argb, role) ->
      Box(
        Modifier.size(22.dp)
          .background(Color(argb.toInt()), CircleShape)
          .border(
            width = if (reference.markupColorArgb == argb) 2.dp else 1.dp,
            color =
              if (reference.markupColorArgb == argb) MaterialTheme.colorScheme.onSurface
              else MaterialTheme.colorScheme.outline,
            shape = CircleShape,
          )
          .clickable { dispatch(UiBuilderEditorEvent.SelectMarkupColor(argb)) }
          .semantics {
            contentDescription =
              role?.let { "Markup colour, $it" } ?: "Markup colour ${argb.toString(16)}"
          }
      )
    }
  }
  if (reference.tool == ReferenceTool.Fill) {
    Text(
      "Paints over the reference in the chosen colour. Pick the screen background to clear space " +
        "in a screenshot, then build the real components into it.",
      Modifier.padding(top = 4.dp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
    )
  }

  // Wrapped and unweighted: three equal thirds of a 360 dp panel is not enough for "Component…"
  // to stay on one line, and a button whose label breaks mid-word reads as a rendering fault.
  FlowRow(
    Modifier.fillMaxWidth().padding(top = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    OutlinedButton(
      onClick = onPlacePiece,
      enabled = !busy,
      modifier = Modifier.semantics { contentDescription = "Place a piece" },
    ) {
      Text("Image…", maxLines = 1)
    }
    ReferenceComponentMenu(catalogItems = catalogItems, onPick = onPlaceComponent)
    OutlinedButton(
      onClick = onFlatten,
      enabled = reference.hasContent,
      modifier = Modifier.semantics { contentDescription = "Flatten reference" },
    ) {
      Text("Flatten", maxLines = 1)
    }
  }
  Text(
    "Image drops a picture on the frame where you put it — one copied out of Figma, say. " +
      "Component captures one from this catalog as a picture, which can be built for real once " +
      "it is where it belongs. Flatten bakes everything into one reference and clears it.",
    Modifier.padding(top = 4.dp),
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.labelSmall,
  )

  reference.pieces.forEach { piece ->
    Row(
      Modifier.fillMaxWidth().padding(top = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      TextButton(
        onClick = { dispatch(UiBuilderEditorEvent.SelectReferencePiece(piece.id)) },
        modifier = Modifier.weight(1f),
      ) {
        Text(
          piece.image.name,
          style = MaterialTheme.typography.labelSmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          fontWeight =
            if (piece.id == reference.selectedPieceId) FontWeight.Bold else FontWeight.Normal,
        )
      }
      TextButton(
        onClick = { dispatch(UiBuilderEditorEvent.ScaleReferencePiece(piece.id, 1f / 1.1f)) },
        modifier = Modifier.semantics { contentDescription = "Shrink ${piece.image.name}" },
      ) {
        Text("−")
      }
      TextButton(
        onClick = { dispatch(UiBuilderEditorEvent.ScaleReferencePiece(piece.id, 1.1f)) },
        modifier = Modifier.semantics { contentDescription = "Grow ${piece.image.name}" },
      ) {
        Text("+")
      }
      TextButton(
        onClick = { dispatch(UiBuilderEditorEvent.RemoveReferencePiece(piece.id)) },
        modifier = Modifier.semantics { contentDescription = "Remove ${piece.image.name}" },
      ) {
        Text("×", color = MaterialTheme.colorScheme.error)
      }
    }
    if (piece.componentId != null) {
      // Only for a piece that knows what it is. A picture with no provenance has no honest button
      // here — deciding which component a screenshot region *is* is a judgement, and this panel
      // does not make judgements; that is the case an agent is for.
      val promotable = canPromotePiece(piece)
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
          if (promotable) "Ready to build where it sits"
          else "Nothing under it accepts ${piece.componentId}",
          Modifier.weight(1f),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.labelSmall,
        )
        TextButton(
          onClick = { onPromotePiece(piece) },
          enabled = promotable,
          modifier = Modifier.semantics { contentDescription = "Build ${piece.componentId}" },
        ) {
          Text("Build for real")
        }
      }
    }
  }

  if (reference.marks.isNotEmpty()) {
    Text(
      "${reference.marks.size} marks",
      Modifier.padding(top = 4.dp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
    )
    // Wrapped and on its own line under the count, because three verbs do not fit beside a label
    // in a 320 dp dock: squeezed into one row, "5 marks" is broken one letter per line, which
    // reads as a rendering fault rather than as a count.
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
      // The link into the discussion, at the point somebody has just finished drawing: this row
      // is where "I have circled the problem" turns into "…and here is what is wrong with it".
      // The Talk panel opens with its pin already offering the last mark drawn.
      TextButton(
        onClick = { dispatch(UiBuilderEditorEvent.ShowInspector(EditorInspectorMode.Comments)) }
      ) {
        Text("Discuss")
      }
      TextButton(onClick = { dispatch(UiBuilderEditorEvent.UndoReferenceMark) }) {
        Text("Undo mark")
      }
      TextButton(onClick = { dispatch(UiBuilderEditorEvent.ClearReferenceMarkup) }) {
        Text("Clear marks")
      }
    }
  }
}

/**
 * The words the next text mark or image placeholder will carry.
 *
 * Typed before the shape is drawn, so the field is here rather than appearing over the canvas where
 * the drag ended — and so the same label can be dropped in three places without retyping it.
 */
@Composable
private fun ReferenceLabelField(text: String, onChange: (String) -> Unit) {
  Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
    Text(
      "Label",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    BasicTextField(
      value = text,
      onValueChange = onChange,
      modifier =
        Modifier.fillMaxWidth()
          .semantics { contentDescription = "Markup label" }
          .padding(top = 3.dp)
          .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
          .padding(horizontal = 8.dp, vertical = 7.dp),
      textStyle =
        MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
      singleLine = true,
    )
  }
}

/** Which kinds draw words, and so which ones the label field is offered for. */
private fun ReferenceMarkupKind?.carriesText(): Boolean =
  this == ReferenceMarkupKind.Text || this == ReferenceMarkupKind.ImagePlaceholder

@Composable
private fun ReferenceSectionHeading(label: String) {
  Text(
    label,
    Modifier.padding(top = 10.dp),
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

@Composable
private fun ReferenceStatusText(message: String) {
  // Every refusal this panel shows — a paste the browser would not decode, a store that would not
  // keep the image — comes through here, so this is the one place that has to be selectable for
  // any of them to be quotable.
  SelectionContainer {
    Text(
      message,
      Modifier.padding(top = 6.dp),
      color = MaterialTheme.colorScheme.error,
      style = MaterialTheme.typography.bodySmall,
    )
  }
}

@Composable
private fun ReferenceSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(
    Modifier.fillMaxWidth().padding(top = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    Switch(
      checked = checked,
      onCheckedChange = onChange,
      modifier = Modifier.semantics { contentDescription = label },
    )
  }
}

/**
 * The nudge, in whole dp, as buttons.
 *
 * Buttons rather than a field because aligning a mock is a sequence of one-dp corrections, and each
 * of those is a press rather than a select-all-and-retype.
 */
@Composable
private fun ReferenceNudgeAxis(
  label: String,
  value: Float,
  modifier: Modifier,
  onChange: (Float) -> Unit,
) {
  Row(modifier, verticalAlignment = Alignment.CenterVertically) {
    TextButton(
      onClick = { onChange(value - 1f) },
      modifier = Modifier.semantics { contentDescription = "Nudge reference $label back" },
    ) {
      Text("−")
    }
    Text(
      "$label ${value.toInt()}",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(
      onClick = { onChange(value + 1f) },
      modifier = Modifier.semantics { contentDescription = "Nudge reference $label forward" },
    ) {
      Text("+")
    }
  }
}

@Composable
private fun ReferencePercentSlider(
  label: String,
  percent: Int,
  range: IntRange,
  onChange: (Int) -> Unit,
) {
  Row(
    Modifier.fillMaxWidth().padding(top = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      "$label $percent%",
      Modifier.padding(end = 8.dp),
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Slider(
      value = percent.toFloat(),
      onValueChange = { onChange(it.toInt()) },
      valueRange = range.first.toFloat()..range.last.toFloat(),
      modifier = Modifier.weight(1f).semantics { contentDescription = "Reference $label" },
    )
  }
}

private fun ReferenceDiffMode.explanation(): String =
  when (this) {
    ReferenceDiffMode.Overlay -> "The reference over the canvas, at the opacity below."
    ReferenceDiffMode.Difference ->
      "Matching pixels go black. Anything you can see is a difference."
    ReferenceDiffMode.Split -> "The reference on one side of the wipe, the canvas on the other."
    ReferenceDiffMode.Boxes -> "The SVG's own rectangles, stroked over the canvas."
  }

/**
 * A `#AARRGGBB` (or `#RRGGBB`) theme colour as a markup ARGB value, or null when it is not one.
 *
 * Null rather than a fallback colour: an unreadable theme value should leave the swatch out of the
 * palette, not put a black one in it that claims to be the screen's background.
 */
private fun String.markupArgbOrNull(): Long? {
  val digits = trim().removePrefix("#")
  val parsed = digits.toLongOrNull(radix = 16) ?: return null
  return when (digits.length) {
    8 -> parsed
    6 -> 0xFF000000L or parsed
    else -> null
  }
}

/**
 * The catalog, as a menu, for capturing a component onto the reference.
 *
 * A menu rather than a second component list: the panel beside this one already *is* the catalog,
 * and duplicating it here would be two lists to keep in step. This one exists because the catalog
 * panel's own gesture inserts a real node, which is precisely what placing a piece is not.
 */
@Composable
private fun ReferenceComponentMenu(
  catalogItems: List<EditorCatalogItem>,
  onPick: (String) -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  Box {
    OutlinedButton(
      onClick = { open = true },
      enabled = catalogItems.isNotEmpty(),
      modifier = Modifier.semantics { contentDescription = "Place a component" },
    ) {
      Text("Component…", maxLines = 1)
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      catalogItems.forEach { item ->
        DropdownMenuItem(
          text = { Text(item.displayName, style = MaterialTheme.typography.labelSmall) },
          onClick = {
            open = false
            onPick(item.componentId)
          },
        )
      }
    }
  }
}
