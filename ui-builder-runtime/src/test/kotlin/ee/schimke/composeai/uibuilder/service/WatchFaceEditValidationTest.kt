package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlExporter
import ee.schimke.composeai.uibuilder.export.toUiBuilderDocument
import ee.schimke.composeai.uibuilder.protocol.AcceptedOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.ColorValueV1
import ee.schimke.composeai.uibuilder.protocol.DecimalValueV1
import ee.schimke.composeai.uibuilder.protocol.DeleteNodeMutationV1
import ee.schimke.composeai.uibuilder.protocol.DesignMutationV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.protocol.IntegerValueV1
import ee.schimke.composeai.uibuilder.protocol.RejectedOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.RejectionCodeV1
import ee.schimke.composeai.uibuilder.protocol.SetPropertyMutationV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import java.io.File
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
 * An edit that would make a watch face the exporter refuses (or write XML the format rejects) is
 * refused where it is made, with the reason, and the design stays at its revision.
 */
class WatchFaceEditValidationTest {
  private val repo: File =
    generateSequence(File("").absoluteFile) { it.parentFile }
      .first { File(it, "docs/design/fixtures/ui-builder/wff-watchface").isDirectory }

  private val composed =
    assertIs<PublishedUiBuilderCatalog.Result.Composed>(
        PublishedUiBuilderCatalog.compose(
          File(repo, "docs/design/fixtures/ui-builder/wff-watchface-published-v1.json").readText(),
          null,
          ExportCapabilitiesV1.Builder().apply { composeCode = true }.build(),
        ),
      )
      .catalog

  private val owner = AuthenticatedUiBuilderActor("owner")

  private val face =
    """
        <WatchFace width="450" height="450" clipShape="CIRCLE">
          <Metadata key="CLOCK_TYPE" value="DIGITAL"/>
          <Scene backgroundColor="#ff000000">
            <Group name="column" x="0" y="0" width="450" height="450">
              <PartImage x="0" y="0" width="450" height="450" tintColor="#ff112233">
                <Image resource="dial"/>
              </PartImage>
            </Group>
            <ComplicationSlot slotId="0" supportedTypes="SHORT_TEXT" x="10" y="10" width="100" height="100">
              <BoundingOval x="0" y="0" width="100" height="100"/>
              <Complication type="SHORT_TEXT"><PartText x="0" y="0" width="90" height="90"><Text><Font family="SYNC_TO_DEVICE" size="20" color="#ffffffff"><Template>%s<Parameter expression="[COMPLICATION.TEXT]"/></Template></Font></Text></PartText></Complication>
    </ComplicationSlot>
            <ComplicationSlot slotId="1" supportedTypes="SHORT_TEXT" x="200" y="10" width="100" height="100">
              <BoundingOval x="0" y="0" width="100" height="100"/>
              <Complication type="SHORT_TEXT"><PartText x="0" y="0" width="90" height="90"><Text><Font family="SYNC_TO_DEVICE" size="20" color="#ffffffff"><Template>%s<Parameter expression="[COMPLICATION.TEXT]"/></Template></Font></Text></PartText></Complication>
    </ComplicationSlot>
          </Scene>
        </WatchFace>
    """
      .trimIndent()

  private val service =
    PersistentUiBuilderService(
      storage =
        object : UiBuilderStateStorage {
          var bytes: ByteArray? = null

          override fun load(): ByteArray? = bytes?.copyOf()

          override fun replace(value: ByteArray) {
            bytes = value.copyOf()
          }
        },
      catalogs =
        fixtureCatalogExecutor(
          catalogSystemIds = setOf("wff-watchface"),
          published = mapOf("wff-watchface" to composed),
        ),
      exporter = { _ -> error("no export in this test") },
      clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
    )

  private fun <T> run(block: suspend () -> T): T {
    var completion: Result<T>? = null
    block.startCoroutine(
      object : Continuation<T> {
        override val context = EmptyCoroutineContext

        override fun resumeWith(result: Result<T>) {
          completion = result
        }
      },
    )
    return checkNotNull(completion) { "did not complete synchronously" }.getOrThrow()
  }

  private var operations = 0
  private var revision = 0L

  private fun create(xml: String = face, version: Int = 2) {
    val created =
      assertIs<WatchFaceXmlCreateResult.Created>(
        run {
          service.createDesignFromWatchFaceXml(
            owner,
            xml,
            WatchFaceXmlCreateOptions.Builder()
              .apply {
                designId = "face"
                wffVersion = version
              }
              .build(),
          )
        },
      )
    revision = created.revision
  }

  private fun document(): UiBuilderDocument =
    assertIs<UiBuilderServiceResponse.Snapshot>(
        run {
          service.execute(
            UiBuilderServiceCall(owner, UiBuilderServiceRequest.GetSnapshot("face", null)),
          )
        },
      )
      .snapshot
      .state
      .document
      .toUiBuilderDocument()

  private fun nodeOf(component: String, nth: Int = 0): String =
    document().nodes.values.filter { it.componentId == component }[nth].id

  private fun apply(vararg mutations: DesignMutationV1) =
    assertIs<UiBuilderServiceResponse.OperationOutcome>(
        run {
          service.execute(
            UiBuilderServiceCall(
              owner,
              UiBuilderServiceRequest.ApplyOperation(
                UiBuilderSubmission.Batch(
                  "face",
                  "op-${++operations}",
                  "test",
                  revision,
                  mutations.toList(),
                ),
              ),
            ),
          )
        },
      )
      .outcome

  private fun accept(vararg mutations: DesignMutationV1) {
    val outcome = assertIs<AcceptedOutcomeV1>(apply(*mutations), mutations.toString())
    revision = outcome.committedRevision
  }

  private fun refuse(vararg mutations: DesignMutationV1): RejectedOutcomeV1 {
    val outcome = assertIs<RejectedOutcomeV1>(apply(*mutations), mutations.toString())
    // The design did not move, and the rejection says which revision it is at.
    assertEquals(revision, document().revision.toLong())
    assertEquals(revision, outcome.currentRevision, outcome.toString())
    return outcome
  }

  private fun set(
    node: String,
    property: String,
    value: ee.schimke.composeai.uibuilder.protocol.UiValueV1,
  ) = SetPropertyMutationV1(node, property, value)

  private fun xmlOf(node: String) =
    (document().nodes.getValue(node).properties.getValue("xml")
        as kotlinx.serialization.json.JsonObject)
      .getValue("value")
      .toString()
      .trim('"')

  // ---- A raw node's xml -------------------------------------------------------------------------

  @Test
  fun `a raw node's xml must be well formed, one element, and the element it replaces`() {
    create()
    val metadata = nodeOf(WatchFaceXmlExporter.RAW)
    val before = xmlOf(metadata)

    val malformed =
      refuse(set(metadata, "xml", StringValueV1("<Metadata key=\"a\"><Oops></Metadata>")))
    assertEquals(RejectionCodeV1.INVALID_PROPERTY, malformed.code)
    assertTrue("not well formed" in malformed.message, malformed.message)
    // The wrapper the service parses with is not part of the message.
    assertTrue("</r>" !in malformed.message, malformed.message)
    assertEquals(metadata, malformed.nodeId)

    val two =
      refuse(
        set(
          metadata,
          "xml",
          StringValueV1("<Metadata key=\"a\" value=\"b\"/><Metadata key=\"c\" value=\"d\"/>"),
        ),
      )
    assertTrue("exactly one element or comment" in two.message, two.message)

    val unrelated = refuse(set(metadata, "xml", StringValueV1("<Banana a=\"1\"/>")))
    assertTrue(
      "<Metadata>" in unrelated.message && "<Banana>" in unrelated.message,
      unrelated.message,
    )

    val other = refuse(set(metadata, "xml", StringValueV1("<UserConfigurations/>")))
    assertTrue("<Metadata>" in other.message, other.message)

    val comment = refuse(set(metadata, "xml", StringValueV1("<!-- no longer a Metadata -->")))
    assertTrue("comment" in comment.message, comment.message)

    assertEquals(before, xmlOf(metadata))
    accept(set(metadata, "xml", StringValueV1("<Metadata key=\"CLOCK_TYPE\" value=\"ANALOG\"/>")))
    assertTrue("ANALOG" in xmlOf(metadata))
  }

  @Test
  fun `a raw node's xml must be allowed under its parent at the face's version`() {
    // A Condition holds Expressions raw; an Expressions with a child it does not allow is refused,
    // and so is an element the format only has from a later version.
    create(
      """
      <WatchFace width="450" height="450">
        <Scene>
          <Group name="g" x="0" y="0" width="450" height="450">
            <Transform target="angle" value="1"/>
          </Group>
        </Scene>
      </WatchFace>
      """
        .trimIndent(),
    )
    val transform = nodeOf(WatchFaceXmlExporter.RAW)
    val notAllowed =
      refuse(
        set(
          transform,
          "xml",
          StringValueV1("<Transform target=\"angle\" value=\"1\"><PartText/></Transform>"),
        ),
      )
    assertTrue("PartText" in notAllowed.message, notAllowed.message)
    accept(set(transform, "xml", StringValueV1("<Transform target=\"angle\" value=\"2\"/>")))
  }

  // ---- The root, and values ---------------------------------------------------------------------

  @Test
  fun `the scene root cannot be deleted`() {
    create()
    val outcome = refuse(DeleteNodeMutationV1("scene"))
    assertEquals(RejectionCodeV1.INVALID_DOCUMENT, outcome.code)
    assertTrue("root" in outcome.message, outcome.message)
  }

  @Test
  fun `a node the format requires cannot be deleted, one it does not require can`() {
    create(
      """
      <WatchFace width="450" height="450">
        <UserConfigurations>
          <ListConfiguration id="look" displayName="look" defaultValue="a">
            <ListOption id="a" displayName="A"/>
            <ListOption id="b" displayName="B"/>
          </ListConfiguration>
        </UserConfigurations>
        <Scene><Group name="g" x="0" y="0" width="450" height="450"/></Scene>
      </WatchFace>
      """
        .trimIndent(),
    )
    val options =
      document().nodes.values.filter { it.componentId == WatchFaceXmlExporter.LIST_OPTION }
    assertEquals(2, options.size)
    accept(DeleteNodeMutationV1(options[0].id))
    val outcome = refuse(DeleteNodeMutationV1(options[1].id))
    assertEquals(RejectionCodeV1.INVALID_COMMAND, outcome.code)
    assertTrue("ListOption" in outcome.message && "requires" in outcome.message, outcome.message)
    // The last configuration is required by `UserConfigurations` too; the whole of it can go, and
    // so can a group nothing requires.
    val last = refuse(DeleteNodeMutationV1(nodeOf(WatchFaceXmlExporter.LIST_CONFIGURATION)))
    assertTrue("UserConfigurations" in last.message, last.message)
    accept(DeleteNodeMutationV1(nodeOf(WatchFaceXmlExporter.USER_CONFIGURATIONS)))
    accept(DeleteNodeMutationV1(nodeOf(WatchFaceXmlExporter.GROUP)))
  }

  @Test
  fun `a rejection reports the revision the design is at`() {
    create()
    val group = nodeOf(WatchFaceXmlExporter.GROUP)
    accept(set(group, "x", IntegerValueV1(5)))
    accept(set(group, "x", IntegerValueV1(6)))
    // INVALID_DOCUMENT, from the whole-document check, at revision 2: not 0.
    val outcome = refuse(DeleteNodeMutationV1("scene"))
    assertEquals(RejectionCodeV1.INVALID_DOCUMENT, outcome.code)
    assertEquals(2L, outcome.currentRevision)
  }

  @Test
  fun `a size cannot be negative and a number is a number`() {
    create()
    val group = nodeOf(WatchFaceXmlExporter.GROUP)
    val negative = refuse(set(group, "width", IntegerValueV1(-5)))
    assertTrue("width" in negative.message && "negative" in negative.message, negative.message)
    refuse(set(group, "height", DecimalValueV1(-0.5)))
    // A string where a number goes is the catalog's finding.
    val notNumber = refuse(set(group, "x", StringValueV1("abc")))
    assertTrue(notNumber.message.isNotBlank())
    accept(set(group, "x", DecimalValueV1(12.5)))
  }

  @Test
  fun `a colour is a colour literal or an expression`() {
    create()
    val image = nodeOf(WatchFaceXmlExporter.PART_IMAGE)
    val bad = refuse(set(image, "tintColor", ColorValueV1("notacolor")))
    assertTrue("tintColor" in bad.message, bad.message)
    refuse(set(image, "tintColor", ColorValueV1("#12345")))
    accept(set(image, "tintColor", ColorValueV1("#ff00ff00")))
  }

  @Test
  fun `a slot id is used once and a complication type needs its version`() {
    create()
    val second = nodeOf(WatchFaceXmlExporter.COMPLICATION_SLOT, 1)
    val duplicate = refuse(set(second, "slotId", IntegerValueV1(0)))
    assertTrue("slotId 0" in duplicate.message, duplicate.message)
    accept(set(second, "slotId", IntegerValueV1(7)))

    // GOAL_PROGRESS is from version 2; the face targets 2 here, so lower it first to see it.
    accept(set(second, "supportedTypes", StringValueV1("SHORT_TEXT GOAL_PROGRESS")))
    val scene = "scene"
    val lowered = refuse(set(scene, "wffVersion", IntegerValueV1(1)))
    assertTrue(
      "GOAL_PROGRESS" in lowered.message && "version 2" in lowered.message,
      lowered.message,
    )
    refuse(set(second, "supportedTypes", StringValueV1("SHORT_TEXT NOT_A_TYPE")))
  }

  @Test
  fun `a complication type newer than the version is refused when it is written`() {
    create(version = 1)
    val first = nodeOf(WatchFaceXmlExporter.COMPLICATION_SLOT, 0)
    val outcome = refuse(set(first, "supportedTypes", StringValueV1("GOAL_PROGRESS")))
    assertTrue(
      "GOAL_PROGRESS" in outcome.message && "version 1" in outcome.message,
      outcome.message,
    )
  }

  @Test
  fun `an unknown property is refused, not dropped`() {
    create()
    val group = nodeOf(WatchFaceXmlExporter.GROUP)
    val outcome = refuse(set(group, "noSuchProp", StringValueV1("x")))
    assertTrue("noSuchProp" in outcome.message, outcome.message)
  }

  @Test
  fun `what the service accepts still exports`() {
    create()
    val group = nodeOf(WatchFaceXmlExporter.GROUP)
    accept(set(group, "x", IntegerValueV1(12)), set(group, "width", IntegerValueV1(300)))
    val result = WatchFaceXmlExporter.export(document())
    val xml =
      assertIs<WatchFaceXmlExporter.Result.Emitted>(
          result,
          (result as? WatchFaceXmlExporter.Result.Refused)?.reasons.toString(),
        )
        .xml
    assertTrue("""<Group name="column" x="12" y="0" width="300" height="450">""" in xml, xml)
  }
}
