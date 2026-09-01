package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.*
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.io.TempDir

class PersistentUiBuilderServiceTest {
  @TempDir lateinit var temporaryDirectory: Path

  private val owner = AuthenticatedUiBuilderActor("owner")
  private val viewer = AuthenticatedUiBuilderActor("viewer")
  private val outsider = AuthenticatedUiBuilderActor("outsider")

  @Test
  fun `private designs enforce access CAS and every request variant`() {
    val exports = mutableListOf<RevisionPinnedUiBuilderExport>()
    val service = service(exportRequests = exports)

    assertIs<UiBuilderServiceResponse.Catalogs>(
      execute(service, owner, UiBuilderServiceRequest.ListCatalogs)
    )
    assertIs<UiBuilderServiceResponse.Snapshot>(
      execute(service, owner, UiBuilderServiceRequest.CreateDesign(document()))
    )
    assertEquals(
      emptyList(),
      assertIs<UiBuilderServiceResponse.Designs>(
          execute(service, outsider, UiBuilderServiceRequest.ListDesigns(null, 50))
        )
        .designs,
    )
    assertEquals(
      ServiceErrorCodeV1.FORBIDDEN,
      error(execute(service, outsider, UiBuilderServiceRequest.OpenDesign("design"))).code,
    )
    assertEquals(
      ServiceErrorCodeV1.FORBIDDEN,
      error(execute(service, viewer, UiBuilderServiceRequest.GetDesignAccess("design"))).code,
    )

    val access =
      assertIs<UiBuilderServiceResponse.DesignAccess>(
        execute(
          service,
          owner,
          UiBuilderServiceRequest.UpdateDesignAccess(
            designId = "design",
            baseAccessRevision = 0,
            mutations =
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
    assertEquals(1, access.access.accessRevision)
    assertIs<UiBuilderServiceResponse.Snapshot>(
      execute(service, viewer, UiBuilderServiceRequest.OpenDesign("design"))
    )
    assertEquals(
      ServiceErrorCodeV1.FORBIDDEN,
      error(
          execute(
            service,
            viewer,
            UiBuilderServiceRequest.ApplyOperation(
              batch("viewer-write", 0, InsertNodeMutationV1(textNode("denied"), NodeLocationV1()))
            ),
          )
        )
        .code,
    )
    assertEquals(
      ServiceErrorCodeV1.FORBIDDEN,
      error(
          execute(
            service,
            viewer,
            UiBuilderServiceRequest.ExportDesign("design", null, ExportFormatV1.SVG),
          )
        )
        .code,
    )

    assertEquals(
      ServiceErrorCodeV1.ACCESS_REVISION_MISMATCH,
      error(
          execute(
            service,
            owner,
            UiBuilderServiceRequest.UpdateDesignAccess(
              "design",
              0,
              listOf(RevokeActorAccessMutationV1(viewer.actorId)),
            ),
          )
        )
        .code,
    )
    assertEquals(
      ServiceErrorCodeV1.BAD_REQUEST,
      error(
          execute(
            service,
            owner,
            UiBuilderServiceRequest.UpdateDesignAccess(
              "design",
              1,
              listOf(
                CreateDesignShareLinkMutationV1(
                  DesignAccessRoleV1.VIEWER,
                  listOf(DesignAccessActionV1.READ),
                )
              ),
            ),
          )
        )
        .code,
    )

    val accepted =
      accepted(
        execute(
          service,
          owner,
          UiBuilderServiceRequest.ApplyOperation(
            batch("insert", 0, InsertNodeMutationV1(textNode("node"), NodeLocationV1()))
          ),
        )
      )
    assertEquals(1, accepted.sequence)
    assertIs<UiBuilderServiceResponse.Snapshot>(
      execute(service, owner, UiBuilderServiceRequest.GetSnapshot("design", 1))
    )
    assertEquals(
      listOf("insert"),
      assertIs<UiBuilderServiceResponse.Delta>(
          execute(service, owner, UiBuilderServiceRequest.GetDelta("design", 0, 50))
        )
        .delta
        .operations
        .map { it.outcome.operationId },
    )
    assertIs<UiBuilderServiceResponse.PresenceAccepted>(
      execute(
        service,
        viewer,
        UiBuilderServiceRequest.UpdatePresence(
          "design",
          UiBuilderPresence(
            "browser",
            "Viewer",
            "#FF112233",
            listOf("node"),
            10.0,
            20.0,
            1,
          ),
        ),
      )
    )

    val ownerAccess =
      assertIs<UiBuilderServiceResponse.DesignAccess>(
        execute(service, owner, UiBuilderServiceRequest.GetDesignAccess("design"))
      )
    assertEquals("owner", ownerAccess.access.ownerActorId)
    assertEquals(
      listOf("design"),
      assertIs<UiBuilderServiceResponse.Designs>(
          execute(service, owner, UiBuilderServiceRequest.ListDesigns(null, 50))
        )
        .designs
        .map { it.designId },
    )

    grant(
      service,
      owner,
      viewer,
      baseRevision = 1,
      actions = listOf(DesignAccessActionV1.READ, DesignAccessActionV1.EXPORT),
    )
    val exported =
      assertIs<UiBuilderServiceResponse.Export>(
        execute(
          service,
          viewer,
          UiBuilderServiceRequest.ExportDesign("design", 1, ExportFormatV1.SVG),
        )
      )
    assertEquals("<svg data-revision=\"1\"/>", exported.artifact.content)
    assertEquals(viewer, exports.single().actor)
    assertEquals(1, exports.single().revision)
  }

  @Test
  fun `retry rejection presence and compaction preserve the durable cursor`() {
    val service = service(retained = 2)
    create(service)

    val firstRequest =
      UiBuilderServiceRequest.ApplyOperation(
        batch("one", 0, InsertNodeMutationV1(textNode("one"), NodeLocationV1()))
      )
    val first = accepted(execute(service, owner, firstRequest))
    val retry = accepted(execute(service, owner, firstRequest))
    assertEquals(1, first.sequence)
    assertEquals(1, retry.sequence)
    assertTrue(retry.idempotentReplay)

    val rejectedRequest =
      UiBuilderServiceRequest.ApplyOperation(
        batch("bad", 1, SetPropertyMutationV1("missing", "text", StringValueV1("bad")))
      )
    val rejected = rejected(execute(service, owner, rejectedRequest))
    val rejectedRetry = rejected(execute(service, owner, rejectedRequest))
    assertEquals(rejected, rejectedRetry)
    assertEquals(1, rejected.currentRevision)

    execute(
      service,
      owner,
      UiBuilderServiceRequest.UpdatePresence(
        "design",
        UiBuilderPresence("browser", "Owner", "#FF000000", listOf("one"), null, null, 1),
      ),
    )
    assertEquals(
      1,
      snapshot(execute(service, owner, UiBuilderServiceRequest.OpenDesign("design")))
        .state
        .lastSequence,
    )

    accepted(
      execute(
        service,
        owner,
        UiBuilderServiceRequest.ApplyOperation(
          batch(
            "two",
            1,
            InsertNodeMutationV1(textNode("two"), NodeLocationV1(afterNodeId = "one")),
          )
        ),
      )
    )
    accepted(
      execute(
        service,
        owner,
        UiBuilderServiceRequest.ApplyOperation(
          batch(
            "three",
            2,
            InsertNodeMutationV1(textNode("three"), NodeLocationV1(afterNodeId = "two")),
          )
        ),
      )
    )
    val compacted = execute(service, owner, UiBuilderServiceRequest.GetDelta("design", 0, 50))
    assertEquals(ServiceErrorCodeV1.SNAPSHOT_REQUIRED, error(compacted).code)

    val updates = mutableListOf<UiBuilderServiceUpdate>()
    service.subscribe(UiBuilderSubscriptionCall(owner, "design", 0), updates::add).close()
    assertIs<UiBuilderServiceUpdate.Snapshot>(updates.single())
  }

  @Test
  fun `presence expires without persistence or durable sequence advancement`() {
    val mutableClock = MutableClock(1_000)
    val service =
      PersistentUiBuilderService(
        storage = MemoryStorage(),
        catalogs = TestCatalogs,
        exporter = validExporter(),
        clock = mutableClock,
        limits = UiBuilderServiceLimits(presenceTtlMillis = 30_000),
      )
    create(service)
    grant(
      service,
      owner,
      viewer,
      baseRevision = 0,
      actions = listOf(DesignAccessActionV1.READ),
    )
    val updates = mutableListOf<UiBuilderServiceUpdate>()
    val subscription =
      service.subscribe(UiBuilderSubscriptionCall(owner, "design", 0), updates::add)
    execute(
      service,
      owner,
      UiBuilderServiceRequest.UpdatePresence(
        "design",
        UiBuilderPresence("owner-browser", "Owner", "#FF000000", emptyList(), null, null, 0),
      ),
    )

    mutableClock.epochMillis += 30_000
    execute(
      service,
      viewer,
      UiBuilderServiceRequest.UpdatePresence(
        "design",
        UiBuilderPresence("viewer-browser", "Viewer", "#FFFFFFFF", emptyList(), null, null, 0),
      ),
    )

    val presence = updates.filterIsInstance<UiBuilderServiceUpdate.Presence>().map { it.update }
    assertIs<PresenceUpsertV1>(presence[0])
    assertEquals(owner.actorId, assertIs<PresenceLeaveV1>(presence[1]).actorId)
    assertIs<PresenceUpsertV1>(presence[2])
    val snapshot = snapshot(execute(service, owner, UiBuilderServiceRequest.OpenDesign("design")))
    assertEquals(listOf(viewer.actorId), snapshot.presence.map { it.actorId })
    assertEquals(0, snapshot.state.lastSequence)
    subscription.close()
  }

  @Test
  fun `atomic mixed batches and compensating undo redo never overwrite later work`() {
    val service = service()
    create(service)

    val failed =
      rejected(
        execute(
          service,
          owner,
          UiBuilderServiceRequest.ApplyOperation(
            UiBuilderSubmission.Batch(
              "design",
              "atomic-fail",
              "browser",
              0,
              listOf(
                InsertNodeMutationV1(textNode("temporary"), NodeLocationV1()),
                SetPropertyMutationV1("missing", "text", StringValueV1("bad")),
              ),
            )
          ),
        )
      )
    assertEquals(RejectionCodeV1.UNKNOWN_NODE, failed.code)
    assertFalse(
      "temporary" in
        snapshot(execute(service, owner, UiBuilderServiceRequest.OpenDesign("design")))
          .state
          .document
          .nodes
    )

    accepted(
      execute(
        service,
        owner,
        UiBuilderServiceRequest.ApplyOperation(
          batch("insert", 0, InsertNodeMutationV1(textNode("node"), NodeLocationV1()))
        ),
      )
    )
    accepted(
      execute(
        service,
        owner,
        UiBuilderServiceRequest.ApplyOperation(
          batch("set", 1, SetPropertyMutationV1("node", "text", StringValueV1("First")))
        ),
      )
    )
    accepted(
      execute(
        service,
        owner,
        UiBuilderServiceRequest.ApplyOperation(
          UiBuilderSubmission.Undo("design", "undo-set", "browser", 2, "set")
        ),
      )
    )
    assertNull(currentNode(service, "node").properties["text"])
    accepted(
      execute(
        service,
        owner,
        UiBuilderServiceRequest.ApplyOperation(
          UiBuilderSubmission.Redo("design", "redo-set", "browser", 3, "undo-set")
        ),
      )
    )
    assertEquals(StringValueV1("First"), currentNode(service, "node").properties["text"])

    accepted(
      execute(
        service,
        owner,
        UiBuilderServiceRequest.ApplyOperation(
          batch("later", 4, SetPropertyMutationV1("node", "text", StringValueV1("Later")))
        ),
      )
    )
    val unsafe =
      rejected(
        execute(
          service,
          owner,
          UiBuilderServiceRequest.ApplyOperation(
            UiBuilderSubmission.Undo("design", "undo-set-again", "browser", 5, "set")
          ),
        )
      )
    assertEquals(RejectionCodeV1.UNSAFE_COMPENSATION, unsafe.code)
    assertEquals(StringValueV1("Later"), currentNode(service, "node").properties["text"])

    accepted(
      execute(
        service,
        owner,
        UiBuilderServiceRequest.ApplyOperation(batch("delete", 5, DeleteNodeMutationV1("node"))),
      )
    )
    assertFalse("node" in currentDocument(service).nodes)
    accepted(
      execute(
        service,
        owner,
        UiBuilderServiceRequest.ApplyOperation(batch("restore", 6, RestoreNodeMutationV1("node"))),
      )
    )
    assertTrue("node" in currentDocument(service).nodes)
  }

  @Test
  fun `stale concurrent inserts at the same anchors converge independent of arrival order`() {
    val firstOrder = service()
    val secondOrder = service()
    create(firstOrder)
    create(secondOrder)
    val alpha =
      UiBuilderServiceRequest.ApplyOperation(
        batch("client-a-insert", 0, InsertNodeMutationV1(textNode("alpha"), NodeLocationV1()))
      )
    val beta =
      UiBuilderServiceRequest.ApplyOperation(
        batch("client-b-insert", 0, InsertNodeMutationV1(textNode("beta"), NodeLocationV1()))
      )

    accepted(execute(firstOrder, owner, alpha))
    val firstFinal = accepted(execute(firstOrder, owner, beta))
    accepted(execute(secondOrder, owner, beta))
    val secondFinal = accepted(execute(secondOrder, owner, alpha))

    assertEquals(currentDocument(firstOrder), currentDocument(secondOrder))
    assertEquals(firstFinal.documentHash, secondFinal.documentHash)
    assertEquals(listOf("alpha", "beta"), currentDocument(firstOrder).roots)
    assertTrue(accepted(execute(firstOrder, owner, alpha)).idempotentReplay)
    assertEquals(currentDocument(firstOrder), currentDocument(secondOrder))
  }

  @Test
  fun `captured catch up is delivered before concurrent live commit without holding service lock`() {
    val service = service()
    create(service)
    accepted(
      execute(
        service,
        owner,
        UiBuilderServiceRequest.ApplyOperation(
          batch("one", 0, InsertNodeMutationV1(textNode("one"), NodeLocationV1()))
        ),
      )
    )

    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val subscribed = CountDownLatch(1)
    val delivered = mutableListOf<Long>()
    val activeCallbacks = AtomicInteger()
    val maximumCallbacks = AtomicInteger()
    var subscription: Closeable? = null
    val subscriberThread = thread {
      subscription =
        service.subscribe(UiBuilderSubscriptionCall(owner, "design", 0)) { update ->
          val active = activeCallbacks.incrementAndGet()
          maximumCallbacks.updateAndGet { maxOf(it, active) }
          try {
            delivered += update.sequence()
            if (delivered.size == 1) {
              entered.countDown()
              assertTrue(release.await(5, TimeUnit.SECONDS))
            }
          } finally {
            activeCallbacks.decrementAndGet()
          }
        }
      subscribed.countDown()
    }
    assertTrue(entered.await(5, TimeUnit.SECONDS))

    val committed = CountDownLatch(1)
    thread {
      accepted(
        execute(
          service,
          owner,
          UiBuilderServiceRequest.ApplyOperation(
            batch(
              "two",
              1,
              InsertNodeMutationV1(textNode("two"), NodeLocationV1(afterNodeId = "one")),
            )
          ),
        )
      )
      committed.countDown()
    }
    assertTrue(committed.await(5, TimeUnit.SECONDS), "commit must not wait for subscriber callback")
    assertEquals(listOf(1L), delivered)
    release.countDown()
    assertTrue(subscribed.await(5, TimeUnit.SECONDS))
    subscriberThread.join(5_000)
    assertEquals(listOf(1L, 2L), delivered)
    assertEquals(1, maximumCallbacks.get())
    subscription?.close()
  }

  @Test
  fun `oversized batches reject before reduction without advancing durable state`() {
    val service = service(limits = UiBuilderServiceLimits(maximumOperationsPerBatch = 1))
    create(service)
    val outcome =
      assertIs<RejectedOutcomeV1>(
        assertIs<UiBuilderServiceResponse.OperationOutcome>(
            execute(
              service,
              owner,
              UiBuilderServiceRequest.ApplyOperation(
                batch(
                  "too-many",
                  0,
                  InsertNodeMutationV1(textNode("one"), NodeLocationV1()),
                  InsertNodeMutationV1(textNode("two"), NodeLocationV1()),
                )
              ),
            )
          )
          .outcome
      )
    assertEquals(RejectionCodeV1.INVALID_COMMAND, outcome.code)
    assertEquals(0, currentDocument(service).revision)
    assertEquals(1, service.diagnostics().rejectedBatchLimit)
  }

  @Test
  fun `subscriber and presence limits reject without durable sequence changes`() {
    val service =
      service(
        limits =
          UiBuilderServiceLimits(
            maximumSubscribers = 1,
            maximumSubscribersPerDesign = 2,
            maximumPresenceSelections = 1,
          )
      )
    create(service)
    accepted(
      execute(
        service,
        owner,
        UiBuilderServiceRequest.ApplyOperation(
          batch("one", 0, InsertNodeMutationV1(textNode("one"), NodeLocationV1()))
        ),
      )
    )
    val first = service.subscribe(UiBuilderSubscriptionCall(owner, "design", null)) {}
    val rejected =
      assertFailsWith<UiBuilderSubscriptionRejectedException> {
        service.subscribe(UiBuilderSubscriptionCall(owner, "design", null)) {}
      }
    assertEquals(ServiceErrorCodeV1.BAD_REQUEST, rejected.error.code)
    assertEquals(
      ServiceErrorCodeV1.BAD_REQUEST,
      error(
          execute(
            service,
            owner,
            UiBuilderServiceRequest.UpdatePresence(
              "design",
              UiBuilderPresence(
                "browser",
                "Owner",
                "#FF000000",
                listOf("one", "one"),
                null,
                null,
                1,
              ),
            ),
          )
        )
        .code,
    )
    val diagnostics = service.diagnostics()
    assertEquals(1, diagnostics.activeSubscribers)
    assertEquals(1, diagnostics.peakSubscribers)
    assertEquals(1, diagnostics.rejectedSubscriberLimit)
    assertEquals(1, diagnostics.rejectedPresenceLimit)
    assertEquals(
      1,
      snapshot(execute(service, owner, UiBuilderServiceRequest.OpenDesign("design")))
        .state
        .lastSequence,
    )
    first.close()
    assertEquals(0, service.diagnostics().activeSubscribers)
  }

  @Test
  fun `slow subscriber is closed at its mailbox bound without affecting durable commits`() {
    val service = service(limits = UiBuilderServiceLimits(subscriberQueueCapacity = 1))
    create(service)
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val subscriberThread = thread {
      service.subscribe(UiBuilderSubscriptionCall(owner, "design", null)) {
        entered.countDown()
        assertTrue(release.await(5, TimeUnit.SECONDS))
      }
    }
    assertTrue(entered.await(5, TimeUnit.SECONDS))

    accepted(
      execute(
        service,
        owner,
        UiBuilderServiceRequest.ApplyOperation(
          batch("one", 0, InsertNodeMutationV1(textNode("one"), NodeLocationV1()))
        ),
      )
    )
    accepted(
      execute(
        service,
        owner,
        UiBuilderServiceRequest.ApplyOperation(
          batch(
            "two",
            1,
            InsertNodeMutationV1(textNode("two"), NodeLocationV1(afterNodeId = "one")),
          )
        ),
      )
    )

    assertEquals(2, currentDocument(service).revision)
    assertEquals(
      2,
      snapshot(execute(service, owner, UiBuilderServiceRequest.OpenDesign("design")))
        .state
        .lastSequence,
    )
    assertEquals(0, service.diagnostics().activeSubscribers)
    assertEquals(1, service.diagnostics().slowSubscribersClosed)
    release.countDown()
    subscriberThread.join(5_000)
    assertFalse(subscriberThread.isAlive)
  }

  @Test
  fun `concurrent exports reject before audit or exporter work and expose aggregate pressure`() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val exporter = UiBuilderExportExecutor { request ->
      entered.countDown()
      assertTrue(release.await(5, TimeUnit.SECONDS))
      validExporter().export(request)
    }
    val service =
      service(
        limits = UiBuilderServiceLimits(maximumConcurrentExports = 1),
        exporter = exporter,
      )
    create(service)
    val completed = CountDownLatch(1)
    thread {
      assertIs<UiBuilderServiceResponse.Export>(
        execute(
          service,
          owner,
          UiBuilderServiceRequest.ExportDesign("design", 0, ExportFormatV1.SVG),
        )
      )
      completed.countDown()
    }
    assertTrue(entered.await(5, TimeUnit.SECONDS))
    val rejected =
      error(
        execute(
          service,
          owner,
          UiBuilderServiceRequest.ExportDesign("design", 0, ExportFormatV1.SVG),
        )
      )
    assertEquals(ServiceErrorCodeV1.BAD_REQUEST, rejected.code)
    assertTrue(rejected.retryable)
    assertEquals(1, service.diagnostics().activeExports)
    assertEquals(1, service.diagnostics().peakExports)
    assertEquals(1, service.diagnostics().rejectedExportLimit)
    release.countDown()
    assertTrue(completed.await(5, TimeUnit.SECONDS))
    assertEquals(0, service.diagnostics().activeExports)
  }

  @Test
  fun `mutation token bucket is isolated by actor and design and refills from injected clock`() {
    val clock = MutableClock(1_000)
    val service =
      service(
        clock = clock,
        limits =
          UiBuilderServiceLimits(
            mutationBurstCapacity = 1,
            mutationRefillAmount = 1,
            mutationRefillIntervalMillis = 1_000,
          ),
      )
    create(service)
    accepted(
      execute(
        service,
        owner,
        UiBuilderServiceRequest.ApplyOperation(
          batch("one", 0, InsertNodeMutationV1(textNode("one"), NodeLocationV1()))
        ),
      )
    )

    val rejected =
      assertIs<RejectedOutcomeV1>(
        assertIs<UiBuilderServiceResponse.OperationOutcome>(
            execute(
              service,
              owner,
              UiBuilderServiceRequest.ApplyOperation(
                batch("two", 1, InsertNodeMutationV1(textNode("two"), NodeLocationV1()))
              ),
            )
          )
          .outcome
      )
    assertEquals(RejectionCodeV1.INVALID_COMMAND, rejected.code)
    assertEquals(1, currentDocument(service).revision)
    assertEquals(1, service.diagnostics().rejectedMutationRate)

    clock.nowMillis += 1_000
    accepted(
      execute(
        service,
        owner,
        UiBuilderServiceRequest.ApplyOperation(
          batch("three", 1, InsertNodeMutationV1(textNode("three"), NodeLocationV1()))
        ),
      )
    )
    assertEquals(2, currentDocument(service).revision)
  }

  @Test
  fun `document and embedded asset byte limits reject before persistence`() {
    val documentLimited =
      service(limits = UiBuilderServiceLimits(maximumSerializedDocumentBytes = 1_000))
    create(documentLimited)
    accepted(
      execute(
        documentLimited,
        owner,
        UiBuilderServiceRequest.ApplyOperation(
          batch("insert", 0, InsertNodeMutationV1(textNode("node"), NodeLocationV1()))
        ),
      )
    )
    val oversized =
      assertIs<RejectedOutcomeV1>(
        assertIs<UiBuilderServiceResponse.OperationOutcome>(
            execute(
              documentLimited,
              owner,
              UiBuilderServiceRequest.ApplyOperation(
                batch(
                  "oversized",
                  1,
                  SetPropertyMutationV1("node", "text", StringValueV1("x".repeat(2_000))),
                )
              ),
            )
          )
          .outcome
      )
    assertContains(oversized.message, "serialized document byte limit")
    assertEquals(1, currentDocument(documentLimited).revision)
    assertEquals(1, documentLimited.diagnostics().rejectedDocumentBytes)

    val assetLimited = service(limits = UiBuilderServiceLimits(maximumEmbeddedAssetBytes = 3))
    val response =
      execute(
        assetLimited,
        owner,
        UiBuilderServiceRequest.CreateDesign(
          document()
            .copy(
              assets =
                mapOf(
                  "large" to
                    AssetBindingV1(
                      mediaType = "image/png",
                      contentDigest = "sha256:unused",
                      source = EmbeddedAssetSourceV1("AAAAAAAA"),
                    )
                )
            )
        ),
      )
    assertEquals(ServiceErrorCodeV1.BAD_REQUEST, error(response).code)
    assertEquals(1, assetLimited.diagnostics().rejectedAssetBytes)
    assertEquals(
      ServiceErrorCodeV1.NOT_FOUND,
      error(execute(assetLimited, owner, UiBuilderServiceRequest.OpenDesign("design"))).code,
    )
  }

  @Test
  fun `timed out exporter is interrupted and cannot write a success audit`() {
    val storage = MemoryStorage()
    val entered = CountDownLatch(1)
    val interrupted = CountDownLatch(1)
    val service =
      PersistentUiBuilderService(
        storage = storage,
        catalogs = TestCatalogs,
        exporter =
          UiBuilderExportExecutor {
            entered.countDown()
            try {
              CountDownLatch(1).await()
            } catch (failure: InterruptedException) {
              interrupted.countDown()
              throw failure
            }
            error("unreachable")
          },
        clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
        limits = UiBuilderServiceLimits(exportTimeoutMillis = 50),
      )
    create(service)

    val failure =
      error(
        execute(
          service,
          owner,
          UiBuilderServiceRequest.ExportDesign("design", 0, ExportFormatV1.SVG),
        )
      )

    assertTrue(entered.await(1, TimeUnit.SECONDS))
    assertTrue(interrupted.await(1, TimeUnit.SECONDS))
    assertEquals(ServiceErrorCodeV1.INTERNAL, failure.code)
    assertTrue(failure.retryable)
    assertFalse(storage.bytes!!.decodeToString().contains("\"EXPORT\""))
    assertEquals(1, service.diagnostics().timedOutExports)
    assertEquals(0, service.diagnostics().activeExports)
  }

  @Test
  fun `file restart preserves document access outcomes and sequence while presence disappears`() {
    val storage = FileUiBuilderStateStorage(temporaryDirectory)
    var service = service(storage = storage)
    create(service)
    grant(
      service,
      owner,
      viewer,
      baseRevision = 0,
      actions = listOf(DesignAccessActionV1.READ),
    )
    val request =
      UiBuilderServiceRequest.ApplyOperation(
        batch("insert", 0, InsertNodeMutationV1(textNode("node"), NodeLocationV1()))
      )
    accepted(execute(service, owner, request))
    execute(
      service,
      owner,
      UiBuilderServiceRequest.UpdatePresence(
        "design",
        UiBuilderPresence("browser", "Owner", "#FF000000", listOf("node"), null, null, 1),
      ),
    )
    val before = snapshot(execute(service, owner, UiBuilderServiceRequest.OpenDesign("design")))

    service = service(storage = FileUiBuilderStateStorage(temporaryDirectory))
    val after = snapshot(execute(service, owner, UiBuilderServiceRequest.OpenDesign("design")))
    assertEquals(before.state, after.state)
    assertEquals(before.access, after.access)
    assertEquals(emptyList(), after.presence)
    assertIs<UiBuilderServiceResponse.Snapshot>(
      execute(service, viewer, UiBuilderServiceRequest.OpenDesign("design"))
    )
    val retry = accepted(execute(service, owner, request))
    assertTrue(retry.idempotentReplay)
    assertEquals(1, retry.sequence)
  }

  @Test
  fun `checksum corruption fails closed while an orphan partial temporary file is ignored`() {
    val storage = FileUiBuilderStateStorage(temporaryDirectory)
    create(service(storage = storage))
    Files.writeString(
      temporaryDirectory.resolve(".${FileUiBuilderStateStorage.STATE_FILE}.partial.tmp"),
      "partial",
    )
    assertNotNull(service(storage = FileUiBuilderStateStorage(temporaryDirectory)))

    val stateFile = temporaryDirectory.resolve(FileUiBuilderStateStorage.STATE_FILE)
    val bytes = Files.readAllBytes(stateFile)
    bytes[bytes.lastIndex / 2] = (bytes[bytes.lastIndex / 2].toInt() xor 1).toByte()
    Files.write(stateFile, bytes)
    assertFailsWith<UiBuilderPersistenceException> {
      service(storage = FileUiBuilderStateStorage(temporaryDirectory))
    }
  }

  @Test
  fun `explicit backup restore recovers the previous acknowledged snapshot`() {
    val storage = FileUiBuilderStateStorage(temporaryDirectory)
    var service = service(storage = storage)
    create(service)
    accepted(
      execute(
        service,
        owner,
        UiBuilderServiceRequest.ApplyOperation(
          batch("insert", 0, InsertNodeMutationV1(textNode("node"), NodeLocationV1()))
        ),
      )
    )
    assertTrue(Files.exists(temporaryDirectory.resolve(FileUiBuilderStateStorage.BACKUP_FILE)))

    val stateFile = temporaryDirectory.resolve(FileUiBuilderStateStorage.STATE_FILE)
    Files.writeString(stateFile, "truncated")
    assertFailsWith<UiBuilderPersistenceException> {
      service(storage = FileUiBuilderStateStorage(temporaryDirectory))
    }

    assertTrue(FileUiBuilderStateStorage(temporaryDirectory).restoreBackup())
    service = service(storage = FileUiBuilderStateStorage(temporaryDirectory))
    val restored = snapshot(execute(service, owner, UiBuilderServiceRequest.OpenDesign("design")))
    assertEquals(0, restored.state.document.revision)
    assertTrue(restored.state.document.nodes.isEmpty())
    assertFalse(FileUiBuilderStateStorage(temporaryDirectory.resolve("empty")).restoreBackup())
  }

  @Test
  fun `unsupported persistence schema fails with a migration diagnostic`() {
    val storage = FileUiBuilderStateStorage(temporaryDirectory)
    create(service(storage = storage))
    val stateFile = temporaryDirectory.resolve(FileUiBuilderStateStorage.STATE_FILE)
    Files.writeString(
      stateFile,
      Files.readString(stateFile)
        .replace("compose-preview-ui-builder-service/v2", "compose-preview-ui-builder-service/v99"),
    )

    val failure =
      assertFailsWith<UiBuilderPersistenceException> {
        service(storage = FileUiBuilderStateStorage(temporaryDirectory))
      }
    assertContains(failure.message.orEmpty(), "unsupported UI-builder persistence format")
    assertContains(failure.message.orEmpty(), "compose-preview-ui-builder-service/v99")
  }

  @Test
  fun `explicit v1 migration preflights and retains an exact rollback generation`() {
    val storage = FileUiBuilderStateStorage(temporaryDirectory)
    create(service(storage = storage))
    val stateFile = temporaryDirectory.resolve(FileUiBuilderStateStorage.STATE_FILE)
    val legacy = legacyV1(Files.readAllBytes(stateFile))
    Files.write(stateFile, legacy)

    val migrating = service(storage = FileUiBuilderStateStorage(temporaryDirectory))
    assertContentEquals(legacy, Files.readAllBytes(stateFile), "startup must not rewrite v1")
    accepted(
      execute(
        migrating,
        owner,
        UiBuilderServiceRequest.ApplyOperation(
          batch("pre-migration", 0, InsertNodeMutationV1(textNode("node"), NodeLocationV1()))
        ),
      )
    )
    val preMigration = Files.readAllBytes(stateFile)
    assertContains(preMigration.decodeToString(), "compose-preview-ui-builder-service/v1")
    val result = migrating.migratePersistenceToLatest()

    assertTrue(result.migrated)
    assertEquals("compose-preview-ui-builder-service/v1", result.fromFormat)
    assertEquals("compose-preview-ui-builder-service/v2", result.toFormat)
    assertEquals(1, migrating.diagnostics().persistenceMigrations)
    assertContains(Files.readString(stateFile), "compose-preview-ui-builder-service/v2")
    assertContentEquals(
      preMigration,
      Files.readAllBytes(temporaryDirectory.resolve(FileUiBuilderStateStorage.BACKUP_FILE)),
    )
    assertEquals(1, currentDocument(migrating).revision)
    assertFalse(migrating.migratePersistenceToLatest().migrated)
    assertEquals(1, migrating.diagnostics().persistenceMigrations)

    val fileStorage = FileUiBuilderStateStorage(temporaryDirectory)
    assertTrue(fileStorage.restoreMigrationBackup())
    assertContentEquals(preMigration, Files.readAllBytes(stateFile))
    val rolledBack = service(storage = FileUiBuilderStateStorage(temporaryDirectory))
    assertEquals(1, currentDocument(rolledBack).revision)
    assertContentEquals(
      preMigration,
      Files.readAllBytes(stateFile),
      "rollback stays v1 until explicit retry",
    )
  }

  @Test
  fun `failed migrated readback restores v1 and reports no successful migration`() {
    val seed = MemoryStorage()
    create(service(storage = seed))
    val legacy = legacyV1(assertNotNull(seed.bytes))
    val storage = CorruptingMigrationStorage(legacy)
    val migrating = service(storage = storage)

    val failure =
      assertFailsWith<UiBuilderPersistenceException> { migrating.migratePersistenceToLatest() }

    assertContains(failure.message.orEmpty(), "v1 backup was restored")
    assertContentEquals(legacy, storage.bytes)
    assertEquals(1, storage.restoreCalls)
    assertEquals(0, migrating.diagnostics().persistenceMigrations)
    assertEquals(0, currentDocument(migrating).revision)
  }

  @Test
  fun `v2 catalog pin manifest mismatch fails closed even with a valid checksum`() {
    val storage = FileUiBuilderStateStorage(temporaryDirectory)
    create(service(storage = storage))
    val stateFile = temporaryDirectory.resolve(FileUiBuilderStateStorage.STATE_FILE)
    val root = persistenceJson.parseToJsonElement(Files.readString(stateFile)).jsonObject
    val payload = root.getValue("payload").jsonObject
    val mismatchedPayload = JsonObject(payload + ("catalogPins" to JsonObject(emptyMap())))
    val rewritten =
      JsonObject(
        root +
          mapOf(
            "checksumSha256" to
              JsonPrimitive(
                sha256(canonicalPersistenceJson(mismatchedPayload).encodeToByteArray())
              ),
            "payload" to mismatchedPayload,
          )
      )
    Files.writeString(stateFile, rewritten.toString())

    val failure =
      assertFailsWith<UiBuilderPersistenceException> {
        service(storage = FileUiBuilderStateStorage(temporaryDirectory))
      }
    assertContains(failure.message.orEmpty(), "catalog pin manifest mismatch")
  }

  @Test
  fun `durable write failure cannot publish or partially expose a command`() {
    val storage = FailingStorage()
    val failures = mutableListOf<Throwable>()
    val service =
      PersistentUiBuilderService(
        storage = storage,
        catalogs = TestCatalogs,
        exporter = validExporter(),
        subscriberFailureHandler = UiBuilderSubscriberFailureHandler(failures::add),
        clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
      )
    create(service)
    val updates = mutableListOf<UiBuilderServiceUpdate>()
    val subscription =
      service.subscribe(UiBuilderSubscriptionCall(owner, "design", 0), updates::add)
    updates.clear()
    storage.failWrites = true

    assertFailsWith<UiBuilderPersistenceException> {
      execute(
        service,
        owner,
        UiBuilderServiceRequest.ApplyOperation(
          batch("not-durable", 0, InsertNodeMutationV1(textNode("node"), NodeLocationV1()))
        ),
      )
    }
    storage.failWrites = false
    assertEquals(0, currentDocument(service).revision)
    assertFalse("node" in currentDocument(service).nodes)
    assertEquals(emptyList(), updates)
    assertEquals(emptyList(), failures)
    subscription.close()
  }

  @Test
  fun `access revoke transfer subscription denial and export verification fail closed`() {
    val service = service()
    create(service)
    assertFailsWith<UiBuilderSubscriptionRejectedException> {
      service.subscribe(UiBuilderSubscriptionCall(outsider, "design", null)) {}
    }
    grant(
      service,
      owner,
      viewer,
      baseRevision = 0,
      actions = listOf(DesignAccessActionV1.READ),
    )
    assertIs<UiBuilderServiceResponse.DesignAccess>(
      execute(
        service,
        owner,
        UiBuilderServiceRequest.UpdateDesignAccess(
          "design",
          1,
          listOf(RevokeActorAccessMutationV1(viewer.actorId)),
        ),
      )
    )
    assertEquals(
      ServiceErrorCodeV1.FORBIDDEN,
      error(execute(service, viewer, UiBuilderServiceRequest.OpenDesign("design"))).code,
    )
    grant(
      service,
      owner,
      viewer,
      baseRevision = 2,
      actions = listOf(DesignAccessActionV1.READ),
    )
    val transferred =
      assertIs<UiBuilderServiceResponse.DesignAccess>(
        execute(
          service,
          owner,
          UiBuilderServiceRequest.UpdateDesignAccess(
            "design",
            3,
            listOf(TransferDesignOwnershipMutationV1(viewer.actorId)),
          ),
        )
      )
    assertEquals(viewer.actorId, transferred.access.ownerActorId)
    assertEquals(
      ServiceErrorCodeV1.FORBIDDEN,
      error(execute(service, owner, UiBuilderServiceRequest.GetDesignAccess("design"))).code,
    )
    assertIs<UiBuilderServiceResponse.DesignAccess>(
      execute(service, viewer, UiBuilderServiceRequest.GetDesignAccess("design"))
    )

    val invalidExportService =
      PersistentUiBuilderService(
        storage = MemoryStorage(),
        catalogs = TestCatalogs,
        exporter =
          UiBuilderExportExecutor {
            ExportArtifactV1(
              ExportFormatV1.SVG,
              "image/svg+xml",
              ExportEncodingV1.UTF8,
              "<svg/>",
              "not-the-content-digest",
            )
          },
        clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
      )
    create(invalidExportService)
    assertEquals(
      ServiceErrorCodeV1.INTERNAL,
      error(
          execute(
            invalidExportService,
            owner,
            UiBuilderServiceRequest.ExportDesign("design", 0, ExportFormatV1.SVG),
          )
        )
        .code,
    )
  }

  private fun service(
    storage: UiBuilderStateStorage = MemoryStorage(),
    retained: Int = 16,
    exportRequests: MutableList<RevisionPinnedUiBuilderExport> = mutableListOf(),
    limits: UiBuilderServiceLimits? = null,
    exporter: UiBuilderExportExecutor = validExporter(exportRequests),
    clock: Clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
  ): PersistentUiBuilderService =
    PersistentUiBuilderService(
      storage = storage,
      catalogs = TestCatalogs,
      exporter = exporter,
      clock = clock,
      limits =
        limits
          ?: UiBuilderServiceLimits(
            retainedCommittedOperations = retained,
            retainedRevisionSnapshots = retained + 1,
          ),
    )

  private class MutableClock(var nowMillis: Long) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = Instant.ofEpochMilli(nowMillis)
  }

  private fun validExporter(
    exportRequests: MutableList<RevisionPinnedUiBuilderExport> = mutableListOf()
  ): UiBuilderExportExecutor = UiBuilderExportExecutor { request ->
    exportRequests += request
    val content = "<svg data-revision=\"${request.revision}\"/>"
    ExportArtifactV1(
      request.format,
      "image/svg+xml",
      ExportEncodingV1.UTF8,
      content,
      sha256(content.encodeToByteArray()),
    )
  }

  private fun create(service: PersistentUiBuilderService) {
    assertIs<UiBuilderServiceResponse.Snapshot>(
      execute(service, owner, UiBuilderServiceRequest.CreateDesign(document()))
    )
  }

  private fun grant(
    service: PersistentUiBuilderService,
    grantingActor: AuthenticatedUiBuilderActor,
    actor: AuthenticatedUiBuilderActor,
    baseRevision: Long,
    actions: List<DesignAccessActionV1>,
  ) {
    assertIs<UiBuilderServiceResponse.DesignAccess>(
      execute(
        service,
        grantingActor,
        UiBuilderServiceRequest.UpdateDesignAccess(
          "design",
          baseRevision,
          listOf(GrantActorAccessMutationV1(actor.actorId, DesignAccessRoleV1.VIEWER, actions)),
        ),
      )
    )
  }

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

  private fun textNode(id: String): DesignNodeV1 = DesignNodeV1(id = id, componentId = "m3.Text")

  private fun batch(
    operationId: String,
    baseRevision: Long,
    vararg mutations: DesignMutationV1,
  ): UiBuilderSubmission.Batch =
    UiBuilderSubmission.Batch("design", operationId, "browser", baseRevision, mutations.toList())

  private fun currentDocument(service: PersistentUiBuilderService): DesignDocumentV1 =
    snapshot(execute(service, owner, UiBuilderServiceRequest.OpenDesign("design"))).state.document

  private fun currentNode(service: PersistentUiBuilderService, nodeId: String): DesignNodeV1 =
    currentDocument(service).nodes.getValue(nodeId)

  private class MemoryStorage : UiBuilderStateStorage {
    var bytes: ByteArray? = null

    override fun load(): ByteArray? = bytes?.copyOf()

    override fun replace(value: ByteArray) {
      bytes = value.copyOf()
    }
  }

  private class CorruptingMigrationStorage(initial: ByteArray) :
    RecoverableUiBuilderMigrationStorage {
    var bytes: ByteArray = initial.copyOf()
    private var backup: ByteArray? = null
    var restoreCalls: Int = 0

    override fun load(): ByteArray = bytes.copyOf()

    override fun replace(value: ByteArray) {
      bytes = value.copyOf()
    }

    override fun replaceForMigration(value: ByteArray) {
      backup = bytes.copyOf()
      bytes = "corrupt migrated readback".encodeToByteArray()
    }

    override fun restoreMigrationBackup(): Boolean {
      restoreCalls += 1
      val value = backup ?: return false
      bytes = value.copyOf()
      return true
    }
  }

  private class MutableClock(var epochMillis: Long) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = Instant.ofEpochMilli(epochMillis)

    override fun millis(): Long = epochMillis
  }

  private class FailingStorage : UiBuilderStateStorage {
    private var bytes: ByteArray? = null
    var failWrites: Boolean = false

    override fun load(): ByteArray? = bytes?.copyOf()

    override fun replace(value: ByteArray) {
      if (failWrites) throw UiBuilderPersistenceException("injected durable write failure")
      bytes = value.copyOf()
    }
  }

  private object TestCatalogs : UiBuilderCatalogExecutor {
    override fun listCatalogs(): List<CatalogCapabilityV1> = listOf(CATALOG)

    override fun resolve(reference: CatalogReferenceV1): CatalogCapabilityV1? = CATALOG.takeIf {
      reference == CATALOG_REFERENCE
    }

    override fun validate(
      document: DesignDocumentV1,
      catalog: CatalogCapabilityV1,
    ): UiBuilderCatalogIssue? {
      document.nodes.values.forEach { node ->
        if (node.componentId != "m3.Text") {
          return UiBuilderCatalogIssue("UNKNOWN_COMPONENT", "unknown component", node.id)
        }
        val unknownProperty = node.properties.keys.firstOrNull { it != "text" }
        if (unknownProperty != null) {
          return UiBuilderCatalogIssue(
            "UNKNOWN_PROPERTY",
            "unknown property",
            node.id,
            unknownProperty,
          )
        }
        val text = node.properties["text"]
        if (text != null && text !is StringValueV1) {
          return UiBuilderCatalogIssue("INVALID_PROPERTY", "text must be a string", node.id, "text")
        }
      }
      return null
    }
  }

  companion object {
    private val CATALOG_REFERENCE = CatalogReferenceV1("m3", "catalog", "digest", "m3-runtime")
    private val CATALOG =
      CatalogCapabilityV1(
        schema = "compose-catalog-capabilities/v1",
        benchmark = CatalogBenchmarkV1("m3", "source", "m3", "catalog", "m3-runtime"),
        components =
          listOf(
            ComponentCapabilityV1(
              componentId = "m3.Text",
              displayName = "Text",
              role = "text",
              properties =
                listOf(PropertyCapabilityV1("text", JsonPrimitive("string"), required = false)),
              wasm = WasmCapabilityV1(JsonPrimitive(true), WasmAdapterStatusV1.SUPPORTED),
            )
          ),
        exportCapabilities = ExportCapabilitiesV1(composeCode = true, svg = true, png = true),
      )
  }
}

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
  return completion?.getOrThrow() ?: error("synchronous service call suspended unexpectedly")
}

private fun error(response: UiBuilderServiceResponse): UiBuilderServiceError =
  assertIs<UiBuilderServiceResponse.Error>(response).error

private fun accepted(response: UiBuilderServiceResponse): AcceptedOutcomeV1 =
  assertIs<AcceptedOutcomeV1>(assertIs<UiBuilderServiceResponse.OperationOutcome>(response).outcome)

private fun rejected(response: UiBuilderServiceResponse): RejectedOutcomeV1 =
  assertIs<RejectedOutcomeV1>(assertIs<UiBuilderServiceResponse.OperationOutcome>(response).outcome)

private fun snapshot(response: UiBuilderServiceResponse): ServiceSnapshotV1 =
  assertIs<UiBuilderServiceResponse.Snapshot>(response).snapshot

private fun UiBuilderServiceUpdate.sequence(): Long =
  when (this) {
    is UiBuilderServiceUpdate.Snapshot -> snapshot.state.lastSequence
    is UiBuilderServiceUpdate.Delta -> delta.throughSequence
    is UiBuilderServiceUpdate.Presence -> error("presence has no durable sequence")
    is UiBuilderServiceUpdate.Outcome ->
      (outcome as? AcceptedOutcomeV1)?.sequence ?: error("rejection has no durable sequence")
  }

private fun sha256(bytes: ByteArray): String =
  MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private val persistenceJson = Json { encodeDefaults = true }

private fun legacyV1(v2: ByteArray): ByteArray {
  val root = persistenceJson.parseToJsonElement(v2.decodeToString()).jsonObject
  val service = root.getValue("payload").jsonObject.getValue("service")
  val checksum = sha256(canonicalPersistenceJson(service).encodeToByteArray())
  return JsonObject(
      mapOf(
        "format" to JsonPrimitive("compose-preview-ui-builder-service/v1"),
        "checksumSha256" to JsonPrimitive(checksum),
        "payload" to service,
      )
    )
    .toString()
    .encodeToByteArray()
}

private fun canonicalPersistenceJson(element: JsonElement): String =
  when (element) {
    is JsonObject ->
      element.entries
        .sortedBy { it.key }
        .joinToString(",", "{", "}") { (key, value) ->
          "${JsonPrimitive(key)}:${canonicalPersistenceJson(value)}"
        }
    is JsonArray -> element.joinToString(",", "[", "]", transform = ::canonicalPersistenceJson)
    is JsonPrimitive -> element.toString()
  }
