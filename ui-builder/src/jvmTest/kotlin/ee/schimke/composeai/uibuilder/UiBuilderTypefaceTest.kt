package ee.schimke.composeai.uibuilder

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.Test

class UiBuilderTypefaceTest {

  @Test
  fun `every role takes the family, not just the ones a design happens to use`() {
    // The exported code references roles by name, so a role left on the default face renders one
    // way in the builder and another in the consuming app. Asserting the whole scale rather than a
    // sample: a helper that covered fourteen of fifteen would pass a spot check and still leave one
    // role wrong, and which role that is would depend on the design.
    val base = Typography()
    val applied = base.withFontFamily(FontFamily.Monospace)

    val roles =
      listOf<Pair<String, (Typography) -> androidx.compose.ui.text.TextStyle>>(
        "displayLarge" to { it.displayLarge },
        "displayMedium" to { it.displayMedium },
        "displaySmall" to { it.displaySmall },
        "headlineLarge" to { it.headlineLarge },
        "headlineMedium" to { it.headlineMedium },
        "headlineSmall" to { it.headlineSmall },
        "titleLarge" to { it.titleLarge },
        "titleMedium" to { it.titleMedium },
        "titleSmall" to { it.titleSmall },
        "bodyLarge" to { it.bodyLarge },
        "bodyMedium" to { it.bodyMedium },
        "bodySmall" to { it.bodySmall },
        "labelLarge" to { it.labelLarge },
        "labelMedium" to { it.labelMedium },
        "labelSmall" to { it.labelSmall },
      )

    assertEquals(15, roles.size, "Material 3 publishes fifteen type roles")
    roles.forEach { (name, read) ->
      assertEquals(FontFamily.Monospace, read(applied).fontFamily, "$name kept the default face")
    }
  }

  @Test
  fun `applying a family changes nothing else about the scale`() {
    // Size, weight and line height are the type scale; the family is the only thing this replaces.
    val base = Typography()
    val applied = base.withFontFamily(FontFamily.Monospace)

    assertEquals(base.bodyMedium.fontSize, applied.bodyMedium.fontSize)
    assertEquals(base.bodyMedium.lineHeight, applied.bodyMedium.lineHeight)
    assertEquals(base.bodyMedium.fontWeight, applied.bodyMedium.fontWeight)
    assertEquals(base.displayLarge.letterSpacing, applied.displayLarge.letterSpacing)
    assertNotEquals(base.bodyMedium.fontFamily, applied.bodyMedium.fontFamily)
  }
}
