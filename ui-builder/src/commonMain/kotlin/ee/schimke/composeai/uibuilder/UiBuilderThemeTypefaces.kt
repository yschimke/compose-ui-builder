package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.wear.compose.material3.MaterialTheme as WearMaterialTheme
import androidx.wear.compose.material3.Typography as WearTypography
import ee.schimke.composeai.uibuilder.export.ThemeTypefaces

/**
 * Draw [content] under both type scales with the theme host's typefaces applied — Material 3's for
 * the mobile and foundation components that read it, Wear's for the Wear port's.
 *
 * [read] is the host's string property by name. A host that names no typeface leaves both scales
 * exactly as they were, so a design without one composes nothing extra.
 */
@Composable
internal fun ThemeTypefacesHost(read: (String) -> String?, content: @Composable () -> Unit) {
  val families = ThemeTypefaces.families(read)
  if (families.isEmpty()) {
    content()
    return
  }
  val m3 = rememberThemeRoleFamilies(families, wear = false)
  val wear = rememberThemeRoleFamilies(families, wear = true)
  // Both themes default every other argument to the one around them, so only the type changes.
  MaterialTheme(typography = MaterialTheme.typography.withRoleFamilies(m3)) {
    WearMaterialTheme(
      typography = WearMaterialTheme.typography.withRoleFamilies(wear),
      content = content,
    )
  }
}

/** This Material 3 scale with each role in [families] re-pointed at its family. */
fun Typography.withRoleFamilies(families: Map<String, FontFamily>): Typography {
  if (families.isEmpty()) return this
  fun f(role: String) = families[role]
  return copy(
    displayLarge = f("displayLarge")?.let { displayLarge.copy(fontFamily = it) } ?: displayLarge,
    displayMedium =
      f("displayMedium")?.let { displayMedium.copy(fontFamily = it) } ?: displayMedium,
    displaySmall = f("displaySmall")?.let { displaySmall.copy(fontFamily = it) } ?: displaySmall,
    headlineLarge =
      f("headlineLarge")?.let { headlineLarge.copy(fontFamily = it) } ?: headlineLarge,
    headlineMedium =
      f("headlineMedium")?.let { headlineMedium.copy(fontFamily = it) } ?: headlineMedium,
    headlineSmall =
      f("headlineSmall")?.let { headlineSmall.copy(fontFamily = it) } ?: headlineSmall,
    titleLarge = f("titleLarge")?.let { titleLarge.copy(fontFamily = it) } ?: titleLarge,
    titleMedium = f("titleMedium")?.let { titleMedium.copy(fontFamily = it) } ?: titleMedium,
    titleSmall = f("titleSmall")?.let { titleSmall.copy(fontFamily = it) } ?: titleSmall,
    bodyLarge = f("bodyLarge")?.let { bodyLarge.copy(fontFamily = it) } ?: bodyLarge,
    bodyMedium = f("bodyMedium")?.let { bodyMedium.copy(fontFamily = it) } ?: bodyMedium,
    bodySmall = f("bodySmall")?.let { bodySmall.copy(fontFamily = it) } ?: bodySmall,
    labelLarge = f("labelLarge")?.let { labelLarge.copy(fontFamily = it) } ?: labelLarge,
    labelMedium = f("labelMedium")?.let { labelMedium.copy(fontFamily = it) } ?: labelMedium,
    labelSmall = f("labelSmall")?.let { labelSmall.copy(fontFamily = it) } ?: labelSmall,
  )
}

/**
 * This Wear scale with each role in [families] re-pointed at its family.
 *
 * Explicitly per role: `Typography(defaultFontFamily = …)` is a no-op on Wear, because every stock
 * role already names `roboto-flex`. The arc roles keep the stock face — they are `CurvedTextStyle`
 * and draw the curved status strip, system chrome rather than the design's type.
 */
fun WearTypography.withRoleFamilies(families: Map<String, FontFamily>): WearTypography {
  if (families.isEmpty()) return this
  fun f(role: String) = families[role]
  return copy(
    displayLarge = f("displayLarge")?.let { displayLarge.copy(fontFamily = it) } ?: displayLarge,
    displayMedium =
      f("displayMedium")?.let { displayMedium.copy(fontFamily = it) } ?: displayMedium,
    displaySmall = f("displaySmall")?.let { displaySmall.copy(fontFamily = it) } ?: displaySmall,
    titleLarge = f("titleLarge")?.let { titleLarge.copy(fontFamily = it) } ?: titleLarge,
    titleMedium = f("titleMedium")?.let { titleMedium.copy(fontFamily = it) } ?: titleMedium,
    titleSmall = f("titleSmall")?.let { titleSmall.copy(fontFamily = it) } ?: titleSmall,
    labelLarge = f("labelLarge")?.let { labelLarge.copy(fontFamily = it) } ?: labelLarge,
    labelMedium = f("labelMedium")?.let { labelMedium.copy(fontFamily = it) } ?: labelMedium,
    labelSmall = f("labelSmall")?.let { labelSmall.copy(fontFamily = it) } ?: labelSmall,
    bodyLarge = f("bodyLarge")?.let { bodyLarge.copy(fontFamily = it) } ?: bodyLarge,
    bodyMedium = f("bodyMedium")?.let { bodyMedium.copy(fontFamily = it) } ?: bodyMedium,
    bodySmall = f("bodySmall")?.let { bodySmall.copy(fontFamily = it) } ?: bodySmall,
    bodyExtraSmall =
      f("bodyExtraSmall")?.let { bodyExtraSmall.copy(fontFamily = it) } ?: bodyExtraSmall,
    numeralExtraLarge =
      f("numeralExtraLarge")?.let { numeralExtraLarge.copy(fontFamily = it) } ?: numeralExtraLarge,
    numeralLarge = f("numeralLarge")?.let { numeralLarge.copy(fontFamily = it) } ?: numeralLarge,
    numeralMedium =
      f("numeralMedium")?.let { numeralMedium.copy(fontFamily = it) } ?: numeralMedium,
    numeralSmall = f("numeralSmall")?.let { numeralSmall.copy(fontFamily = it) } ?: numeralSmall,
    numeralExtraSmall =
      f("numeralExtraSmall")?.let { numeralExtraSmall.copy(fontFamily = it) } ?: numeralExtraSmall,
  )
}
