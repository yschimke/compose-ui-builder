package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.materialkolor.Contrast
import com.materialkolor.PaletteStyle
import ee.schimke.composeai.uibuilder.export.ThemeTextStyle
import ee.schimke.composeai.uibuilder.export.ThemeTypefaces
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.role

/** Where Material Theme Builder lives; its Export → JSON is what the import reads. */
internal const val MATERIAL_THEME_BUILDER_URL =
  "https://material-foundation.github.io/material-theme-builder/"

/**
 * The Theme panel: the design-wide settings, in the order a theme is made.
 *
 * - **Colours** — import a Material Theme Builder file, generate a scheme from a seed colour, or
 *   type the theme host's own colour roles.
 * - **Typography** — the type scale, a typeface per group of roles, and the default text style.
 * - **Shape** — the corner radius.
 * - **Design-system tokens**, when the catalog declares them.
 *
 * Every apply or import is one edit, so one undo takes it back. The host is whichever node carries
 * the theme ([UiBuilderEditorReducer.themePanelHost]): a root `m3/surface`, a Wear screen, a Remote
 * widget container.
 */
@Composable
internal fun ThemePanel(
  state: UiBuilderEditorState,
  settings: EditorThemeSettings,
  host: EditorThemeHost?,
  designTokens: List<EditorDesignTokenRow>,
  onTextInputFocusChanged: (Boolean) -> Unit,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  val hostNode = host?.let { state.document.nodes[it.nodeId] }
  val preferredScheme = ThemeSchemes.preferredScheme(state.platform)
  val tokens = designTokens.map { it.token }
  // What a scheme can land on: the host's colour properties and the catalog's colour tokens.
  val schemeTarget = SchemeTarget(host?.colorProperties.orEmpty(), tokens)
  Column(Modifier.verticalScroll(rememberScrollState())) {
    ThemeSection("Colours", "Import, generate or type the design's colour roles.", first = true)
    if (schemeTarget.isEmpty) {
      ThemeNote("Add a root Material surface to hold the theme's colours.")
    } else {
      ThemeBuilderImport(schemeTarget, preferredScheme, onTextInputFocusChanged, dispatch)
      SeedSchemeGenerator(
        schemeTarget,
        initialSeed = settings.primaryColor.takeIf { host?.scaleAndShape == true },
        preferredScheme = preferredScheme,
        onTextInputFocusChanged = onTextInputFocusChanged,
        dispatch = dispatch,
      )
    }
    if (host?.scaleAndShape == true) {
      MaterialColourFields(settings, onTextInputFocusChanged, dispatch)
    }

    ThemeSection("Typography", "Type scale, typefaces and the default text style.")
    if (host?.scaleAndShape == true) {
      ThemeNumberField(
        label = "Type scale (0.75–1.5)",
        value = settings.typeScale.toString(),
        actionLabel = "Apply type scale",
        onTextInputFocusChanged = onTextInputFocusChanged,
      ) {
        dispatch(UiBuilderEditorEvent.ApplyTheme(settings.copy(typeScale = it ?: Float.NaN)))
      }
    }
    if (hostNode == null || host == null) {
      ThemeNote("Add a theme host — a root Material surface, a Wear screen or a widget — first.")
    } else {
      ThemeTypefacePickers(hostNode, host.wearScale, onTextInputFocusChanged, dispatch)
    }

    if (host?.scaleAndShape == true) {
      ThemeSection("Shape", "The corner radius every shape starts from.")
      ThemeNumberField(
        label = "Corner radius (0–48dp)",
        value = settings.cornerRadiusDp.toString(),
        actionLabel = "Apply corner radius",
        onTextInputFocusChanged = onTextInputFocusChanged,
      ) {
        dispatch(UiBuilderEditorEvent.ApplyTheme(settings.copy(cornerRadiusDp = it ?: Float.NaN)))
      }
    }

    if (designTokens.isNotEmpty()) {
      ThemeSection("Design-system tokens", "The values this design system lets a design re-skin.")
      DesignTokensSection(
        designTokens,
        state.tunables,
        onTextInputFocusChanged,
        dispatch,
        preferredScheme = preferredScheme,
      )
    }
  }
}

/** What a colour scheme can be written onto in this design. */
private class SchemeTarget(
  val hostProperties: Map<String, String>,
  val tokens: List<ee.schimke.composeai.uibuilder.capability.DesignToken>,
) {
  val isEmpty: Boolean =
    hostProperties.isEmpty() && ThemeSchemes.tokenValues(ALL_ROLES, tokens).isEmpty()

  /** One line saying what applying [roles] writes. */
  fun summary(roles: Map<String, String>): String {
    val host = ThemeSchemes.hostWrites(roles, hostProperties).size
    val tokenCount = ThemeSchemes.tokenValues(roles, tokens).size
    val parts =
      listOfNotNull(
        host.takeIf { it > 0 }?.let { "$it theme colour${if (it == 1) "" else "s"}" },
        tokenCount.takeIf { it > 0 }?.let { "$it token${if (it == 1) "" else "s"}" },
      )
    return if (parts.isEmpty()) "Nothing here holds these roles."
    else "Sets ${parts.joinToString(" and ")}, as one undoable edit."
  }

  private companion object {
    /** Every Material role, to ask which of them this design holds. */
    val ALL_ROLES: Map<String, String> = ThemeSchemes.generate("#6750A4", dark = false).orEmpty()
  }
}

@Composable
private fun ThemeSection(title: String, supporting: String, first: Boolean = false) {
  if (!first) HorizontalDivider(Modifier.padding(vertical = 14.dp))
  Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
  Text(
    supporting,
    Modifier.padding(bottom = 8.dp),
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.bodySmall,
  )
}

@Composable
private fun ThemeNote(text: String) {
  Text(
    text,
    Modifier.padding(vertical = 4.dp),
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.bodySmall,
  )
}

/**
 * Material Theme Builder's JSON export, pasted in. On a catalog with colour tokens the scheme fills
 * them; on every catalog it fills the theme host's colour properties. A DTCG file pasted here goes
 * to the design tokens, as the tokens section's own import does.
 */
@Composable
private fun ThemeBuilderImport(
  target: SchemeTarget,
  preferredScheme: String,
  onTextInputFocusChanged: (Boolean) -> Unit,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  var text by remember { mutableStateOf("") }
  var scheme by remember(preferredScheme) { mutableStateOf(preferredScheme) }
  var applied by remember { mutableStateOf(false) }
  val uriHandler = LocalUriHandler.current
  OutlinedButton(
    onClick = { open = !open },
    modifier =
      Modifier.fillMaxWidth().semantics {
        contentDescription = "Import from Material Theme Builder"
      },
  ) {
    Text(
      if (open) "Hide Material Theme Builder import" else "Import from Material Theme Builder",
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
  if (!open) return
  Text(
    "In Material Theme Builder, Export → JSON, then paste the file here.",
    Modifier.padding(top = 6.dp),
    style = MaterialTheme.typography.bodySmall,
  )
  TextButton(
    onClick = { runCatching { uriHandler.openUri(MATERIAL_THEME_BUILDER_URL) } },
    modifier = Modifier.semantics { contentDescription = "Open Material Theme Builder" },
  ) {
    Text("Open Material Theme Builder ↗")
  }
  OutlinedTextField(
    text,
    {
      text = it
      applied = false
    },
    Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 200.dp).onFocusChanged {
      onTextInputFocusChanged(it.hasFocus)
    },
    label = { Text("Theme Builder JSON") },
    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
  )
  if (text.isBlank()) return
  if (!isThemeBuilderExport(text)) {
    // Not a Theme Builder file: a DTCG token file is still welcome where there are tokens.
    val read = importDesignTokens(text, target.tokens).getOrNull()
    if (read != null && read.values.isNotEmpty()) {
      ThemeNote(
        "${read.format}: ${read.values.size} token${if (read.values.size == 1) "" else "s"}."
      )
      TextButton(
        enabled = !applied,
        onClick = {
          dispatch(UiBuilderEditorEvent.ImportDesignTokens(read.values))
          applied = true
        },
      ) {
        Text("Import ${read.values.size}")
      }
    } else {
      Text(
        "This is not a Material Theme Builder export: it has no \"schemes\".",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
      )
    }
    return
  }
  val read = readThemeBuilderScheme(text, scheme)
  read.onFailure {
    Text(
      it.message.orEmpty(),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.error,
    )
  }
  val found = read.getOrNull() ?: return
  SchemeChoice(found.available.ifEmpty { listOf(found.name) }, found.name) { scheme = it }
  SchemeSwatches(found.roles)
  ThemeNote(target.summary(found.roles))
  OutlinedButton(
    enabled =
      !applied &&
        (ThemeSchemes.hostWrites(found.roles, target.hostProperties).isNotEmpty() ||
          ThemeSchemes.tokenValues(found.roles, target.tokens).isNotEmpty()),
    onClick = {
      dispatch(UiBuilderEditorEvent.ApplyColorScheme(found.roles))
      applied = true
    },
    modifier =
      Modifier.fillMaxWidth().semantics { contentDescription = "Apply Theme Builder scheme" },
  ) {
    Text(if (applied) "Applied" else "Apply ${found.name} scheme")
  }
}

/**
 * A Material You scheme generated from one seed colour by materialkolor, previewed role by role
 * before it is applied.
 */
@Composable
private fun SeedSchemeGenerator(
  target: SchemeTarget,
  initialSeed: String?,
  preferredScheme: String,
  onTextInputFocusChanged: (Boolean) -> Unit,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  var seed by remember(initialSeed) { mutableStateOf(initialSeed?.toRrggbb() ?: "#6750A4") }
  var style by remember { mutableStateOf(PaletteStyle.TonalSpot) }
  var scheme by remember(preferredScheme) { mutableStateOf(preferredScheme) }
  var contrast by remember { mutableStateOf(Contrast.Default) }
  OutlinedButton(
    onClick = { open = !open },
    modifier =
      Modifier.fillMaxWidth().padding(top = 6.dp).semantics {
        contentDescription = "Generate from a seed colour"
      },
  ) {
    Text(if (open) "Hide seed generator" else "Generate from a seed colour")
  }
  if (!open) return
  val roles =
    remember(seed, style, scheme, contrast) {
      ThemeSchemes.generate(seed, dark = scheme == "dark", style = style, contrast = contrast.value)
    }
  Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    ColorSwatch(
      ThemeSchemes.parseHex(seed),
      size = 28.dp,
      selected = false,
      modifier = Modifier.semantics { contentDescription = "Seed colour swatch" },
    )
    Spacer(Modifier.width(8.dp))
    OutlinedTextField(
      seed,
      { seed = it.trim() },
      Modifier.weight(1f).onFocusChanged { onTextInputFocusChanged(it.hasFocus) },
      label = { Text("Seed colour") },
      singleLine = true,
      isError = roles == null,
      textStyle = MaterialTheme.typography.bodySmall,
    )
  }
  Text(
    "Style",
    Modifier.padding(top = 8.dp, bottom = 2.dp),
    style = MaterialTheme.typography.labelMedium,
  )
  ChoiceDropdown(
    selected = style,
    choices = ThemeSchemes.STYLES,
    label = { it.name },
    contentDescription = "Scheme style",
  ) {
    style = it
  }
  SchemeChoice(listOf("light", "dark"), scheme) { scheme = it }
  Text(
    "Contrast",
    Modifier.padding(top = 4.dp, bottom = 2.dp),
    style = MaterialTheme.typography.labelMedium,
  )
  CompactChips(ThemeSchemes.CONTRASTS, contrast, { it.label() }) { contrast = it }
  if (roles == null) {
    Text(
      "A seed is #RRGGBB.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.error,
    )
    return
  }
  SchemeSwatches(roles)
  ThemeNote(target.summary(roles))
  LocalUiBuilderChrome.current.InspectorAction(
    UiBuilderInspectorActionModel(
      label = "Apply generated scheme",
      primary = true,
      filled = true,
      modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
      onClick = { dispatch(UiBuilderEditorEvent.ApplyColorScheme(roles)) },
    )
  )
}

/** The Material surface's four colours, typed, applied together. */
@Composable
private fun MaterialColourFields(
  settings: EditorThemeSettings,
  onTextInputFocusChanged: (Boolean) -> Unit,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  var primary by remember(settings) { mutableStateOf(settings.primaryColor) }
  var background by remember(settings) { mutableStateOf(settings.backgroundColor) }
  var surface by remember(settings) { mutableStateOf(settings.surfaceColor) }
  var content by remember(settings) { mutableStateOf(settings.contentColor) }
  Text(
    "Theme colours",
    Modifier.padding(top = 12.dp, bottom = 2.dp),
    style = MaterialTheme.typography.labelLarge,
  )
  ThemeField("Primary", primary, onTextInputFocusChanged) { primary = it }
  ThemeField("Background", background, onTextInputFocusChanged) { background = it }
  ThemeField("Surface", surface, onTextInputFocusChanged) { surface = it }
  ThemeField("Content (on surface)", content, onTextInputFocusChanged) { content = it }
  LocalUiBuilderChrome.current.InspectorAction(
    UiBuilderInspectorActionModel(
      label = "Apply colours",
      primary = true,
      filled = true,
      modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
      onClick = {
        dispatch(
          UiBuilderEditorEvent.ApplyTheme(
            settings.copy(
              primaryColor = primary,
              backgroundColor = background,
              surfaceColor = surface,
              contentColor = content,
            )
          )
        )
      },
    )
  )
}

/** One number of the Material surface's theme, typed and applied on its own. */
@Composable
private fun ThemeNumberField(
  label: String,
  value: String,
  actionLabel: String,
  onTextInputFocusChanged: (Boolean) -> Unit,
  onApply: (Float?) -> Unit,
) {
  var draft by remember(value) { mutableStateOf(value) }
  Row(verticalAlignment = Alignment.Bottom) {
    Box(Modifier.weight(1f)) { ThemeField(label, draft, onTextInputFocusChanged) { draft = it } }
    LocalUiBuilderChrome.current.InspectorAction(
      UiBuilderInspectorActionModel(
        label = "Apply",
        contentDescription = actionLabel,
        enabled = draft != value,
        onClick = { onApply(draft.toFloatOrNull()) },
      )
    )
  }
}

/** The light / dark (or any other named) scheme to read or generate. */
@Composable
private fun SchemeChoice(choices: List<String>, selected: String, onPick: (String) -> Unit) {
  CompactChips(
    choices,
    selected,
    { it.replaceFirstChar { c -> c.uppercase() }.replace('-', ' ') },
    onPick,
  )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> CompactChips(
  choices: List<T>,
  selected: T,
  label: (T) -> String,
  onPick: (T) -> Unit,
) {
  CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
    FlowRow(
      Modifier.padding(vertical = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      choices.forEach { choice ->
        FilterChip(
          selected = choice == selected,
          onClick = { onPick(choice) },
          label = { Text(label(choice), style = MaterialTheme.typography.labelMedium) },
        )
      }
    }
  }
}

@Composable
private fun <T> ChoiceDropdown(
  selected: T,
  choices: List<T>,
  label: (T) -> String,
  contentDescription: String,
  onPick: (T) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  Box(Modifier.fillMaxWidth()) {
    OutlinedButton(
      onClick = { expanded = true },
      modifier = Modifier.fillMaxWidth().semantics { this.contentDescription = contentDescription },
    ) {
      Text(label(selected), maxLines = 1, modifier = Modifier.weight(1f))
      Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
    }
    TrackEditorOverlay(expanded)
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      choices.forEach { choice ->
        DropdownMenuItem(
          text = { Text(label(choice)) },
          trailingIcon =
            if (choice == selected) {
              { Icon(Icons.Filled.Check, contentDescription = "Current") }
            } else null,
          onClick = {
            expanded = false
            onPick(choice)
          },
        )
      }
    }
  }
}

/** The scheme's key roles as labelled tiles, so a scheme is judged before it is applied. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SchemeSwatches(roles: Map<String, String>) {
  FlowRow(
    Modifier.padding(vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    ThemeSchemes.PREVIEW_ROLES.forEach { role ->
      val colour = roles[role]?.let(ThemeSchemes::parseHex) ?: return@forEach
      Column(
        Modifier.width(52.dp).semantics { contentDescription = "$role ${roles[role]}" },
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Box(
          Modifier.size(width = 52.dp, height = 28.dp)
            .background(colour, RoundedCornerShape(6.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
        )
        Text(
          role,
          style = MaterialTheme.typography.labelSmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

private fun Contrast.label(): String =
  when (this) {
    Contrast.Default -> "Standard"
    Contrast.Medium -> "Medium"
    Contrast.High -> "High"
    Contrast.Reduced -> "Reduced"
  }

/** `#AARRGGBB` as `#RRGGBB`, for a seed field that ignores alpha. */
private fun String.toRrggbb(): String =
  if (length == 9 && startsWith("#")) "#" + substring(3).uppercase() else uppercase()

/**
 * The theme host's typefaces, one picker per group of type-scale roles (see [ThemeTypefaces]), and
 * its default text style. A Wear or Remote host offers Wear's groups and roles.
 *
 * Commits on pick: a family is one choice, and the thing to do after making it is to look at the
 * canvas.
 */
@Composable
private fun ThemeTypefacePickers(
  host: UiBuilderNode,
  wear: Boolean,
  onTextInputFocusChanged: (Boolean) -> Unit,
  dispatch: (UiBuilderEditorEvent) -> Unit,
) {
  val groups = if (wear) ThemeTypefaces.WEAR_GROUPS else ThemeTypefaces.GROUPS
  groups.forEach { group ->
    val name = group.name.replaceFirstChar { it.uppercase() }
    Text(
      "$name typeface",
      style = MaterialTheme.typography.labelMedium,
      modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
    FontFamilyPicker(
      selected = host.propertyText(group.property).takeIf { it.isNotBlank() },
      contentDescription = "$name typeface",
      onTextInputFocusChanged = onTextInputFocusChanged,
    ) { family ->
      dispatch(UiBuilderEditorEvent.CommitProperty(host.id, group.property, family.orEmpty()))
    }
  }
  Text(
    "Default text style",
    style = MaterialTheme.typography.labelMedium,
    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
  )
  TextRolePicker(
    selected = host.propertyText(ThemeTextStyle.PROPERTY).takeIf { it.isNotBlank() },
    roles = if (wear) ThemeTextStyle.WEAR_ROLES else ThemeTextStyle.M3_ROLES,
  ) { role ->
    dispatch(
      if (role == null) UiBuilderEditorEvent.ClearProperty(host.id, ThemeTextStyle.PROPERTY)
      else UiBuilderEditorEvent.CommitProperty(host.id, ThemeTextStyle.PROPERTY, role)
    )
  }
}

/**
 * The type role text with no `style` of its own is set in ([ThemeTextStyle]), picked from [roles]
 * with each drawn in itself — under the theme the panel is editing, so a role shows the size and
 * the face the canvas will use. [onPick] gets null for the default, which unsets the property.
 */
@Composable
private fun TextRolePicker(selected: String?, roles: List<String>, onPick: (String?) -> Unit) {
  var expanded by remember { mutableStateOf(false) }
  Box(Modifier.fillMaxWidth()) {
    OutlinedButton(
      onClick = { expanded = true },
      modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Default text style" },
    ) {
      Text(
        selected ?: "${ThemeTextStyle.DEFAULT} (default)",
        maxLines = 1,
        modifier = Modifier.weight(1f),
      )
      Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
    }
    TrackEditorOverlay(expanded)
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      fun pick(role: String?) {
        expanded = false
        onPick(role)
      }
      DropdownMenuItem(
        text = { Text("${ThemeTextStyle.DEFAULT} (default)") },
        trailingIcon =
          if (selected == null) {
            { Icon(Icons.Filled.Check, contentDescription = "Current text style") }
          } else null,
        onClick = { pick(null) },
      )
      roles.forEach { role ->
        DropdownMenuItem(
          text = {
            Text(
              role,
              style = MaterialTheme.typography.role(role) ?: LocalTextStyle.current,
              maxLines = 1,
            )
          },
          trailingIcon =
            if (role == selected) {
              { Icon(Icons.Filled.Check, contentDescription = "Current text style") }
            } else null,
          modifier = Modifier.semantics { this.selected = role == selected },
          onClick = { pick(role) },
        )
      }
    }
  }
}

@Composable
private fun ThemeField(
  label: String,
  value: String,
  onFocusChanged: (Boolean) -> Unit,
  onValueChange: (String) -> Unit,
) {
  LocalUiBuilderChrome.current.InspectorValueField(
    UiBuilderInspectorValueFieldModel(
      label = label,
      value = value,
      modifier = Modifier.fillMaxWidth(),
      onFocusChanged = onFocusChanged,
      onValueChange = onValueChange,
    )
  )
}
