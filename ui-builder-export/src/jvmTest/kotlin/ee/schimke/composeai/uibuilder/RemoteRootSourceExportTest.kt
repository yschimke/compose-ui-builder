package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.protocol.*
import java.io.File
import kotlin.test.*
import kotlinx.serialization.json.*

class RemoteRootSourceExportTest {
  @kotlin.test.BeforeTest
  fun requireExperimentalBuild() {
    org.junit.Assume.assumeTrue(
      "Enable with -PuiBuilderRemoteCompose=true",
      UiBuilderBuildFeatures.remoteCompose,
    )
  }

  private val json = Json { ignoreUnknownKeys = true }

  private fun document() =
    json.decodeFromString<DesignDocumentV1>(
      File("../docs/design/evidence/ui-builder-live-document-preview/sample.document.json")
        .readText()
    )

  @Test
  fun `ordinary roots export exact Kotlin compiled by the Android proof`() {
    val doc = document()
    val result =
      assertIs<RecordFreeExport.Generated.Emitted>(
        RecordFreeExport.generate(
          doc,
          UiBuilderCatalogPlatform.REMOTE_COMPOSE,
          "generated.uibuilder",
        )
      )
    assertTrue("RemoteStateLayout" in result.source, result.source)
    assertTrue("valueChange" in result.source, result.source)
    assertFalse("WearWidget" in result.source, result.source)
    assertEquals("StatefulPreviewRemoteContent", result.composableName)
    File("build/remote-state-selection").mkdirs()
    File("build/remote-state-selection/PlainRoot.kt").writeText(result.source)
    val fixture = File("../docs/design/fixtures/ui-builder/remote-root.kt.txt")
    if (System.getenv("UPDATE_UI_BUILDER_REMOTE_ROOT_FIXTURE") == "true")
      fixture.writeText(result.source)
    assertEquals(fixture.readText(), result.source)
    assertEquals(doc, document())
  }

  @Test
  fun `platform declaration controls routing independently of the catalog name`() {
    val doc =
      document().copy(catalogPin = document().catalogPin.copy(systemId = "other-remote-catalog"))
    assertIs<RecordFreeExport.Generated.Emitted>(
      RecordFreeExport.generate(doc, UiBuilderCatalogPlatform.REMOTE_COMPOSE)
    )
    assertNull(RecordFreeExport.generate(doc, UiBuilderCatalogPlatform.MOBILE))
    assertNull(RecordFreeExport.generate(doc, UiBuilderCatalogPlatform.WEAR))
  }

  @Test
  fun `missing children and cycles refuse without partial source`() {
    val doc = document().toUiBuilderDocument()
    for (child in listOf("missing", "choice")) {
      val broken =
        doc.copy(
          nodes =
            doc.nodes +
              ("First" to
                doc.nodes.getValue("First").copy(slots = mapOf("children" to listOf(child))))
        )
      val result =
        assertIs<RecordFreeExport.Generated.Refused>(
          RecordFreeExport.generate(broken, UiBuilderCatalogPlatform.REMOTE_COMPOSE)
        )
      assertTrue(result.reasons.any { child in it }, result.toString())
    }
  }

  @Test
  fun `wire-only semantics are refused before the editor-model conversion`() {
    val doc = document()
    for (node in
      listOf(
        doc.nodes.getValue("First").copy(predicate = StateTruthyPredicateV1("page")),
        doc.nodes.getValue("First").copy(accessibility = AccessibilityV1(label = "First")),
      )) {
      val result =
        assertIs<RecordFreeExport.Generated.Refused>(
          RecordFreeExport.generate(
            doc.copy(nodes = doc.nodes + ("First" to node)),
            UiBuilderCatalogPlatform.REMOTE_COMPOSE,
          )
        )
      assertTrue(result.reasons.any { "nodes.First" in it }, result.toString())
    }
  }

  @Test
  fun `multiple roots retain authored order without an invented layout`() {
    val doc = document().toUiBuilderDocument().copy(roots = listOf("First", "Second"))
    val source =
      assertIs<RecordFreeExport.Generated.Emitted>(
          RecordFreeExport.generate(doc, UiBuilderCatalogPlatform.REMOTE_COMPOSE)
        )
        .source
    assertEquals(2, Regex("RemoteBox\\(").findAll(source).count())
    assertTrue(source.indexOf("0xFF6750A4") < source.indexOf("0xFF008577"), source)
  }
}
