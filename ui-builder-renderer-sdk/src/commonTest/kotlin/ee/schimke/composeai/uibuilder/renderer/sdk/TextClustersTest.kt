package ee.schimke.composeai.uibuilder.renderer.sdk

import kotlin.test.Test
import kotlin.test.assertEquals

/** Curved text places one reader-visible character at a time, never half of one. */
class TextClustersTest {
  @Test
  fun `plain text splits per character`() {
    assertEquals(listOf("1", "2", ":", "3", "0"), clusters("12:30"))
  }

  @Test
  fun `surrogate pairs, marks and emoji sequences stay whole`() {
    val thumbsUpDark = "👍🏿"
    val family = "👩‍👧"
    val flag = "🇬🇧"
    val heart = "❤️"
    val accented = "é"

    assertEquals(
      listOf("a", thumbsUpDark, family, flag, flag, heart, accented, "b"),
      clusters("a$thumbsUpDark$family$flag$flag$heart${accented}b"),
    )
  }
}
