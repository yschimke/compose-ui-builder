package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlExporter
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlImporter
import ee.schimke.composeai.uibuilder.export.toUiBuilderDocument
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/** Creating a watch face design from WFF XML through the service, and editing it afterwards. */
class WatchFaceDesignCreationTest {
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

  private fun service() =
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

  private fun snapshot(service: PersistentUiBuilderService, designId: String) =
    assertIs<UiBuilderServiceResponse.Snapshot>(
        run {
          service.execute(
            UiBuilderServiceCall(owner, UiBuilderServiceRequest.GetSnapshot(designId, null)),
          )
        },
      )
      .snapshot

  private fun exported(service: PersistentUiBuilderService, designId: String): String {
    val document: UiBuilderDocument =
      snapshot(service, designId).state.document.toUiBuilderDocument()
    return assertIs<WatchFaceXmlExporter.Result.Emitted>(WatchFaceXmlExporter.export(document)).xml
  }

  private fun faces(): Map<String, String> {
    val faces = linkedMapOf<String, String>()
    for (name in listOf("analog-watch-face", "digital-watch-face")) {
      val template =
        Json.decodeFromString(
          UiBuilderDocument.serializer(),
          File(repo, "docs/design/fixtures/ui-builder/wff-watchface/designs/$name.json").readText(),
        )
      faces[name] =
        assertIs<WatchFaceXmlExporter.Result.Emitted>(WatchFaceXmlExporter.export(template)).xml
    }
    File(repo, "ui-builder-export/src/jvmTest/resources/wear-os-samples")
      .listFiles { f -> f.isDirectory }
      .orEmpty()
      .sortedBy { it.name }
      .forEach { faces[it.name] = File(it, "raw/watchface.xml").readText() }
    return faces
  }

  @Test
  fun `the templates and all seven samples are created and export to the same XML`() {
    val faces = faces()
    assertEquals(9, faces.size)
    for ((name, xml) in faces) {
      val service = service()
      val created =
        assertIs<WatchFaceXmlCreateResult.Created>(
          run {
            service.createDesignFromWatchFaceXml(
              owner,
              xml,
              WatchFaceXmlCreateOptions.Builder().apply { designId = name }.build(),
            )
          },
          name,
        )
      assertEquals(name, created.designId)
      assertEquals(0L, created.revision)
      // The importer's own round trip (tree-equal to the input, see WatchFaceXmlImporterTest).
      val direct =
        assertIs<WatchFaceXmlImporter.Result.Imported>(WatchFaceXmlImporter.import(xml)).document
      assertEquals(
        (WatchFaceXmlExporter.export(direct) as WatchFaceXmlExporter.Result.Emitted).xml,
        exported(service, name),
        name,
      )
      // The shipped templates are written canonically, so they come back byte for byte.
      if (name.endsWith("-watch-face")) assertEquals(xml, exported(service, name), name)
    }
  }

  @Test
  fun `malformed xml and an unsupported version are typed failures, and nothing is created`() {
    val service = service()
    val malformed =
      assertIs<WatchFaceXmlCreateResult.Failed>(
        run { service.createDesignFromWatchFaceXml(owner, "<WatchFace>\n  <Scene>\n</WatchFace>") },
      )
    assertEquals(3, malformed.line)
    assertNotNull(malformed.column)
    val version =
      assertIs<WatchFaceXmlCreateResult.Failed>(
        run {
          service.createDesignFromWatchFaceXml(
            owner,
            "<WatchFace width=\"450\" height=\"450\"><Scene/></WatchFace>",
            WatchFaceXmlCreateOptions.Builder().apply { wffVersion = 9 }.build(),
          )
        },
      )
    assertTrue(version.reasons.single().contains("wffVersion 9"))
    val listed =
      assertIs<UiBuilderServiceResponse.Designs>(
        run {
          service.execute(
            UiBuilderServiceCall(owner, UiBuilderServiceRequest.ListDesigns(null, 10)),
          )
        },
      )
    assertTrue(listed.designs.isEmpty())
  }

  @Test
  fun `a created design is edited with a normal operation and the revision bumps`() {
    val service = service()
    val xml = faces().getValue("digital-watch-face")
    val created =
      assertIs<WatchFaceXmlCreateResult.Created>(
        run {
          service.createDesignFromWatchFaceXml(
            owner,
            xml,
            WatchFaceXmlCreateOptions.Builder().apply { designId = "face" }.build(),
          )
        },
      )
    val outcome = run {
      service.execute(
        UiBuilderServiceCall(
          owner,
          UiBuilderServiceRequest.ApplyOperation(
            UiBuilderSubmission.Batch(
              "face",
              "edit-preview-time",
              "test",
              created.revision,
              listOf(SetPropertyMutationV1("scene", "previewTime", StringValueV1("12:34:56"))),
            ),
          ),
        ),
      )
    }
    assertIs<UiBuilderServiceResponse.OperationOutcome>(outcome, outcome.toString())
    assertEquals(1L, snapshot(service, "face").state.document.revision)
    val after = exported(service, "face")
    assertTrue("""<Metadata key="PREVIEW_TIME" value="12:34:56"/>""" in after, after)
    assertTrue("10:08:32" !in after)
  }
}
