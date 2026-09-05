package ee.schimke.composeai.uibuilder

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import dev.snipme.highlights.model.SyntaxThemes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Code pane's highlighter, at the seam that has no Compose UI in it.
 *
 * What is worth asserting here is not "the tokenizer found the keywords" — that is the library's
 * own test, and pinning this repository to which spans it produces would break on a bump that
 * improved them. What is worth asserting is the contract the pane depends on: the text is
 * untouched, the colours are drawn rather than transparent, and nothing the highlighter can say
 * takes the pane down.
 */
class UiBuilderCodeHighlightingTest {

  private val theme = SyntaxThemes.darcula(darkMode = true)

  private val kotlin =
    """
    package generated.uibuilder

    @Composable
    fun AgentScreen() {
      Column {
        // a comment
        Text("heading")
      }
    }
    """
      .trimIndent()

  @Test
  fun `the highlighted text is the source, unchanged`() {
    // The pane shows generated Kotlin, and a highlighter that dropped or reordered a character
    // would be showing source the export does not write.
    assertEquals(kotlin, highlightKotlin(kotlin, theme).text)
  }

  @Test
  fun `something is styled`() {
    val styles = highlightKotlin(kotlin, theme).spanStyles

    assertTrue(styles.isNotEmpty(), "no spans over a keyword, a string and a comment")
  }

  @Test
  fun `every colour is opaque`() {
    // The regression this exists for: `Highlights` reports 0xRRGGBB and `Color(Int)` reads ARGB, so
    // passing one straight through yields alpha 0 — every styled run invisible, which reads as a
    // pane that lost its text rather than as a colour bug.
    val colors = highlightKotlin(kotlin, theme).spanStyles.mapNotNull { it.item.color }

    assertTrue(colors.isNotEmpty(), "no coloured spans to check")
    assertTrue(colors.all { it.alpha == 1f }, "transparent spans: $colors")
    assertEquals(Color(0xFFEDEDED), theme.codeColor())
  }

  @Test
  fun `empty source is not an error`() {
    // The pane composes before a design has generated anything, and on a document whose export
    // emitted nothing.
    assertEquals(AnnotatedString(""), highlightKotlin("", theme))
  }

  @Test
  fun `text the tokenizer cannot make sense of still renders`() {
    val broken = "fun ( { \"unterminated /* @@@"

    assertEquals(broken, highlightKotlin(broken, theme).text)
  }
}
