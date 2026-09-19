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
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

/**
 * A catalog that stops declaring a property degrades its designs instead of killing them.
 *
 * Thirty-six designs on `preview.coo.ee` were refused outright after the catalog source flip, and
 * twenty-six of those for nothing worse than `property containerColor is not declared by
 * m3/surface`. The value was never lost — it is still in the stored document — but `unusableReason`
 * treated the verdict as fatal, so every request naming the design was refused and the design was
 * gone.
 *
 * What must hold, and each has a case here: the design opens, it stays EDITABLE (opening a design
 * that refuses every edit is a worse place to be than plainly unusable), the stored value survives,
 * a newly written undeclared property is still refused, and an unknown component is still fatal
 * because there is nothing to draw.
 */
class UndeclaredPropertyToleranceTest {

  private val owner = AuthenticatedUiBuilderActor("owner")

  // ── the pure decision, asserted without a service ──────────────────────────────────────────────

  @Test
  fun `undeclared properties are found per node, and only for components the catalog has`() {
    val document =
      document("d")
        .withNode(node("kept", TEXT, mapOf("text" to "hi")))
        .withNode(node("degraded", TEXT, mapOf("text" to "hi", "tint" to "#FF0000")))
        .withNode(node("foreign", "other/widget", mapOf("anything" to "1")))

    val found = undeclaredProperties(document, catalogDeclaring("text"))

    assertEquals(
      mapOf("degraded" to mapOf("tint" to StringValueV1("#FF0000"))),
      found,
      "only the undeclared name, only its node, carrying the value that is tolerated",
    )
  }

  @Test
  fun `a property whose value has moved on is not the one that was tolerated`() {
    val document = document("d").withNode(node("a", TEXT, mapOf("tint" to "#00FF00")))

    val probe = document.withoutProperties(mapOf("a" to mapOf("tint" to StringValueV1("#FF0000"))))

    assertEquals(
      setOf("tint"),
      probe.nodes.getValue("a").properties.keys,
      "tolerance is for the value the design already held, not for the name",
    )
  }

  @Test
  fun `a document with nothing to drop is returned by identity`() {
    val document = document("d").withNode(node("a", TEXT, mapOf("text" to "hi")))

    assertSame(document, document.withoutProperties(emptyMap()))
  }

  @Test
  fun `dropping takes the named property and leaves every sibling alone`() {
    val document =
      document("d")
        .withNode(node("a", TEXT, mapOf("text" to "hi", "tint" to "#FF0000")))
        .withNode(node("b", TEXT, mapOf("text" to "there", "tint" to "#00FF00")))

    val probe = document.withoutProperties(mapOf("a" to mapOf("tint" to StringValueV1("#FF0000"))))

    assertEquals(setOf("text"), probe.nodes.getValue("a").properties.keys)
    assertEquals(setOf("text", "tint"), probe.nodes.getValue("b").properties.keys, "untouched")
    assertEquals(
      setOf("text", "tint"),
      document.nodes.getValue("a").properties.keys,
      "the probe is a copy; the document it was asked about is not edited",
    )
  }

  // ── the service behaviour ─────────────────────────────────────────────────────────────────────

  @Test
  fun `a design whose only fault is an undeclared property opens`() {
    val root = createTempDirectory("undeclared")
    create(service(root, declares = listOf("text", "tint")), "widget", withTint = true)

    val narrowed = service(root, declares = listOf("text"))

    val label = openedDocument(narrowed, "widget").nodes.getValue("label")

    assertTrue(
      "tint" in label.properties,
      "the design opens, and opens carrying the value the catalog stopped declaring",
    )
  }

  @Test
  fun `a degraded design is counted and named with each stale node property`() {
    val root = createTempDirectory("undeclared")
    create(service(root, declares = listOf("text", "tint")), "widget", withTint = true)

    val narrowed = service(root, declares = listOf("text"))

    assertEquals(1, narrowed.diagnostics().degradedDesigns)
    assertEquals(
      mapOf("widget" to "catalog no longer declares properties on node `label`: `tint`"),
      narrowed.adminDegradedDesigns(),
    )
    assertEquals(0, narrowed.diagnostics().unusableDesigns)
  }

  /** The claim the whole approach rests on: nothing is parked, moved or dropped on disk. */
  @Test
  fun `the stored document keeps the undeclared property`() {
    val root = createTempDirectory("undeclared")
    create(service(root, declares = listOf("text", "tint")), "widget", withTint = true)

    openedDocument(service(root, declares = listOf("text")), "widget")

    val stored =
      checkNotNull(UiBuilderDesignStateStore.open(root).store.load().designs["widget"])
        .document
        .nodes
        .getValue("label")
    assertTrue("tint" in stored.properties, "the value has to survive for a catalog to restore it")
  }

  /** Opening a design that then refuses every edit is worse than plainly unusable. */
  @Test
  fun `a design the catalog outgrew can still be edited`() {
    val root = createTempDirectory("undeclared")
    create(service(root, declares = listOf("text", "tint")), "widget", withTint = true)

    val narrowed = service(root, declares = listOf("text"))
    val outcome = applyText(narrowed, "widget", "label", "edited")

    assertIs<AcceptedOutcomeV1>(
      outcome,
      "a pre-existing undeclared property must not block a write",
    )
  }

  @Test
  fun `REWRITING a tolerated property is refused`() {
    val root = createTempDirectory("undeclared")
    create(service(root, declares = listOf("text", "tint")), "widget", withTint = true)

    val narrowed = service(root, declares = listOf("text"))
    val outcome = applyProperty(narrowed, "widget", "label", "tint", "#00FF00")

    assertIs<RejectedOutcomeV1>(
      outcome,
      "the old value is carried, not authored against; a new one is authoring against what the " +
        "catalog lacks",
    )
  }

  @Test
  fun `writing a NEW undeclared property is still refused`() {
    val root = createTempDirectory("undeclared")
    create(service(root, declares = listOf("text")), "widget", withTint = false)

    val outcome =
      applyProperty(service(root, declares = listOf("text")), "widget", "label", "tint", "#123456")

    assertIs<RejectedOutcomeV1>(
      outcome,
      "tolerance is for what a catalog took away, never a licence to author against what it lacks",
    )
  }

  @Test
  fun `an unknown component is still fatal`() {
    val root = createTempDirectory("undeclared")
    create(service(root, declares = listOf("text", "tint")), "widget", withTint = true)

    // The catalog keeps the property but loses the component the design is built from.
    val gone = service(root, declares = listOf("text", "tint"), draws = "m3/label")

    val refused =
      assertIs<UiBuilderServiceResponse.Error>(
        execute(gone, owner, UiBuilderServiceRequest.OpenDesign("widget"))
      )
    assertEquals(ServiceErrorCodeV1.INTERNAL, refused.error.code)
  }

  // ── harness ───────────────────────────────────────────────────────────────────────────────────

  private fun catalogDeclaring(vararg properties: String): CatalogCapabilityV1 =
    catalogDeclaring(properties.toList(), TEXT)

  private fun catalogDeclaring(properties: List<String>, draws: String): CatalogCapabilityV1 =
    CatalogCapabilityV1(
      schema = "compose-catalog-capabilities/v1",
      benchmark = CatalogBenchmarkV1("m3", "source", "m3", "candidate", "m3-runtime"),
      components =
        listOf(
          ComponentCapabilityV1(
            componentId = draws,
            displayName = draws,
            role = "text",
            properties =
              properties.map {
                PropertyCapabilityV1(
                  name = it,
                  jsonType = JsonPrimitive("string"),
                  required = false,
                )
              },
            wasm =
              WasmCapabilityV1.Builder(JsonPrimitive(true), WasmAdapterStatusV1.SUPPORTED).build(),
          )
        ),
      exportCapabilities = ExportCapabilitiesV1(composeCode = true, svg = true, png = true),
    )

  /** Mirrors the production validator on the two rules this test is about. */
  private class NarrowingCatalogs(private val catalog: CatalogCapabilityV1) :
    UiBuilderCatalogExecutor {
    override fun listCatalogs(): List<CatalogCapabilityV1> = listOf(catalog)

    override fun resolve(reference: CatalogReferenceV1): CatalogCapabilityV1? = catalog.takeIf {
      reference == PIN
    }

    override fun reference(catalog: CatalogCapabilityV1): CatalogReferenceV1? = PIN.takeIf {
      catalog == this.catalog
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
              "component ${node.componentId} is not in m3",
              node.id,
            )
        val names = component.properties.mapTo(mutableSetOf()) { it.name }
        node.properties.keys
          .firstOrNull { it !in names }
          ?.let {
            return UiBuilderCatalogIssue(
              "UNKNOWN_PROPERTY",
              "property $it is not declared by ${node.componentId}",
              node.id,
              it,
            )
          }
      }
      return null
    }
  }

  private fun service(
    root: Path,
    declares: List<String>,
    draws: String = TEXT,
  ): PersistentUiBuilderService =
    PersistentUiBuilderService(
      designStore = UiBuilderDesignStateStore.open(root),
      catalogs = NarrowingCatalogs(catalogDeclaring(declares, draws)),
      exporter = UiBuilderExportExecutor { error("no export in this test") },
      clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
    )

  private fun create(service: PersistentUiBuilderService, designId: String, withTint: Boolean) {
    val properties =
      if (withTint) mapOf("text" to "hi", "tint" to "#FF0000") else mapOf("text" to "hi")
    val document = document(designId).withNode(node("label", TEXT, properties))
    assertIs<UiBuilderServiceResponse.Snapshot>(
      execute(service, owner, UiBuilderServiceRequest.CreateDesign(document))
    )
  }

  private fun applyText(
    service: PersistentUiBuilderService,
    designId: String,
    nodeId: String,
    value: String,
  ) = applyProperty(service, designId, nodeId, "text", value)

  private fun applyProperty(
    service: PersistentUiBuilderService,
    designId: String,
    nodeId: String,
    property: String,
    value: String,
  ): CommandOutcomeV1 {
    val response =
      execute(
        service,
        owner,
        UiBuilderServiceRequest.ApplyOperation(
          UiBuilderSubmission.Batch(
            designId,
            "op-$property",
            "browser",
            0,
            listOf(
              SetPropertyMutationV1(
                nodeId = nodeId,
                property = property,
                value = StringValueV1(value),
              )
            ),
          )
        ),
      )
    return assertIs<UiBuilderServiceResponse.OperationOutcome>(response).outcome
  }

  private fun openedDocument(service: PersistentUiBuilderService, designId: String) =
    assertIs<UiBuilderServiceResponse.Snapshot>(
        execute(service, owner, UiBuilderServiceRequest.OpenDesign(designId))
      )
      .snapshot
      .state
      .document

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
      catalogPin = PIN,
      environment =
        DesignEnvironmentV1(
          widthDp = 400,
          heightDp = 400,
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

  private companion object {
    private const val TEXT = "m3/text"
    private val PIN = CatalogReferenceV1("m3", "candidate", "candidate", "m3-runtime")
  }
}
