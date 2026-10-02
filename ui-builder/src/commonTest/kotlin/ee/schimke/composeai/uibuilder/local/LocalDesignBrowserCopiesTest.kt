package ee.schimke.composeai.uibuilder.local

import ee.schimke.composeai.uibuilder.client.toProtocolDocument
import ee.schimke.composeai.uibuilder.editor.EditorExportFormat
import ee.schimke.composeai.uibuilder.editor.UiBuilderExportHost
import ee.schimke.composeai.uibuilder.editor.refusing
import ee.schimke.composeai.uibuilder.export.UiBuilderDocumentHome
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules a page follows for a caller who may read a server's designs but not write them (#342):
 * where an edit goes, which refusals keep the edit, how browser designs are listed, and how one is
 * published once the server will take it.
 */
class LocalDesignBrowserCopiesTest {
  @Test
  fun `a caller who may not write never sends an edit to the server`() {
    assertEquals(
      EditorEditRoute.BrowserCopy,
      editorEditRoute(keptInBrowser = false, canWrite = false, revisionPinned = false),
    )
  }

  @Test
  fun `a caller who may write, and a browser design, edit where they are`() {
    assertEquals(
      EditorEditRoute.Server,
      editorEditRoute(keptInBrowser = false, canWrite = true, revisionPinned = false),
    )
    // Its edits go to the local service, which is the browser; a second copy would be a copy of a
    // copy with nothing gained.
    assertEquals(
      EditorEditRoute.Server,
      editorEditRoute(keptInBrowser = true, canWrite = false, revisionPinned = false),
    )
  }

  @Test
  fun `history is never copied by an edit`() {
    assertEquals(
      EditorEditRoute.Server,
      editorEditRoute(keptInBrowser = false, canWrite = false, revisionPinned = true),
    )
  }

  @Test
  fun `only a refusal of the caller keeps the edit as a browser copy`() {
    assertTrue(isWriteRefusal(ServiceErrorCodeV1.UNAUTHORIZED))
    assertTrue(isWriteRefusal(ServiceErrorCodeV1.FORBIDDEN))
    // A refusal of the edit itself is the reducer's to reconcile, not a reason to fork.
    assertFalse(isWriteRefusal(ServiceErrorCodeV1.BAD_REQUEST))
    assertFalse(isWriteRefusal(ServiceErrorCodeV1.NOT_FOUND))
  }

  @Test
  fun `a copy is listed with where it came from, after a reload`() {
    val storage = InMemoryLocalDesignStorage()
    LocalDesignStore(storage)
      .write(
        localCopyRecord(
          document = LocalDesignFixtures.document(),
          documentDigest = "digest",
          catalogSystemId = LocalDesignFixtures.CATALOG_SYSTEM_ID,
          newDesignId = "brave-kettle",
          server = "https://preview.coo.ee",
          nowEpochMillis = 42,
        )
      )

    // A fresh store over the same storage is what a reload is.
    val reloaded = LocalDesignStore(storage)
    assertEquals(LocalDesignFixtures.DESIGN_ID, reloaded.read("brave-kettle")?.copiedFrom?.designId)
    assertNull(reloaded.read("brave-kettle")?.origin)

    val listed = browserHomeDesigns(reloaded.list()) { "at $it" }.single()
    assertEquals("brave-kettle", listed.designId)
    assertEquals(LocalDesignFixtures.DESIGN_ID, listed.copiedFrom)
    assertEquals("edited at 42", listed.updatedLabel)
    assertFalse(listed.unreadable)
  }

  @Test
  fun `an unreadable record is listed so it can be downloaded and deleted`() {
    val storage = InMemoryLocalDesignStorage()
    storage.write("${LocalDesignStore.DESIGN_KEY_PREFIX}mystery", "{not json")
    val store = LocalDesignStore(storage)

    val listed = browserHomeDesigns(store.list()) { "at $it" }.single()
    assertTrue(listed.unreadable)
    assertEquals("", listed.updatedLabel)
    // Downloadable as it is stored, which is the only way to keep a newer builder's record.
    assertEquals("{not json", store.storedText("mystery"))
  }

  @Test
  fun `storage is called nearly full only near the budget`() {
    fun summaries(bytes: Int) =
      listOf(
        LocalDesignSummary(
          designId = "a",
          catalogSystemId = "m3-catalog",
          title = "A",
          updatedAtEpochMillis = 1,
          storedBytes = bytes,
        )
      )

    assertNull(localStorageNotice(summaries(1_000), budgetChars = 10_000))
    val notice = assertNotNull(localStorageNotice(summaries(9_000), budgetChars = 10_000))
    assertTrue("90%" in notice, notice)
    assertTrue("Download" in notice, notice)
  }

  @Test
  fun `in IndexedDB the origin's quota, not the designs' size, decides the warning`() {
    val bigDesigns =
      listOf(
        LocalDesignSummary(
          designId = "a",
          catalogSystemId = "m3-catalog",
          title = "A",
          updatedAtEpochMillis = 1,
          storedBytes = 9_000_000,
        )
      )
    val roomy =
      BrowserStorageEstimate(
        usageBytes = 9_000_000,
        quotaBytes = 1_000_000_000,
        persisted = true,
        indexedDb = true,
      )
    assertNull(localStorageNotice(bigDesigns, estimate = roomy))

    val full = roomy.copy(usageBytes = 950_000_000, persisted = false)
    val notice = assertNotNull(localStorageNotice(bigDesigns, estimate = full))
    assertTrue("906.0 MB of 953.7 MB" in notice, notice)
    assertTrue("may clear" in notice, notice)
  }

  @Test
  fun `in localStorage the estimate is added to the designs' own warning`() {
    val summaries =
      listOf(
        LocalDesignSummary(
          designId = "a",
          catalogSystemId = "m3-catalog",
          title = "A",
          updatedAtEpochMillis = 1,
          storedBytes = 9_000,
        )
      )
    val estimate =
      BrowserStorageEstimate(
        usageBytes = 1_048_576,
        quotaBytes = 10_485_760,
        persisted = true,
        indexedDb = false,
      )
    val notice =
      assertNotNull(localStorageNotice(summaries, budgetChars = 10_000, estimate = estimate))
    assertTrue("90%" in notice, notice)
    assertTrue("1.0 MB of 10.0 MB this browser allows this site" in notice, notice)
  }

  @Test
  fun `a published copy starts at revision zero with no home of its own`() {
    val wire =
      LocalDesignFixtures.document()
        .copy(
          revision = 7,
          home = UiBuilderDocumentHome.Server("https://preview.coo.ee", "shameless-potato"),
        )
        .toProtocolDocument()

    val published = publishableDocument(wire)

    assertEquals(0L, published.revision)
    assertNull(published.home)
    assertEquals(wire.nodes, published.nodes)
    assertEquals(wire.id, published.id)
  }

  @Test
  fun `a publish is refused in words`() {
    assertNull(publishRefusal("brave-kettle", 201, "created brave-kettle"))
    assertTrue("already has a design" in publishRefusal("brave-kettle", 412, "")!!)
    assertTrue("members of the google" in publishRefusal("x", 403, "members of the google org")!!)
  }

  @Test
  fun `a refusing export host keeps its menu and answers with the reason`() = runImmediate {
    val served =
      object : UiBuilderExportHost {
        override val formats = listOf(EditorExportFormat.Png, EditorExportFormat.Svg)

        override suspend fun copyPicture(format: EditorExportFormat) = error("not called")

        override suspend fun copyLink(format: EditorExportFormat) = error("not called")

        override suspend fun download(format: EditorExportFormat) = error("not called")
      }

    val refusing = served.refusing("members only")

    assertEquals(served.formats, refusing.formats)
    assertEquals("members only", refusing.copyPicture(EditorExportFormat.Png))
    assertEquals("members only", refusing.copyLink(EditorExportFormat.Svg))
    assertEquals("members only", refusing.download(EditorExportFormat.Png))
  }

  private fun <T> runImmediate(block: suspend () -> T): T {
    var completed: Result<T>? = null
    block.startCoroutine(
      object : Continuation<T> {
        override val context = EmptyCoroutineContext

        override fun resumeWith(result: Result<T>) {
          completed = result
        }
      }
    )
    return completed?.getOrThrow() ?: error("suspended unexpectedly")
  }
}
