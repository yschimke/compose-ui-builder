package ee.schimke.composeai.uibuilder.local

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import ee.schimke.composeai.uibuilder.export.toDesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.CatalogBenchmarkV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.CatalogTemplateDocumentsV1
import ee.schimke.composeai.uibuilder.protocol.SnapshotResponseV1
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive

class LocalCatalogTemplatesTest {
  private val storage = InMemoryLocalDesignStorage()

  private fun catalog(revision: String = "revision-7") =
    CatalogCapabilityV1.Builder(
        "compose-ui-builder-catalog/v1",
        CatalogBenchmarkV1.Builder("watch", "source", "watch-kit", revision, "runtime-7").build(),
        emptyList(),
      )
      .build()

  private val catalog = catalog()
  private val template =
    localDesignJson.decodeFromString<UiBuilderDocument>(
      """{"schema":"compose-ui-builder-document/v1-candidate","id":"published-card","title":"Published card","revision":19,
      "catalogPin":{"systemId":"watch-kit","catalogRevision":"candidate","capabilityDigest":"candidate","nativeRuntimeId":"candidate"},
      "environment":{"widthDp":192,"heightDp":192,"density":2.0,"theme":"dark","locale":"en-US","fontScale":1.0,"layoutDirection":"ltr","exportDevices":["id:pixel_6"]},"stateVariables":{},"roots":["root"],
      "nodes":{"root":{"id":"root","componentId":"watch/card","properties":{},"modifiers":[],"slots":{}}}}"""
    )

  private fun payload(
    revision: String = catalog.benchmark.catalogRevision,
    digest: String = "candidate",
  ) =
    localDesignJson.encodeToString(
      CatalogTemplateDocumentsV1.Builder(
          CatalogReferenceV1("watch-kit", revision, digest, catalog.benchmark.nativeRuntimeId)
        )
        .also { it.templates = mapOf("card" to template.toDesignDocumentV1()) }
        .build()
    )

  @Test
  fun `an unsupported publication version cannot enter the cache`() =
    runBlocking<Unit> {
      assertFailsWith<LocalDesignStorageException> {
        LocalCatalogTemplates(storage).load(catalog) {
          payload().replace("\"schemaVersion\":1", "\"schemaVersion\":99")
        }
      }
      assertFailsWith<LocalDesignStorageException> {
        LocalCatalogTemplates(storage).load(catalog) { error("offline") }
      }
    }

  @Test
  fun `a fresh offline session creates from the cached published document and catalog`() =
    runBlocking<Unit> {
      val catalogs = CachingLocalCatalogSource(storage, { listOf(catalog) })
      catalogs.catalogs()
      LocalCatalogTemplates(storage).load(catalog) { payload() }

      // Both network sources are now down; recreate the session objects as after a page reload.
      val offlineCatalogs = CachingLocalCatalogSource(storage, { error("server down") })
      val remembered = offlineCatalogs.catalogs().single()
      val templates = LocalCatalogTemplates(storage).load(remembered) { error("server down") }
      val seeded =
        UiBuilderNewDesignSeed.document(
          "offline-copy",
          "card",
          CatalogReferenceV1(
            "watch-kit",
            remembered.benchmark.catalogRevision,
            "candidate",
            remembered.benchmark.nativeRuntimeId,
          ),
          templates,
        )
      val store = LocalDesignStore(storage)
      val service = LocalUiBuilderService(store, offlineCatalogs, clock = { 123L })
      assertIs<SnapshotResponseV1>(service.create(seeded))
      val kept = store.read("offline-copy")!!.seed
      assertEquals("offline-copy", kept.id)
      assertEquals(0, kept.revision)
      assertEquals(template.nodes, kept.nodes)
      assertEquals(template.environment, kept.environment)
      assertEquals(
        JsonPrimitive(remembered.benchmark.catalogRevision),
        kept.catalogPin["catalogRevision"],
      )
    }

  @Test
  fun `a racing revision does not poison an existing cache`() =
    runBlocking<Unit> {
      LocalCatalogTemplates(storage).load(catalog) { payload() }
      val loaded = LocalCatalogTemplates(storage).load(catalog) { payload("another-revision") }
      assertEquals(template, loaded["card"]!!.document)
      assertEquals(
        template,
        LocalCatalogTemplates(storage)
          .load(catalog) { payload(digest = "changed-digest") }["card"]!!
          .document,
      )
      assertEquals(
        template,
        LocalCatalogTemplates(storage).load(catalog) { error("offline") }["card"]!!.document,
      )
    }

  @Test
  fun `templates for a different revision are never used on a first offline visit`() =
    runBlocking<Unit> {
      assertFailsWith<LocalDesignStorageException> {
        LocalCatalogTemplates(storage).load(catalog) { payload("another-revision") }
      }
      assertFailsWith<LocalDesignStorageException> {
        LocalCatalogTemplates(storage).load(catalog) { error("offline") }
      }
    }

  @Test
  fun `catalog upgrades replace old templates within a single publication quota`() =
    runBlocking<Unit> {
      // Enough for one publication, but never two complete copies under different revision keys.
      val bounded = InMemoryLocalDesignStorage(payload().length + 200)
      for (revision in listOf("revision-7", "revision-8", "revision-9")) {
        LocalCatalogTemplates(bounded).load(catalog(revision)) { payload(revision) }
        assertEquals(1, bounded.keys().size)
        assertEquals(
          template,
          LocalCatalogTemplates(bounded)
            .load(catalog(revision)) { error("offline") }["card"]!!
            .document,
        )
      }
      // Replacing an entry never allows a newer publication to satisfy an older catalog pin.
      assertFailsWith<LocalDesignStorageException> {
        LocalCatalogTemplates(bounded).load(catalog) { error("offline") }
      }
    }

  @Test
  fun `a refused replacement retains the previous matching publication`() =
    runBlocking<Unit> {
      val bounded = InMemoryLocalDesignStorage(payload().length + 200)
      LocalCatalogTemplates(bounded).load(catalog) { payload() }
      val revision = "revision-" + "x".repeat(500)
      assertEquals(
        template,
        LocalCatalogTemplates(bounded)
          .load(catalog(revision)) { payload(revision) }["card"]!!
          .document,
      )
      assertEquals(
        template,
        LocalCatalogTemplates(bounded).load(catalog) { error("offline") }["card"]!!.document,
      )
      assertFailsWith<LocalDesignStorageException> {
        LocalCatalogTemplates(bounded).load(catalog(revision)) { error("offline") }
      }
    }

  @Test
  fun `quota refusal preserves online templates and cancellation is not an offline fallback`() =
    runBlocking<Unit> {
      assertEquals(
        listOf("card"),
        LocalCatalogTemplates(InMemoryLocalDesignStorage(1)).load(catalog) { payload() }.ids,
      )
      LocalCatalogTemplates(storage).load(catalog) { payload() }
      assertFailsWith<CancellationException> {
        LocalCatalogTemplates(storage).load(catalog) { throw CancellationException("left page") }
      }
    }
}
