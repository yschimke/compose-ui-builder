package ee.schimke.composeai.uibuilder

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import ee.schimke.composeai.rcplayer.edit.RcTargetProfiles
import ee.schimke.composeai.rcplayer.edit.validateFor
import ee.schimke.composeai.rcplayer.protocol.RcBitmapFontData
import ee.schimke.composeai.rcplayer.protocol.RcColumnLayout
import ee.schimke.composeai.rcplayer.protocol.RcCoreText
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcLayoutContent
import ee.schimke.composeai.rcplayer.protocol.RcNamedVariable
import ee.schimke.composeai.rcplayer.protocol.RcNoArg
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcOperation
import ee.schimke.composeai.rcplayer.protocol.RcRootLayout
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcTextStyleProperty.FloatValue
import ee.schimke.composeai.rcplayer.protocol.RcTextStyleProperty.IntValue
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import ee.schimke.composeai.rcplayer.protocol.RcWireLimits
import ee.schimke.composeai.uibuilder.editor.BitmapFontWidgetHost
import ee.schimke.composeai.uibuilder.editor.WidgetBitmapFonts
import ee.schimke.composeai.uibuilder.editor.widgetTypefaces
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Export menu's bitmap-font `.rc`: a widget host can't load a design's named faces, so those
 * texts must come out drawn from embedded glyphs, the host's own faces must be left alone, and the
 * result must still be something the host's player decodes.
 */
class WidgetBitmapFontsTest {
  private val end: RcOperation = RcNoArg(RcOpcodes.CONTAINER_END)

  private fun text(textId: Int, componentId: Int, family: Int) =
    listOf(
      RcCoreText(
        textId,
        listOf(
          IntValue(1, componentId),
          FloatValue(5, RcFloatWord.literal(24f)),
          IntValue(8, family),
        ),
      ),
      RcLayoutContent(componentId - 100),
      end,
      end,
    )

  /** A widget: a fixed heading and a bound reading in Orbitron, and a label in `sans-serif`. */
  private val widget: ByteArray =
    RcDocumentCodec.encode(
      RcDocument(
        RcHeader(RcVersion(1, 1, 0)),
        listOf(
          RcTextData(50, "google:Orbitron"),
          RcTextData(51, "sans-serif"),
          RcTextData(42, "Kitchen"),
          RcTextData(43, "21.5°C"),
          RcNamedVariable(43, RcNamedVariable.STRING_TYPE, "USER:temperature"),
          RcTextData(44, "Inside"),
          RcRootLayout(-1),
          RcLayoutContent(-2),
          *text(42, -7, 50).toTypedArray(),
          *text(43, -8, 50).toTypedArray(),
          *text(44, -9, 51).toTypedArray(),
          end,
          end,
        ),
      ),
    )

  private val orbitron =
    widgetTypefaces(
      mapOf(
        "google:Orbitron" to
          FontFamily(
            Font("orbitron", File("../assets/rc-fonts/orbitron-variable.ttf").readBytes()),
          ),
      ),
    )

  @Test
  fun aNamedFaceIsDrawnFromGlyphsAndTheHostsOwnLeftAsText() {
    assertEquals(setOf("google:Orbitron"), WidgetBitmapFonts.namedFamilies(widget))
    val converted =
      assertNotNull(WidgetBitmapFonts.convert(widget, BitmapFontWidgetHost.Wear, orbitron))
    assertEquals(2, converted.converted, "the heading and the reading")
    assertEquals(0, converted.skipped)
    val document = RcDocumentCodec.decode(converted.bytes, RcWireLimits())
    assertEquals(
      listOf(44),
      document.operations.filterIsInstance<RcCoreText>().map { it.textId },
      "only the sans-serif label stays text",
    )
    val glyphs =
      document.operations
        .filterIsInstance<RcBitmapFontData>()
        .flatMap { font -> font.glyphs.map { it.chars } }
        .toSet()
    assertTrue(
      WidgetBitmapFonts.LIVE_GLYPHS.map { it.toString() }.all { it in glyphs },
      "the bound reading can show any letter, digit or common sign",
    )
    assertEquals(
      emptyList(),
      document.validateFor(RcTargetProfiles.WEAR_WIDGETS).filter { it.opcode != null },
    )
  }

  @Test
  fun aBoundTextTooLargeForAFullSlotIsSizedToWhatItShows() {
    // A 40 px reading in a column: twelve of its widest glyph are wider than its place.
    val reading =
      RcDocumentCodec.encode(
        RcDocument(
          RcHeader(RcVersion(1, 1, 0)),
          listOf(
            RcTextData(50, "google:Orbitron"),
            RcTextData(43, "21.5°C"),
            RcNamedVariable(43, RcNamedVariable.STRING_TYPE, "USER:temperature"),
            RcRootLayout(-1),
            RcLayoutContent(-2),
            RcColumnLayout(-3, -1, 1, 1, RcFloatWord.literal(6f)),
            RcLayoutContent(-4),
            RcCoreText(
              43,
              listOf(IntValue(1, -8), FloatValue(5, RcFloatWord.literal(40f)), IntValue(8, 50)),
            ),
            RcLayoutContent(-108),
            end,
            end,
            end,
            end,
            end,
            end,
          ),
        ),
      )
    val converted =
      assertNotNull(WidgetBitmapFonts.convert(reading, BitmapFontWidgetHost.Wear, orbitron))
    assertEquals(1, converted.converted)
    assertEquals(0, converted.skipped)
    val fonts =
      RcDocumentCodec.decode(converted.bytes, RcWireLimits())
        .operations
        .filterIsInstance<RcBitmapFontData>()
    assertEquals(1, fonts.size, "no glyphs left behind from the slot that didn't fit")
  }

  @Test
  fun aFaceTheEditorHasNotLoadedStaysText() {
    val converted =
      assertNotNull(
        WidgetBitmapFonts.convert(
          widget,
          BitmapFontWidgetHost.Launcher,
          widgetTypefaces(emptyMap()),
        ),
      )
    assertEquals(0, converted.converted, "no glyphs drawn in a fallback face")
    assertEquals(2, converted.skipped)
  }

  @Test
  fun aDocumentWithNoNamedFaceIsUnchangedAndNotADocumentIsRefused() {
    val plain =
      RcDocumentCodec.encode(
        RcDocument(
          RcHeader(RcVersion(1, 1, 0)),
          listOf(
            RcTextData(51, "sans-serif"),
            RcTextData(44, "Inside"),
            RcRootLayout(-1),
            RcLayoutContent(-2),
            *text(44, -9, 51).toTypedArray(),
            end,
            end,
          ),
        ),
      )
    val converted =
      assertNotNull(WidgetBitmapFonts.convert(plain, BitmapFontWidgetHost.Wear, orbitron))
    assertTrue(converted.bytes.contentEquals(plain))
    assertNull(WidgetBitmapFonts.convert(byteArrayOf(1, 2, 3), BitmapFontWidgetHost.Wear, orbitron))
  }
}
