package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A stored design the current catalog or limits cannot serve does not stop the service starting.
 *
 * It used to. The check ran in `init`, so one design out of a possible thousand could stop the
 * whole server coming up — and the shapes that trigger it are ordinary rather than exotic: a
 * catalog revision moves and every design pinned to the old one stops resolving, an operator stops
 * serving a catalog, a node limit is tightened. The blast radius of a content change had no
 * relationship to its cause.
 *
 * Now every design loads, one that cannot be served answers each request naming it with the reason,
 * and `diagnostics()` counts them. The rest of the store is untouched, which is the assertion that
 * matters most here.
 *
 * The line this does not cross is integrity: a state file that fails its checksum, is truncated, or
 * declares a format this build cannot read is still refused by the storage layer, with
 * `restoreBackup` as the recovery. `PersistentUiBuilderServiceTest` owns those, and they still
 * throw.
 */
class UnusableStoredDesignTest {
  @Test
  fun `a design whose catalog is gone loads, reports why, and leaves the rest of the store working`() {
    val storage = MemoryStorage()
    val stocked = service(storage, PinnedCatalogs(resolves = true))
    create(stocked, "kept")
    create(stocked, "orphaned")

    // The catalog this deployment serves no longer resolves the pin both designs were authored
    // against — a revision moved, or an operator stopped serving it.
    val reopened = service(storage, PinnedCatalogs(resolves = false))

    assertEquals(2, reopened.diagnostics().unusableDesigns)
    val error = assertIs<UiBuilderServiceResponse.Error>(open(reopened, "kept"))
    assertEquals(ServiceErrorCodeV1.CATALOG_UNAVAILABLE, error.error.code)
    assertTrue(error.error.message.contains("kept"), error.error.message)

    // Still listed. A design nobody can open is bad; one nobody can even see is worse.
    val listed =
      assertIs<UiBuilderServiceResponse.Designs>(
        execute(reopened, OWNER, UiBuilderServiceRequest.ListDesigns(cursor = null, limit = 10))
      )
    assertEquals(listOf("kept", "orphaned"), listed.designs.map { it.designId }.sorted())
  }

  @Test
  fun `the operator can see which designs are unusable and why, not merely how many`() {
    val storage = MemoryStorage()
    val stocked = service(storage, PinnedCatalogs(resolves = true))
    create(stocked, "kept")
    val reopened = service(storage, PinnedCatalogs(resolves = false))

    // `diagnostics()` says one design is being held back. That is enough to alert on and not enough
    // to act on: repairing the catalog a design pins, or retiring it, both need to know which.
    assertEquals(1, reopened.diagnostics().unusableDesigns)
    val unusable = reopened.adminUnusableDesigns()
    assertEquals(setOf("kept"), unusable.keys)
    assertTrue(unusable.getValue("kept").contains("catalog unavailable"), unusable.getValue("kept"))

    // Still listed for the operator too — reported beside the listing rather than by dropping the
    // row, for the same reason `ListDesigns` keeps it.
    assertEquals(listOf("kept"), reopened.adminListDesigns().map { it.designId })
  }

  @Test
  fun `one unusable design does not take the others with it`() {
    val storage = MemoryStorage()
    val stocked = service(storage, PinnedCatalogs(resolves = true))
    create(stocked, "small")
    create(stocked, "large", nodes = 3)

    // A tightened per-design node limit, which the larger design no longer satisfies.
    val reopened =
      service(
        storage,
        PinnedCatalogs(resolves = true),
        limits = UiBuilderServiceLimits(maximumNodesPerDesign = 2),
      )

    assertEquals(1, reopened.diagnostics().unusableDesigns)
    val refused = assertIs<UiBuilderServiceResponse.Error>(open(reopened, "large"))
    assertEquals(ServiceErrorCodeV1.INTERNAL, refused.error.code)
    assertTrue(refused.error.message.contains("large"), refused.error.message)

    val snapshot = assertIs<UiBuilderServiceResponse.Snapshot>(open(reopened, "small"))
    assertEquals("small", snapshot.snapshot.designId)
  }

  @Test
  fun `an unusable design refuses every request that names it, not only opening`() {
    val storage = MemoryStorage()
    create(service(storage, PinnedCatalogs(resolves = true)), "orphaned")
    val reopened = service(storage, PinnedCatalogs(resolves = false))

    val requests =
      listOf(
        UiBuilderServiceRequest.OpenDesign("orphaned"),
        UiBuilderServiceRequest.GetDesignAccess("orphaned"),
        UiBuilderServiceRequest.GetSnapshot("orphaned", revision = null),
        UiBuilderServiceRequest.GetDelta("orphaned", afterSequence = 0, limit = 10),
        UiBuilderServiceRequest.ApplyOperation(
          UiBuilderSubmission.Batch(
            "orphaned",
            "operation",
            "browser",
            0,
            listOf(
              InsertNodeMutationV1(
                DesignNodeV1(id = "n", componentId = "m3.Text"),
                NodeLocationV1(),
              )
            ),
          )
        ),
      )

    requests.forEach { request ->
      val response = execute(reopened, OWNER, request)
      val error = assertIs<UiBuilderServiceResponse.Error>(response, request.toString())
      assertEquals(ServiceErrorCodeV1.CATALOG_UNAVAILABLE, error.error.code, request.toString())
    }

    // Subscribing is the other door in, and it is closed the same way rather than by pretending the
    // design is missing.
    val rejection = runCatching {
      reopened.subscribe(UiBuilderSubscriptionCall(OWNER, "orphaned", 0)) {}
    }
      .exceptionOrNull()
    val rejected = assertIs<UiBuilderSubscriptionRejectedException>(rejection)
    assertEquals(ServiceErrorCodeV1.CATALOG_UNAVAILABLE, rejected.error.code)
  }

  private fun open(service: PersistentUiBuilderService, designId: String) =
    execute(service, OWNER, UiBuilderServiceRequest.OpenDesign(designId))

  private fun create(service: PersistentUiBuilderService, designId: String, nodes: Int = 1) {
    val ids = (1..nodes).map { "$designId-node-$it" }
    val response =
      execute(
        service,
        OWNER,
        UiBuilderServiceRequest.CreateDesign(
          document(designId)
            .copy(
              roots = ids,
              nodes = ids.associateWith { DesignNodeV1(id = it, componentId = "m3.Text") },
            )
        ),
      )
    assertIs<UiBuilderServiceResponse.Snapshot>(response, "creating $designId")
  }

  private fun service(
    storage: UiBuilderStateStorage,
    catalogs: UiBuilderCatalogExecutor,
    limits: UiBuilderServiceLimits = UiBuilderServiceLimits(),
  ) =
    PersistentUiBuilderService(
      storage = storage,
      catalogs = catalogs,
      exporter = { _ -> error("no export in this test") },
      clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
      limits = limits,
    )

  private fun document(designId: String) =
    DesignDocumentV1(
      schema = "compose-ui-builder/v1",
      id = designId,
      title = designId,
      revision = 0,
      catalogPin = REFERENCE,
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

  /** Resolves the one pin, or nothing at all — which is a catalog revision moving under a store. */
  private class PinnedCatalogs(private val resolves: Boolean) : UiBuilderCatalogExecutor {
    override fun listCatalogs(): List<CatalogCapabilityV1> = listOf(CATALOG)

    override fun resolve(reference: CatalogReferenceV1): CatalogCapabilityV1? = CATALOG.takeIf {
      resolves && reference == REFERENCE
    }

    override fun validate(
      document: DesignDocumentV1,
      catalog: CatalogCapabilityV1,
    ): UiBuilderCatalogIssue? = null
  }

  private class MemoryStorage : UiBuilderStateStorage {
    private var bytes: ByteArray? = null

    override fun load(): ByteArray? = bytes?.copyOf()

    override fun replace(value: ByteArray) {
      bytes = value.copyOf()
    }
  }

  private companion object {
    val OWNER = AuthenticatedUiBuilderActor("owner")

    val REFERENCE =
      CatalogReferenceV1(
        systemId = "test-catalog",
        catalogRevision = "v1",
        capabilityDigest = "digest",
        nativeRuntimeId = "runtime",
      )

    val CATALOG =
      CatalogCapabilityV1(
        schema = "compose-ui-builder-capabilities/v1",
        benchmark =
          CatalogBenchmarkV1(
            id = "test",
            sourceRevision = "test",
            catalogSystemId = "test-catalog",
            catalogRevision = "v1",
            nativeRuntimeId = "runtime",
          ),
        components = emptyList(),
      )

    fun execute(
      service: PersistentUiBuilderService,
      actor: AuthenticatedUiBuilderActor,
      request: UiBuilderServiceRequest,
    ): UiBuilderServiceResponse {
      var completion: Result<UiBuilderServiceResponse>? = null
      suspend { service.execute(UiBuilderServiceCall(actor, request)) }
        .startCoroutine(
          object : Continuation<UiBuilderServiceResponse> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<UiBuilderServiceResponse>) {
              completion = result
            }
          }
        )
      return checkNotNull(completion) { "service did not complete synchronously" }.getOrThrow()
    }
  }
}
