package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.*
import java.io.IOException
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.serialization.json.JsonPrimitive

/**
 * A design stored against one catalog source opens pinned to the one being served.
 *
 * The second half of #796, and the mirror of `CatalogSourceFlipTest` in `:server` — that one proves
 * the **server** accepts the other source's reference (#816); this proves the document the
 * **editor** is handed names the catalog in front of it (#818). Without it a stored design came
 * back from the flip and could do nothing: `ExportValidation.validateCatalogPin` compares the pin
 * field by field against the served catalog, so the browser put `CATALOG_PIN_MISMATCH` in the
 * Issues panel and both export projections, which share that fail-closed validator, refused.
 *
 * The catalog here is a stub with two accepted references because that is exactly what the
 * production runtime holds after a flip: `acceptedReferences` carries both sources' references for
 * one `systemId`, while `reference()` answers with the one this process serves.
 */
class CatalogSourceRePinTest {

  private val owner = AuthenticatedUiBuilderActor("owner")

  @Test
  fun `a design stored against the other source opens pinned to the served catalog`() {
    val root = createTempDirectory("ui-builder-repin")
    create(service(root, served = BEFORE_FLIP), "checkout")

    val afterFlip = service(root, served = AFTER_FLIP)

    assertEquals(
      AFTER_FLIP,
      openedDocument(afterFlip, "checkout").catalogPin,
      "the editor is handed the stored pin, so it reports CATALOG_PIN_MISMATCH and refuses export",
    )
  }

  /**
   * The re-pin is written through, at startup, for designs nobody opens.
   *
   * This was the opposite assertion until #819 step 3 came into view. Leaving the rewrite in memory
   * was defensible only while both sources' references stayed computable — `acceptedReferences`
   * keeps the other source's catalog resident, so an old pin still resolves. Retiring a synthesised
   * catalog takes its reference out of the process, and `rePinned` asks `resolve` first: every
   * design not saved since the flip would then be `CATALOG_UNAVAILABLE` with no way back. So the
   * convergence has to have happened before the fallback is removed, and it has to reach designs
   * nobody edits.
   *
   * Read the file directly rather than through a service — whichever source a service serves, it
   * re-pins what it hands out, so only the stored bytes can tell the two apart.
   */
  @Test
  fun `re-pinning writes through at startup`() {
    val root = createTempDirectory("ui-builder-repin")
    create(service(root, served = BEFORE_FLIP), "checkout")

    // Constructed and never asked for the design: the write is the load's own doing.
    val afterFlip = service(root, served = AFTER_FLIP)

    assertEquals(AFTER_FLIP, storedPin(root, "checkout"))
    assertEquals(1, afterFlip.diagnostics().rePinnedDesigns)
    assertEquals(null, afterFlip.diagnostics().rePinPersistenceFailure)
  }

  /** And having converged, it stays converged: the next boot has nothing to write. */
  @Test
  fun `a converged store is re-pinned no further`() {
    val root = createTempDirectory("ui-builder-repin")
    create(service(root, served = BEFORE_FLIP), "checkout")
    service(root, served = AFTER_FLIP)

    val reopened = service(root, served = AFTER_FLIP)

    assertEquals(AFTER_FLIP, storedPin(root, "checkout"))
    assertEquals(
      0,
      reopened.diagnostics().rePinnedDesigns,
      "a count that stays non-zero across restarts is how a failing write shows up",
    )
  }

  /** And the stored file converges on its own, because a write carries the pin it was handed. */
  @Test
  fun `the next save carries the served pin forward`() {
    val root = createTempDirectory("ui-builder-repin")
    create(service(root, served = BEFORE_FLIP), "checkout")

    val afterFlip = service(root, served = AFTER_FLIP)
    apply(afterFlip, "checkout", "op-1", 0, InsertNodeMutationV1(node("node-1"), NodeLocationV1()))

    assertEquals(
      AFTER_FLIP,
      storedPin(root, "checkout"),
      "the saved document still names the source it was authored on",
    )
  }

  /**
   * A store that cannot be written to reports and serves, rather than refusing to start.
   *
   * The rewrite has already been applied in memory by the time the write is attempted, so this
   * process is correct either way; what a failure costs is the convergence, which the next boot
   * retries. Dying here would put back exactly the trap that moving validation off startup removed
   * — one content condition taking down a server that holds a thousand designs — and a read-only or
   * full store is a condition an operator acts on, not one to crash over.
   */
  @Test
  fun `a store that refuses the write is reported, not fatal`() {
    val root = createTempDirectory("ui-builder-repin")
    create(service(root, served = BEFORE_FLIP), "checkout")

    val afterFlip =
      service(
        root,
        served = AFTER_FLIP,
        // Shaped like the store's own message, so the assertions below are about a string that
        // would really leak rather than a harmless one invented for the test.
        store = { UnwritableStore(it, "cannot store UI-builder design checkout under $root") },
      )

    assertEquals(
      AFTER_FLIP,
      openedDocument(afterFlip, "checkout").catalogPin,
      "the in-memory re-pin still holds, so the editor is handed a usable document",
    )
    assertEquals(BEFORE_FLIP, storedPin(root, "checkout"))
    assertEquals(1, afterFlip.diagnostics().rePinnedDesigns)
    // The exception's CLASS reaches diagnostics. Its message must not: `/status.json` is
    // unauthenticated on a `--public` host, this map is owner-free by contract, and the store's
    // real messages name the design and the absolute state path. Asserted rather than promised,
    // because the field is a String and nothing else would stop a later edit widening it.
    assertEquals("IOException", afterFlip.diagnostics().rePinPersistenceFailure)
    assertFalse(
      afterFlip.diagnostics().rePinPersistenceFailure.orEmpty().contains("checkout"),
      "the design id must not travel to an unauthenticated status page",
    )
    assertFalse(
      afterFlip.diagnostics().rePinPersistenceFailure.orEmpty().contains(root.toString()),
      "the state path must not travel to an unauthenticated status page",
    )
  }

  /** Reads like the real store and refuses every batched write. */
  private class UnwritableStore(
    private val delegate: UiBuilderDesignStore,
    private val reason: String,
  ) : UiBuilderDesignStore by delegate {
    override fun commitAll(
      changed: Map<String, Pair<PersistedDesignV1?, PersistedDesignV1>>
    ): Unit = throw IOException(reason)
  }

  /** The pin as the file holds it, read past every service that would re-pin what it hands out. */
  private fun storedPin(root: Path, designId: String): CatalogReferenceV1 =
    checkNotNull(UiBuilderDesignStateStore.open(root).store.load().designs[designId]) {
        "no stored design $designId"
      }
      .document
      .catalogPin

  /**
   * The drift check is untouched: a document the catalog refuses keeps its own pin.
   *
   * This is the clause the whole mechanism rests on. Re-pinning says "only the revision string is
   * stale, the document demonstrably fits" — so a document that does not fit must not be re-pinned,
   * or a design that has drifted would be handed to the editor wearing the served catalog's name.
   */
  @Test
  fun `a document the served catalog refuses is not re-pinned`() {
    val root = createTempDirectory("ui-builder-repin")
    val first = service(root, served = BEFORE_FLIP)
    create(first, "checkout")
    apply(first, "checkout", "op-1", 0, InsertNodeMutationV1(node("node-1"), NodeLocationV1()))

    // The same design against a catalog that no longer draws `m3.Text`: the document is unchanged
    // and now invalid, which is the shape of a real drift.
    val afterFlip = service(root, served = AFTER_FLIP, draws = "m3.Label")

    val response = execute(afterFlip, owner, UiBuilderServiceRequest.OpenDesign("checkout"))
    val refused = assertIs<UiBuilderServiceResponse.Error>(response)
    assertEquals(ServiceErrorCodeV1.INTERNAL, refused.error.code)
    assertEquals(
      BEFORE_FLIP,
      storedPin(root, "checkout"),
      "a document that does not fit is not re-pinned, so there is nothing to write through either",
    )
    assertEquals(0, afterFlip.diagnostics().rePinnedDesigns)
  }

  private fun openedDocument(
    service: PersistentUiBuilderService,
    designId: String,
  ): DesignDocumentV1 =
    assertIs<UiBuilderServiceResponse.Snapshot>(
        execute(service, owner, UiBuilderServiceRequest.OpenDesign(designId))
      )
      .snapshot
      .state
      .document

  private fun service(
    root: Path,
    served: CatalogReferenceV1,
    draws: String = "m3.Text",
    store: (UiBuilderDesignStore) -> UiBuilderDesignStore = { it },
  ): PersistentUiBuilderService =
    PersistentUiBuilderService(
      designStore = UiBuilderDesignStateStore(store(UiBuilderDesignStateStore.open(root).store)),
      catalogs = TwoSourceCatalogs(served, draws),
      exporter = UiBuilderExportExecutor { error("no export in this test") },
      clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
    )

  private fun create(service: PersistentUiBuilderService, designId: String) {
    assertIs<UiBuilderServiceResponse.Snapshot>(
      execute(service, owner, UiBuilderServiceRequest.CreateDesign(document(designId)))
    )
  }

  private fun apply(
    service: PersistentUiBuilderService,
    designId: String,
    operationId: String,
    baseRevision: Long,
    vararg mutations: DesignMutationV1,
  ) {
    val outcome =
      assertIs<UiBuilderServiceResponse.OperationOutcome>(
        execute(
          service,
          owner,
          UiBuilderServiceRequest.ApplyOperation(
            UiBuilderSubmission.Batch(
              designId,
              operationId,
              "browser",
              baseRevision,
              mutations.toList(),
            )
          ),
        )
      )
    assertIs<AcceptedOutcomeV1>(outcome.outcome)
  }

  private fun node(id: String): DesignNodeV1 = DesignNodeV1(id = id, componentId = "m3.Text")

  private fun document(designId: String): DesignDocumentV1 =
    DesignDocumentV1(
      schema = "compose-ui-builder/v1",
      id = designId,
      title = designId,
      revision = 0,
      // Whatever the service is serving when the design is created — which is the point: the
      // stored file is written against the source that was running then.
      catalogPin = BEFORE_FLIP,
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

  private fun execute(
    service: PersistentUiBuilderService,
    actor: AuthenticatedUiBuilderActor,
    request: UiBuilderServiceRequest,
  ): UiBuilderServiceResponse = runSuspend { service.execute(UiBuilderServiceCall(actor, request)) }

  private fun <T> runSuspend(block: suspend () -> T): T {
    var completion: Result<T>? = null
    block.startCoroutine(
      object : Continuation<T> {
        override val context = EmptyCoroutineContext

        override fun resumeWith(result: Result<T>) {
          completion = result
        }
      }
    )
    return checkNotNull(completion) { "suspended without completing" }.getOrThrow()
  }

  /**
   * One catalog, two accepted references, one of them served — the runtime's shape after a flip.
   *
   * @param draws the single component this catalog can draw, so a test can make a stored document
   *   drift from the catalog without touching the document.
   */
  private class TwoSourceCatalogs(
    private val served: CatalogReferenceV1,
    private val draws: String,
  ) : UiBuilderCatalogExecutor {

    private val catalog =
      CatalogCapabilityV1(
        schema = "compose-catalog-capabilities/v1",
        benchmark = CatalogBenchmarkV1("m3", "source", "m3", served.catalogRevision, "m3-runtime"),
        components =
          listOf(
            ComponentCapabilityV1(
              componentId = draws,
              displayName = draws,
              role = "text",
              properties = emptyList(),
              wasm = WasmCapabilityV1(JsonPrimitive(true), WasmAdapterStatusV1.SUPPORTED),
            )
          ),
        exportCapabilities = ExportCapabilitiesV1(composeCode = true, svg = true, png = true),
      )

    override fun listCatalogs(): List<CatalogCapabilityV1> = listOf(catalog)

    override fun resolve(reference: CatalogReferenceV1): CatalogCapabilityV1? = catalog.takeIf {
      reference in setOf(BEFORE_FLIP, AFTER_FLIP)
    }

    override fun reference(catalog: CatalogCapabilityV1): CatalogReferenceV1? = served.takeIf {
      catalog == this.catalog
    }

    override fun validate(
      document: DesignDocumentV1,
      catalog: CatalogCapabilityV1,
    ): UiBuilderCatalogIssue? =
      document.nodes.values
        .firstOrNull { it.componentId != draws }
        ?.let { UiBuilderCatalogIssue("UNKNOWN_COMPONENT", "unknown component", it.id) }
  }

  private companion object {
    /** The synthesised source: its revision is the `candidate` convention. */
    private val BEFORE_FLIP = CatalogReferenceV1("m3", "candidate", "candidate", "m3-runtime")

    /** The published source: a content-hash revision, which is what makes the references differ. */
    private val AFTER_FLIP = CatalogReferenceV1("m3", "sha256:0f0f", "candidate", "m3-runtime")
  }
}
