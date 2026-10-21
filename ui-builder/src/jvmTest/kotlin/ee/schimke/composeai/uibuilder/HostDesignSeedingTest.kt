package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import ee.schimke.composeai.uibuilder.export.toDesignDocumentV1
import ee.schimke.composeai.uibuilder.local.localDesignJson
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.CatalogTemplateDocumentsV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive

/** An embedding host's empty design file, seeded through the host bridge (`HostDesignSeed`). */
class HostDesignSeedingTest {
  private val catalog = publishedTestCatalog("wear-m3")
  private val revision = catalog.benchmark.catalogRevision
  private val runtime = catalog.benchmark.nativeRuntimeId

  private val template =
    localDesignJson.decodeFromString<UiBuilderDocument>(
      """{"schema":"compose-ui-builder-document/v1-candidate","id":"published-card","title":"Published card","revision":19,
      "catalogPin":{"systemId":"wear-m3","catalogRevision":"candidate","capabilityDigest":"candidate","nativeRuntimeId":"candidate"},
      "environment":{"widthDp":192,"heightDp":192,"density":2.0,"theme":"dark","locale":"en-US","fontScale":1.0,"layoutDirection":"ltr","exportDevices":["id:pixel_6"]},"stateVariables":{},"roots":["root"],
      "nodes":{"root":{"id":"root","componentId":"wear-m3/card","properties":{},"modifiers":[],"slots":{}}}}"""
    )

  private fun templates(revision: String = this.revision) =
    localDesignJson.encodeToString(
      CatalogTemplateDocumentsV1.Builder(
          CatalogReferenceV1("wear-m3", revision, "candidate", runtime)
        )
        .also { it.templates = mapOf("card" to template.toDesignDocumentV1()) }
        .build()
    )

  @Test
  fun `a host that sends the published templates is seeded from them, pinned to the served catalog`() {
    val seeded = hostSeedDocument(catalog, "my-watch", "card", fixture = "", templates())

    assertEquals("my-watch", seeded.id)
    assertEquals(0, seeded.revision)
    assertEquals(template.nodes, seeded.nodes)
    assertEquals(JsonPrimitive(revision), seeded.catalogPin["catalogRevision"])
    assertEquals(JsonPrimitive(runtime), seeded.catalogPin["nativeRuntimeId"])
  }

  @Test
  fun `a template the catalog does not publish is refused by name`() {
    val failure =
      assertFailsWith<IllegalArgumentException> {
        hostSeedDocument(catalog, "my-watch", "wear-list", fixture = "", templates())
      }
    assertTrue("`wear-list`" in failure.message.orEmpty(), failure.message)
  }

  @Test
  fun `templates published for another revision never seed this one`() {
    assertFailsWith<IllegalArgumentException> {
      hostSeedDocument(catalog, "my-watch", "card", fixture = "", templates("$revision-older"))
    }
  }

  @Test
  fun `without templates only the Jetcaster fixture seeds, and only its own catalog`() {
    val fixture = checkNotNull(javaClass.getResource("/jetcaster-discover-operations-v1.json"))
    val m3 = publishedTestCatalog("m3-catalog")
    val seeded =
      hostSeedDocument(
        m3,
        "my-phone",
        UiBuilderNewDesignSeed.FIXTURE_TEMPLATE,
        fixture.readText(),
        templates = "",
      )

    assertEquals("my-phone", seeded.id)
    assertEquals(0, seeded.revision)
    assertEquals(JsonPrimitive("m3-catalog"), seeded.catalogPin["systemId"])
    assertEquals(
      JsonPrimitive(m3.benchmark.catalogRevision),
      seeded.catalogPin["catalogRevision"],
    )
    // The fixture is an m3-catalog design: re-pinning it would hide that wear-m3 lacks its parts.
    val wrongCatalog =
      assertFailsWith<IllegalArgumentException> {
        hostSeedDocument(
          catalog,
          "my-watch",
          UiBuilderNewDesignSeed.FIXTURE_TEMPLATE,
          fixture.readText(),
          templates = "",
        )
      }
    assertTrue("cannot seed wear-m3" in wrongCatalog.message.orEmpty(), wrongCatalog.message)
    // A template the catalog publishes still needs the published set: no built-in seed answers.
    assertFailsWith<IllegalArgumentException> {
      hostSeedDocument(
        catalog,
        "my-watch",
        UiBuilderNewDesignSeed.WEAR_LIST_TEMPLATE,
        fixture.readText(),
        templates = "",
      )
    }
    assertFailsWith<IllegalArgumentException> {
      hostSeedDocument(m3, "my-phone", UiBuilderNewDesignSeed.FIXTURE_TEMPLATE, "", "")
    }
  }
}
