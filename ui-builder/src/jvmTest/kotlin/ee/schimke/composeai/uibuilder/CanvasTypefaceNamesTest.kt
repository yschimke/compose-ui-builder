package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
import ee.schimke.composeai.rcplayer.compose.RcTypefaceLoader
import ee.schimke.composeai.rcplayer.protocol.RcCoreText
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcFloatWord
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcLayoutContent
import ee.schimke.composeai.rcplayer.protocol.RcNoArg
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcOperation
import ee.schimke.composeai.rcplayer.protocol.RcRootLayout
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcTextStyleProperty.FloatValue
import ee.schimke.composeai.rcplayer.protocol.RcTextStyleProperty.IntValue
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import ee.schimke.composeai.uibuilder.canvas.hostTypefaces
import ee.schimke.composeai.uibuilder.canvas.rememberHostTypefaces
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

/**
 * The Remote Compose canvas draws a design's named faces. The editor keys a loaded family by the
 * document's spelling (`google:Orbitron`, `Lobster Two`) while the player asks by its normalised
 * name (`orbitron`, `lobster two`); looked up verbatim the two never met, and every named face on a
 * Remote Compose canvas fell back to the default.
 */
class CanvasTypefaceNamesTest {
  private val fontsDir: File =
    generateSequence(File(".").absoluteFile) { it.parentFile }
      .map { File(it, "assets/rc-fonts") }
      .first { File(it, "fonts.json").isFile }

  private val orbitron =
    FontFamily(Font("test:orbitron", File(fontsDir, "orbitron-variable.ttf").readBytes()))

  private val end: RcOperation = RcNoArg(RcOpcodes.CONTAINER_END)

  private fun document(family: String) =
    RcDocument(
      RcHeader(RcVersion(1, 1, 0)),
      listOf(
        RcTextData(50, family),
        RcTextData(42, "Kitchen 21.5"),
        RcRootLayout(-1),
        RcLayoutContent(-2),
        RcCoreText(
          42,
          listOf(IntValue(1, -7), FloatValue(5, RcFloatWord.literal(30f)), IntValue(8, 50)),
        ),
        RcLayoutContent(-107),
        end,
        end,
        end,
        end,
      ),
    )

  private fun draw(document: RcDocument, typefaces: RcTypefaceLoader): Int {
    val scene = ImageComposeScene(400, 100, Density(1f))
    try {
      scene.setContent { RcComposePlayer(document = document, typefaces = typefaces) }
      scene.render()
      return scene.render().encodeToData()!!.bytes.contentHashCode()
    } finally {
      scene.close()
    }
  }

  @Test
  fun aFaceKeyedByTheDocumentsSpellingIsTheOneThePlayerAsksFor() {
    val loaded = hostTypefaces(mapOf("google:Orbitron" to orbitron, "Lobster Two" to orbitron))
    assertSame(orbitron, loaded.typeface("orbitron"))
    assertSame(orbitron, loaded.typeface("lobster two"))
    assertEquals(null, loaded.typeface("exo 2"), "a family nobody loaded is the player's to answer")
  }

  /**
   * The host's map fills in place as families load: a face that arrives later reaches the canvas.
   */
  @Test
  fun aFaceThatLoadsAfterTheCanvasComposesIsUsed() {
    val fonts = mutableStateMapOf<String, FontFamily>()
    var loader: RcTypefaceLoader? = null
    val scene = ImageComposeScene(10, 10, Density(1f))
    try {
      scene.setContent {
        CompositionLocalProvider(LocalUiBuilderFontFamilies provides fonts) {
          loader = rememberHostTypefaces()
        }
      }
      scene.render()
      assertEquals(null, loader!!.typeface("orbitron"))
      fonts["google:Orbitron"] = orbitron
      scene.render()
      assertSame(orbitron, loader!!.typeface("orbitron"))
    } finally {
      scene.close()
    }
  }

  @Test
  fun theCanvasDrawsANamedFaceOnceItHasLoaded() {
    for (family in listOf("google:Orbitron", "Orbitron")) {
      val document = document(family)
      assertNotEquals(
        draw(document, hostTypefaces(emptyMap())),
        draw(document, hostTypefaces(mapOf(family to orbitron))),
        "$family drew in the default face with Orbitron loaded",
      )
    }
  }
}
