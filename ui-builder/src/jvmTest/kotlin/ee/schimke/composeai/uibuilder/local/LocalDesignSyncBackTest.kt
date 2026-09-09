package ee.schimke.composeai.uibuilder.local

import ee.schimke.composeai.uibuilder.client.UiBuilderHttpResult
import ee.schimke.composeai.uibuilder.client.canonicalDocumentHash
import ee.schimke.composeai.uibuilder.client.toProtocolDocument
import ee.schimke.composeai.uibuilder.protocol.AcceptedOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.ApplyOperationRequestV1
import ee.schimke.composeai.uibuilder.protocol.CatalogBenchmarkV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.CommandConflictV1
import ee.schimke.composeai.uibuilder.protocol.ConflictCodeV1
import ee.schimke.composeai.uibuilder.protocol.DesignCommandV1
import ee.schimke.composeai.uibuilder.protocol.DesignStateV1
import ee.schimke.composeai.uibuilder.protocol.GetSnapshotRequestV1
import ee.schimke.composeai.uibuilder.protocol.OperationOutcomeResponseV1
import ee.schimke.composeai.uibuilder.protocol.RejectedOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.RejectionCodeV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorV1
import ee.schimke.composeai.uibuilder.protocol.ServiceSnapshotV1
import ee.schimke.composeai.uibuilder.protocol.SnapshotResponseV1
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRequestV1
import ee.schimke.composeai.uibuilder.protocol.UiBuilderResponseV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Bringing an offline session home.
 *
 * The rule under test is the base chain: the first command claims the fork point and every command
 * after it claims the revision its predecessor landed at. That is what keeps a concurrent edit
 * *reportable* rather than silently overwritten, what keeps a command's anchors resolvable, and
 * what makes an interrupted sync resumable by re-running it
 * ([`UI_BUILDER_DESIGN_PORTABILITY.md`](../../docs/design/UI_BUILDER_DESIGN_PORTABILITY.md)).
 */
class LocalDesignSyncBackTest {
  @Test
  fun `a design this browser made has no fork point and is not a merge`() =
    runBlocking<Unit> {
      val record = LocalDesignFixtures.storedRecord(origin = null)

      val result = sync(record) { error("nothing should be asked of the server") }

      assertIs<LocalSyncResult.NotLinked>(result)
    }

  @Test
  fun `the run claims the fork point once and its own committed revisions after that`() =
    runBlocking<Unit> {
      val record =
        LocalDesignFixtures.storedRecord(
          log =
            listOf(
              LocalDesignFixtures.insert("a", baseRevision = 7),
              LocalDesignFixtures.insert("b", baseRevision = 8),
              LocalDesignFixtures.insert("c", baseRevision = 9),
            )
        )
      val server = FakeServer(forkDocument = record.seed)

      val result = sync(record, server::answer)

      val report = assertIs<LocalSyncResult.Replayed>(result).report
      assertTrue(report.complete, report.summary())
      assertEquals(
        listOf(7L, 12L, 13L),
        server.baseRevisions,
        "the first command claims the fork point, and each one after it claims where its " +
          "predecessor landed — never the server's current revision, and never the fork throughout",
      )
      assertEquals(listOf(12L, 13L, 14L), report.landed.map { it.revision })
    }

  @Test
  fun `a stale write that wins is reported rather than silently merged`() =
    runBlocking<Unit> {
      val record =
        LocalDesignFixtures.storedRecord(
          log = listOf(LocalDesignFixtures.insert("a", baseRevision = 7))
        )
      val server =
        FakeServer(
          forkDocument = record.seed,
          conflicts =
            listOf(
              CommandConflictV1(ConflictCodeV1.STALE_PROPERTY_WRITE, "node-1", "text", 11, null)
            ),
        )

      val report = assertIs<LocalSyncResult.Replayed>(sync(record, server::answer)).report

      assertEquals(1, report.conflicts.size)
      assertTrue(report.summary().contains("overwrote a newer edit"), report.summary())
    }

  @Test
  fun `a refused command stops the run and says how much is still here`() =
    runBlocking<Unit> {
      val record =
        LocalDesignFixtures.storedRecord(
          log =
            listOf(
              LocalDesignFixtures.insert("a", baseRevision = 7),
              LocalDesignFixtures.insert("b", baseRevision = 8),
              LocalDesignFixtures.insert("c", baseRevision = 9),
            )
        )
      val server = FakeServer(forkDocument = record.seed, rejectFrom = 1)

      val report = assertIs<LocalSyncResult.Replayed>(sync(record, server::answer)).report

      assertEquals(1, report.landed.size)
      val refusal = checkNotNull(report.refusal)
      assertEquals("REVISION_MISMATCH", refusal.code)
      assertEquals(
        1,
        refusal.remaining,
        "the command after the refused one is still in this browser",
      )
      assertTrue(report.summary().contains("stopped at"), report.summary())
    }

  @Test
  fun `re-running an interrupted sync sends identical commands and is a no-op`() =
    runBlocking<Unit> {
      val record =
        LocalDesignFixtures.storedRecord(
          log =
            listOf(
              LocalDesignFixtures.insert("a", baseRevision = 7),
              LocalDesignFixtures.insert("b", baseRevision = 8),
            )
        )
      val first = FakeServer(forkDocument = record.seed)
      sync(record, first::answer)

      val second = FakeServer(forkDocument = record.seed, idempotent = true)
      val report = assertIs<LocalSyncResult.Replayed>(sync(record, second::answer)).report

      assertEquals(
        first.baseRevisions,
        second.baseRevisions,
        "the bases come from what the server answered, so the re-run is the same run",
      )
      assertTrue(report.landed.all { it.idempotentReplay })
      assertTrue(report.summary().contains("already there"), report.summary())
    }

  @Test
  fun `a fork point the server no longer retains refuses before anything is sent`() =
    runBlocking<Unit> {
      val record =
        LocalDesignFixtures.storedRecord(
          log = listOf(LocalDesignFixtures.insert("a", baseRevision = 7))
        )
      val server = FakeServer(forkDocument = record.seed, forkRetained = false)

      val result = sync(record, server::answer)

      val gone = assertIs<LocalSyncResult.ForkPointGone>(result)
      assertEquals(7, gone.revision)
      assertTrue(server.baseRevisions.isEmpty(), "nothing was sent, so nothing was half-applied")
    }

  @Test
  fun `a fork point that hashes differently is another design's history`() =
    runBlocking<Unit> {
      val record =
        LocalDesignFixtures.storedRecord(
          log = listOf(LocalDesignFixtures.insert("a", baseRevision = 7)),
          origin = LocalDesignFixtures.origin(documentDigest = "sha256:not-this-document"),
        )
      val server = FakeServer(forkDocument = record.seed)

      val result = sync(record, server::answer)

      assertIs<LocalSyncResult.ForkPointDisagrees>(result)
      assertTrue(server.baseRevisions.isEmpty())
    }

  @Test
  fun `a server that cannot be reached mid-run reports what landed before it`() =
    runBlocking<Unit> {
      val record =
        LocalDesignFixtures.storedRecord(
          log =
            listOf(
              LocalDesignFixtures.insert("a", baseRevision = 7),
              LocalDesignFixtures.insert("b", baseRevision = 8),
            )
        )
      val server = FakeServer(forkDocument = record.seed, failFrom = 1)

      val report = assertIs<LocalSyncResult.Replayed>(sync(record, server::answer)).report

      assertEquals(1, report.landed.size)
      assertEquals("UNREACHABLE", checkNotNull(report.refusal).code)
    }

  @Test
  fun `a server that cannot be reached at all sends nothing`() =
    runBlocking<Unit> {
      val record =
        LocalDesignFixtures.storedRecord(
          log = listOf(LocalDesignFixtures.insert("a", baseRevision = 7))
        )

      val result = sync(record) { throw IllegalStateException("offline") }

      assertEquals("offline", assertIs<LocalSyncResult.Unreachable>(result).message)
    }

  private suspend fun sync(
    record: LocalDesignRecordV1,
    execute: suspend (UiBuilderRequestV1) -> UiBuilderHttpResult,
  ): LocalSyncResult =
    LocalDesignSyncBack(actorId = "tester", clientId = "test-client", execute = execute)
      .sync(record)

  /**
   * A server that answers the fork-point question and then commits, refuses or dies.
   *
   * Deliberately not the real service: what is under test is which base each command claims and
   * what the report says, and a real reducer would make those answers depend on the fixture's
   * content.
   */
  private class FakeServer(
    forkDocument: ee.schimke.composeai.uibuilder.UiBuilderDocument,
    private val forkRetained: Boolean = true,
    private val conflicts: List<CommandConflictV1> = emptyList(),
    private val idempotent: Boolean = false,
    private val rejectFrom: Int? = null,
    private val failFrom: Int? = null,
  ) {
    private val protocolDocument = forkDocument.toProtocolDocument()
    private var nextRevision = 12L
    private var applied = 0

    /** The base revision each `applyOperation` claimed, in order. */
    val baseRevisions = mutableListOf<Long>()

    suspend fun answer(request: UiBuilderRequestV1): UiBuilderHttpResult =
      when (request) {
        is GetSnapshotRequestV1 ->
          if (forkRetained) {
            UiBuilderHttpResult.Response(
              "request",
              SnapshotResponseV1(
                ServiceSnapshotV1(
                  designId = protocolDocument.id,
                  state = DesignStateV1(lastSequence = 3, document = protocolDocument),
                  catalog = CATALOG,
                  retainedFromSequence = 0,
                )
              )
                as UiBuilderResponseV1,
            )
          } else {
            UiBuilderHttpResult.SnapshotRequired(
              "request",
              ServiceErrorV1(
                ServiceErrorCodeV1.SNAPSHOT_REQUIRED,
                "revision 7 is no longer retained",
                retainedFromSequence = 900,
              ),
            )
          }
        is ApplyOperationRequestV1 -> {
          val command = request.submission as DesignCommandV1
          val index = applied
          applied += 1
          failFrom?.takeIf { index >= it }?.let { throw IllegalStateException("connection lost") }
          baseRevisions += command.baseRevision
          if (rejectFrom != null && index >= rejectFrom) {
            UiBuilderHttpResult.Response(
              "request",
              OperationOutcomeResponseV1(
                RejectedOutcomeV1(
                  operationId = command.operationId,
                  currentRevision = nextRevision,
                  code = RejectionCodeV1.REVISION_MISMATCH,
                  message = "stale delete/restore requires the current revision",
                )
              ),
            )
          } else {
            val committed = nextRevision
            nextRevision += 1
            UiBuilderHttpResult.Response(
              "request",
              OperationOutcomeResponseV1(
                AcceptedOutcomeV1(
                  operationId = command.operationId,
                  committedRevision = committed,
                  sequence = committed,
                  documentHash = "hash-$committed",
                  idempotentReplay = idempotent,
                  conflicts = conflicts,
                )
              ),
            )
          }
        }
        else -> error("unexpected request ${request::class.simpleName}")
      }
  }
}

private val CATALOG =
  CatalogCapabilityV1(
    schema = "compose-ui-builder-catalog/v1",
    benchmark =
      CatalogBenchmarkV1(
        id = "test",
        sourceRevision = "test",
        catalogSystemId = LocalDesignFixtures.CATALOG_SYSTEM_ID,
        catalogRevision = "test",
        nativeRuntimeId = "test",
      ),
    components = emptyList(),
  )

/** The stored record these tests sync, with a fork point that matches the seed by default. */
internal fun LocalDesignFixtures.storedRecord(
  log: List<LocalSubmissionRecordV1> = emptyList(),
  origin: LocalDesignOriginV1? = origin(),
): LocalDesignRecordV1 =
  LocalDesignRecordV1(
    designId = LocalDesignFixtures.DESIGN_ID,
    catalogSystemId = LocalDesignFixtures.CATALOG_SYSTEM_ID,
    seed = document().copy(revision = 7),
    seedSequence = 3,
    log = log,
    updatedAtEpochMillis = 1_000,
    origin = origin,
  )

internal fun LocalDesignFixtures.origin(
  documentDigest: String =
    document().copy(revision = 7).toProtocolDocument().canonicalDocumentHash()
): LocalDesignOriginV1 =
  LocalDesignOriginV1(
    server = "https://preview.example",
    designId = LocalDesignFixtures.DESIGN_ID,
    revision = 7,
    sequence = 3,
    documentDigest = documentDigest,
    takenAtEpochMillis = 1_000,
  )
