package ee.schimke.composeai.uibuilder.editor

import androidx.compose.runtime.snapshotFlow
import ee.schimke.composeai.rcplayer.compose.RcComposeTextRasterizer
import ee.schimke.composeai.rcplayer.compose.RcTypefaceLoader
import ee.schimke.composeai.rcplayer.edit.RcBitmapFontGlyphs
import ee.schimke.composeai.rcplayer.edit.RcDisplay
import ee.schimke.composeai.rcplayer.edit.RcTargetProfile
import ee.schimke.composeai.rcplayer.edit.RcTargetProfiles
import ee.schimke.composeai.rcplayer.edit.coreTextAsBitmapFonts
import ee.schimke.composeai.rcplayer.protocol.RcCoreText
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcTextStyleProperty
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.composeai.uibuilder.UiBuilderFontRegistry
import ee.schimke.composeai.uibuilder.canvas.hostTypefaces
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A widget host that plays a Remote Compose document but can't load the fonts it names: the system
 * draws the widget, with its own faces only. [profile] is what its player decodes, and [density]
 * the display its glyphs are drawn for.
 */
enum class BitmapFontWidgetHost(
  /** How the Export menu names it. */
  val label: String,
  /** The file name's suffix, so the two downloads don't overwrite each other. */
  val fileSuffix: String,
  val profile: RcTargetProfile,
  val density: Float,
) {
  /** A Wear widget; the watch scales its glyphs with its own display. */
  Wear("Wear widget", "wear-widget", RcTargetProfiles.WEAR_WIDGETS, 2f),

  /** An Android 16 launcher widget, drawn at a phone's density. */
  Launcher("launcher widget", "launcher-widget", RcTargetProfiles.WIDGETS_V6, 3f),
}

/**
 * The Export menu's bitmap-font download: an exported `.rc` with every text in a named face (a
 * theme typeface, Google Fonts, a vendored family) drawn from glyphs embedded in the document,
 * rather than the face itself, which a [BitmapFontWidgetHost] can't load. The rc-players edit
 * library's `coreTextAsBitmapFonts`, each glyph drawn once by the player's own text stack over the
 * faces the editor has loaded.
 *
 * A text whose value the design fixes is drawn from its own glyphs. A text bound to state can't be
 * known yet, so it gets [LIVE_GLYPHS], with room for [LIVE_LENGTH] of them — or, where that slot is
 * wider than its place in the design (a large reading), for as many as it shows now — unless what
 * it shows now is already outside that set. A text the conversion can't draw stays text, in the
 * host's own face: a family the editor hasn't loaded, a text on a path, styling a bitmap font can't
 * draw. Text in a generic family (`sans-serif`, `serif`, `monospace`) is the host's and is left
 * alone.
 */
object WidgetBitmapFonts {
  /** Letters, digits, space and the signs readings and titles use: any bound text's glyphs. */
  val LIVE_GLYPHS: String =
    ('A'..'Z').joinToString("") +
      ('a'..'z').joinToString("") +
      ('0'..'9').joinToString("") +
      " .,:;-+/%°()'&!?"

  /** The most characters a bound text shows, where its place in the design has room: its slot. */
  const val LIVE_LENGTH: Int = 12

  /** The faces every widget host has; text in them is left as it is. */
  val HOST_FAMILIES: Set<String> =
    setOf("", "default", "sans-serif", "serif", "monospace", "cursive")

  /** What [convert] did: the document, and how many texts it drew from glyphs or left. */
  class Converted(val bytes: ByteArray, val converted: Int, val skipped: Int)

  /**
   * [bytes] with its named faces drawn from bitmap fonts for [host], or null when [bytes] isn't a
   * Remote Compose document. [typefaces] are the faces the glyphs are drawn in.
   */
  fun convert(
    bytes: ByteArray,
    host: BitmapFontWidgetHost,
    typefaces: RcTypefaceLoader,
  ): Converted? {
    val document =
      runCatching { RcDocumentCodec.decode(bytes, RcWireLimits()) }.getOrNull() ?: return null
    val families = namedFamilies(document)
    if (families.isEmpty()) return Converted(bytes, converted = 0, skipped = 0)
    val rasterizer = RcComposeTextRasterizer(typefaces)
    fun pass(tight: Set<Int>) =
      document.coreTextAsBitmapFonts(
        host.profile,
        families,
        rasterizer,
        RcDisplay(host.density),
        glyphs = liveGlyphs(document, tight),
      )
    // A slot that doesn't fit is skipped after its glyphs are drawn, and they stay embedded: so
    // learn which bound texts don't fit, then convert once from the source with those sized to
    // what they show now.
    val first = pass(tight = emptySet())
    val tight = first.skipped.filter { liveSample(document, it) != null }.toSet()
    val result = if (tight.isEmpty()) first else pass(tight)
    return Converted(
      RcDocumentCodec.encode(result.document),
      converted = result.texts.size,
      skipped = result.skipped.size + result.skippedOnPath.size,
    )
  }

  /** [namedFamilies] of [bytes], or null when [bytes] isn't a Remote Compose document. */
  fun namedFamilies(bytes: ByteArray): Set<String>? = runCatching {
    RcDocumentCodec.decode(bytes, RcWireLimits())
  }
    .getOrNull()
    ?.let(::namedFamilies)

  /** The families [document]'s texts are set in that a widget host doesn't have. */
  fun namedFamilies(document: RcDocument): Set<String> {
    val strings = document.operations.filterIsInstance<RcTextData>().associate { it.id to it.text }
    return document.operations
      .filterIsInstance<RcCoreText>()
      .mapNotNull { text ->
        (text.properties.firstOrNull { it.id == FONT_FAMILY } as? RcTextStyleProperty.IntValue)
          ?.value
          ?.let(strings::get)
      }
      .filter { it.trim().lowercase() !in HOST_FAMILIES }
      .toSet()
  }

  /**
   * A bound text's glyphs by its component id, or null to leave it as text; see [LIVE_GLYPHS]. A
   * text in [tight] gets a slot for what it shows now, rather than [LIVE_LENGTH].
   */
  private fun liveGlyphs(document: RcDocument, tight: Set<Int>): (Int) -> RcBitmapFontGlyphs? =
    glyphs@{ componentId ->
      val sample = liveSample(document, componentId) ?: return@glyphs null
      // What it shows now says what it will show: outside the set, leave it.
      if (sample.any { it !in LIVE_GLYPHS }) return@glyphs null
      val length = if (componentId in tight) sample.length else maxOf(LIVE_LENGTH, sample.length)
      RcBitmapFontGlyphs(LIVE_GLYPHS, length.coerceAtLeast(1))
    }

  /** What the text with [componentId] shows now, or null when [document] has no such text. */
  private fun liveSample(document: RcDocument, componentId: Int): String? {
    val text =
      document.operations.filterIsInstance<RcCoreText>().firstOrNull { text ->
        (text.properties.firstOrNull { it.id == COMPONENT_ID } as? RcTextStyleProperty.IntValue)
          ?.value == componentId
      } ?: return null
    return document.operations
      .filterIsInstance<RcTextData>()
      .firstOrNull { it.id == text.textId }
      ?.text
      .orEmpty()
  }

  /** `CoreText`'s component id and font family properties. */
  private const val COMPONENT_ID = 1
  private const val FONT_FAMILY = 8
}

/**
 * The faces [families] name, from this registry, as the canvas looks them up ([hostTypefaces]): any
 * not loaded yet are asked for and waited on, up to [timeoutMillis], since the glyphs are drawn in
 * them. A family that doesn't arrive in time is declined by the rasterizer and stays text.
 */
suspend fun UiBuilderFontRegistry.typefacesFor(
  families: Set<String>,
  timeoutMillis: Long = 10_000,
): RcTypefaceLoader {
  families.filter { it !in loaded }.forEach(::request)
  withTimeoutOrNull(timeoutMillis) { snapshotFlow { families.all { it in loaded } }.first { it } }
  return hostTypefaces(loaded.toMap())
}
