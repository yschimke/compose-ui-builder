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
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * A catalog that starts REQUIRING a property does not strand the designs written before it did.
 *
 * On preview.coo.ee the record-built `m3/primary-tab-row` made `selectedTabIndex` required
 * (Compose's `PrimaryTabRow` has no default for it). Five Google1P tablet designs drawn against the
 * Kotlin catalog that left it optional became unopenable: one as `invalid stored design
 * google-play-tablet: required property selectedTabIndex is missing`, and four as `catalog
 * unavailable` -- their pins had moved on, and the automatic catalog recovery left them alone
 * because every upgrade preview came back BLOCKED on the same missing property.
 */
class NewlyRequiredPropertyTest {

  private val owner = AuthenticatedUiBuilderActor("owner")

  // ── the placeholder, asserted without a service ───────────────────────────────────────────────

  @Test
  fun `a required scalar is given the generator's zero, first scalar type winning`() {
    assertEquals(IntegerValueV1(0), requiredPlaceholder(property("integer")))
    assertEquals(DecimalValueV1(0.0), requiredPlaceholder(property("number")))
    assertEquals(BooleanValueV1(false), requiredPlaceholder(property("boolean")))
    assertEquals(StringValueV1(""), requiredPlaceholder(property("string")))
    assertEquals(
      IntegerValueV1(0),
      requiredPlaceholder(
        property(JsonArray(listOf(JsonPrimitive("object"), JsonPrimitive("integer"))))
      ),
      "a union takes its scalar alternative, not the object",
    )
  }

  @Test
  fun `a required property with allowed values takes the first one`() {
    assertEquals(
      EnumValueV1("start"),
      requiredPlaceholder(
        property("string", allowed = listOf(JsonPrimitive("start"), JsonPrimitive("end")))
      ),
    )
  }

  @Test
  fun `a required colour is a drawable theme role, and a required asset key gets nothing`() {
    for (name in listOf("color", "containerColor")) {
      val placeholder = requiredPlaceholder(property("string", name = name))
      // A `string` zero passed validation and was refused at write and export.
      assertEquals(ColorTokenValueV1("onSurface"), placeholder, name)
      assertTrue(
        (placeholder as ColorTokenValueV1).value in
          CurrentM3UiBuilderCatalogExecutor.CANVAS_COLOR_TOKENS
      )
    }
    assertNull(
      requiredPlaceholder(property("string", name = "assetKey")),
      "no key anybody could guess resolves",
    )
  }

  @Test
  fun `a required colour constrained by allowed values takes the first allowed token`() {
    assertEquals(
      ColorTokenValueV1("primary"),
      requiredPlaceholder(
        property(
          "string",
          name = "containerColor",
          allowed = listOf(JsonPrimitive("primary"), JsonPrimitive("secondary")),
        )
      ),
    )
  }

  @Test
  fun `a required object or list has no placeholder`() {
    assertNull(requiredPlaceholder(property("object")), "no zero means anything for an object")
    assertNull(requiredPlaceholder(property("array")))
  }

  @Test
  fun `only missing required properties of declared components are filled`() {
    val document =
      document("d")
        .withNode(node("row", TABS, emptyMap()))
        .withNode(node("set", TABS, mapOf("selectedTabIndex" to IntegerValueV1(2))))
        .withNode(node("foreign", "other/widget", emptyMap()))

    val (filled, changed) = document.withRequiredPlaceholders(catalog(required = true))

    assertEquals(
      listOf(FilledRequiredProperty("row", "selectedTabIndex", IntegerValueV1(0))),
      changed,
    )
    assertEquals(IntegerValueV1(0), filled.nodes.getValue("row").properties["selectedTabIndex"])
    assertEquals(
      IntegerValueV1(2),
      filled.nodes.getValue("set").properties["selectedTabIndex"],
      "a value the author set is never replaced",
    )
    assertTrue(filled.nodes.getValue("foreign").properties.isEmpty(), "not the catalog's to judge")
  }

  @Test
  fun `a document with nothing missing is returned by identity`() {
    val document =
      document("d").withNode(node("row", TABS, mapOf("selectedTabIndex" to IntegerValueV1(1))))
    assertSame(document, document.withRequiredPlaceholders(catalog(required = true)).first)
  }

  // ── the upgrade plan: what lets the automatic recovery move a stranded design ─────────────────

  @Test
  fun `an upgrade onto a catalog that requires the property fills it, with a warning, and is not blocked`() {
    val document = document("d").withNode(node("row", TABS, emptyMap()))
    val target = catalog(required = true)

    val outcome = planCatalogUpgrade(document, target, TARGET_PIN)

    assertEquals(
      IntegerValueV1(0),
      outcome.candidate.nodes.getValue("row").properties["selectedTabIndex"],
    )
    assertTrue(
      outcome.issues.none { it.severity == CatalogUpgradeIssueSeverityV1.ERROR },
      "${outcome.issues}",
    )
    val warning = outcome.issues.single { it.code == "REQUIRED_PROPERTY_FILLED" }
    assertEquals(CatalogUpgradeIssueSeverityV1.WARNING, warning.severity)
    assertEquals("/nodes/row/properties/selectedTabIndex", warning.path)
    assertTrue(
      outcome.changes.any {
        it is AddCatalogUpgradeChangeV1 && it.path == "/nodes/row/properties/selectedTabIndex"
      },
      "the fill is a change a person can read, not a silent edit",
    )
  }

  // ── the stored design whose pin still resolves ────────────────────────────────────────────────

  @Test
  fun `a stored design whose only fault is a newly required property opens, and the fill is stored`() {
    val root = createTempDirectory("newly-required")
    create(service(root, required = false), "tablet")

    val opened = openedDocument(service(root, required = true), "tablet")

    assertEquals(IntegerValueV1(0), opened.nodes.getValue("row").properties["selectedTabIndex"])
    val stored = checkNotNull(UiBuilderDesignStateStore.open(root).store.load().designs["tablet"])
    assertEquals(
      IntegerValueV1(0),
      stored.document.nodes.getValue("row").properties["selectedTabIndex"],
      "written through like a moved pin, so the next start does not do it again",
    )
    // And the retained snapshot of that revision, which `GetSnapshot` and a default export read:
    // one revision, one document.
    val retained =
      stored.revisionSnapshots.single { it.document.revision == stored.document.revision }
    assertEquals(
      IntegerValueV1(0),
      retained.document.nodes.getValue("row").properties["selectedTabIndex"],
    )
  }

  @Test
  fun `a design with another defect keeps its refusal and is not touched`() {
    val root = createTempDirectory("newly-required")
    create(service(root, required = false, withWidget = true), "tablet", widget = true)

    // The later catalog requires the property AND no longer has the widget: an unknown component is
    // a real defect, so the fill must not paper over it or be written.
    val gone = service(root, required = true, withWidget = false)

    val refused =
      assertIs<UiBuilderServiceResponse.Error>(
        execute(gone, owner, UiBuilderServiceRequest.OpenDesign("tablet"))
      )
    assertEquals(ServiceErrorCodeV1.INTERNAL, refused.error.code)
    val stored = checkNotNull(UiBuilderDesignStateStore.open(root).store.load().designs["tablet"])
    assertTrue(
      "selectedTabIndex" !in stored.document.nodes.getValue("row").properties,
      "a fill is only written when it is the ONLY thing wrong",
    )
  }

  // ── harness ───────────────────────────────────────────────────────────────────────────────────

  private fun property(
    type: String,
    allowed: List<JsonElement> = emptyList(),
    name: String = "p",
  ) = property(JsonPrimitive(type), allowed, name)

  private fun property(
    type: JsonElement,
    allowed: List<JsonElement> = emptyList(),
    name: String = "p",
  ) =
    PropertyCapabilityV1.Builder(name, type)
      .also {
        it.required = true
        it.allowedValues = allowed
      }
      .build()

  private fun catalog(required: Boolean, withWidget: Boolean = false): CatalogCapabilityV1 =
    CatalogCapabilityV1.Builder(
        "compose-catalog-capabilities/v1",
        CatalogBenchmarkV1.Builder("m3", "source", "m3", "candidate", "m3-runtime").build(),
        listOf(
          ComponentCapabilityV1.Builder(
              TABS,
              TABS,
              "tabs",
              WasmCapabilityV1.Builder(JsonPrimitive(true), WasmAdapterStatusV1.SUPPORTED).build(),
            )
            .also {
              it.properties =
                listOf(
                  PropertyCapabilityV1.Builder("selectedTabIndex", JsonPrimitive("integer"))
                    .also { it.required = required }
                    .build()
                )
            }
            .build()
        ) +
          listOfNotNull(
            if (!withWidget) null
            else
              ComponentCapabilityV1.Builder(
                  WIDGET,
                  WIDGET,
                  "widget",
                  WasmCapabilityV1.Builder(JsonPrimitive(true), WasmAdapterStatusV1.SUPPORTED)
                    .build(),
                )
                .build()
          ),
      )
      .build()

  /** Mirrors the production validator on the rules this test is about: required, and declared. */
  private class RequiringCatalogs(private val catalog: CatalogCapabilityV1) :
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
              "unknown ${node.componentId}",
              node.id,
            )
        component.properties
          .firstOrNull { it.required && it.name !in node.properties }
          ?.let {
            return UiBuilderCatalogIssue(
              "MISSING_REQUIRED_PROPERTY",
              "required property ${it.name} is missing",
              node.id,
              it.name,
            )
          }
      }
      return null
    }
  }

  private fun service(
    root: Path,
    required: Boolean,
    withWidget: Boolean = false,
  ): PersistentUiBuilderService =
    PersistentUiBuilderService(
      designStore = UiBuilderDesignStateStore.open(root),
      catalogs = RequiringCatalogs(catalog(required, withWidget)),
      exporter = UiBuilderExportExecutor { error("no export in this test") },
      clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
    )

  private fun create(
    service: PersistentUiBuilderService,
    designId: String,
    widget: Boolean = false,
  ) {
    var document = document(designId).withNode(node("row", TABS, emptyMap()))
    if (widget) {
      // One root per design: the widget holds the tab row in a slot, so dropping the widget's
      // component from the catalog leaves an unknown component above a merely incomplete row.
      val holder =
        DesignNodeV1(id = "w", componentId = WIDGET, slots = mapOf("content" to listOf("row")))
      document = document.copy(nodes = document.nodes + ("w" to holder), roots = listOf("w"))
    }
    val created = execute(service, owner, UiBuilderServiceRequest.CreateDesign(document))
    if (created is UiBuilderServiceResponse.Error) error("create refused: ${created.error.message}")
    assertIs<UiBuilderServiceResponse.Snapshot>(created)
  }

  private fun openedDocument(service: PersistentUiBuilderService, designId: String) =
    assertIs<UiBuilderServiceResponse.Snapshot>(
        execute(service, owner, UiBuilderServiceRequest.OpenDesign(designId))
      )
      .snapshot
      .state
      .document

  private fun node(id: String, componentId: String, properties: Map<String, UiValueV1>) =
    DesignNodeV1(id = id, componentId = componentId, properties = properties)

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
    return checkNotNull(completion).getOrThrow()
  }

  private companion object {
    const val TABS = "m3/primary-tab-row"
    const val WIDGET = "m3/widget"
    val PIN = CatalogReferenceV1("m3", "source", "candidate", "m3-runtime")
    val TARGET_PIN = CatalogReferenceV1("m3", "next", "candidate", "m3-runtime")
  }
}
