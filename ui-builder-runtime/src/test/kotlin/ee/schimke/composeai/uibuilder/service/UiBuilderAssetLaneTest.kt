package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.AcceptedOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.CatalogBenchmarkV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessActionV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessRoleV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.ExportArtifactV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.protocol.ExportEncodingV1
import ee.schimke.composeai.uibuilder.protocol.GrantActorAccessMutationV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.UploadedAssetSourceV1
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir

/**
 * The asset lane: bytes in, a pinned `assets` entry out, and every other lane seeing it.
 *
 * This is the requirement in yschimke/compose-preview-server#478 — a design could not contain a
 * picture from any direction — and the safety half of #484: what a design names has to be something
 * the service knows about, and what it does not know about must not fail a render.
 */
class UiBuilderAssetLaneTest {
  @TempDir lateinit var directory: Path

  private val owner = AuthenticatedUiBuilderActor("owner")
  private val viewer = AuthenticatedUiBuilderActor("viewer")
  private val outsider = AuthenticatedUiBuilderActor("outsider")

  @Test
  fun `a put pins an uploaded binding, moves the revision and reads back the same bytes`() {
    val store = MemoryAssetStore()
    val service = service(store)
    create(service)

    val outcome = put(service, owner, "avatar-lain", PNG_HEADER)
    val accepted = assertIs<AcceptedOutcomeV1>(outcome)
    assertEquals(1, accepted.committedRevision)
    assertEquals(1, accepted.sequence)
    assertFalse(accepted.idempotentReplay)

    val document = currentDocument(service)
    assertEquals(1, document.revision)
    val binding = assertNotNull(document.assets["avatar-lain"])
    assertEquals("image/png", binding.mediaType)
    assertEquals(UiBuilderAssetDigests.of(PNG_HEADER), binding.contentDigest)
    assertEquals(UploadedAssetSourceV1(binding.contentDigest), binding.source)
    assertEquals(3 to 2, binding.widthPx to binding.heightPx)
    assertContentEquals(PNG_HEADER, store.read(binding.contentDigest))

    val read = read(service, owner, "avatar-lain")
    val found = assertIs<UiBuilderAssetReadResult.Found>(read)
    assertContentEquals(PNG_HEADER, found.bytes)
    assertEquals(binding, found.binding)
  }

  @Test
  fun `the same bytes under the same key replay idempotently and different bytes re-point it`() {
    val service = service(MemoryAssetStore())
    create(service)
    put(service, owner, "cover", PNG_HEADER)

    val replay = assertIs<AcceptedOutcomeV1>(put(service, owner, "cover", PNG_HEADER))
    assertTrue(replay.idempotentReplay)
    assertEquals(1, replay.committedRevision)
    assertEquals(1, currentDocument(service).revision)

    val repointed = assertIs<AcceptedOutcomeV1>(put(service, owner, "cover", GIF_HEADER))
    assertFalse(repointed.idempotentReplay)
    assertEquals(2, repointed.committedRevision)
    assertEquals("image/gif", currentDocument(service).assets.getValue("cover").mediaType)
  }

  @Test
  fun `bytes that are not a raster image, a malformed key and an oversized asset are refused`() {
    val service =
      service(MemoryAssetStore(), UiBuilderServiceLimits(maximumAssetBytes = PNG_HEADER.size))
    create(service)

    val junk = error(putResponse(service, owner, "junk", "not an image".encodeToByteArray()))
    assertEquals(ServiceErrorCodeV1.BAD_REQUEST, junk.code)
    assertTrue("PNG, JPEG, GIF or WebP" in junk.message, junk.message)

    val key = error(putResponse(service, owner, "../etc", PNG_HEADER))
    assertEquals(ServiceErrorCodeV1.BAD_REQUEST, key.code)
    assertTrue("asset key" in key.message, key.message)

    val large = error(putResponse(service, owner, "big", PNG_HEADER + byteArrayOf(0)))
    assertEquals(ServiceErrorCodeV1.BAD_REQUEST, large.code)
    assertEquals(1, service.diagnostics().rejectedAssetBytes)

    assertEquals(0, currentDocument(service).revision)
    assertTrue(currentDocument(service).assets.isEmpty())
  }

  @Test
  fun `a viewer may read an asset but not put one, and an outsider may do neither`() {
    val service = service(MemoryAssetStore())
    create(service)
    put(service, owner, "cover", PNG_HEADER)
    grantViewer(service)

    assertEquals(
      ServiceErrorCodeV1.FORBIDDEN,
      error(putResponse(service, viewer, "x", PNG_HEADER)).code,
    )
    assertIs<UiBuilderAssetReadResult.Found>(read(service, viewer, "cover"))
    val outsiderRead = assertIs<UiBuilderAssetReadResult.Failed>(read(service, outsider, "cover"))
    assertEquals(ServiceErrorCodeV1.FORBIDDEN, outsiderRead.error.code)
    val missing = assertIs<UiBuilderAssetReadResult.Failed>(read(service, owner, "nope"))
    assertEquals(ServiceErrorCodeV1.NOT_FOUND, missing.error.code)
  }

  @Test
  fun `a subscriber is handed a snapshot, and a delta from before the put asks for one`() {
    val service = service(MemoryAssetStore())
    create(service)
    val updates = CopyOnWriteArrayList<UiBuilderServiceUpdate>()
    val subscription =
      service.subscribe(UiBuilderSubscriptionCall(owner, "design", afterSequence = 0)) {
        updates += it
      }
    try {
      put(service, owner, "cover", PNG_HEADER)
    } finally {
      subscription.close()
    }
    val snapshot =
      assertNotNull(updates.filterIsInstance<UiBuilderServiceUpdate.Snapshot>().lastOrNull())
    assertEquals(1, snapshot.snapshot.state.lastSequence)
    assertTrue("cover" in snapshot.snapshot.state.document.assets)
    // Broadcast to every reader, so it must not carry the owner's access list.
    assertNull(snapshot.snapshot.access)

    val delta =
      execute(
        service,
        owner,
        UiBuilderServiceRequest.GetDelta("design", afterSequence = 0, limit = 10),
      )
    assertEquals(ServiceErrorCodeV1.SNAPSHOT_REQUIRED, error(delta).code)
    // A retained revision still opens: the put wrote one like any commit.
    val pinned =
      execute(service, owner, UiBuilderServiceRequest.GetSnapshot("design", revision = 1))
    assertIs<UiBuilderServiceResponse.Snapshot>(pinned)
  }

  @Test
  fun `a host with no asset store refuses a put and says so`() {
    val service = service(store = null)
    create(service)
    val refusal = error(putResponse(service, owner, "cover", PNG_HEADER))
    assertEquals(ServiceErrorCodeV1.BAD_REQUEST, refusal.code)
    assertTrue("asset store" in refusal.message, refusal.message)
  }

  @Test
  fun `the renderer projection inlines stored bytes and keeps what it cannot find`() {
    val store = MemoryAssetStore()
    val service = service(store)
    create(service)
    put(service, owner, "cover", PNG_HEADER)
    val document = currentDocument(service)
    val orphan =
      document.copy(
        assets =
          document.assets +
            ("ghost" to
              document.assets
                .getValue("cover")
                .copy(source = UploadedAssetSourceV1("sha256:" + "0".repeat(64))))
      )

    val projected =
      Json.parseToJsonElement(
          projectRendererDocument(orphan) { binding ->
            (binding.source as? UploadedAssetSourceV1)?.let { store.read(it.storageKey) }
          }
        )
        .jsonObject
    val assets = projected.getValue("assets").jsonObject
    val cover = assets.getValue("cover").jsonObject.getValue("source").jsonObject
    assertEquals("embedded", cover.getValue("type").jsonPrimitive.content)
    assertEquals(
      Base64.getEncoder().encodeToString(PNG_HEADER),
      cover.getValue("base64").jsonPrimitive.content,
    )
    val ghost = assets.getValue("ghost").jsonObject.getValue("source").jsonObject
    assertEquals("uploaded", ghost.getValue("type").jsonPrimitive.content)
    // Without a resolver the projection is the one it always was: no `assets` key at all for an
    // empty registry, and the bindings untouched otherwise.
    assertFalse("assets" in Json.parseToJsonElement(projectRendererDocument(document())).jsonObject)
  }

  @Test
  fun `the file store is content addressed and refuses a key that is not a digest`() {
    val store = FileUiBuilderAssetStore(directory.resolve("assets"))
    val digest = UiBuilderAssetDigests.of(PNG_HEADER)
    assertNull(store.read(digest))
    store.write(digest, PNG_HEADER)
    store.write(digest, PNG_HEADER)
    assertContentEquals(PNG_HEADER, store.read(digest))
    assertEquals(1, Files.list(directory.resolve("assets")).count())
    assertNull(store.read("../../etc/passwd"))
    assertNull(store.read("sha256:nothex"))
    assertTrue(runCatching { store.write("../escape", PNG_HEADER) }.isFailure)
  }

  @Test
  fun `the sniffer names the four raster formats and their sizes`() {
    assertEquals(UiBuilderImageBytes("image/png", 3, 2), UiBuilderImageBytes.sniff(PNG_HEADER))
    assertEquals(UiBuilderImageBytes("image/gif", 7, 5), UiBuilderImageBytes.sniff(GIF_HEADER))
    assertEquals(UiBuilderImageBytes("image/jpeg", 9, 4), UiBuilderImageBytes.sniff(JPEG_HEADER))
    assertEquals(
      UiBuilderImageBytes("image/webp", 16, 8),
      UiBuilderImageBytes.sniff(WEBP_VP8X_HEADER),
    )
    assertNull(
      UiBuilderImageBytes.sniff("<svg xmlns='http://www.w3.org/2000/svg'/>".encodeToByteArray())
    )
    assertNull(UiBuilderImageBytes.sniff(ByteArray(0)))
  }

  private fun service(
    store: UiBuilderAssetStore?,
    limits: UiBuilderServiceLimits = UiBuilderServiceLimits(),
  ): PersistentUiBuilderService =
    PersistentUiBuilderService(
      storage = MemoryStorage(),
      catalogs = TestCatalogs,
      exporter = { request ->
        ExportArtifactV1(request.format, "text/plain", ExportEncodingV1.UTF8, "", "")
      },
      limits = limits,
      assets = store,
    )

  private fun create(service: PersistentUiBuilderService) {
    assertIs<UiBuilderServiceResponse.Snapshot>(
      execute(service, owner, UiBuilderServiceRequest.CreateDesign(document()))
    )
  }

  private fun grantViewer(service: PersistentUiBuilderService) {
    val access =
      assertIs<UiBuilderServiceResponse.DesignAccess>(
        execute(service, owner, UiBuilderServiceRequest.GetDesignAccess("design"))
      )
    assertIs<UiBuilderServiceResponse.DesignAccess>(
      execute(
        service,
        owner,
        UiBuilderServiceRequest.UpdateDesignAccess(
          "design",
          access.access.accessRevision,
          listOf(
            GrantActorAccessMutationV1(
              viewer.actorId,
              DesignAccessRoleV1.VIEWER,
              listOf(DesignAccessActionV1.READ),
            )
          ),
        ),
      )
    )
  }

  private fun putResponse(
    service: PersistentUiBuilderService,
    actor: AuthenticatedUiBuilderActor,
    assetKey: String,
    bytes: ByteArray,
  ): UiBuilderServiceResponse = blockingSuspend {
    service.putAsset(UiBuilderAssetWrite(actor, "design", assetKey, bytes))
  }

  private fun put(
    service: PersistentUiBuilderService,
    actor: AuthenticatedUiBuilderActor,
    assetKey: String,
    bytes: ByteArray,
  ) =
    assertIs<UiBuilderServiceResponse.OperationOutcome>(
        putResponse(service, actor, assetKey, bytes)
      )
      .outcome

  private fun read(
    service: PersistentUiBuilderService,
    actor: AuthenticatedUiBuilderActor,
    assetKey: String,
  ): UiBuilderAssetReadResult = blockingSuspend {
    service.readAsset(UiBuilderAssetRead(actor, "design", assetKey))
  }

  private fun execute(
    service: PersistentUiBuilderService,
    actor: AuthenticatedUiBuilderActor,
    request: UiBuilderServiceRequest,
  ): UiBuilderServiceResponse = blockingSuspend {
    service.execute(UiBuilderServiceCall(actor, request))
  }

  private fun error(response: UiBuilderServiceResponse): UiBuilderServiceError =
    assertIs<UiBuilderServiceResponse.Error>(response).error

  private fun currentDocument(service: PersistentUiBuilderService): DesignDocumentV1 =
    assertIs<UiBuilderServiceResponse.Snapshot>(
        execute(service, owner, UiBuilderServiceRequest.OpenDesign("design"))
      )
      .snapshot
      .state
      .document

  private fun document(): DesignDocumentV1 =
    DesignDocumentV1(
      schema = "compose-ui-builder/v1",
      id = "design",
      title = "Discover",
      revision = 0,
      catalogPin = CATALOG_REFERENCE,
      environment =
        DesignEnvironmentV1(
          widthDp = 1280,
          heightDp = 800,
          density = 1.0,
          theme = ThemeV1.DARK,
          locale = "en-GB",
          fontScale = 1.0,
          layoutDirection = LayoutDirectionV1.LTR,
        ),
      roots = emptyList(),
      nodes = emptyMap(),
    )

  private class MemoryStorage : UiBuilderStateStorage {
    var bytes: ByteArray? = null

    override fun load(): ByteArray? = bytes?.copyOf()

    override fun replace(value: ByteArray) {
      bytes = value.copyOf()
    }
  }

  private class MemoryAssetStore : UiBuilderAssetStore {
    private val blobs = linkedMapOf<String, ByteArray>()

    override fun read(storageKey: String): ByteArray? = blobs[storageKey]?.copyOf()

    override fun write(storageKey: String, bytes: ByteArray) {
      blobs.putIfAbsent(storageKey, bytes.copyOf())
    }
  }

  private object TestCatalogs : UiBuilderCatalogExecutor {
    override fun listCatalogs(): List<CatalogCapabilityV1> = listOf(CATALOG)

    override fun resolve(reference: CatalogReferenceV1): CatalogCapabilityV1? = CATALOG.takeIf {
      reference == CATALOG_REFERENCE
    }

    override fun validate(document: DesignDocumentV1, catalog: CatalogCapabilityV1) = null
  }

  private companion object {
    val CATALOG_REFERENCE = CatalogReferenceV1("m3", "catalog", "digest", "m3-runtime")
    val CATALOG =
      CatalogCapabilityV1(
        schema = "compose-catalog-capabilities/v1",
        benchmark = CatalogBenchmarkV1("m3", "source", "m3", "catalog", "m3-runtime"),
        components = emptyList(),
        exportCapabilities = ExportCapabilitiesV1(composeCode = true, svg = false, png = false),
      )

    /** A PNG signature and an IHDR chunk declaring 3×2; enough for the sniffer, not a picture. */
    val PNG_HEADER: ByteArray =
      byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
        byteArrayOf(0, 0, 0, 0x0D) +
        "IHDR".encodeToByteArray() +
        byteArrayOf(0, 0, 0, 3, 0, 0, 0, 2) +
        byteArrayOf(8, 6, 0, 0, 0) +
        ByteArray(4)

    val GIF_HEADER: ByteArray =
      "GIF89a".encodeToByteArray() + byteArrayOf(7, 0, 5, 0) + ByteArray(6)

    /** SOI, an APP0 marker, then a SOF0 declaring 4 rows by 9 columns. */
    val JPEG_HEADER: ByteArray =
      byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
        byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0, 4, 0, 0) +
        byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0, 11, 8, 0, 4, 0, 9, 1, 0, 0, 0) +
        ByteArray(4)

    /** RIFF/WEBP with a VP8X chunk declaring 16×8 (stored as width-1 and height-1, 24-bit LE). */
    val WEBP_VP8X_HEADER: ByteArray =
      "RIFF".encodeToByteArray() +
        byteArrayOf(0, 0, 0, 0) +
        "WEBP".encodeToByteArray() +
        "VP8X".encodeToByteArray() +
        byteArrayOf(10, 0, 0, 0) +
        byteArrayOf(0, 0, 0, 0) +
        byteArrayOf(15, 0, 0) +
        byteArrayOf(7, 0, 0) +
        ByteArray(4)
  }
}

/** The service answers without suspending; a real dispatcher would be a dependency for nothing. */
private fun <T> blockingSuspend(block: suspend () -> T): T {
  var completion: Result<T>? = null
  block.startCoroutine(
    object : Continuation<T> {
      override val context = EmptyCoroutineContext

      override fun resumeWith(result: Result<T>) {
        completion = result
      }
    }
  )
  return completion?.getOrThrow() ?: error("synchronous service call suspended unexpectedly")
}
