package ee.schimke.composeai.uibuilder.editor

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import ee.schimke.composeai.uibuilder.UiBuilderBrand

/** The host's brand ([UiBuilderBrand]), or null on an ordinary host. */
internal val LocalUiBuilderBrand = staticCompositionLocalOf<UiBuilderBrand?> { null }

/** Opens the brand's home, or null where the host gave none or cannot navigate. */
internal val LocalUiBuilderOpenBrandHome = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * Makes [brand] the editor's for [content]: its name in the chrome, its accent in the theme, and
 * [openHome] behind the brand name. A null [brand] provides nothing, so the editor is unchanged.
 */
@Composable
fun ProvideUiBuilderBrand(
  brand: UiBuilderBrand?,
  openHome: (String) -> Unit,
  content: @Composable () -> Unit,
) {
  if (brand == null) {
    content()
    return
  }
  val home = brand.homeHref
  CompositionLocalProvider(
    LocalUiBuilderBrand provides brand,
    LocalUiBuilderOpenBrandHome provides home?.let { href -> { openHome(href) } },
    content = content,
  )
}

/** This scheme with [brand]'s accent as its primary role, and a legible colour on top of it. */
internal fun ColorScheme.branded(brand: UiBuilderBrand?): ColorScheme {
  val accent = brand?.accentArgb?.let { Color(it.toInt()) } ?: return this
  return copy(
    primary = accent,
    onPrimary = if (accent.luminance() > 0.4f) Color.Black else Color.White,
  )
}
