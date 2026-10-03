package ee.schimke.composeai.uibuilder

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.wear.compose.material3.LocalTextStyle as WearLocalTextStyle
import androidx.wear.compose.material3.MaterialTheme as WearMaterialTheme
import androidx.wear.compose.material3.Typography as WearTypography
import ee.schimke.composeai.uibuilder.export.ThemeTypefaces
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/** A theme host's typefaces, per role group, on both type scales the canvas draws with. */
@OptIn(ExperimentalTestApi::class)
class ThemeTypefacesTest {
  private val fontsDir = File("../assets/rc-fonts")

  @Test
  fun `each group names its own roles, and Wear has no headline group`() {
    val families =
      ThemeTypefaces.families(
        mapOf("themeDisplayTypeface" to "Michroma", "themeBodyTypeface" to " Exo 2 ")::get
      )
    assertEquals(setOf("display", "body"), families.keys.map { it.name }.toSet())
    val wear = ThemeTypefaces.wearRoleFamilies(families)
    assertEquals("Michroma", wear["numeralLarge"], "numerals ride with display on Wear")
    assertEquals("Exo 2", wear["bodyExtraSmall"])
    assertEquals(null, wear["labelLarge"], "an unset group keeps the platform face")
    assertEquals(
      listOf("display", "title", "body", "label"),
      ThemeTypefaces.WEAR_GROUPS.map { it.name },
    )
    assertEquals("Michroma", ThemeTypefaces.m3RoleFamilies(families)["displaySmall"])
  }

  @Test
  fun `only the roles named move, on both scales`() {
    val face = FontFamily.Monospace
    val m3 = Typography().withRoleFamilies(mapOf("titleLarge" to face))
    assertSame(face, m3.titleLarge.fontFamily)
    assertEquals(Typography().bodyLarge, m3.bodyLarge)
    val wear = WearTypography().withRoleFamilies(mapOf("numeralSmall" to face))
    assertSame(face, wear.numeralSmall.fontFamily)
    assertEquals(WearTypography().displayLarge, wear.displayLarge)
  }

  /**
   * The host's default text role ([ThemeTextStyle]) becomes the ambient style on both scales, each
   * as its own role: Wear has no headline roles, so a headline takes the matching title role there.
   */
  @Test
  fun `a theme host's default text role is the ambient style on both scales`() =
    runDesktopComposeUiTest {
      var m3: TextStyle? = null
      var wear: TextStyle? = null
      var expectedM3: TextStyle? = null
      var expectedWear: TextStyle? = null
      setContent {
        WearMaterialTheme {
          ThemeTypefacesHost(read = mapOf("themeTextStyle" to "headlineSmall")::get) {
            m3 = LocalTextStyle.current
            wear = WearLocalTextStyle.current
            expectedM3 = MaterialTheme.typography.headlineSmall
            expectedWear = WearMaterialTheme.typography.titleSmall
          }
        }
      }
      waitForIdle()
      assertEquals(expectedM3?.fontSize, m3?.fontSize)
      assertEquals(expectedWear?.fontSize, wear?.fontSize)
      assertNotEquals(WearTypography().bodyLarge.fontSize, wear?.fontSize)
    }

  /**
   * The canvas path end to end: a host naming a family the manifest does not list gets it from the
   * font service, and both the Material 3 and the Wear scale under it draw those roles in it.
   */
  @Test
  fun `a theme host draws its roles in the families it names`() = runDesktopComposeUiTest {
    var m3Display: FontFamily? = null
    var m3Body: FontFamily? = null
    var wearDisplay: FontFamily? = null
    var wearLabel: FontFamily? = null
    val stock = WearTypography().labelLarge.fontFamily
    val host =
      mapOf("themeDisplayTypeface" to "Michroma", "themeBodyTypeface" to "google:Space Grotesk")
    setContent {
      val scope = rememberCoroutineScope()
      val registry =
        UiBuilderFontRegistry(
          scope = scope,
          readManifest = { File(fontsDir, "fonts.json").readText() },
          readFont = { File(fontsDir, it).readBytes() },
          readRemoteFont = { _, weight -> File(fontsDir, "orbitron-$weight.ttf").readBytes() },
        )
      ProvideUiBuilderFonts(registry) {
        WearMaterialTheme {
          ThemeTypefacesHost(read = host::get) {
            m3Display = MaterialTheme.typography.displayLarge.fontFamily
            m3Body = MaterialTheme.typography.bodyLarge.fontFamily
            wearDisplay = WearMaterialTheme.typography.displayLarge.fontFamily
            wearLabel = WearMaterialTheme.typography.labelLarge.fontFamily
          }
        }
      }
    }
    waitUntil(timeoutMillis = 10_000) {
      m3Display != Typography().displayLarge.fontFamily &&
        m3Body != Typography().bodyLarge.fontFamily &&
        wearDisplay != WearTypography().displayLarge.fontFamily
    }
    assertNotNull(m3Body)
    assertNotEquals(m3Display, m3Body)
    assertSame(m3Display, wearDisplay, "one family, both scales")
    assertEquals(stock, wearLabel, "label was not named")
  }
}
