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
 * Suggestions — short-lived branches of kind `SUGGESTION` — end to end on the production per-design
 * store: an agent proposes, a person lists, accepts (whole or in part) or rejects, a conflicting
 * accept is reported and commits nothing, and all of it survives a restart. The model is
 * `docs/design/UI_BUILDER_BRANCHES.md` → Suggestions.
 */
class DesignSuggestionsTest {
  private val owner = AuthenticatedUiBuilderActor("owner")
  private val agent = AuthenticatedUiBuilderActor("agent")
  private val outsider = AuthenticatedUiBuilderActor("outsider")

  @Test
  fun `an agent's suggestion is listed apart from branches and accepted as a merge`() {
    val service = service(createTempDirectory("suggestions"))
    seed(service)
    grantEditor(service, agent)

    val suggestion = suggest(service, agent, "Bigger title", "design-s1")
    assertEquals(UiBuilderBranchKind.SUGGESTION, suggestion.kind)
    assertEquals("agent", suggestion.ownerActorId)
    assertEquals(1, suggestion.forkRevision)
    accepted(service, agent, setText("design-s1", "s1-1", 1, "Hello"))
    accepted(
      service,
      agent,
      batch("design-s1", "s1-2", 2, InsertNodeMutationV1(text("cta"), inRoot)),
    )
    // An ordinary branch and a second suggestion at the same fork point.
    branch(service, owner, "Explore", "design-b")
    suggest(service, agent, "Other idea", "design-s2")

    // The person's view: the open suggestions, newest first, and not the branch.
    val open = suggestions(service, owner)
    assertEquals(setOf("design-s1", "design-s2"), open.map { it.branchId }.toSet())
    val listed = open.single { it.branchId == "design-s1" }
    assertEquals("Bigger title", listed.name)
    assertEquals(listOf("s1-1", "s1-2"), listed.operationIds)
    assertEquals(2, listed.commandCount)
    assertEquals(
      listOf("design-b"),
      list(service, owner, kind = UiBuilderBranchKind.BRANCH).map { it.branchId },
    )
    assertEquals(3, list(service, owner, kind = null).size)
    // Nobody the design was never shared with sees its suggestions.
    assertEquals(
      ServiceErrorCodeV1.NOT_FOUND,
      branchError(
          service,
          outsider,
          UiBuilderBranchRequest.ListBranches("design", kind = UiBuilderBranchKind.SUGGESTION),
        )
        .code,
    )
    // The same create, retried, is the same suggestion; the same id as a plain branch is not.
    assertEquals("design-s1", suggest(service, agent, "Bigger title", "design-s1").branchId)
    assertEquals(
      ServiceErrorCodeV1.BAD_REQUEST,
      branchError(
          service,
          agent,
          UiBuilderBranchRequest.CreateBranch("design", "Bigger title", branchId = "design-s1"),
        )
        .code,
    )

    // Accept: the commands land on the parent, attributed to the agent that proposed them.
    val report = merge(service, owner, "design-s1")
    assertTrue(report.merged, report.toString())
    assertEquals(listOf("agent", "agent"), report.commands.map { it.actorId })
    assertEquals(3, report.parentRevisionAfter)
    val parent = document(service, "design")
    assertEquals(StringValueV1("Hello"), parent.text("title"))
    assertTrue("cta" in parent.nodes)
    assertEquals(UiBuilderBranchStatus.MERGED, get(service, owner, "design-s1").status)

    // Accepting one suggestion archives nothing: the other suggestion and the branch stay open.
    assertEquals(emptyList(), report.archivedSiblingIds)
    assertEquals(UiBuilderBranchStatus.OPEN, get(service, owner, "design-s2").status)
    assertEquals(UiBuilderBranchStatus.OPEN, get(service, owner, "design-b").status)
    assertEquals(listOf("design-s2"), suggestions(service, owner).map { it.branchId })
  }

  @Test
  fun `merging a branch never archives a suggestion at its fork point`() {
    val service = service(createTempDirectory("suggestions"))
    seed(service)
    branch(service, owner, "A", "design-a")
    branch(service, owner, "B", "design-b")
    suggest(service, owner, "Tweak", "design-s")
    accepted(service, owner, setText("design-a", "a-1", 1, "From A"))

    val report = merge(service, owner, "design-a")
    assertTrue(report.merged)
    assertEquals(listOf("design-b"), report.archivedSiblingIds)
    assertEquals(UiBuilderBranchStatus.OPEN, get(service, owner, "design-s").status)
  }

  @Test
  fun `reject archives the suggestion, by the person or by the agent that made it`() {
    val service = service(createTempDirectory("suggestions"))
    seed(service)
    grantEditor(service, agent)
    suggest(service, agent, "First", "design-s1")
    accepted(service, agent, setText("design-s1", "s1-1", 1, "Nope"))
    suggest(service, agent, "Second", "design-s2")

    val rejected = reject(service, owner, "design-s1")
    assertEquals(UiBuilderBranchStatus.ARCHIVED, rejected.status)
    assertEquals("owner", rejected.closedByActorId)
    // The parent never saw it, and the suggestion takes no more edits.
    assertNull(document(service, "design").text("title"))
    assertEquals(1, document(service, "design").revision)
    assertEquals(
      RejectionCodeV1.INVALID_COMMAND,
      rejected(service, agent, setText("design-s1", "s1-2", 2, "Again")).code,
    )
    // Withdrawn by its own author.
    assertEquals(UiBuilderBranchStatus.ARCHIVED, reject(service, agent, "design-s2").status)
    assertEquals(emptyList(), suggestions(service, owner))
    // Still readable as history.
    assertEquals(
      setOf("design-s1", "design-s2"),
      list(service, owner, includeClosed = true, kind = UiBuilderBranchKind.SUGGESTION)
        .map { it.branchId }
        .toSet(),
    )
    // A rejected suggestion cannot then be accepted.
    assertEquals(
      ServiceErrorCodeV1.BAD_REQUEST,
      branchError(service, owner, UiBuilderBranchRequest.MergeBranch("design-s1")).code,
    )
  }

  @Test
  fun `part of a suggestion is accepted by naming the operations to keep`() {
    val service = service(createTempDirectory("suggestions"))
    seed(service)
    suggest(service, owner, "Two things", "design-s")
    accepted(service, owner, setText("design-s", "s-1", 1, "Kept"))
    accepted(
      service,
      owner,
      batch("design-s", "s-2", 2, InsertNodeMutationV1(text("extra"), inRoot)),
    )

    assertEquals(
      ServiceErrorCodeV1.BAD_REQUEST,
      branchError(
          service,
          owner,
          UiBuilderBranchRequest.MergeBranch("design-s", acceptOperationIds = setOf("nope")),
        )
        .code,
    )
    assertEquals(
      ServiceErrorCodeV1.BAD_REQUEST,
      branchError(
          service,
          owner,
          UiBuilderBranchRequest.MergeBranch("design-s", acceptOperationIds = emptySet()),
        )
        .code,
      "accepting nothing is a reject",
    )

    val report =
      assertIs<UiBuilderBranchResponse.Merge>(
          execute(
            service,
            owner,
            UiBuilderBranchRequest.MergeBranch("design-s", acceptOperationIds = setOf("s-1")),
          )
        )
        .report
    assertTrue(report.merged)
    assertEquals(listOf("s-1"), report.commands.map { it.operationId })
    assertEquals(listOf("s-2"), report.skippedOperationIds)
    val parent = document(service, "design")
    assertEquals(StringValueV1("Kept"), parent.text("title"))
    assertFalse("extra" in parent.nodes)
  }

  @Test
  fun `an accept that conflicts commits nothing and the report says which command and why`() {
    val service = service(createTempDirectory("suggestions"))
    seed(service)
    grantEditor(service, agent)
    accepted(
      service,
      owner,
      batch("design", "victim", 1, InsertNodeMutationV1(text("victim"), inRoot)),
    )
    suggest(service, agent, "Edit the victim", "design-s")
    accepted(service, agent, setText("design-s", "s-1", 2, "Title"))
    accepted(service, agent, setText("design-s", "s-2", 3, "Victim", nodeId = "victim"))
    // The person deletes the node while the suggestion waits.
    accepted(service, owner, batch("design", "p-1", 2, DeleteNodeMutationV1("victim")))

    val report = merge(service, owner, "design-s")
    assertFalse(report.merged)
    val stop = report.commands.last()
    assertEquals(UiBuilderBranchMergeCommandStatus.REFUSED, stop.status)
    assertEquals("s-2", stop.operationId)
    assertEquals("agent", stop.actorId)
    assertEquals("victim", stop.nodeId)
    assertEquals(3, document(service, "design").revision, "nothing committed")
    assertEquals(UiBuilderBranchStatus.OPEN, get(service, owner, "design-s").status)

    // Resolved by accepting the part that still applies.
    val partial =
      assertIs<UiBuilderBranchResponse.Merge>(
          execute(
            service,
            owner,
            UiBuilderBranchRequest.MergeBranch("design-s", acceptOperationIds = setOf("s-1")),
          )
        )
        .report
    assertTrue(partial.merged, partial.toString())
    assertEquals(StringValueV1("Title"), document(service, "design").text("title"))
  }

  @Test
  fun `suggestions and their outcome survive a restart`() {
    val root = createTempDirectory("suggestions")
    val first = service(root)
    seed(first)
    grantEditor(first, agent)
    suggest(first, agent, "Accept me", "design-s1")
    accepted(first, agent, setText("design-s1", "s1-1", 1, "Accepted"))
    suggest(first, agent, "Reject me", "design-s2")
    branch(first, owner, "A branch", "design-b")

    val second = service(root)
    val open = suggestions(second, owner)
    assertEquals(setOf("design-s1", "design-s2"), open.map { it.branchId }.toSet())
    assertTrue(open.all { it.kind == UiBuilderBranchKind.SUGGESTION })
    assertEquals(UiBuilderBranchKind.BRANCH, get(second, owner, "design-b").kind)
    assertEquals(listOf("s1-1"), get(second, owner, "design-s1").operationIds)
    assertTrue(merge(second, owner, "design-s1").merged)
    reject(second, owner, "design-s2")

    val third = service(root)
    assertEquals(UiBuilderBranchStatus.MERGED, get(third, owner, "design-s1").status)
    assertEquals(UiBuilderBranchStatus.ARCHIVED, get(third, owner, "design-s2").status)
    assertEquals(UiBuilderBranchKind.SUGGESTION, get(third, owner, "design-s2").kind)
    assertEquals(UiBuilderBranchStatus.OPEN, get(third, owner, "design-b").status)
    assertEquals(emptyList(), suggestions(third, owner))
    assertEquals(StringValueV1("Accepted"), document(third, "design").text("title"))
  }

  // ------------------------------------------------------------------------------------ helpers

  private fun suggest(
    service: PersistentUiBuilderService,
    actor: AuthenticatedUiBuilderActor,
    summary: String,
    suggestionId: String,
  ): UiBuilderBranch =
    assertIs<UiBuilderBranchResponse.Branch>(
        execute(
          service,
          actor,
          UiBuilderBranchRequest.CreateBranch(
            "design",
            summary,
            branchId = suggestionId,
            kind = UiBuilderBranchKind.SUGGESTION,
          ),
        )
      )
      .branch

  private fun suggestions(
    service: PersistentUiBuilderService,
    actor: AuthenticatedUiBuilderActor,
  ): List<UiBuilderBranch> =
    list(service, actor, includeClosed = false, kind = UiBuilderBranchKind.SUGGESTION)

  private fun reject(
    service: PersistentUiBuilderService,
    actor: AuthenticatedUiBuilderActor,
    suggestionId: String,
  ): UiBuilderBranch =
    assertIs<UiBuilderBranchResponse.Branch>(
        execute(service, actor, UiBuilderBranchRequest.ArchiveBranch(suggestionId))
      )
      .branch

  private fun list(
    service: PersistentUiBuilderService,
    actor: AuthenticatedUiBuilderActor,
    includeClosed: Boolean = true,
    kind: UiBuilderBranchKind?,
  ): List<UiBuilderBranch> =
    assertIs<UiBuilderBranchResponse.Branches>(
        execute(service, actor, UiBuilderBranchRequest.ListBranches("design", includeClosed, kind))
      )
      .branches

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
