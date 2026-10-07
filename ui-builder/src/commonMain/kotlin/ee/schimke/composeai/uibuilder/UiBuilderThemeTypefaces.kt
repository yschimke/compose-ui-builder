package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.wear.compose.material3.MaterialTheme as WearMaterialTheme
import androidx.wear.compose.material3.ProvideTextStyle as WearProvideTextStyle
import androidx.wear.compose.material3.Typography as WearTypography
import ee.schimke.composeai.uibuilder.canvas.LocalThemeHostProperties
import ee.schimke.composeai.uibuilder.export.ThemeTextStyle
import ee.schimke.composeai.uibuilder.export.ThemeTypefaces

/**
 * Draw [content] under both type scales with the theme host's typefaces applied — Material 3's for
 * the mobile and foundation components that read it, Wear's for the Wear port's.
 *
 * [read] is the host's string property by name. Its [ThemeTextStyle] role, when it names one,
 * becomes the ambient text style on both scales. A host that names neither a typeface nor a role
 * leaves both scales exactly as they were, so a design without one composes nothing extra.
 */
@Composable
internal fun ThemeTypefacesHost(read: (String) -> String?, content: @Composable () -> Unit) {
  val families = ThemeTypefaces.families(read)
  val textRole = ThemeTextStyle.role(read)
  if (families.isEmpty() && textRole == null) {
    // Still this design's theme host: a text's font settings resolve their typeface against it.
    CompositionLocalProvider(LocalThemeHostProperties provides read, content = content)
    return
  }
  val m3 = rememberThemeRoleFamilies(families, wear = false)
  val wear = rememberThemeRoleFamilies(families, wear = true)
  // Both themes default every other argument to the one around them, so only the type changes.
  MaterialTheme(typography = MaterialTheme.typography.withRoleFamilies(m3)) {
    ProvideThemeTextStyle(textRole) {
      WearMaterialTheme(typography = WearMaterialTheme.typography.withRoleFamilies(wear)) {
        // After the Wear theme, which provides its own `bodyLarge` as the ambient style.
        CompositionLocalProvider(LocalThemeHostProperties provides read) {
          ProvideWearThemeTextStyle(textRole, content)
        }
      }
    }
  }
}

/**
 * [content] with Material 3's ambient text style set to [role] (see [ThemeTextStyle]); unchanged
 * when [role] is null. Inside a `MaterialTheme`, so the role is the themed one.
 */
@Composable
internal fun ProvideThemeTextStyle(role: String?, content: @Composable () -> Unit) {
  val style = role?.let { MaterialTheme.typography.role(ThemeTextStyle.m3Role(it)) }
  if (style == null) content() else ProvideTextStyle(style, content)
}

/** [ProvideThemeTextStyle] for Wear's type scale and Wear's ambient style. */
@Composable
internal fun ProvideWearThemeTextStyle(role: String?, content: @Composable () -> Unit) {
  val style = role?.let { WearMaterialTheme.typography.role(ThemeTextStyle.wearRole(it)) }
  if (style == null) content() else WearProvideTextStyle(style, content)
}

/** This scale's role named [name], or null for a name it has no role for. */
fun Typography.role(name: String): TextStyle? =
  when (name) {
    "displayLarge" -> displayLarge
    "displayMedium" -> displayMedium
    "displaySmall" -> displaySmall
    "headlineLarge" -> headlineLarge
    "headlineMedium" -> headlineMedium
    "headlineSmall" -> headlineSmall
    "titleLarge" -> titleLarge
    "titleMedium" -> titleMedium
    "titleSmall" -> titleSmall
    "bodyLarge" -> bodyLarge
    "bodyMedium" -> bodyMedium
    "bodySmall" -> bodySmall
    "labelLarge" -> labelLarge
    "labelMedium" -> labelMedium
    "labelSmall" -> labelSmall
    else -> null
  }

/** This Wear scale's role named [name], or null for a name it has no role for. */
fun WearTypography.role(name: String): TextStyle? =
  when (name) {
    "displayLarge" -> displayLarge
    "displayMedium" -> displayMedium
    "displaySmall" -> displaySmall
    "titleLarge" -> titleLarge
    "titleMedium" -> titleMedium
    "titleSmall" -> titleSmall
    "labelLarge" -> labelLarge
    "labelMedium" -> labelMedium
    "labelSmall" -> labelSmall
    "bodyLarge" -> bodyLarge
    "bodyMedium" -> bodyMedium
    "bodySmall" -> bodySmall
    "bodyExtraSmall" -> bodyExtraSmall
    "numeralExtraLarge" -> numeralExtraLarge
    "numeralLarge" -> numeralLarge
    "numeralMedium" -> numeralMedium
    "numeralSmall" -> numeralSmall
    "numeralExtraSmall" -> numeralExtraSmall
    else -> null
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
