package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class UiBuilderComponentPacksTest {
  @Test
  fun `a catalog that says nothing is a mobile catalog with no packs`() {
    val nothing = JsonObject(emptyMap())
    assertEquals(UiBuilderCatalogPlatform.MOBILE, UiBuilderCatalogPlatform.from(nothing))
    assertTrue(UiBuilderComponentPacks.from(nothing).isEmpty)
  }

  @Test
  fun `the platform is read by its wire word and an unknown word is the default`() {
    assertEquals(
      UiBuilderCatalogPlatform.WEAR,
      UiBuilderCatalogPlatform.from(buildJsonObject { put("platform", "wear") }),
    )
    assertEquals(
      UiBuilderCatalogPlatform.REMOTE_COMPOSE,
      UiBuilderCatalogPlatform.from(buildJsonObject { put("platform", "Remote-Compose") }),
    )
    assertEquals(
      UiBuilderCatalogPlatform.MOBILE,
      UiBuilderCatalogPlatform.from(buildJsonObject { put("platform", "desktop") }),
    )
    assertNull(UiBuilderCatalogPlatform.fromWord("desktop"))
  }

  @Test
  fun `a pack round-trips through the catalog and answers which component it owns`() {
    val pack =
      UiBuilderComponentPack(
        id = "confetti-mobile",
        label = "Confetti Mobile",
        platform = UiBuilderCatalogPlatform.MOBILE,
        nativeCatalog = "confetti-mobile",
        componentIds = listOf("confetti-mobile/session-card", "confetti-mobile/speaker-row"),
        notes = "Two components.",
      )
    val statusSemantics = buildJsonObject {
      put("platform", "mobile")
      put("componentPacks", buildJsonArray { add(pack.toJson()) })
    }

    val packs = UiBuilderComponentPacks.from(statusSemantics)

    assertEquals(listOf(pack), packs.packs)
    assertEquals(pack, packs.packOf("confetti-mobile/speaker-row"))
    assertNull(packs.packOf("m3/card"))
    assertEquals(pack, packs["confetti-mobile"])
  }

  @Test
  fun `a malformed entry is dropped and the rest are kept`() {
    val statusSemantics = buildJsonObject {
      put(
        "componentPacks",
        buildJsonArray {
          add(JsonPrimitive("not an object"))
          add(buildJsonObject { put("label", "no id") })
          add(
            buildJsonObject {
              put("id", "unknown-platform")
              put("platform", "desktop")
            }
          )
          add(
            buildJsonObject {
              put("id", "bare")
              put("platform", "wear")
            }
          )
        },
      )
    }

    val packs = UiBuilderComponentPacks.from(statusSemantics)

    assertEquals(
      listOf(
        UiBuilderComponentPack(
          id = "bare",
          label = "bare",
          platform = UiBuilderCatalogPlatform.WEAR,
          nativeCatalog = null,
          componentIds = emptyList(),
        )
      ),
      packs.packs,
    )
  }

  @Test
  fun `component ids are kebab-cased from the composable name under the pack id`() {
    assertEquals(
      "confetti-mobile/session-card",
      UiBuilderComponentPack.componentId("confetti-mobile", "SessionCard"),
    )
    assertEquals("http-client", UiBuilderComponentPack.kebabCase("HTTPClient"))
    assertEquals("a11y-badge", UiBuilderComponentPack.kebabCase("A11yBadge"))
    assertEquals("speaker-details-view", UiBuilderComponentPack.kebabCase("SpeakerDetailsView"))
    assertEquals("text", UiBuilderComponentPack.kebabCase("Text"))
    assertEquals("session-card", UiBuilderComponentPack.kebabCase("Session_Card"))
  }
}
