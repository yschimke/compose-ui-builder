package ee.schimke.composeai.uibuilder.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A Wear design whose root is neither the screen scaffold nor a widget container.
 *
 * Both Wear emitters route on the root, so a bare `wear-m3/button` at the root used to fall through
 * to the record-driven generator, which has no record for any `wear-m3` component and answered "no
 * component `wear-m3/button` in this catalog" — once per component, and none of them the cause. The
 * cause is the root, and the fix is to put the content inside a Wear screen.
 */
class WearRootRefusalTest {
  private val expected =
    "the root is `wear-m3/button`, but a Wear design exports only as a Wear screen or a Wear " +
      "widget: put its content inside a `wear-m3/screen-scaffold` (or start from the " +
      "\"wear-screen\" template), or make the root a Wear widget container"

  @Test
  fun `a bare wear button at the root is refused with what to do, once`() {
    val bare = document(listOf("cell-0"), button("cell-0"))

    val refused =
      assertIs<RecordFreeExport.Generated.Refused>(
        RecordFreeExport.generate(bare, UiBuilderCatalogPlatform.WEAR)
      )

    assertEquals(listOf(expected), refused.reasons)
  }

  @Test
  fun `the saved document takes the same road, so the served export says the same thing`() {
    val saved = document(listOf("cell-0"), button("cell-0")).toDesignDocumentV1()

    assertTrue(RecordFreeExport.applies(saved, UiBuilderCatalogPlatform.WEAR))
    val refused =
      assertIs<RecordFreeExport.Generated.Refused>(
        RecordFreeExport.generate(saved, UiBuilderCatalogPlatform.WEAR, "com.example")
      )
    assertEquals(listOf(expected), refused.reasons)
  }

  @Test
  fun `the tagged overloads give the same sentence when handed the platform`() {
    // The server's native preview lane needs `tagNodes`, which only the platform-less overloads
    // take. Handed the platform, they refuse exactly as the export does; without it, nothing moves.
    val bare = document(listOf("cell-0"), button("cell-0"))
    val saved = bare.toDesignDocumentV1()

    listOf(
        RecordFreeExport.generate(
          bare,
          "com.example",
          tagNodes = true,
          platform = UiBuilderCatalogPlatform.WEAR,
        ),
        RecordFreeExport.generate(
          saved,
          "com.example",
          tagNodes = true,
          platform = UiBuilderCatalogPlatform.WEAR,
        ),
      )
      .forEach {
        assertEquals(listOf(expected), assertIs<RecordFreeExport.Generated.Refused>(it).reasons)
      }

    assertNull(RecordFreeExport.generate(saved, "com.example", tagNodes = true))
    assertNull(RecordFreeExport.generate(saved, "com.example", tagNodes = true, platform = null))
    assertNull(
      RecordFreeExport.generate(
        saved,
        "com.example",
        tagNodes = true,
        platform = UiBuilderCatalogPlatform.MOBILE,
      )
    )
  }

  @Test
  fun `several roots are named as several roots`() {
    val two = document(listOf("cell-0", "cell-1"), button("cell-0") + button("cell-1"))

    val refused =
      assertIs<RecordFreeExport.Generated.Refused>(
        RecordFreeExport.generate(two, UiBuilderCatalogPlatform.WEAR)
      )

    assertEquals(
      listOf(
        "this design has 2 roots, but a Wear design exports only as a Wear screen or a Wear " +
          "widget: put its content inside a `wear-m3/screen-scaffold` (or start from the " +
          "\"wear-screen\" template), or make the root a Wear widget container"
      ),
      refused.reasons,
    )
  }

  @Test
  fun `a wear screen holding the same button still generates`() {
    val screen =
      document(
        listOf("screen"),
        mapOf(
          "screen" to
            UiBuilderNode(
              "screen",
              WearScreenCodeExporter.SCAFFOLD,
              slots = mapOf("content" to listOf("list")),
            ),
          "list" to
            UiBuilderNode(
              "list",
              "wear-m3/transforming-lazy-column",
              slots = mapOf("items" to listOf("cell-0")),
            ),
        ) + button("cell-0"),
      )

    assertIs<RecordFreeExport.Generated.Emitted>(
      RecordFreeExport.generate(screen, UiBuilderCatalogPlatform.WEAR)
    )
  }

  @Test
  fun `only a wear catalog's own components are claimed`() {
    // Nothing here is a `wear-m3` or `remote-m3` component, so no Wear emitter is the one that
    // would have written it and the record-driven generator still owns the question.
    val plain = document(listOf("col"), mapOf("col" to UiBuilderNode("col", "layout/column")))
    assertNull(RecordFreeExport.generate(plain, UiBuilderCatalogPlatform.WEAR))
    assertTrue(!RecordFreeExport.applies(plain.toDesignDocumentV1(), UiBuilderCatalogPlatform.WEAR))

    // And a mobile catalog is untouched, whatever its nodes are called.
    val bare = document(listOf("cell-0"), button("cell-0"))
    assertNull(RecordFreeExport.generate(bare, UiBuilderCatalogPlatform.MOBILE))
  }

  private fun button(id: String) =
    mapOf(
      id to UiBuilderNode(id, "wear-m3/button", slots = mapOf("content" to listOf("$id-label"))),
      "$id-label" to
        UiBuilderNode(
          "$id-label",
          "wear-m3/text",
          properties =
            buildJsonObject {
              put(
                "text",
                buildJsonObject {
                  put("type", "string")
                  put("value", "Start")
                },
              )
            },
        ),
    )

  private fun document(roots: List<String>, nodes: Map<String, UiBuilderNode>) =
    UiBuilderDocument(
      schema = "compose-ui-builder-document/v1",
      id = "wear-root",
      title = "Wear root",
      revision = 1,
      catalogPin =
        buildJsonObject {
          put("systemId", "wear-m3")
          put("catalogRevision", "wear-screen-scaffold-v1")
          put("capabilityDigest", "candidate")
          put("nativeRuntimeId", "candidate")
        },
      environment =
        buildJsonObject {
          put("widthDp", 192)
          put("heightDp", 192)
          put("density", 2.0)
          put("theme", "dark")
          put("locale", "en-US")
          put("fontScale", 1.0)
          put("layoutDirection", "ltr")
        },
      stateVariables = JsonObject(emptyMap()),
      roots = roots,
      nodes = nodes,
    )
}
