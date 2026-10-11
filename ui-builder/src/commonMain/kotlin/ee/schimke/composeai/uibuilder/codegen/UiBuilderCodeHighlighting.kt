package ee.schimke.composeai.uibuilder.codegen

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxTheme
import dev.snipme.highlights.model.SyntaxThemes
import ee.schimke.composeai.uibuilder.editor.EditorGeneratedCode

/**
 * Kotlin syntax highlighting for the Code pane, as an [AnnotatedString].
 *
 * Not the playground's CodeMirror: that is DOM-based and couldn't live inside the Compose `Text` on
 * the Wasm canvas. `dev.snipme:highlights` is pure Kotlin with a `wasmJs` variant and produces
 * spans.
 *
 * Never throws: any failure (a tokenizer error, an out-of-range span) degrades to plain code.
 */
internal fun highlightKotlin(code: String, theme: SyntaxTheme): AnnotatedString {
  val highlights =
    runCatching {
      Highlights.Builder()
        .code(code)
        .language(SyntaxLanguage.KOTLIN)
        .theme(theme)
        .build()
        .getHighlights()
    }
      .getOrNull() ?: return AnnotatedString(code)

  return buildAnnotatedString {
    append(code)
    for (highlight in highlights) {
      val start = highlight.location.start
      val end = highlight.location.end
      // `addStyle` throws on an invalid range, so spans outside the code's bounds are dropped.
      if (start < 0 || end > code.length || start >= end) continue
      when (highlight) {
        is ColorHighlight -> addStyle(SpanStyle(color = highlight.color()), start, end)
        is BoldHighlight -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
      }
    }
  }
}

/**
 * The palette the Code pane highlights with, following the surface it is drawn on (today always
 * dark, via [EditorColors]).
 *
 * Two themes rather than one theme's two modes: `SyntaxThemes.darcula(darkMode = false)` keeps a
 * dark-ground palette (a #909090 comment on white); Atom One's light half is designed for a light
 * ground.
 */
@Composable
internal fun rememberCodePaneSyntaxTheme(): SyntaxTheme {
  val dark = MaterialTheme.colorScheme.surface.luminance() < LIGHT_SURFACE_LUMINANCE
  return remember(dark) {
    if (dark) SyntaxThemes.darcula(darkMode = true) else SyntaxThemes.atom(darkMode = false)
  }
}

/** The theme's colour for ordinary code — the base every unhighlighted character takes. */
internal fun SyntaxTheme.codeColor(): Color = rgbColor(code)

/**
 * `Highlights` reports colours as `0xRRGGBB` with no alpha; [Color] reads ARGB, so opacity is added
 * here.
 */
private fun ColorHighlight.color(): Color = rgbColor(rgb)

private fun rgbColor(rgb: Int): Color = Color(rgb or ALPHA_OPAQUE)

private const val ALPHA_OPAQUE = 0xFF shl 24

/** Below this the surface is dark enough that a dark-ground palette is the readable one. */
private const val LIGHT_SURFACE_LUMINANCE = 0.5f
