package ee.schimke.composeai.uibuilder

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

/**
 * Kotlin syntax highlighting for the Code pane, as an [AnnotatedString].
 *
 * ## Why not the highlighter the playground already ships
 *
 * The playground's Source lane runs a vendored CodeMirror 5, served from the host's own origin. It
 * is a JS library that owns DOM nodes, and the builder's Code pane is a Compose `Text` drawn on a
 * Wasm canvas — nothing about CodeMirror can reach inside that. Using it here would mean a real DOM
 * element positioned over the canvas, with focus, scrolling, theming, selection and z-order all
 * becoming manual, and the mobile panel would be Compose on one path and DOM on the other. The
 * price buys gutters and squiggles this pane has no use for: it shows generated code, and a design
 * the export cannot express is already an [EditorGeneratedCode.Refused] with reasons.
 *
 * `dev.snipme:highlights` is pure Kotlin with a published `wasmJs` variant, so the tokenizer runs
 * in the same binary as the pane and produces spans rather than markup.
 *
 * ## Why it never throws
 *
 * A highlighter is decoration on a pane whose actual job is showing the source. Any failure — a
 * tokenizer that raised, a span reported outside the text it was given — degrades to the plain code
 * that was there before, because a pane that shows nothing is strictly worse than a pane that shows
 * unstyled Kotlin.
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
      // `addStyle` throws on a range it cannot apply, which would take the whole pane down over a
      // decoration. The bound is the code's, not the highlighter's, so a span that disagrees with
      // it is dropped rather than trusted.
      if (start < 0 || end > code.length || start >= end) continue
      when (highlight) {
        is ColorHighlight -> addStyle(SpanStyle(color = highlight.color()), start, end)
        is BoldHighlight -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
      }
    }
  }
}

/**
 * The palette the Code pane highlights with, following the surface it is drawn on.
 *
 * The editor chrome is [EditorColors] — dark, unconditionally — so today this always resolves to
 * the dark half. It reads the surface rather than hard-coding that, because the pane being
 * unreadable is the failure mode of guessing wrong, and a chrome that ever gains a light mode
 * should not have to remember this file exists.
 *
 * Two different themes rather than one theme's two modes: `SyntaxThemes.darcula(darkMode = false)`
 * only darkens its foreground and keeps a palette drawn for a dark ground, which puts a #909090
 * comment on white. Atom One's light half is a palette designed for that ground.
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
 * `Highlights` reports colours as `0xRRGGBB`, with no alpha channel at all.
 *
 * [Color] reads an `Int` as ARGB, so passing one straight through yields alpha 0 — an invisible
 * pane whose text is all there and none of it drawn. Opacity is supplied here, once.
 */
private fun ColorHighlight.color(): Color = rgbColor(rgb)

private fun rgbColor(rgb: Int): Color = Color(rgb or ALPHA_OPAQUE)

private const val ALPHA_OPAQUE = 0xFF shl 24

/** Below this the surface is dark enough that a dark-ground palette is the readable one. */
private const val LIGHT_SURFACE_LUMINANCE = 0.5f
