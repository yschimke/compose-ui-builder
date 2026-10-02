package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.LocalUiBuilderFontFamilies
import ee.schimke.composeai.uibuilder.LocalUiBuilderFontRegistry
import ee.schimke.composeai.uibuilder.VendoredFontFamily
import ee.schimke.composeai.uibuilder.canonicalFamilyName

/**
 * What [FontFamilyPicker] offers for [query]: the vendored families first, then the font service's,
 * each list filtered by a case-insensitive substring match and the second capped at [limit] so a
 * two-letter query does not draw two thousand rows. A vendored family is not offered again from the
 * service's list.
 */
internal data class FontFamilyChoices(
  val vendored: List<VendoredFontFamily>,
  val remote: List<String>,
  /** How many service families matched before the cap. */
  val remoteMatches: Int,
  /** The query itself, offered as a name when nothing listed spells it exactly. */
  val custom: String?,
)

internal fun fontFamilyChoices(
  vendored: List<VendoredFontFamily>,
  remote: List<String>,
  query: String,
  limit: Int = 40,
): FontFamilyChoices {
  val needle = query.trim().lowercase()
  fun matches(name: String) = needle.isEmpty() || needle in name.lowercase()
  val vendoredNames = vendored.mapNotNullTo(mutableSetOf()) { canonicalFamilyName(it.name) }
  val shownVendored = vendored.filter { matches(it.name) || matches(it.label) }
  val remoteMatches = remote.filter { matches(it) && canonicalFamilyName(it) !in vendoredNames }
  val exact = canonicalFamilyName(query)
  val listed =
    exact == null || exact in vendoredNames || remote.any { canonicalFamilyName(it) == exact }
  return FontFamilyChoices(
    vendored = shownVendored,
    remote = remoteMatches.take(limit),
    remoteMatches = remoteMatches.size,
    custom = query.trim().takeUnless { listed },
  )
}

/**
 * A family name, chosen from a searchable menu whose every option is drawn in the family it names.
 *
 * The vendored families come first; then, on a host with a font service, the whole Google Fonts
 * catalogue, searched as you type. Faces load as they are shown — the vendored ones as soon as the
 * menu opens, the service's only for the first few results of a query, because each is a download
 * and the catalogue is two thousand of them. A name the lists do not hold can still be typed and
 * used: the canvas draws it if the font service has it, and the platform face otherwise.
 *
 * Commits on pick. [onPick] gets null for "Default", which unsets the property.
 */
@Composable
internal fun FontFamilyPicker(
  selected: String?,
  contentDescription: String,
  onTextInputFocusChanged: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  onPick: (String?) -> Unit,
) {
  val registry = LocalUiBuilderFontRegistry.current
  val families = registry?.families.orEmpty()
  val remote = registry?.remoteFamilies.orEmpty()
  val faces = LocalUiBuilderFontFamilies.current
  var expanded by remember { mutableStateOf(false) }
  var query by remember { mutableStateOf("") }
  LaunchedEffect(registry) { registry?.loadFamilies() }
  LaunchedEffect(registry, selected) { selected?.let { registry?.request(it) } }
  val choices = remember(families, remote, query) { fontFamilyChoices(families, remote, query) }
  LaunchedEffect(registry, expanded) {
    if (expanded) {
      registry?.loadRemoteFamilies()
      families.forEach { registry?.request(it.name) }
    }
  }
  // Only a query's first few service results are fetched: enough to compare faces, not a download
  // of the catalogue for a menu someone is scrolling past.
  LaunchedEffect(registry, expanded, choices.remote) {
    if (expanded && query.isNotBlank()) {
      choices.remote.take(PREVIEWED_REMOTE_FACES).forEach { registry?.request(it) }
    }
  }
  val selectedCanonical = selected?.let(::canonicalFamilyName)
  val selectedLabel =
    families.firstOrNull { canonicalFamilyName(it.name) == selectedCanonical }?.label ?: selected
  Box(modifier.fillMaxWidth()) {
    OutlinedButton(
      onClick = { expanded = true },
      modifier = Modifier.fillMaxWidth().semantics { this.contentDescription = contentDescription },
    ) {
      Text(
        selectedLabel ?: "Default",
        fontFamily = selected?.let(faces::get),
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 1,
        modifier = Modifier.weight(1f),
      )
      Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
    }
    TrackEditorOverlay(expanded)
    DropdownMenu(
      expanded = expanded,
      onDismissRequest = {
        expanded = false
        query = ""
      },
      modifier = Modifier.widthIn(min = 280.dp),
    ) {
      fun pick(name: String?) {
        expanded = false
        query = ""
        onPick(name)
      }
      OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        singleLine = true,
        placeholder = {
          Text(if (remote.isEmpty()) "Search typefaces" else "Search ${remote.size} Google Fonts")
        },
        modifier =
          Modifier.fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .semantics { this.contentDescription = "Search typefaces" }
            .onFocusChanged { onTextInputFocusChanged(it.isFocused) },
      )
      if (query.isBlank()) {
        FontFamilyOption("Default", null, "The platform's own face", selected == null) {
          pick(null)
        }
      }
      choices.vendored.forEach { family ->
        FontFamilyOption(
          label = family.label,
          face = faces[family.name],
          supporting = if (faces[family.name] == null) "Bundled · loading…" else "Bundled",
          selected = canonicalFamilyName(family.name) == selectedCanonical,
        ) {
          pick(family.name)
        }
      }
      if (choices.remote.isNotEmpty()) {
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        choices.remote.forEach { name ->
          FontFamilyOption(
            label = name,
            face = faces[name],
            supporting = "Google Fonts",
            selected = canonicalFamilyName(name) == selectedCanonical,
          ) {
            pick(name)
          }
        }
        if (choices.remoteMatches > choices.remote.size) {
          Text(
            "${choices.remoteMatches - choices.remote.size} more — keep typing to narrow",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
          )
        }
      }
      choices.custom?.let { name ->
        FontFamilyOption(
          label = "Use “$name”",
          face = null,
          supporting = "Not in either list — drawn only if the font service has it",
          selected = false,
        ) {
          pick(name)
        }
      }
      // A name the design already carries that neither list holds, so the menu never hides what
      // the design says.
      if (
        query.isBlank() &&
          selected != null &&
          choices.vendored.none { canonicalFamilyName(it.name) == selectedCanonical } &&
          remote.none { canonicalFamilyName(it) == selectedCanonical }
      ) {
        val face = faces[selected]
        FontFamilyOption(
          label = selected,
          face = face,
          supporting =
            if (face != null) "Not listed — fetched by name"
            else "Not listed — drawn in the default face",
          selected = true,
        ) {
          pick(selected)
        }
      }
    }
  }
}

/** How many of a query's Google Fonts results load their face for the menu. */
private const val PREVIEWED_REMOTE_FACES = 8

@Composable
private fun FontFamilyOption(
  label: String,
  face: FontFamily?,
  supporting: String?,
  selected: Boolean,
  onClick: () -> Unit,
) {
  DropdownMenuItem(
    text = {
      Column {
        Text(label, fontFamily = face, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        supporting?.let {
          Text(
            it,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    },
    trailingIcon =
      if (selected) {
        { Icon(Icons.Filled.Check, contentDescription = "Current typeface", Modifier.size(18.dp)) }
      } else null,
    modifier = Modifier.semantics { this.selected = selected },
    onClick = onClick,
  )
}
