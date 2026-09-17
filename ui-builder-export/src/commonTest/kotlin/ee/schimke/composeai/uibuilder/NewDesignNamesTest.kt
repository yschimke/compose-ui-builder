package ee.schimke.composeai.uibuilder

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NewDesignNamesTest {
  /** The create dialog's own rule, so a generated name never opens the dialog on an error. */
  private val designId = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

  @Test
  fun `every generated name is a valid design id`() {
    NewDesignNames.adjectives.forEach { adjective ->
      NewDesignNames.nouns.forEach { noun ->
        val name = "$adjective-$noun"
        assertTrue(designId.matches(name), name)
      }
    }
  }

  @Test
  fun `the same seed gives the same name and different seeds vary`() {
    assertEquals(NewDesignNames.random(Random(7)), NewDesignNames.random(Random(7)))
    val names = (0 until 200).map { NewDesignNames.random(Random(it)) }.toSet()
    assertTrue(names.size > 100, "expected variety, got ${names.size} distinct names")
  }

  @Test
  fun `no word repeats across the two lists`() {
    val all = NewDesignNames.adjectives + NewDesignNames.nouns
    assertEquals(all.size, all.toSet().size)
    assertTrue(all.all { it == it.lowercase() && it.all(Char::isLetter) })
  }
}
