package ee.schimke.composeai.uibuilder

import androidx.compose.material3.Typography
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontFamily

/**
 * Families the host has already loaded, keyed by the family name a document's `typeface` names.
 *
 * **The renderer resolves, it does not fetch.** A document carries a family NAME
 * (`DesignEnvironmentV1.typeface`), and how that name becomes glyphs differs per host: the browser
 * renderer vendors eight families in `assets/rc-fonts` and can download anything else from Google
 * Fonts; a JVM test host has neither. Loading is asynchronous and platform-specific, and this
 * module is `commonMain` with no I/O in it, so the host does the loading and provides the result
 * here.
 *
 * That split is also what keeps the network question where it can be answered. A vendored family
 * resolves with no request at all, so the common cases — Roboto Flex and Google Sans Flex among
 * them — never touch the network; only a family nobody vendored needs one, and the host is the only
 * layer that knows whether the document's `networkAccess` permits it.
 *
 * Empty by default, which is exactly today's behaviour: nothing resolves, and every document
 * renders in the platform default face.
 */
val LocalUiBuilderFontFamilies = staticCompositionLocalOf<Map<String, FontFamily>> { emptyMap() }

/**
 * Apply [family] to every role of [base].
 *
 * All fifteen roles, not the text ones a design happens to use today: the exported code references
 * roles by name (`MaterialTheme.typography.bodyMedium`), so a role left on the default face would
 * render one way in the builder and another in the consuming app — the divergence a theme typeface
 * exists to remove.
 */
fun Typography.withFontFamily(family: FontFamily): Typography =
  copy(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
  )
