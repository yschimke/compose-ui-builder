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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Moving a design off the two Material 3 ids the synthesised `remote-m3` catalog used to borrow
 * (`m3/text`, `m3/surface`), which the published catalog does not declare. The preview reports the
 * cost before anything moves: `remote-m3/remote-text`, a box with a background, and what neither
 * can carry.
 */
class CatalogUpgradePreviewTest {

  private val owner = AuthenticatedUiBuilderActor("owner")

  // ── the plan, asserted without a service ──────────────────────────────────────────────────────

  @Test
  fun `text moves onto the component the catalog really publishes`() {
    val document =
      document("d")
        .withNode(
          node("label", "m3/text", mapOf("text" to "Discover Weekly", "color" to "#FFFFFF"))
        )
        // A size is a number on both sides (`fontSizeSp` and `fontSize`, via
        // `ComponentRecordPacks.jsonTypeOf`).
        .withProperty("label", "fontSizeSp", DecimalValueV1(14.0))

    val outcome = planCatalogUpgrade(document, remoteM3(), TARGET)

    val moved = outcome.candidate.nodes.getValue("label")
    assertEquals("remote-m3/remote-text", moved.componentId)
    assertEquals(
      setOf("text", "color", "fontSize"),
      moved.properties.keys,
      "what the published catalog declares, and nothing the rename wished into it",
    )
    assertEquals(
      DecimalValueV1(14.0),
      moved.properties["fontSize"],
      "a rename carries the value, it does not reset it",
    )
  }

  @Test
  fun `a property the target has no place for is reported rather than silently lost`() {
    val document =
      document("d")
        .withNode(node("label", "m3/text", mapOf("text" to "hi")))
        .withProperty("label", "letterSpacingSp", DecimalValueV1(0.5))

    val outcome = planCatalogUpgrade(document, remoteM3(), TARGET)

    assertNull(outcome.candidate.nodes.getValue("label").properties["letterSpacingSp"])
    val issue =
      assertNotNull(outcome.issues.singleOrNull { it.code == "PROPERTY_NOT_DECLARED" }, "one issue")
    assertEquals(CatalogUpgradeIssueSeverityV1.WARNING, issue.severity)
    assertEquals("/nodes/label/properties/letterSpacingSp", issue.path)
    assertTrue(
      outcome.changes.any {
        it is RemoveCatalogUpgradeChangeV1 && it.path == "/nodes/label/properties/letterSpacingSp"
      },
      "and it is a change a reviewer can see, not only prose",
    )
  }

  /** The document the question was asked about is never the document that is edited. */
  @Test
  fun `planning does not touch the document it was given`() {
    val document =
      document("d")
        .withNode(node("label", "m3/text", emptyMap()))
        .withProperty("label", "letterSpacingSp", DecimalValueV1(0.5))

    planCatalogUpgrade(document, remoteM3(), TARGET)

    assertEquals("m3/text", document.nodes.getValue("label").componentId)
    assertEquals(setOf("letterSpacingSp"), document.nodes.getValue("label").properties.keys)
  }

  @Test
  fun `a surface becomes a box carrying its colour as a background`() {
    val document =
      document("d")
        .withNode(
          node("panel", "m3/surface", emptyMap())
            .copy(
              properties =
                mapOf(
                  "containerColor" to StringValueV1("#101010"),
                  "shapeDp" to DecimalValueV1(12.0),
                ),
              slots = mapOf("content" to listOf("label")),
            )
        )
        .withNode(node("label", "m3/text", mapOf("text" to "hi")))

    val outcome = planCatalogUpgrade(document, remoteM3(), TARGET)

    val box = outcome.candidate.nodes.getValue("panel")
    assertEquals("layout/box", box.componentId)
    assertEquals(
      listOf(BackgroundModifierV1(StringValueV1("#101010"))),
      box.modifiers,
      "the colour is where a box states a colour: the modifier chain",
    )
    assertEquals(mapOf("children" to listOf("label")), box.slots, "a box fills `children`")
    assertTrue("shapeDp" !in box.properties, "a radius is not a shape token, so it does not move")
    assertTrue(
      outcome.issues.any {
        it.code == "PROPERTY_BECOMES_MODIFIER" && it.severity == CatalogUpgradeIssueSeverityV1.INFO
      },
      "moving a property to the chain is worth saying, but it is not a loss",
    )
  }

  @Test
  fun `a modifier this build cannot write blocks, rather than losing the value it carried`() {
    val document =
      document("d").withNode(node("panel", "m3/surface", mapOf("containerColor" to "#101010")))

    val outcome =
      planCatalogUpgrade(document, remoteM3(containerColorBecomes = "elevation"), TARGET)

    val issue = assertNotNull(outcome.issues.singleOrNull { it.code == "UNSUPPORTED_MODIFIER" })
    assertEquals(CatalogUpgradeIssueSeverityV1.ERROR, issue.severity)
    assertEquals(
      StringValueV1("#101010"),
      outcome.candidate.nodes.getValue("panel").properties["containerColor"],
      "a colour this build cannot move is kept where it is, not quietly dropped",
    )
  }

  @Test
  fun `a split id collapses back onto one component with the style it implied`() {
    // `m3/outlined-card` said "outlined" only by its id; the collapsed `m3/card` says it with
    // `style`. A rename alone would lose it, and the required-property placeholder would then make
    // every card filled.
    val document =
      document("d")
        .withNode(node("card", "m3/outlined-card", mapOf("containerColor" to "#FFFFFF")))
        .withProperty("card", "style", EnumValueV1("elevated"))

    val outcome = planCatalogUpgrade(document, collapsedCards(), COLLAPSED_TARGET)

    val moved = outcome.candidate.nodes.getValue("card")
    assertEquals("m3/card", moved.componentId)
    assertEquals(EnumValueV1("outlined"), moved.properties["style"], "the id's style wins")
    assertEquals(StringValueV1("#FFFFFF"), moved.properties["containerColor"])
    assertTrue(
      outcome.issues.none { it.severity == CatalogUpgradeIssueSeverityV1.ERROR },
      outcome.issues.toString(),
    )
    assertNotNull(outcome.issues.singleOrNull { it.code == "COMPONENT_BECOMES_VARIANT" })
    assertTrue(
      outcome.changes.any {
        it is ReplaceCatalogUpgradeChangeV1 && it.path == "/nodes/card/properties/style"
      },
      outcome.changes.toString(),
    )
  }

  @Test
  fun `a component placement passes through, and its body is upgraded like any node`() {
    // Design-local components: `body` is the declared body, `card` places it. A placement is a
    // document construct the target catalog never declares, so it must not read as an unknown
    // component and block the move (yschimke/compose-ui-builder: the Google tablet samples).
    val document =
      document("d")
        .withNode(node("body", "m3/text", mapOf("text" to "Discover Weekly")))
        .withNode(
          DesignNodeV1(
            "card",
            DESIGN_COMPONENT_INSTANCE_COMPONENT_ID,
            component = DesignComponentInstanceV1("summary", emptyMap()),
          )
        )
        .copy(components = mapOf("summary" to DesignComponentV1("Summary", "body")))

    val outcome = planCatalogUpgrade(document, remoteM3(), TARGET)

    assertTrue(
      outcome.issues.none { it.severity == CatalogUpgradeIssueSeverityV1.ERROR },
      outcome.issues.toString(),
    )
    assertEquals(document.nodes.getValue("card"), outcome.candidate.nodes.getValue("card"))
    assertEquals("remote-m3/remote-text", outcome.candidate.nodes.getValue("body").componentId)
    assertTrue(outcome.changes.none { it.path.startsWith("/nodes/card") }, "nothing to report")
  }

  @Test
  fun `a component with no successor is an error, and the node is left alone`() {
    val document = document("d").withNode(node("chip", "m3/assist-chip", mapOf("label" to "hi")))

    val outcome = planCatalogUpgrade(document, remoteM3(), TARGET)

    val issue = assertNotNull(outcome.issues.singleOrNull { it.code == "UNKNOWN_COMPONENT" })
    assertEquals(CatalogUpgradeIssueSeverityV1.ERROR, issue.severity)
    assertEquals(
      "m3/assist-chip",
      outcome.candidate.nodes.getValue("chip").componentId,
      "deleting somebody's content to make a document validate is never the repair",
    )
  }

  @Test
  fun `a legacy variant selects a concrete successor component`() {
    val target =
      remoteM3()
        .newBuilder()
        .also {
          it.statusSemantics =
            JsonObject(
              mapOf(
                "supersedes" to
                  JsonObject(
                    mapOf(
                      "m3/card" to
                        JsonObject(
                          mapOf(
                            "componentId" to JsonPrimitive("m3/card"),
                            "variants" to
                              JsonObject(
                                mapOf(
                                  "property" to JsonPrimitive("variant"),
                                  "components" to
                                    JsonObject(
                                      mapOf(
                                        "filled" to JsonPrimitive("m3/card"),
                                        "elevated" to JsonPrimitive("m3/elevated-card"),
                                      )
                                    ),
                                )
                              ),
                          )
                        )
                    )
                  )
              )
            )
          it.components =
            listOf(
              component("m3/card", listOf("containerColor")),
              component("m3/elevated-card", listOf("containerColor")),
            )
        }
        .build()
    val document =
      document("d")
        .withNode(
          node("card", "m3/card", mapOf("variant" to "elevated", "containerColor" to "#fff"))
        )

    val outcome = planCatalogUpgrade(document, target, TARGET)
    val card = outcome.candidate.nodes.getValue("card")

    assertEquals("m3/elevated-card", card.componentId)
    assertTrue("variant" !in card.properties)
    assertEquals(StringValueV1("#fff"), card.properties["containerColor"])
    assertTrue(outcome.issues.any { it.code == "VARIANT_BECOMES_COMPONENT" })
  }

  // ── the service ───────────────────────────────────────────────────────────────────────────────

  @Test
  fun `a preview is READY and changes nothing on disk`() {
    val root = createTempDirectory("upgrade")
    val service = service(root)
    create(service, "widget")

    val preview = preview(service, "widget", revision = 0)

    assertEquals(CatalogUpgradePreviewStatusV1.READY, preview.status)
    // Nullable on the wire, because a preview that could not build one still has to answer.
    val candidate = assertNotNull(preview.candidateDocument)
    assertEquals("remote-m3/remote-text", candidate.nodes.getValue("label").componentId)
    val stored =
      checkNotNull(UiBuilderDesignStateStore.open(root).store.load().designs["widget"]).document
    assertEquals(
      "m3/text",
      stored.nodes.getValue("label").componentId,
      "a preview proposes; nothing moves until an apply does it",
    )
    assertEquals(SOURCE, stored.catalogPin, "including the pin")
  }

  @Test
  fun `a design the target cannot draw previews as BLOCKED rather than failing`() {
    val root = createTempDirectory("upgrade")
    val service = service(root)
    create(service, "widget", componentId = "m3/assist-chip")

    val preview = preview(service, "widget", revision = 0)

    assertEquals(CatalogUpgradePreviewStatusV1.BLOCKED, preview.status)
    assertTrue(
      preview.issues.any { it.severity == CatalogUpgradeIssueSeverityV1.ERROR },
      "the refusal travels with the answer",
    )
  }

  /**
   * The designs that need a preview are exactly the ones refused everywhere else, so this is
   * covered at the service level.
   */
  @Test
  fun `a quarantined design can still be asked what moving it would cost`() {
    val root = createTempDirectory("upgrade")
    create(service(root), "widget")

    // Restarted against a catalog that no longer declares what the stored design names, exactly as
    // the live box was after the published-catalog flip.
    val narrowed = service(root, borrowedStillDeclaresText = false)

    assertEquals(
      ServiceErrorCodeV1.INTERNAL,
      assertIs<UiBuilderServiceResponse.Error>(
          execute(narrowed, owner, UiBuilderServiceRequest.OpenDesign("widget"))
        )
        .error
        .code,
      "the design is unusable, which is what makes this the case that matters",
    )
    val preview = preview(narrowed, "widget", revision = 0)
    assertEquals(CatalogUpgradePreviewStatusV1.READY, preview.status)
    assertEquals(
      "remote-m3/remote-text",
      assertNotNull(preview.candidateDocument).nodes.getValue("label").componentId,
    )
  }

  @Test
  fun `an owner can preview and apply recovery after the old pin disappears`() {
    val root = createTempDirectory("upgrade")
    create(service(root), "widget")
    val stranded = service(root, sourceAvailable = false)

    assertEquals(
      ServiceErrorCodeV1.CATALOG_UNAVAILABLE,
      assertIs<UiBuilderServiceResponse.Error>(
          execute(stranded, owner, UiBuilderServiceRequest.OpenDesign("widget"))
        )
        .error
        .code,
    )
    val preview =
      assertIs<UiBuilderServiceResponse.CatalogUpgradePreview>(
          execute(
            stranded,
            owner,
            UiBuilderServiceRequest.PreviewCurrentCatalogUpgrade("widget"),
          )
        )
        .preview
    assertEquals(CatalogUpgradePreviewStatusV1.READY, preview.status)
    assertEquals(SOURCE, preview.sourceCatalogPin)
    assertEquals(TARGET, preview.targetCatalogPin)

    val outcome =
      assertIs<UiBuilderServiceResponse.OperationOutcome>(
          execute(
            stranded,
            owner,
            UiBuilderServiceRequest.ApplyOperation(
              UiBuilderSubmission.Batch(
                designId = "widget",
                operationId = "recover-pin",
                clientId = "browser",
                baseRevision = preview.baseRevision,
                operations = listOf(preview.mutation()),
              )
            ),
          )
        )
        .outcome
    assertIs<AcceptedOutcomeV1>(outcome)
    val opened =
      assertIs<UiBuilderServiceResponse.Snapshot>(
        execute(stranded, owner, UiBuilderServiceRequest.OpenDesign("widget"))
      )
    assertEquals(TARGET, opened.snapshot.state.document.catalogPin)
    assertEquals(1, opened.snapshot.state.document.revision)
    assertEquals(0, stranded.diagnostics().unusableDesigns)
  }

  @Test
  fun `recovery refuses a preview digest that was not the one shown`() {
    val root = createTempDirectory("upgrade")
    create(service(root), "widget")
    val stranded = service(root, sourceAvailable = false)
    val preview =
      assertIs<UiBuilderServiceResponse.CatalogUpgradePreview>(
          execute(
            stranded,
            owner,
            UiBuilderServiceRequest.PreviewCurrentCatalogUpgrade("widget"),
          )
        )
        .preview

    val outcome =
      assertIs<UiBuilderServiceResponse.OperationOutcome>(
          execute(
            stranded,
            owner,
            UiBuilderServiceRequest.ApplyOperation(
              UiBuilderSubmission.Batch(
                "widget",
                "tampered-recovery",
                "browser",
                preview.baseRevision,
                listOf(preview.mutation().copy(previewDigest = "not-the-preview")),
              )
            ),
          )
        )
        .outcome
    assertIs<RejectedOutcomeV1>(outcome)
    assertEquals(1, stranded.diagnostics().unusableDesigns)
  }

  @Test
  fun `a design broken in itself stays refused, because no catalog move repairs it`() {
    val root = createTempDirectory("upgrade")
    create(service(root), "widget")
    // A node naming itself as its own child: a topology fault, not a catalog one.
    val store = UiBuilderDesignStateStore.open(root).store
    val stored = checkNotNull(store.load().designs["widget"])
    val cyclic =
      stored.document.copy(
        nodes =
          stored.document.nodes.mapValues { (id, node) ->
            node.copy(slots = mapOf("children" to listOf(id)))
          }
      )
    store.commitAll(mapOf("widget" to (stored to stored.copy(document = cyclic))))

    val response =
      execute(
        service(root),
        owner,
        UiBuilderServiceRequest.PreviewCatalogUpgrade("widget", 0, SOURCE, TARGET),
      )

    assertEquals(
      ServiceErrorCodeV1.INTERNAL,
      assertIs<UiBuilderServiceResponse.Error>(response).error.code,
    )
  }

  @Test
  fun `a stale base revision is refused, because the candidate would describe another document`() {
    val root = createTempDirectory("upgrade")
    val service = service(root)
    create(service, "widget")

    val response =
      execute(
        service,
        owner,
        UiBuilderServiceRequest.PreviewCatalogUpgrade("widget", 99, SOURCE, TARGET),
      )

    assertEquals(
      ServiceErrorCodeV1.BAD_REQUEST,
      assertIs<UiBuilderServiceResponse.Error>(response).error.code,
    )
  }

  // ── harness ───────────────────────────────────────────────────────────────────────────────────

  /**
   * The published catalog's vocabulary, narrowed to these cases. The `supersedes` block is the
   * fixture's own (the runtime names no catalog's successors): `RemoteText` for borrowed text, a
   * box with a background for the surface.
   */
  private fun remoteM3(containerColorBecomes: String = "background"): CatalogCapabilityV1 =
    CatalogCapabilityV1.Builder(
        "compose-catalog-capabilities/v1",
        CatalogBenchmarkV1.Builder(
            "remote-m3",
            "source",
            "remote-m3",
            "published",
            "remote-runtime",
          )
          .build(),
        listOf(
          component(
            "remote-m3/remote-text",
            // What the published catalog declares: `jsonTypeOf` drops parameters without a JSON
            // type (`RemoteTextStyle`, text enums), while `RemoteTextUnit` maps to `fontSize`.
            listOf("text", "color", "maxLines", "fontSize"),
          ),
          component("layout/box", listOf("contentAlignment")),
        ),
      )
      .also {
        it.statusSemantics =
          JsonObject(
            mapOf(
              "supersedes" to
                JsonObject(
                  mapOf(
                    "m3/text" to
                      JsonObject(
                        mapOf(
                          "componentId" to JsonPrimitive("remote-m3/remote-text"),
                          "properties" to
                            JsonObject(mapOf("fontSizeSp" to JsonPrimitive("fontSize"))),
                        )
                      ),
                    "m3/surface" to
                      JsonObject(
                        mapOf(
                          "componentId" to JsonPrimitive("layout/box"),
                          "slots" to JsonObject(mapOf("content" to JsonPrimitive("children"))),
                          "modifiers" to
                            JsonObject(
                              mapOf("containerColor" to JsonPrimitive(containerColorBecomes))
                            ),
                        )
                      ),
                  )
                )
            )
          )
        it.exportCapabilities =
          ExportCapabilitiesV1.Builder()
            .also {
              it.composeCode = true
              it.svg = true
              it.png = true
            }
            .build()
      }
      .build()

  /** A catalog that collapses `m3/outlined-card` onto `m3/card` with `style = outlined`. */
  private fun collapsedCards(): CatalogCapabilityV1 =
    CatalogCapabilityV1.Builder(
        "compose-catalog-capabilities/v1",
        CatalogBenchmarkV1.Builder("m3-catalog", "source", "m3-catalog", "published", "m3-runtime")
          .build(),
        listOf(component("m3/card", listOf("style", "containerColor"))),
      )
      .also {
        it.statusSemantics =
          JsonObject(
            mapOf(
              "supersedes" to
                JsonObject(
                  mapOf(
                    "m3/outlined-card" to
                      JsonObject(
                        mapOf(
                          "componentId" to JsonPrimitive("m3/card"),
                          "setProperties" to
                            JsonObject(mapOf("style" to JsonPrimitive("outlined"))),
                        )
                      )
                  )
                )
            )
          )
        it.exportCapabilities =
          ExportCapabilitiesV1.Builder()
            .also {
              it.composeCode = true
              it.svg = true
              it.png = true
            }
            .build()
      }
      .build()

  private fun component(id: String, properties: List<String>) =
    ComponentCapabilityV1.Builder(
        id,
        id,
        if (id == "layout/box") "Container" else "Leaf",
        WasmCapabilityV1.Builder(JsonPrimitive(true), WasmAdapterStatusV1.SUPPORTED).build(),
      )
      .also {
        it.properties = properties.map {
          PropertyCapabilityV1.Builder(it, JsonPrimitive(if (it in NUMERIC) "number" else "string"))
            .also { it.required = false }
            .build()
        }
      }
      .build()

  /**
   * Serves the published catalog under [TARGET] and the borrowed vocabulary under [SOURCE].
   * [borrowedStillDeclaresText] false is the live situation: the stored pin resolves to a catalog
   * without `m3/text`, so the design is quarantined at boot.
   */
  private inner class TwoPinCatalogs(
    private val borrowedStillDeclaresText: Boolean = true,
    private val sourceAvailable: Boolean = true,
  ) : UiBuilderCatalogExecutor {
    private val published = remoteM3()
    private val borrowed =
      published
        .newBuilder()
        .also {
          it.benchmark =
            published.benchmark.newBuilder().also { it.catalogRevision = "candidate" }.build()
          it.components =
            published.components +
              listOfNotNull(
                component("m3/text", listOf("text", "color", "fontSizeSp", "letterSpacingSp"))
                  .takeIf { borrowedStillDeclaresText }
              ) +
              component("m3/assist-chip", listOf("text"))
        }
        .build()

    override fun listCatalogs(): List<CatalogCapabilityV1> = listOf(published)

    override fun resolve(reference: CatalogReferenceV1): CatalogCapabilityV1? =
      when (reference) {
        TARGET -> published
        SOURCE -> borrowed.takeIf { sourceAvailable }
        else -> null
      }

    override fun reference(catalog: CatalogCapabilityV1): CatalogReferenceV1? =
      when (catalog) {
        published -> TARGET
        borrowed -> SOURCE
        else -> null
      }

    override fun validate(
      document: DesignDocumentV1,
      catalog: CatalogCapabilityV1,
    ): UiBuilderCatalogIssue? {
      val declared = catalog.components.associateBy { it.componentId }
      document.nodes.values.forEach { node ->
        val component =
          declared[node.componentId]
            ?: return UiBuilderCatalogIssue(
              "UNKNOWN_COMPONENT",
              "component ${node.componentId} is not in ${catalog.benchmark.catalogSystemId}",
              node.id,
            )
        val declaredProperties = component.properties.associateBy { it.name }
        node.properties.keys
          .firstOrNull { it !in declaredProperties }
          ?.let {
            return UiBuilderCatalogIssue(
              "UNKNOWN_PROPERTY",
              "property $it is not declared by ${node.componentId}",
              node.id,
              it,
            )
          }
        // The type rule, because a plan that moves a value into a property of another type is a
        // candidate production would refuse. Production compares `jsonType` against the JSON the
        // value unwraps to; two kinds is all this fixture carries, so this compares those.
        node.properties.forEach { (name, value) ->
          val wants = declaredProperties.getValue(name).jsonType.toString().trim('"')
          val isNumber = value is DecimalValueV1 || value is IntegerValueV1
          if ((wants == "number") != isNumber) {
            return UiBuilderCatalogIssue(
              "INVALID_PROPERTY_VALUE",
              "property $name on ${node.componentId} wants $wants",
              node.id,
              name,
            )
          }
        }
      }
      return null
    }
  }

  private fun service(
    root: Path,
    borrowedStillDeclaresText: Boolean = true,
    sourceAvailable: Boolean = true,
  ): PersistentUiBuilderService =
    PersistentUiBuilderService(
      designStore = UiBuilderDesignStateStore.open(root),
      catalogs = TwoPinCatalogs(borrowedStillDeclaresText, sourceAvailable),
      exporter = UiBuilderExportExecutor { error("no export in this test") },
      clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
    )

  private fun create(
    service: PersistentUiBuilderService,
    designId: String,
    componentId: String = "m3/text",
  ) {
    val document =
      document(designId).withNode(node("label", componentId, mapOf("text" to "Discover Weekly")))
    assertIs<UiBuilderServiceResponse.Snapshot>(
      execute(service, owner, UiBuilderServiceRequest.CreateDesign(document))
    )
  }

  private fun preview(
    service: PersistentUiBuilderService,
    designId: String,
    revision: Long,
  ): CatalogUpgradePreviewV1 =
    assertIs<UiBuilderServiceResponse.CatalogUpgradePreview>(
        execute(
          service,
          owner,
          UiBuilderServiceRequest.PreviewCatalogUpgrade(designId, revision, SOURCE, TARGET),
        )
      )
      .preview

  private fun CatalogUpgradePreviewV1.mutation(): CatalogUpgradeMutationV1 =
    CatalogUpgradeMutationV1(
      sourceCatalogPin = sourceCatalogPin,
      targetCatalogPin = targetCatalogPin,
      sourceDocumentHash = sourceDocumentHash,
      targetDocumentHash = assertNotNull(candidateDocumentHash),
      previewDigest = previewDigest,
    )

  private fun node(id: String, componentId: String, properties: Map<String, String>) =
    DesignNodeV1(
      id = id,
      componentId = componentId,
      properties = properties.mapValues { (_, v) -> StringValueV1(v) },
    )

  private fun DesignDocumentV1.withNode(node: DesignNodeV1): DesignDocumentV1 =
    copy(nodes = nodes + (node.id to node), roots = if (roots.isEmpty()) listOf(node.id) else roots)

  private fun document(designId: String): DesignDocumentV1 =
    DesignDocumentV1(
      schema = "compose-ui-builder/v1",
      id = designId,
      title = designId,
      revision = 0,
      catalogPin = SOURCE,
      environment =
        DesignEnvironmentV1(
          widthDp = 216,
          heightDp = 124,
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
    return assertNotNull(completion, "suspend function did not complete").getOrThrow()
  }

  private fun DesignDocumentV1.withProperty(
    nodeId: String,
    property: String,
    value: UiValueV1,
  ): DesignDocumentV1 {
    val node = nodes.getValue(nodeId)
    return copy(
      nodes = nodes + (nodeId to node.copy(properties = node.properties + (property to value)))
    )
  }

  private companion object {
    /** The properties this fixture declares as `number`, on whichever component carries them. */
    private val NUMERIC = setOf("fontSizeSp", "fontSize", "letterSpacingSp", "shapeDp")

    private val SOURCE = CatalogReferenceV1("remote-m3", "candidate", "candidate", "remote-runtime")
    private val TARGET = CatalogReferenceV1("remote-m3", "published", "published", "remote-runtime")
    private val COLLAPSED_TARGET =
      CatalogReferenceV1("m3-catalog", "published", "published", "m3-runtime")
  }
}
