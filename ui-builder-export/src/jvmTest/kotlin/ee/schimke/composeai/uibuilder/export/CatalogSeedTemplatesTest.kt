package ee.schimke.composeai.uibuilder.export

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

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

  /** The Jetcaster operations fixture every host seeds from. */
  private val fixture: JsonObject by lazy {
    val root =
      generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "docs/design/fixtures/ui-builder").isDirectory }
    Json.parseToJsonElement(
        File(root, "docs/design/fixtures/ui-builder/jetcaster-discover-operations-v1.json")
          .readText()
      )
      .jsonObject
  }

  @Test
  fun `an owned catalog is seeded from its own document, pinned to the served catalog`() {
    val templates =
      (read(listOf("ui-builder/designs/screen.json")) as CatalogSeedTemplates.Result.Read).templates
    val owned = CatalogOwnership.of(setOf("watch-kit"))
    assertEquals(setOf("screen"), UiBuilderNewDesignSeed.templateIds("watch-kit", owned, templates))

    val seeded =
      UiBuilderNewDesignSeed.document(
        "mine",
        "watch-kit",
        "screen",
        "rev-7",
        "watch-kit-p2-abc",
        fixture,
        owned,
        templates,
      )
    assertEquals("mine", seeded.id)
    assertEquals(0, seeded.revision)
    assertEquals(JsonPrimitive("rev-7"), seeded.catalogPin["catalogRevision"])
    assertEquals(JsonPrimitive("watch-kit-p2-abc"), seeded.catalogPin["nativeRuntimeId"])
    assertEquals(JsonPrimitive("watch-kit"), seeded.catalogPin["systemId"])
    assertEquals(listOf("root"), seeded.roots)
    // The catalog's own seed device, not the fixture's handset.
    assertEquals(JsonPrimitive(192), seeded.environment["widthDp"])
  }

  @Test
  fun `state asked for in the New design form is declared on a published seed too`() {
    val templates =
      (read(listOf("ui-builder/designs/screen.json")) as CatalogSeedTemplates.Result.Read).templates
    val seeded =
      UiBuilderNewDesignSeed.document(
        "mine",
        "watch-kit",
        "screen",
        "r",
        "n",
        fixture,
        CatalogOwnership.ALL,
        templates,
        state = listOf(NewDesignState("expanded", NewDesignStateType.Flag, JsonPrimitive(true))),
      )
    assertEquals(setOf("expanded"), seeded.stateVariables.keys)

    // The rule the built-in blank seed applies: a name that becomes a Kotlin keyword is refused.
    assertFailsWith<IllegalArgumentException> {
      UiBuilderNewDesignSeed.document(
        "mine",
        "watch-kit",
        "screen",
        "r",
        "n",
        fixture,
        CatalogOwnership.ALL,
        templates,
        state = listOf(NewDesignState("when", NewDesignStateType.Flag, JsonPrimitive(true))),
      )
    }
  }

  @Test
  fun `an owned catalog is never handed a template it does not publish`() {
    val templates =
      (read(listOf("ui-builder/designs/screen.json")) as CatalogSeedTemplates.Result.Read).templates
    val failure =
      assertFailsWith<IllegalArgumentException> {
        UiBuilderNewDesignSeed.document(
          "mine",
          "watch-kit",
          UiBuilderNewDesignSeed.WEAR_LIST_TEMPLATE,
          "r",
          "n",
          fixture,
          CatalogOwnership.ALL,
          templates,
        )
      }
    assertTrue("does not publish" in failure.message.orEmpty(), failure.message)
  }

  @Test
  fun `a catalog that publishes nothing is offered the generic blank`() {
    assertEquals(
      setOf(UiBuilderNewDesignSeed.BLANK_TEMPLATE),
      UiBuilderNewDesignSeed.templateIds("glasses-kit", CatalogOwnership.ALL, null),
    )
  }

  @Test
  fun `off the flag nothing a catalog publishes changes its seeds`() {
    val templates =
      (read(listOf("ui-builder/designs/screen.json")) as CatalogSeedTemplates.Result.Read).templates
    assertEquals(
      UiBuilderNewDesignSeed.templateIds("wear-m3"),
      UiBuilderNewDesignSeed.templateIds("wear-m3", CatalogOwnership.NONE, templates),
    )
  }
}
