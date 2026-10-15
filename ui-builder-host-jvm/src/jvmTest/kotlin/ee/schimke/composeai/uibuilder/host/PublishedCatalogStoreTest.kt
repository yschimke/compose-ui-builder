package ee.schimke.composeai.uibuilder.host

import ee.schimke.composeai.uibuilder.editor.EditorSubmission
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.editor.screenEnvironmentSettings
import ee.schimke.composeai.uibuilder.export.toUiBuilderDocument
import ee.schimke.composeai.uibuilder.local.FileLocalDesignStorage
import ee.schimke.composeai.uibuilder.local.LocalDesignStore
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class PublishedCatalogStoreTest {
  private val captures: Path =
    Path.of(checkNotNull(System.getProperty(PublishedCatalogStore.CATALOG_DIR_PROPERTY)))
  private val fromCaptures = CatalogFileFetcher.directory(captures)
  private val offline = CatalogFileFetcher { throw IOException("no route to host") }

  @Test
  fun `a catalog is composed from what it publishes`() {
    val load =
      PublishedCatalogStore(Files.createTempDirectory("cache"), fromCaptures).load("wear-m3")

    val loaded = assertIs<PublishedCatalogStore.Load.Loaded>(load)
    assertFalse(loaded.fromCache)
    assertEquals(listOf("wear-screen", "wear-list"), loaded.templates.ids)
    assertTrue(loaded.capabilityJson.contains("\"wear-m3\""))
  }

  @Test
  fun `a catalog fetched once opens again without a network`() {
    val cache = Files.createTempDirectory("cache")
    assertIs<PublishedCatalogStore.Load.Loaded>(
      PublishedCatalogStore(cache, fromCaptures).load("remote-widgets")
    )

    val again = PublishedCatalogStore(cache, offline).load("remote-widgets")

    val loaded = assertIs<PublishedCatalogStore.Load.Loaded>(again)
    assertTrue(loaded.fromCache)
    assertEquals(
      setOf("hello-widget", "counter-widget", "launcher-widget-2x1"),
      loaded.templates.ids.toSet(),
    )
  }

  @Test
  fun `the flat cache from earlier hosts still opens offline`() {
    val cache = Files.createTempDirectory("legacy-cache")
    val location = PublishedCatalogLocation.DEFAULTS.getValue("wear-m3")
    for (path in
      listOf("ui-builder.json", "components.json") +
        listOf("ui-builder/designs/wear-screen.json", "ui-builder/designs/wear-list.json")) {
      val target = cache.resolve("wear-m3").resolve(path)
      Files.createDirectories(target.parent)
      Files.writeString(target, checkNotNull(fromCaptures.fetch("${location.baseUrl}/$path")))
    }
    val loaded =
      assertIs<PublishedCatalogStore.Load.Loaded>(
        PublishedCatalogStore(cache, offline).load("wear-m3")
      )
    assertTrue(loaded.fromCache)
    assertEquals(listOf("wear-screen", "wear-list"), loaded.templates.ids)
  }

  @Test
  fun `a catalog never fetched and unreachable says so instead of opening a copy`() {
    val load = PublishedCatalogStore(Files.createTempDirectory("cache"), offline).load("m3-catalog")

    val unavailable = assertIs<PublishedCatalogStore.Load.Unavailable>(load)
    assertTrue(unavailable.reason.contains("has not been fetched yet"), unavailable.reason)
    assertTrue(unavailable.reason.contains("no route to host"), unavailable.reason)
  }

  @Test
  fun `a catalog that publishes nothing is not mistaken for an offline machine`() {
    val load =
      PublishedCatalogStore(Files.createTempDirectory("cache"), { null }).load("glimmer-catalog")

    val unavailable = assertIs<PublishedCatalogStore.Load.Unavailable>(load)
    assertTrue(unavailable.reason.contains("publishes no ui-builder.json"), unavailable.reason)
  }

  @Test
  fun `an unknown catalog is refused by name`() {
    val load = PublishedCatalogStore(Files.createTempDirectory("cache"), fromCaptures).load("nope")

    assertIs<PublishedCatalogStore.Load.Unavailable>(load)
  }

  @Test
  fun `a failed first fetch can be retried without restarting the host`() {
    var reachable = false
    var requests = 0
    val store =
      PublishedCatalogStore(
        Files.createTempDirectory("retry-cache"),
        { url ->
          requests++
          if (reachable) fromCaptures.fetch(url) else throw IOException("offline")
        },
      )
    assertIs<PublishedCatalogStore.Load.Unavailable>(store.load("wear-m3"))
    assertNull(store.peek("wear-m3"))
    reachable = true
    val loaded = assertIs<PublishedCatalogStore.Load.Loaded>(store.load("wear-m3"))
    val fetched = requests
    assertEquals(loaded, store.load("wear-m3"))
    assertEquals(fetched, requests, "a successful load is still memoized")
  }

  @Test
  fun `an invalid publication cannot overwrite the last validated offline snapshot`() {
    val cache = Files.createTempDirectory("invalid-update-cache")
    val original =
      assertIs<PublishedCatalogStore.Load.Loaded>(
        PublishedCatalogStore(cache, fromCaptures).load("wear-m3")
      )
    val invalid = CatalogFileFetcher { url ->
      if (url.endsWith("/ui-builder.json")) "{" else fromCaptures.fetch(url)
    }
    assertIs<PublishedCatalogStore.Load.Unavailable>(
      PublishedCatalogStore(cache, invalid).load("wear-m3")
    )
    val offlineLoad =
      assertIs<PublishedCatalogStore.Load.Loaded>(
        PublishedCatalogStore(cache, offline).load("wear-m3")
      )
    assertEquals(original.capabilityJson, offlineLoad.capabilityJson)
    assertEquals(
      original.templates.templates.map { it.document },
      offlineLoad.templates.templates.map { it.document },
    )
  }

  @Test
  fun `an incomplete publication cannot combine new capabilities with an old template`() {
    val cache = Files.createTempDirectory("incomplete-update-cache")
    val original =
      assertIs<PublishedCatalogStore.Load.Loaded>(
        PublishedCatalogStore(cache, fromCaptures).load("wear-m3")
      )
    val incomplete = CatalogFileFetcher { url ->
      when {
        url.endsWith("/wear-list.json") -> null
        url.endsWith("/ui-builder.json") -> fromCaptures.fetch(url) + "\n"
        else -> fromCaptures.fetch(url)
      }
    }
    val refused =
      assertIs<PublishedCatalogStore.Load.Unavailable>(
        PublishedCatalogStore(cache, incomplete).load("wear-m3")
      )
    assertTrue(refused.reason.contains("missing"), refused.reason)
    val offlineLoad =
      assertIs<PublishedCatalogStore.Load.Loaded>(
        PublishedCatalogStore(cache, offline).load("wear-m3")
      )
    assertEquals(original.capabilityJson, offlineLoad.capabilityJson)
    assertEquals(
      original.templates.templates.map { it.document },
      offlineLoad.templates.templates.map { it.document },
    )
  }

  @Test
  fun `a new snapshot does not retain files removed from a publication`() {
    val cache = Files.createTempDirectory("removed-template-cache")
    assertIs<PublishedCatalogStore.Load.Loaded>(
      PublishedCatalogStore(cache, fromCaptures).load("wear-m3")
    )
    val changed = CatalogFileFetcher { url ->
      val text = fromCaptures.fetch(url)
      if (url.endsWith("/ui-builder.json")) {
        val root = Json.parseToJsonElement(checkNotNull(text)).jsonObject
        val semantics = root.getValue("statusSemantics").jsonObject
        JsonObject(
            root +
              ("statusSemantics" to
                JsonObject(
                  semantics +
                    ("templates" to
                      JsonArray(listOf((semantics.getValue("templates") as JsonArray).first())))
                ))
          )
          .toString()
      } else text
    }
    val updated =
      assertIs<PublishedCatalogStore.Load.Loaded>(
        PublishedCatalogStore(cache, changed).load("wear-m3")
      )
    assertEquals(listOf("wear-screen"), updated.templates.ids)
    val dir = cache.resolve("wear-m3")
    val snapshot = dir.resolve(Files.readString(dir.resolve("current")))
    assertFalse(Files.exists(snapshot.resolve("ui-builder/designs/wear-list.json")))
    val cached =
      assertIs<PublishedCatalogStore.Load.Loaded>(
        PublishedCatalogStore(cache, offline).load("wear-m3")
      )
    assertEquals(updated.capabilityJson, cached.capabilityJson)
    assertEquals(
      updated.templates.templates.map { it.document },
      cached.templates.templates.map { it.document },
    )
  }

  @Test
  fun `stored and project designs follow a republished catalog without losing their contents`() =
    runBlocking {
      val previous = PublishedCatalogStore.shared
      try {
        val workspace = Files.createTempDirectory("republished-session")
        PublishedCatalogStore.shared =
          PublishedCatalogStore(Files.createTempDirectory("old-catalog"), fromCaptures)
        val original =
          OfflineUiBuilderSession(workspace, catalogSystemId = "wear-m3").use { session ->
            withTimeout(10.seconds) { session.snapshot.filterNotNull().first() }
              .snapshot
              .state
              .document
              .toUiBuilderDocument()
          }
        // A new delivery changes the published byte digest, with identical component definitions.
        PublishedCatalogStore.shared =
          PublishedCatalogStore(
            Files.createTempDirectory("new-catalog"),
            { url ->
              fromCaptures.fetch(url)?.let {
                if (url.endsWith("/ui-builder.json")) it + "\n" else it
              }
            },
          )
        for (project in listOf(false, true)) {
          val committed =
            CompletableDeferred<ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1>()
          val session =
            if (project)
              OfflineUiBuilderSession.projectDocument(original) { committed.complete(it) }
            else OfflineUiBuilderSession(workspace, catalogSystemId = "wear-m3")
          session.use {
            val reopened =
              withTimeout(10.seconds) { session.snapshot.filterNotNull().first() }
                .snapshot
                .state
                .document
                .toUiBuilderDocument()
            assertNotEquals(original.catalogPin, reopened.catalogPin)
            assertEquals(original.copy(catalogPin = reopened.catalogPin), reopened)
            assertFalse(
              UiBuilderEditorReducer(session.catalog).problems(reopened).any {
                it.code == "CATALOG_PIN_MISMATCH"
              }
            )
            if (!project) {
              val saved =
                checkNotNull(LocalDesignStore(FileLocalDesignStorage(workspace)).read(original.id))
              assertEquals(reopened.catalogPin, saved.seed.catalogPin)
            } else {
              assertEquals(
                reopened,
                withTimeout(10.seconds) { committed.await() }.toUiBuilderDocument(),
              )
            }
          }
        }
      } finally {
        PublishedCatalogStore.shared = previous
      }
    }

  @Test
  fun `an edit queued during project repinning is saved after the updated pin`() = runBlocking {
    val original =
      OfflineUiBuilderSession(
          Files.createTempDirectory("early-repin-seed"),
          catalogSystemId = "wear-m3",
        )
        .use { session ->
          withTimeout(10.seconds) { session.snapshot.filterNotNull().first() }
            .snapshot
            .state
            .document
            .toUiBuilderDocument()
        }
    val drifted =
      original.copy(
        catalogPin =
          JsonObject(
            original.catalogPin + ("catalogRevision" to JsonPrimitive("previous-publication"))
          )
      )
    val saving = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val edited = CompletableDeferred<ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1>()
    val writes = mutableListOf<ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1>()
    OfflineUiBuilderSession.projectDocument(drifted) { document ->
        if (writes.isEmpty()) {
          saving.complete(Unit)
          release.await()
        }
        writes.add(document)
        if (document.revision > original.revision) edited.complete(document)
      }
      .use { session ->
        withTimeout(10.seconds) { saving.await() }
        val reducer = UiBuilderEditorReducer(session.catalog, "early-repin", "early-repin")
        val initial = reducer.initial(drifted)
        val settings = drifted.screenEnvironmentSettings()
        val changed =
          reducer.reduce(
            initial,
            UiBuilderEditorEvent.UpdateEnvironment(settings.copy(heightDp = settings.heightDp + 1)),
          )
        session.submit(
          assertIs<EditorSubmission.Batch>(reducer.acceptedSubmission(initial, changed))
        )
        assertNull(session.snapshot.value, "edits must wait for the repinned seed to be saved")
        release.complete(Unit)
        val saved = withTimeout(10.seconds) { edited.await() }.toUiBuilderDocument()
        assertEquals(
          listOf(original.revision.toLong(), original.revision + 1L),
          writes.map { it.revision },
        )
        assertEquals(original.catalogPin, saved.catalogPin)
        assertEquals(settings.heightDp + 1, saved.screenEnvironmentSettings().heightDp)
      }
  }
}
