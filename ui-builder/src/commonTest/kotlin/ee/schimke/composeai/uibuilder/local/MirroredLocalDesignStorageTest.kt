package ee.schimke.composeai.uibuilder.local

import ee.schimke.composeai.uibuilder.CommandOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The seam between a synchronous reducer and an asynchronous IndexedDB: reads answered from memory,
 * writes landed in memory and written behind in order, and another tab's changes taken in without
 * being written back.
 */
class MirroredLocalDesignStorageTest {
  /** The store behind the mirror, applying changes only when told to — as IndexedDB commits. */
  private class DeferredSink : MirroredLocalDesignStorage.Sink {
    val pending = mutableListOf<Pair<String, String?>>()
    val committed = mutableMapOf<String, String>()

    override fun put(key: String, value: String) {
      pending += key to value
    }

    override fun delete(key: String) {
      pending += key to null
    }

    fun commit() {
      pending.forEach { (key, value) ->
        if (value == null) committed.remove(key) else committed[key] = value
      }
      pending.clear()
    }
  }

  private fun seeded(): LocalDesignRecordV1 =
    localDesignRecord(
      document = LocalDesignFixtures.document(),
      catalogSystemId = LocalDesignFixtures.CATALOG_SYSTEM_ID,
      sequence = 0,
      nowEpochMillis = 0,
    )

  @Test
  fun `an edit is answered Stored before the store behind has written it`() {
    val sink = DeferredSink()
    val store = LocalDesignStore(MirroredLocalDesignStorage(emptyMap(), sink))
    store.write(seeded())
    val session =
      LocalDesignSession.open(assertNotNull(store.read(LocalDesignFixtures.DESIGN_ID)), store)

    val result = session.submit(LocalDesignFixtures.insert("first", baseRevision = 0))

    assertIs<CommandOutcome.Accepted>(result.outcome)
    assertEquals(LocalPersistence.Stored, result.persistence)
    assertTrue(sink.committed.isEmpty(), "nothing has reached the store yet")
    // Read back through the mirror, the edit is already there: a reload in this tab replays it.
    val reopened =
      LocalDesignSession.open(assertNotNull(store.read(LocalDesignFixtures.DESIGN_ID)), store)
    assertTrue("first" in reopened.document.nodes)

    sink.commit()
    val key = LocalDesignStore.designKey(LocalDesignFixtures.DESIGN_ID)
    assertEquals(store.storedText(LocalDesignFixtures.DESIGN_ID), sink.committed[key])
  }

  @Test
  fun `the store behind receives every change in the order it was made`() {
    val sink = DeferredSink()
    val storage = MirroredLocalDesignStorage(mapOf("ui-builder.local.design.a" to "1"), sink)

    storage.write("ui-builder.local.design.a", "2")
    storage.remove("ui-builder.local.design.a")
    storage.write("ui-builder.local.design.a", "3")

    assertEquals(
      listOf(
        "ui-builder.local.design.a" to "2",
        "ui-builder.local.design.a" to null,
        "ui-builder.local.design.a" to "3",
      ),
      sink.pending,
    )
    sink.commit()
    assertEquals("3", sink.committed["ui-builder.local.design.a"])
  }

  @Test
  fun `what the page read at open is listed without the store being asked`() {
    val record = seeded()
    val encoded = localDesignJson.encodeToString(LocalDesignRecordV1.serializer(), record)
    val storage =
      MirroredLocalDesignStorage(
        mapOf(LocalDesignStore.designKey(record.designId) to encoded),
        DeferredSink(),
      )

    val listed = LocalDesignStore(storage).list()

    assertEquals(listOf(record.designId), listed.map { it.designId })
  }

  @Test
  fun `another tab's change is taken into memory and not written back`() {
    val sink = DeferredSink()
    val storage = MirroredLocalDesignStorage(mapOf("k" to "mine"), sink)

    storage.applyExternal("k", "theirs")
    assertEquals("theirs", storage.read("k"))
    storage.applyExternal("k", null)
    assertNull(storage.read("k"))

    assertTrue(sink.pending.isEmpty())
  }
}
