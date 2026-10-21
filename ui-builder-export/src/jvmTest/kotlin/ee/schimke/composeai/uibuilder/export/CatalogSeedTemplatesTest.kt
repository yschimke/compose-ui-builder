package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

class CatalogSeedTemplatesTest {

  private fun document(id: String, systemId: String) =
    """
    {"schema":"compose-ui-builder-document/v1-candidate","id":"$id","title":"Title of $id",
     "revision":0,
     "catalogPin":{"systemId":"$systemId","catalogRevision":"candidate",
       "capabilityDigest":"candidate","nativeRuntimeId":"candidate"},
     "environment":{"widthDp":192,"heightDp":192,"density":2.0},
     "stateVariables":{},"roots":["root"],
     "nodes":{"root":{"id":"root","componentId":"watch/scaffold","properties":{},
       "modifiers":[],"slots":{}}}}
    """
      .trimIndent()

  private val files =
    mapOf(
      "ui-builder/designs/screen.json" to document("screen", "watch-kit"),
      "ui-builder/designs/list.json" to document("list", "watch-kit"),
    )

  private fun read(paths: List<String>, systemId: String = "watch-kit") =
    CatalogSeedTemplates.read(systemId, paths) { files[it] }

  @Test
  fun `templates are read in the catalog's order, named by file`() {
    val read =
      assertIs<CatalogSeedTemplates.Result.Read>(
        read(listOf("ui-builder/designs/list.json", "ui-builder/designs/screen.json"))
      )
    assertEquals(listOf("list", "screen"), read.templates.ids)
    assertEquals("Title of list", read.templates["list"]?.title)
  }

  @Test
  fun `a missing, foreign or malformed template refuses the whole set`() {
    assertIs<CatalogSeedTemplates.Result.Unusable>(read(listOf("ui-builder/designs/gone.json")))
    assertIs<CatalogSeedTemplates.Result.Unusable>(
      read(listOf("ui-builder/designs/screen.json"), systemId = "another-catalog")
    )
    assertIs<CatalogSeedTemplates.Result.Unusable>(
      CatalogSeedTemplates.read("watch-kit", listOf("a.json")) { "{" }
    )
    assertIs<CatalogSeedTemplates.Result.Unusable>(
      CatalogSeedTemplates.read("watch-kit", listOf("ui-builder/designs/screen.txt")) { "" }
    )
  }

  private fun pin(revision: String = "r", runtime: String = "n") =
    CatalogReferenceV1("watch-kit", revision, "candidate", runtime)

  @Test
  fun `a catalog is seeded from its own document, pinned to the served catalog`() {
    val templates =
      (read(listOf("ui-builder/designs/screen.json")) as CatalogSeedTemplates.Result.Read).templates
    assertEquals(listOf("screen"), templates.ids)

    val seeded =
      UiBuilderNewDesignSeed.document("mine", "screen", pin("rev-7", "watch-kit-p2-abc"), templates)
    assertEquals("mine", seeded.id)
    assertEquals(0, seeded.revision)
    assertEquals(JsonPrimitive("rev-7"), seeded.catalogPin["catalogRevision"])
    assertEquals(JsonPrimitive("watch-kit-p2-abc"), seeded.catalogPin["nativeRuntimeId"])
    assertEquals(JsonPrimitive("watch-kit"), seeded.catalogPin["systemId"])
    assertEquals(listOf("root"), seeded.roots)
    // The catalog's own seed device.
    assertEquals(JsonPrimitive(192), seeded.environment["widthDp"])
  }

  @Test
  fun `state asked for in the New design form is declared on a published seed too`() {
    val templates =
      (read(listOf("ui-builder/designs/screen.json")) as CatalogSeedTemplates.Result.Read).templates
    val seeded =
      UiBuilderNewDesignSeed.document(
        "mine",
        "screen",
        pin(),
        templates,
        state = listOf(NewDesignState("expanded", NewDesignStateType.Flag, JsonPrimitive(true))),
      )
    assertEquals(setOf("expanded"), seeded.stateVariables.keys)

    // The rule the blank seed applies: a name that becomes a Kotlin keyword is refused.
    assertFailsWith<IllegalArgumentException> {
      UiBuilderNewDesignSeed.document(
        "mine",
        "screen",
        pin(),
        templates,
        state = listOf(NewDesignState("when", NewDesignStateType.Flag, JsonPrimitive(true))),
      )
    }
  }

  @Test
  fun `a catalog is never handed a template it does not publish`() {
    val templates =
      (read(listOf("ui-builder/designs/screen.json")) as CatalogSeedTemplates.Result.Read).templates
    val failure =
      assertFailsWith<IllegalArgumentException> {
        UiBuilderNewDesignSeed.document(
          "mine",
          UiBuilderNewDesignSeed.WEAR_LIST_TEMPLATE,
          pin(),
          templates,
        )
      }
    assertTrue("does not publish" in failure.message.orEmpty(), failure.message)
  }

  @Test
  fun `a catalog that publishes nothing is seeded with the generic blank`() {
    val nothing = CatalogSeedTemplates("watch-kit", emptyList())
    val blank =
      UiBuilderNewDesignSeed.document("mine", UiBuilderNewDesignSeed.BLANK_TEMPLATE, pin(), nothing)
    assertEquals("mine", blank.id)
    assertTrue(blank.roots.isNotEmpty())
    assertFailsWith<IllegalArgumentException> {
      UiBuilderNewDesignSeed.document("mine", "screen", pin(), nothing)
    }
  }
}
