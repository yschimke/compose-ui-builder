package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Several top-level designs in one `.uid`, one of them active. */
class UidDesignCollectionTest {
  private val fixture: String =
    checkNotNull(javaClass.classLoader.getResource("state-actions.uid")).readText()
  private val home: UiBuilderDocument = UidDesignFiles.decode(fixture)
  private val detail: UiBuilderDocument = home.copy(id = "detail", title = "Detail")
  private val text =
    UidDesignFiles.encodeCollection(UidDesignCollection("detail", listOf(home, detail)))

  @Test
  fun `a collection opens as its active design and round-trips byte for byte`() {
    val opened = UidDesignFiles.open(text)

    assertEquals(detail, opened.document)
    assertNull(opened.production)
    assertEquals(detail, UidDesignFiles.decode(text))
    assertTrue(UidDesignFiles.isCollection(text))
    assertEquals(text, opened.encode())
  }

  @Test
  fun `saving an edit replaces only the active design`() {
    val edited = detail.copy(title = "Detail, edited", revision = detail.revision + 1)

    val saved = UidDesignFiles.decodeCollection(UidDesignFiles.open(text).encode(edited))

    assertEquals("detail", saved.active)
    assertEquals(listOf(home, edited), saved.designs)
  }

  @Test
  fun `switching the active design changes what opens`() {
    val switched =
      UidDesignFiles.encodeCollection(UidDesignFiles.decodeCollection(text).withActive(home.id))

    assertEquals(home, UidDesignFiles.open(switched).document)
  }

  @Test
  fun `an ordinary file reads as a collection of one and grows`() {
    val single = UidDesignFiles.decodeCollection(fixture)
    assertEquals(UidDesignCollection(home.id, listOf(home)), single)

    val grown = single.plus(detail)
    assertEquals("detail", grown.active)
    assertEquals(listOf(home), grown.minus("detail").designs)
    assertEquals(home.id, grown.minus("detail").active)
  }

  @Test
  fun `a collection refuses ambiguity and mixed design systems`() {
    assertFailsWith<IllegalArgumentException> { UidDesignCollection(home.id, listOf(home, home)) }
    assertFailsWith<IllegalArgumentException> { UidDesignCollection("missing", listOf(home)) }
    assertFailsWith<IllegalArgumentException> { UidDesignCollection(home.id, emptyList()) }
    val otherSystem =
      detail.copy(catalogPin = JsonObject(home.catalogPin + ("systemId" to JsonPrimitive("other"))))
    assertFailsWith<IllegalArgumentException> {
      UidDesignCollection(home.id, listOf(home, otherSystem))
    }
    // An edit that renames the active design onto another one is refused, not merged.
    assertFailsWith<IllegalArgumentException> {
      UidDesignFiles.open(text).encode(detail.copy(id = home.id))
    }
    assertFailsWith<IllegalArgumentException> {
      UidDesignCollection(home.id, listOf(home)).minus(home.id)
    }
  }
}
