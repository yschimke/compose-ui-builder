package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.*
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

/**
 * Design branches end to end, on the production per-design store: create, edit, list, archive,
 * merge (happy, refused, skipped, dry run), retention pinning and a restart. The model is
 * `docs/design/UI_BUILDER_BRANCHES.md`.
 */
class DesignBranchesTest {
  private val owner = AuthenticatedUiBuilderActor("owner")
  private val agent = AuthenticatedUiBuilderActor("agent")
  private val outsider = AuthenticatedUiBuilderActor("outsider")

  @Test
  fun `a branch is created, edited, listed and archived without touching its parent`() {
    val service = service(createTempDirectory("branches"))
    seed(service)

    val created = branch(service, owner, "Bigger title", branchId = "design-a")
    assertEquals("design", created.parentDesignId)
    assertEquals(1, created.forkRevision)
    assertEquals(1, created.headRevision)
    assertEquals(0, created.commandCount)
    assertEquals(UiBuilderBranchStatus.OPEN, created.status)
    assertEquals("owner", created.ownerActorId)

    // A branch is a design: opened, and edited through the ordinary apply, by its own id.
    accepted(service, owner, setText("design-a", "a-1", 1, "Hello"))
    assertEquals(2, document(service, "design-a").revision)
    assertEquals(StringValueV1("Hello"), document(service, "design-a").text("title"))
    assertNull(document(service, "design").text("title"), "the parent is untouched")
    assertEquals(1, get(service, owner, "design-a").commandCount)

    // A retried create is the same branch; a different one under that id is refused.
    assertEquals(
      "design-a",
      branch(service, owner, "Bigger title", branchId = "design-a").branchId,
    )
    assertEquals(
      ServiceErrorCodeV1.BAD_REQUEST,
      branchError(
          service,
          owner,
          UiBuilderBranchRequest.CreateBranch("design", "Other", null, "design-a"),
        )
        .code,
    )
    // Not a branch of a branch, in this phase.
    assertEquals(
      ServiceErrorCodeV1.BAD_REQUEST,
      branchError(service, owner, UiBuilderBranchRequest.CreateBranch("design-a", "Nested")).code,
    )

    // Listed under the parent, never beside it.
    val listed = listDesigns(service, owner)
    assertEquals(listOf("design"), listed)
    assertEquals(listOf("design-a"), list(service, owner, "design").map { it.branchId })

    // Somebody the design was never shared with sees no branches of it.
    assertEquals(
      ServiceErrorCodeV1.NOT_FOUND,
      branchError(service, outsider, UiBuilderBranchRequest.ListBranches("design")).code,
    )
    assertEquals(
      ServiceErrorCodeV1.NOT_FOUND,
      branchError(service, outsider, UiBuilderBranchRequest.GetBranch("design-a")).code,
    )

    // Archived: kept, but closed to edits.
    val archived =
      assertIs<UiBuilderBranchResponse.Branch>(
          execute(service, owner, UiBuilderBranchRequest.ArchiveBranch("design-a"))
        )
        .branch
    assertEquals(UiBuilderBranchStatus.ARCHIVED, archived.status)
    assertEquals("owner", archived.closedByActorId)
    val refused = rejected(service, owner, setText("design-a", "a-2", 2, "Again"))
    assertEquals(RejectionCodeV1.INVALID_COMMAND, refused.code)
    assertEquals(
      listOf("design-a"),
      list(service, owner, "design", includeClosed = true).map { it.branchId },
    )
    assertEquals(emptyList(), list(service, owner, "design", includeClosed = false))
  }

  @Test
  fun `a branch refuses whole-document changes its merge could not replay`() {
    val service = service(createTempDirectory("branches"))
    seed(service)
    branch(service, owner, "Restore", branchId = "design-a")
    accepted(service, owner, setText("design-a", "a-1", 1, "Hello"))

    assertEquals(
      RejectionCodeV1.INVALID_COMMAND,
      assertIs<RejectedOutcomeV1>(
          assertIs<UiBuilderServiceResponse.OperationOutcome>(
              execute(
                service,
                owner,
                UiBuilderServiceRequest.RestoreRevision("design-a", 1, 2, "restore"),
              )
            )
            .outcome
        )
        .code,
    )
    val current = document(service, "design-a")
    assertEquals(
      ServiceErrorCodeV1.BAD_REQUEST,
      assertIs<UiBuilderServiceResponse.Error>(
          execute(
            service,
            owner,
            UiBuilderServiceRequest.ReplaceDesignDocument("design-a", current, 2, "replace"),
          )
        )
        .error
        .code,
    )
    assertEquals(1, get(service, owner, "design-a").commandCount)
  }

  @Test
  fun `merge replays the log onto the parent's head, keeps each author, and archives siblings`() {
    val service = service(createTempDirectory("branches"))
    seed(service)
    val a = branch(service, owner, "A", branchId = "design-a")
    branch(service, owner, "B", branchId = "design-b")
    // A collaborator granted on the design after the branches were cut still reaches them: a
    // branch's access is its parent's.
    grantEditor(service, agent)
    accepted(service, agent, setText("design-a", "a-1", 1, "From the branch"))
    accepted(
      service,
      agent,
      batch("design-a", "a-2", 2, InsertNodeMutationV1(text("a-node"), inRoot)),
    )
    accepted(service, agent, undo("design-a", "a-3", 3, "a-2"))
    // The parent moves on meanwhile, so the replay is concurrent with a real edit.
    accepted(
      service,
      owner,
      batch("design", "p-1", 1, InsertNodeMutationV1(text("other"), inRoot)),
    )
    // A later fork is not a sibling of A: it forked from somewhere else.
    val later = branch(service, owner, "Later", branchId = "design-later")
    assertEquals(2, later.forkRevision)

    // Dry run: the whole report, and nothing written to anyone.
    val dry = merge(service, owner, "design-a", dryRun = true)
    assertTrue(dry.merged)
    assertTrue(dry.dryRun)
    assertEquals(listOf("a-1", "a-2", "a-3"), dry.commands.map { it.operationId })
    assertTrue(dry.commands.all { it.status == UiBuilderBranchMergeCommandStatus.APPLIED })
    // The base chain: the fork point first, then each predecessor's landing revision.
    assertEquals(listOf(1L, 3L, 4L), dry.commands.map { it.baseRevision })
    assertEquals(listOf(3L, 4L, 5L), dry.commands.map { it.committedRevision })
    assertEquals(listOf("design-b"), dry.archivedSiblingIds)
    assertEquals(2, document(service, "design").revision)
    assertEquals(UiBuilderBranchStatus.OPEN, get(service, owner, "design-a").status)
    assertEquals(UiBuilderBranchStatus.OPEN, get(service, owner, "design-b").status)

    // A grantee who may only read the parent may not merge into it.
    assertEquals(
      ServiceErrorCodeV1.NOT_FOUND,
      branchError(service, outsider, UiBuilderBranchRequest.MergeBranch("design-a")).code,
    )

    val report = merge(service, owner, "design-a")
    assertTrue(report.merged)
    assertFalse(report.dryRun)
    assertEquals(2, report.parentRevisionBefore)
    assertEquals(5, report.parentRevisionAfter)
    assertEquals(listOf("agent", "agent", "agent"), report.commands.map { it.actorId })
    val merged = document(service, "design")
    assertEquals(5, merged.revision)
    assertEquals(StringValueV1("From the branch"), merged.text("title"))
    assertTrue("other" in merged.nodes, "the parent's concurrent edit is kept")
    assertFalse("a-node" in merged.nodes, "the branch's undo replayed as itself")

    val mergedBranch = get(service, owner, "design-a")
    assertEquals(UiBuilderBranchStatus.MERGED, mergedBranch.status)
    assertEquals(5, mergedBranch.mergedAtParentRevision)
    assertEquals("owner", mergedBranch.closedByActorId)
    val sibling = get(service, owner, "design-b")
    assertEquals(UiBuilderBranchStatus.ARCHIVED, sibling.status)
    assertEquals("design-a", sibling.supersededByBranchId)
    assertEquals(UiBuilderBranchStatus.OPEN, get(service, owner, "design-later").status)
    assertEquals(a.forkRevision, mergedBranch.forkRevision)

    // Attribution: each landed revision names the branch author, and that author — not the
    // merging owner — can undo it on the parent.
    val revisions =
      assertIs<UiBuilderServiceResponse.Revisions>(
        execute(service, owner, UiBuilderServiceRequest.ListRevisions("design"))
      )
    assertEquals(
      listOf("agent", "agent", "agent"),
      revisions.revisions.filter { it.revision in 3L..5L }.map { it.actorId },
    )
    assertEquals(
      RejectionCodeV1.ACTOR_MISMATCH,
      rejected(service, owner, undo("design", "undo-owner", 5, "a-1")).code,
    )
    accepted(service, agent, undo("design", "undo-agent", 5, "a-1"))
    assertNull(document(service, "design").text("title"))

    // A merged branch merges once.
    assertEquals(
      ServiceErrorCodeV1.BAD_REQUEST,
      branchError(service, owner, UiBuilderBranchRequest.MergeBranch("design-a")).code,
    )
  }

  @Test
  fun `a refused command stops the merge and commits nothing, and a skip resolves it`() {
    val service = service(createTempDirectory("branches"))
    seed(service)
    accepted(
      service,
      owner,
      batch("design", "victim", 1, InsertNodeMutationV1(text("victim"), inRoot)),
    )
    branch(service, owner, "Cleanup", branchId = "design-a")
    branch(service, owner, "Sibling", branchId = "design-b")
    accepted(service, owner, setText("design-a", "b-1", 2, "Cleaned"))
    accepted(
      service,
      owner,
      setText("design-a", "b-2", 3, "Edited on the branch", nodeId = "victim"),
    )
    accepted(service, owner, setText("design-a", "b-3", 4, "Cleaned again"))
    // Somebody deletes the node on the parent after the fork; the branch's edit of it cannot land.
    accepted(service, owner, batch("design", "p-1", 2, DeleteNodeMutationV1("victim")))

    val refused = merge(service, owner, "design-a")
    assertFalse(refused.merged)
    assertEquals(
      listOf(
        UiBuilderBranchMergeCommandStatus.APPLIED,
        UiBuilderBranchMergeCommandStatus.REFUSED,
      ),
      refused.commands.map { it.status },
    )
    val stop = refused.commands.last()
    assertEquals("b-2", stop.operationId)
    assertTrue(
      stop.code in setOf(RejectionCodeV1.DELETED_NODE.name, RejectionCodeV1.UNKNOWN_NODE.name),
      stop.toString(),
    )
    assertEquals("victim", stop.nodeId)
    assertEquals(1, refused.remaining)
    assertEquals(emptyList(), refused.archivedSiblingIds)
    assertEquals(3, refused.parentRevisionBefore)
    assertEquals(3, refused.parentRevisionAfter)

    // All or nothing: not even the command that landed in the working copy is in the parent.
    val parent = document(service, "design")
    assertEquals(3, parent.revision)
    assertNull(parent.text("title"))
    assertFalse("victim" in parent.nodes)
    assertEquals(UiBuilderBranchStatus.OPEN, get(service, owner, "design-a").status)
    assertEquals(UiBuilderBranchStatus.OPEN, get(service, owner, "design-b").status)
    // And none of its operation ids were spent: the retry below lands them under the same ids.

    // Resolving it: the edit of a node the parent deleted is left out, deliberately, and the rest
    // lands.
    assertEquals(
      ServiceErrorCodeV1.BAD_REQUEST,
      branchError(
          service,
          owner,
          UiBuilderBranchRequest.MergeBranch("design-a", skipOperationIds = setOf("nope")),
        )
        .code,
    )
    val resolved = merge(service, owner, "design-a", skip = setOf("b-2"))
    assertTrue(resolved.merged)
    assertEquals(listOf("b-2"), resolved.skippedOperationIds)
    assertEquals(listOf("b-1", "b-3"), resolved.commands.map { it.operationId })
    val after = document(service, "design")
    assertEquals(StringValueV1("Cleaned again"), after.text("title"))
    assertFalse("victim" in after.nodes)
    assertEquals(listOf("design-b"), resolved.archivedSiblingIds)
  }

  @Test
  fun `retention cannot expire an open branch's fork point, and releases it once closed`() {
    val root = createTempDirectory("branches")
    val limits =
      UiBuilderServiceLimits(
        retainedCommittedOperations = 3,
        retainedRevisionSnapshots = 3,
        minimumRetainedRevisionSnapshots = 1,
      )
    val service = service(root, limits)
    seed(service)
    branch(service, owner, "Long-lived", branchId = "design-a")
    accepted(service, owner, setText("design-a", "a-1", 1, "From the branch"))
    for (index in 1..6) {
      accepted(
        service,
        owner,
        batch(
          "design",
          "p-$index",
          index.toLong(),
          InsertNodeMutationV1(text("n-$index"), inRoot),
        ),
      )
    }
    val retained = revisions(service, "design")
    assertEquals(listOf(7L, 6L, 5L, 1L), retained.revisions.map { it.revision })
    assertIs<UiBuilderServiceResponse.Snapshot>(
      execute(service, owner, UiBuilderServiceRequest.GetSnapshot("design", 1))
    )
    // Revision 2 is gone, and the floor a client is told is the unbroken run, not the pin below it.
    val gone =
      assertIs<UiBuilderServiceResponse.Error>(
          execute(service, owner, UiBuilderServiceRequest.GetSnapshot("design", 2))
        )
        .error
    assertEquals(ServiceErrorCodeV1.SNAPSHOT_REQUIRED, gone.code)
    assertEquals(
      retained.revisions.first { it.revision == 5L }.sequence,
      gone.retainedFromSequence,
    )
    // An unpinned old revision cannot be branched from.
    assertEquals(
      ServiceErrorCodeV1.SNAPSHOT_REQUIRED,
      branchError(service, owner, UiBuilderBranchRequest.CreateBranch("design", "Old", 2)).code,
    )

    // Six revisions past a three-deep window, the merge still replays from the fork.
    val report = merge(service, owner, "design-a", dryRun = true)
    assertTrue(report.merged, report.toString())
    assertEquals(1, report.commands.single().baseRevision)

    // Archived, the pin is released at the parent's next commit.
    execute(service, owner, UiBuilderBranchRequest.ArchiveBranch("design-a"))
    accepted(service, owner, setText("design", "p-7", 7, "Moving on"))
    assertEquals(listOf(8L, 7L, 6L), revisions(service, "design").revisions.map { it.revision })
  }

  @Test
  fun `a content replacement on the parent cuts the fork point, and the merge says so`() {
    val service = service(createTempDirectory("branches"))
    seed(service)
    branch(service, owner, "Before the replace", branchId = "design-a")
    accepted(service, owner, setText("design-a", "a-1", 1, "From the branch"))
    val current = document(service, "design")
    accepted(
      service,
      owner,
      batch("design", "p-1", 1, SetPropertyMutationV1("title", "text", StringValueV1("Mine"))),
    )
    assertIs<UiBuilderServiceResponse.OperationOutcome>(
      execute(
        service,
        owner,
        UiBuilderServiceRequest.ReplaceDesignDocument("design", current, 2, "replace"),
      )
    )

    val report = merge(service, owner, "design-a")
    assertFalse(report.merged)
    assertEquals(
      RejectionCodeV1.REVISION_NOT_RETAINED.name,
      report.commands.single().code,
      report.toString(),
    )
    // The fork's document is still kept, so the branch can be compared with where it started.
    assertIs<UiBuilderServiceResponse.Snapshot>(
      execute(service, owner, UiBuilderServiceRequest.GetSnapshot("design", 1))
    )
  }

  @Test
  fun `branches, their logs and their pins survive a restart`() {
    val root = createTempDirectory("branches")
    val limits =
      UiBuilderServiceLimits(
        retainedCommittedOperations = 3,
        retainedRevisionSnapshots = 3,
        minimumRetainedRevisionSnapshots = 1,
      )
    val first = service(root, limits)
    seed(first)
    branch(first, owner, "A", branchId = "design-a")
    branch(first, owner, "B", branchId = "design-b")
    accepted(first, owner, setText("design-a", "a-1", 1, "One"))
    accepted(first, owner, setText("design-a", "a-2", 2, "Two"))

    val second = service(root, limits)
    val reloaded = get(second, owner, "design-a")
    assertEquals(2, reloaded.commandCount)
    assertEquals(UiBuilderBranchStatus.OPEN, reloaded.status)
    assertEquals("A", reloaded.name)
    for (index in 1..5) {
      accepted(
        second,
        owner,
        batch(
          "design",
          "p-$index",
          index.toLong(),
          InsertNodeMutationV1(text("n-$index"), inRoot),
        ),
      )
    }
    assertTrue(1L in revisions(second, "design").revisions.map { it.revision })
    // Appended to after a restart, the log carries on rather than starting again.
    accepted(second, owner, setText("design-a", "a-3", 3, "Three"))

    val third = service(root, limits)
    assertEquals(3, get(third, owner, "design-a").commandCount)
    val report = merge(third, owner, "design-a")
    assertTrue(report.merged, report.toString())
    assertEquals(listOf("a-1", "a-2", "a-3"), report.commands.map { it.operationId })

    val fourth = service(root, limits)
    assertEquals(UiBuilderBranchStatus.MERGED, get(fourth, owner, "design-a").status)
    assertEquals(UiBuilderBranchStatus.ARCHIVED, get(fourth, owner, "design-b").status)
    assertEquals(StringValueV1("Three"), document(fourth, "design").text("title"))
  }

  // ------------------------------------------------------------------------------------ helpers

  /** A design whose root holds one Text node, `title`, at revision 1. */
  private fun seed(service: PersistentUiBuilderService) {
    assertIs<UiBuilderServiceResponse.Snapshot>(
      execute(service, owner, UiBuilderServiceRequest.CreateDesign(designDocument()))
    )
    accepted(
      service,
      owner,
      batch("design", "seed", 0, InsertNodeMutationV1(text("title"), inRoot)),
    )
  }

  private fun grantEditor(service: PersistentUiBuilderService, actor: AuthenticatedUiBuilderActor) {
    assertIs<UiBuilderServiceResponse.DesignAccess>(
      execute(
        service,
        owner,
        UiBuilderServiceRequest.UpdateDesignAccess(
          "design",
          0,
          listOf(
            GrantActorAccessMutationV1(
              actor.actorId,
              DesignAccessRoleV1.EDITOR,
              listOf(DesignAccessActionV1.READ, DesignAccessActionV1.WRITE),
            )
          ),
        ),
      )
    )
  }

  private fun branch(
    service: PersistentUiBuilderService,
    actor: AuthenticatedUiBuilderActor,
    name: String,
    branchId: String,
  ): UiBuilderBranch =
    assertIs<UiBuilderBranchResponse.Branch>(
        execute(
          service,
          actor,
          UiBuilderBranchRequest.CreateBranch("design", name, branchId = branchId),
        )
      )
      .branch

  private fun get(
    service: PersistentUiBuilderService,
    actor: AuthenticatedUiBuilderActor,
    branchId: String,
  ): UiBuilderBranch =
    assertIs<UiBuilderBranchResponse.Branch>(
        execute(service, actor, UiBuilderBranchRequest.GetBranch(branchId))
      )
      .branch

  private fun list(
    service: PersistentUiBuilderService,
    actor: AuthenticatedUiBuilderActor,
    designId: String,
    includeClosed: Boolean = true,
  ): List<UiBuilderBranch> =
    assertIs<UiBuilderBranchResponse.Branches>(
        execute(service, actor, UiBuilderBranchRequest.ListBranches(designId, includeClosed))
      )
      .branches

  private fun merge(
    service: PersistentUiBuilderService,
    actor: AuthenticatedUiBuilderActor,
    branchId: String,
    dryRun: Boolean = false,
    skip: Set<String> = emptySet(),
  ): UiBuilderBranchMergeReport =
    assertIs<UiBuilderBranchResponse.Merge>(
        execute(service, actor, UiBuilderBranchRequest.MergeBranch(branchId, dryRun, skip))
      )
      .report

  private fun branchError(
    service: PersistentUiBuilderService,
    actor: AuthenticatedUiBuilderActor,
    request: UiBuilderBranchRequest,
  ): UiBuilderServiceError =
    assertIs<UiBuilderBranchResponse.Error>(execute(service, actor, request)).error

  private fun listDesigns(
    service: PersistentUiBuilderService,
    actor: AuthenticatedUiBuilderActor,
  ): List<String> =
    assertIs<UiBuilderServiceResponse.Designs>(
        execute(service, actor, UiBuilderServiceRequest.ListDesigns(null, 50))
      )
      .designs
      .map { it.designId }

  private fun revisions(
    service: PersistentUiBuilderService,
    designId: String,
  ): UiBuilderServiceResponse.Revisions =
    assertIs<UiBuilderServiceResponse.Revisions>(
      execute(service, owner, UiBuilderServiceRequest.ListRevisions(designId))
    )

  private fun document(service: PersistentUiBuilderService, designId: String): DesignDocumentV1 =
    assertIs<UiBuilderServiceResponse.Snapshot>(
        execute(service, owner, UiBuilderServiceRequest.OpenDesign(designId))
      )
      .snapshot
      .state
      .document

  private fun DesignDocumentV1.text(nodeId: String): UiValueV1? =
    nodes.getValue(nodeId).properties["text"]

  private fun accepted(
    service: PersistentUiBuilderService,
    actor: AuthenticatedUiBuilderActor,
    submission: UiBuilderSubmission,
  ): AcceptedOutcomeV1 {
    val outcome =
      assertIs<UiBuilderServiceResponse.OperationOutcome>(
          execute(service, actor, UiBuilderServiceRequest.ApplyOperation(submission))
        )
        .outcome
    return assertIs<AcceptedOutcomeV1>(outcome, "${submission.operationId}: $outcome")
  }

  private fun rejected(
    service: PersistentUiBuilderService,
    actor: AuthenticatedUiBuilderActor,
    submission: UiBuilderSubmission,
  ): RejectedOutcomeV1 =
    assertIs<RejectedOutcomeV1>(
      assertIs<UiBuilderServiceResponse.OperationOutcome>(
          execute(service, actor, UiBuilderServiceRequest.ApplyOperation(submission))
        )
        .outcome
    )

  private fun batch(
    designId: String,
    operationId: String,
    baseRevision: Long,
    vararg mutations: DesignMutationV1,
  ): UiBuilderSubmission.Batch =
    UiBuilderSubmission.Batch(designId, operationId, "client", baseRevision, mutations.toList())

  private fun setText(
    designId: String,
    operationId: String,
    baseRevision: Long,
    value: String,
    nodeId: String = "title",
  ): UiBuilderSubmission.Batch =
    batch(
      designId,
      operationId,
      baseRevision,
      SetPropertyMutationV1(nodeId, "text", StringValueV1(value)),
    )

  private fun undo(
    designId: String,
    operationId: String,
    baseRevision: Long,
    target: String,
  ): UiBuilderSubmission.Undo =
    UiBuilderSubmission.Undo(designId, operationId, "client", baseRevision, target)

  private fun text(id: String): DesignNodeV1 = DesignNodeV1(id = id, componentId = "m3.Text")

  private fun service(
    root: Path,
    limits: UiBuilderServiceLimits = UiBuilderServiceLimits(),
  ): PersistentUiBuilderService =
    PersistentUiBuilderService(
      designStore = UiBuilderDesignStateStore.open(root),
      catalogs = TestCatalogs,
      exporter = UiBuilderExportExecutor { error("no export in this test") },
      clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
      limits = limits,
    )

  private fun designDocument(): DesignDocumentV1 =
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
      roots = listOf("root"),
      nodes = mapOf("root" to text("root").copy(slots = mapOf("content" to emptyList()))),
    )

  /** The one root's children: a design has one root, so everything else goes in here. */
  private val inRoot = NodeLocationV1(ParentSlotV1("root", "content"))

  private fun execute(
    service: PersistentUiBuilderService,
    actor: AuthenticatedUiBuilderActor,
    request: UiBuilderServiceRequest,
  ): UiBuilderServiceResponse = runSuspend { service.execute(UiBuilderServiceCall(actor, request)) }

  private fun execute(
    service: PersistentUiBuilderService,
    actor: AuthenticatedUiBuilderActor,
    request: UiBuilderBranchRequest,
  ): UiBuilderBranchResponse = runSuspend {
    service.executeBranch(UiBuilderBranchCall(actor, request))
  }

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
    private val CATALOG_REFERENCE = CatalogReferenceV1("m3", "catalog", "digest", "m3-runtime")
    private val CATALOG =
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
