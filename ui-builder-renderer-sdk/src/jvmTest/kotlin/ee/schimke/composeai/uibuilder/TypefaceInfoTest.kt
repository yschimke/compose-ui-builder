package ee.schimke.composeai.uibuilder

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypefaceInfoTest {
  private fun vendored(file: String): ByteArray {
    val dir =
      generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "assets/rc-fonts") }
        .first { it.isDirectory }
    return File(dir, file).readBytes()
  }

  @Test
  fun robotoFlexReadsItsThirteenAxesWithTheirRangesAndNames() {
    val info = readTypefaceInfo(vendored("RobotoFlex.ttf"))
    assertTrue(info.isVariable)
    assertEquals(13, info.axes.size)
    val weight = info.axes.single { it.tag == "wght" }
    assertEquals(100f, weight.min)
    assertEquals(400f, weight.default)
    assertEquals(1000f, weight.max)
    assertEquals("Weight", weight.name)
    val width = info.axes.single { it.tag == "wdth" }
    assertEquals(25f to 151f, width.min to width.max)
    assertTrue("pnum" in info.features)
  }

  @Test
  fun aStaticFaceHasFeaturesAndNoAxes() {
    val info = readTypefaceInfo(vendored("LobsterTwo-Regular.ttf"))
    assertFalse(info.isVariable)
    assertTrue("liga" in info.features)
    assertTrue("salt" in info.features)
  }

  @Test
  fun interIsVariableWithItsFigureFeatures() {
    val info = readTypefaceInfo(vendored("inter-variable.ttf"))
    assertTrue(info.isVariable)
    assertEquals(listOf("opsz", "wght"), info.axes.map { it.tag })
    assertTrue("tnum" in info.features)
    assertTrue("frac" in info.features)
  }

  @Test
  fun garbageReadsAsNothing() {
    assertEquals(TypefaceInfo.EMPTY, readTypefaceInfo(ByteArray(10)))
  }
}
