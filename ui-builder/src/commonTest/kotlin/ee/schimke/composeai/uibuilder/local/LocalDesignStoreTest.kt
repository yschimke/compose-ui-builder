package ee.schimke.composeai.uibuilder.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What "the designs in this browser" means when the browser's keys are not only ours. */
class LocalDesignStoreTest {
  private val storage = InMemoryLocalDesignStorage()
  private val store = LocalDesignStore(storage)

  private fun record(designId: String, updatedAt: Long) =
    localDesignRecord(
      document = LocalDesignFixtures.document(designId),
      catalogSystemId = LocalDesignFixtures.CATALOG_SYSTEM_ID,
      sequence = 0,
      nowEpochMillis = updatedAt,
    )

  @Test
  fun `designs are listed newest first, and anything else in the origin is ignored`() {
    store.write(record("older", updatedAt = 1))
    store.write(record("newer", updatedAt = 2))
    // The preview pages keep their own keys in this origin, and one of them starting with our
    // prefix by accident must not be able to break the list.
    storage.write("cp-theme:default", "dark")
    storage.write("${LocalDesignStore.DESIGN_KEY_PREFIX}broken", "{not json")

    val listed = store.list()

    assertEquals(listOf("newer", "older", "broken"), listed.map { it.designId })
    assertEquals("Local fixture", listed.first().title)
    assertTrue(listed.first().storedBytes > 0)
  }

  @Test
  fun `a record that will not parse is listed as unreadable, and its delete still reaches it`() {
    storage.write("${LocalDesignStore.DESIGN_KEY_PREFIX}broken", "{not json")
    storage.write("${LocalDesignStore.DESIGN_KEY_PREFIX}worse key", "{not json")

    val listed = store.list()

    val broken = listed.filter { it.corrupted }
    assertEquals(
      listOf("broken", "worse key"),
      broken.map { it.designId },
      "sorted last: updatedAt 0",
    )
    assertEquals("(unreadable)", broken.single { it.designId == "broken" }.title)

    // Listed is what makes it cleanable: the delete takes the key the row names, whatever shape it
    // has -- the store never wrote `worse key`, so it was never held to the id shape either.
    store.delete("broken")
    store.delete("worse key")
    assertTrue(store.list().isEmpty(), "the bytes are gone from the quota, not kept forever")
  }

  @Test
  fun `a deleted design is gone, and a design id that is not path-safe is refused`() {
    store.write(record("keeper", updatedAt = 1))
    store.delete("keeper")

    assertNull(store.read("keeper"))
    assertFailsWith<IllegalArgumentException> { LocalDesignStore.designKey("../escape") }
  }
}
