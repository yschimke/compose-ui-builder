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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

/**
 * `DesignCommandV1.stalenessBaseRevision`: a Sync replays an offline run one request per command,
 * each based where its predecessor landed, and tells the service the revision the run started from
 * so every command — not only the first — reports overwriting a concurrent edit (`STALE_*`), and
 * none reports overwriting the run's own earlier writes. See `UI_BUILDER_BRANCHES.md`.
 */
class SyncStalenessBaseTest {
  private val owner = AuthenticatedUiBuilderActor("owner")

  @Test
  fun `every replayed command reports a concurrent edit it overwrites, and the run's own writes are not repeated`() {
    val storage = MemoryStorage()
    var service = service(storage)
    seed(service) // revision 1: title and subtitle; the run forks here.

    // A live edit lands on the server after the run started.
    assertTrue(
      accepted(service, setText("live-title", LIVE, base = 1, "Server")).conflicts.isEmpty()
    )

    val c1 = accepted(service, setText("c1", SYNC, base = 1, "Sub", nodeId = "subtitle"))
    assertTrue(c1.conflicts.isEmpty(), "c1 writes a key nobody else touched")
    assertEquals(3, c1.committedRevision)

    // c2 overwrites the live edit. Its base is c1's revision, which is after the live edit, so the
    // chain base alone would call this clean.
    val c2 = accepted(service, setText("c2", SYNC, base = 3, "Offline", staleness = 1))
    val conflict = c2.conflicts.single()
    assertEquals(ConflictCodeV1.STALE_PROPERTY_WRITE, conflict.code)
    assertEquals("title", conflict.nodeId)
    assertEquals("text", conflict.field)

    // Survives a restart: the run is recognised from what the touch log stored.
    service = service(storage)
    val c3 = accepted(service, setText("c3", SYNC, base = 4, "Offline again", staleness = 1))
    assertTrue(c3.conflicts.isEmpty(), "c3 overwrites c2, which the author wrote: ${c3.conflicts}")

    // A concurrent edit that lands in the middle of the run is newer than the run's own write, and
    // the next command that overwrites it says so.
    accepted(service, setText("live-again", LIVE, base = 5, "Server again"))
    val c4 = accepted(service, setText("c4", SYNC, base = 5, "Offline last", staleness = 1))
    assertEquals(listOf("title"), c4.conflicts.map { it.nodeId })
  }

  @Test
  fun `the same run without the field reports only the first command's conflict, as before`() {
    val service = service(MemoryStorage())
    seed(service)
    accepted(service, setText("live-title", LIVE, base = 1, "Server"))
    accepted(service, setText("c1", SYNC, base = 1, "Sub", nodeId = "subtitle"))

    val c2 = accepted(service, setText("c2", SYNC, base = 3, "Offline"))

    assertTrue(
      c2.conflicts.isEmpty(),
      "the chain base hides the live edit: the bug the field fixes",
    )
  }

  @Test
  fun `a live edit is judged against its own base exactly as before`() {
    val service = service(MemoryStorage())
    seed(service)
    // The same client writes twice from revision 1. Without the field this is two live edits, and
    // the second is told it overwrote a write it never saw — even though its own client made it.
    accepted(service, setText("first", SYNC, base = 1, "One"))
    val second = accepted(service, setText("second", SYNC, base = 1, "Two"))
    assertEquals(listOf("title"), second.conflicts.map { it.nodeId })
    assertEquals(2L, second.conflicts.single().overwrittenRevision)

    val current = accepted(service, setText("current", SYNC, base = 3, "Three"))
    assertTrue(current.conflicts.isEmpty())
  }

  @Test
  fun `a run is one actor's one client from one starting revision`() {
    val service = service(MemoryStorage())
    seed(service)
    accepted(service, setText("c1", SYNC, base = 1, "Run"))

    // Another client of the same actor, claiming the same start, did not write c1.
    val other = accepted(service, setText("other", "other-client", base = 2, "X", staleness = 1))
    assertEquals(listOf("title"), other.conflicts.map { it.nodeId })
  }

  @Test
  fun `the same client from another starting revision is another run`() {
    val service = service(MemoryStorage())
    seed(service)
    accepted(service, setText("c1", SYNC, base = 1, "Run"))
    accepted(service, setText("filler", LIVE, base = 2, "Sub", nodeId = "subtitle"))

    // c1 started at revision 1; a command claiming to have started at 0 is not of c1's run.
    val later = accepted(service, setText("later", SYNC, base = 3, "Y", staleness = 0))

    assertEquals(listOf("title"), later.conflicts.map { it.nodeId })
  }

  @Test
  fun `a staleness base at or past the base, or below zero, is read as absent`() {
    listOf(3L, 7L, -1L).forEach { claimed ->
      val service = service(MemoryStorage())
      seed(service)
      accepted(service, setText("live", LIVE, base = 1, "Server"))
      accepted(service, setText("c1", SYNC, base = 1, "Sub", nodeId = "subtitle"))
      val outcome = accepted(service, setText("c2", SYNC, base = 3, "Offline", staleness = claimed))
      assertTrue(outcome.conflicts.isEmpty(), "staleness $claimed: ${outcome.conflicts}")
    }
  }

  @Test
  fun `a run that outlives its start's retention reads the touches still kept, and is not refused`() {
    val service =
      service(
        MemoryStorage(),
        UiBuilderServiceLimits(retainedRevisionSnapshots = 4, minimumRetainedRevisionSnapshots = 4),
      )
    seed(service)
    repeat(4) {
      accepted(service, setText("filler-$it", LIVE, base = 2L + it - 1, "$it", "subtitle"))
    }
    accepted(service, setText("live", LIVE, base = 5, "Server"))
    assertEquals(
      RejectionCodeV1.REVISION_NOT_RETAINED,
      rejected(service, setText("probe", SYNC, base = 1, "probe")).code,
      "revision 1 has left the window",
    )

    val outcome = accepted(service, setText("c9", SYNC, base = 6, "Offline", staleness = 1))

    assertEquals(listOf("title"), outcome.conflicts.map { it.nodeId })
  }

  @Test
  fun `a touch record written before run identity existed still loads and belongs to no run`() {
    val json = PersistentUiBuilderServiceJson.json
    val old =
      json.decodeFromString(
        ConflictTouchRecordV1.serializer(),
        """{"committedRevision":4,"keys":["p\u0000title\u0000text"]}""",
      )
    assertNull(old.clientId)
    assertFalse(ReplayRunIdentity("owner", SYNC, 1).wrote(old))

    val unattributed = ConflictTouchRecordV1(4, setOf("k"))
    assertEquals(
      """{"committedRevision":4,"keys":["k"]}""",
      json.encodeToString(ConflictTouchRecordV1.serializer(), unattributed),
      "an undo's record keeps the bytes it always had",
    )
  }

  @Test
  fun `a command without the field fingerprints to the bytes it did before the field existed`() {
    val json = PersistentUiBuilderServiceJson.json
    val command = DesignCommandV1.Builder("design", "op", "owner", SYNC, 3, emptyList())
    val unset = submissionFingerprint(json, command.build())
    assertFalse("stalenessBaseRevision" in unset, unset)
    assertEquals(
      """{"actorId":"owner","baseRevision":3,"clientId":"$SYNC","designId":"design",""" +
        """"operationId":"op","operations":[],"type":"batch"}""",
      unset,
    )
    val set = submissionFingerprint(json, command.also { it.stalenessBaseRevision = 1 }.build())
    assertTrue(""""stalenessBaseRevision":1""" in set, set)
  }

  private fun seed(service: PersistentUiBuilderService) {
    assertIs<UiBuilderServiceResponse.Snapshot>(
      execute(service, UiBuilderServiceRequest.CreateDesign(designDocument()))
    )
    accepted(
      service,
      UiBuilderSubmission.Batch(
        "design",
        "seed",
        LIVE,
        0,
        listOf(
          InsertNodeMutationV1(text("title"), inRoot),
          InsertNodeMutationV1(text("subtitle"), NodeLocationV1(ParentSlotV1("root", "content"))),
        ),
      ),
    )
  }

  private fun setText(
    operationId: String,
    clientId: String,
    base: Long,
    value: String,
    nodeId: String = "title",
    staleness: Long? = null,
  ): UiBuilderSubmission.Batch =
    UiBuilderSubmission.Batch(
      "design",
      operationId,
      clientId,
      base,
      listOf(SetPropertyMutationV1(nodeId, "text", StringValueV1(value))),
      staleness,
    )

  private fun accepted(
    service: PersistentUiBuilderService,
    submission: UiBuilderSubmission,
  ): AcceptedOutcomeV1 {
    val outcome =
      assertIs<UiBuilderServiceResponse.OperationOutcome>(
          execute(service, UiBuilderServiceRequest.ApplyOperation(submission))
        )
        .outcome
    return assertIs<AcceptedOutcomeV1>(outcome, "${submission.operationId}: $outcome")
  }

  private fun rejected(
    service: PersistentUiBuilderService,
    submission: UiBuilderSubmission,
  ): RejectedOutcomeV1 =
    assertIs<RejectedOutcomeV1>(
      assertIs<UiBuilderServiceResponse.OperationOutcome>(
          execute(service, UiBuilderServiceRequest.ApplyOperation(submission))
        )
        .outcome
    )

  private fun service(
    storage: UiBuilderStateStorage,
    limits: UiBuilderServiceLimits = UiBuilderServiceLimits(),
  ): PersistentUiBuilderService =
    PersistentUiBuilderService(
      storage = storage,
      catalogs = TestCatalogs,
      exporter = UiBuilderExportExecutor { error("no export in this test") },
      clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
      limits = limits,
    )

  private fun execute(
    service: PersistentUiBuilderService,
    request: UiBuilderServiceRequest,
  ): UiBuilderServiceResponse {
    var completion: Result<UiBuilderServiceResponse>? = null
    val block: suspend () -> UiBuilderServiceResponse = {
      service.execute(UiBuilderServiceCall(owner, request))
    }
    block.startCoroutine(
      object : Continuation<UiBuilderServiceResponse> {
        override val context = EmptyCoroutineContext

        override fun resumeWith(result: Result<UiBuilderServiceResponse>) {
          completion = result
        }
      }
    )
    return checkNotNull(completion) { "suspended without completing" }.getOrThrow()
  }

  private fun text(id: String): DesignNodeV1 = DesignNodeV1(id = id, componentId = "m3.Text")

  private val inRoot = NodeLocationV1(ParentSlotV1("root", "content"))

  private fun designDocument(): DesignDocumentV1 =
    DesignDocumentV1(
      schema = "compose-ui-builder/v1",
      id = "design",
      title = "Sync",
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
      roots = listOf("root"),
      nodes = mapOf("root" to text("root").copy(slots = mapOf("content" to emptyList()))),
    )

  private class MemoryStorage : UiBuilderStateStorage {
    private var bytes: ByteArray? = null

    override fun load(): ByteArray? = bytes?.copyOf()

    override fun replace(value: ByteArray) {
      bytes = value.copyOf()
    }
  }

  private object TestCatalogs : UiBuilderCatalogExecutor {
    override fun listCatalogs(): List<CatalogCapabilityV1> = listOf(CATALOG)

    override fun resolve(reference: CatalogReferenceV1): CatalogCapabilityV1? = CATALOG.takeIf {
      reference == CATALOG_REFERENCE
    }

    override fun reference(catalog: CatalogCapabilityV1): CatalogReferenceV1? =
      CATALOG_REFERENCE.takeIf {
        catalog == CATALOG
      }

    override fun validate(
      document: DesignDocumentV1,
      catalog: CatalogCapabilityV1,
    ): UiBuilderCatalogIssue? =
      document.nodes.values
        .firstOrNull { it.componentId != "m3.Text" }
        ?.let { UiBuilderCatalogIssue("UNKNOWN_COMPONENT", "unknown component", it.id) }
  }

  private companion object {
    const val SYNC = "sync-client"
    const val LIVE = "live-client"
    val CATALOG_REFERENCE = CatalogReferenceV1("m3", "catalog", "digest", "m3-runtime")
    val CATALOG =
      CatalogCapabilityV1.Builder(
          "compose-catalog-capabilities/v1",
          CatalogBenchmarkV1.Builder("m3", "source", "m3", "catalog", "m3-runtime").build(),
          listOf(
            ComponentCapabilityV1.Builder(
                "m3.Text",
                "Text",
                "text",
                WasmCapabilityV1.Builder(JsonPrimitive(true), WasmAdapterStatusV1.SUPPORTED)
                  .build(),
              )
              .also {
                it.properties =
                  listOf(
                    PropertyCapabilityV1.Builder("text", JsonPrimitive("string"))
                      .also { it.required = false }
                      .build()
                  )
              }
              .build()
          ),
        )
        .build()
  }
}
