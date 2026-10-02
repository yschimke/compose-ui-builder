package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.WearScreenCodeExporter
import ee.schimke.composeai.uibuilder.export.wearScreenUiBuilderDocument
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * What a re-skinned Wear screen exports: its layout, its painted surfaces and its theme.
 *
 * Each case here is something the canvas drew and the Wear screen generator either dropped with no
 * diagnostic or refused outright, found re-skinning one design (`petty-rascal2`):
 * yschimke/wear-m3-catalog#680 (row and column arrangement), #681 (`background` and `border` on a
 * card or box) and #682 (a screen theme, and colours for the components that had none).
 */
class WearLayoutAndColourExportTest {
  private val pin = JsonObject(emptyMap())
  private val environment = JsonObject(emptyMap())

  private fun screenWith(
    items: List<UiBuilderNode>,
    vararg nested: UiBuilderNode,
    scaffold: JsonObject? = null,
    edgeButton: UiBuilderNode? = null,
  ): UiBuilderDocument {
    val base = wearScreenUiBuilderDocument("activity", pin, environment)
    val list = base.nodes.getValue("wear-list")
    val rootId = base.roots.single()
    val root = base.nodes.getValue(rootId)
    return base.copy(
      nodes =
        base.nodes +
          ("wear-list" to list.copy(slots = mapOf("items" to items.map { it.id }))) +
          (rootId to
            root.copy(
              properties = JsonObject(root.properties + (scaffold ?: JsonObject(emptyMap()))),
              slots = root.slots + ("edgeButton" to listOfNotNull(edgeButton?.id)),
            )) +
          (items + nested.toList() + listOfNotNull(edgeButton)).associateBy { it.id }
    )
  }

  private fun properties(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
    pairs.forEach { (name, value) ->
      putJsonObject(name) {
        when (value) {
          is Number -> {
            put("type", "number")
            put("value", JsonPrimitive(value))
          }
          else -> {
            val text = value.toString()
            put("type", if (text.startsWith("#")) "color" else "string")
            put("value", text)
          }
        }
      }
    }
  }

  private fun text(id: String) =
    UiBuilderNode(id, WearScreenCodeExporter.TEXT, properties("text" to id))

  private fun export(document: UiBuilderDocument): String =
    assertIs<WearScreenCodeExporter.Result.Emitted>(WearScreenCodeExporter.export(document)).source

  private fun refusals(document: UiBuilderDocument): List<String> =
    assertIs<WearScreenCodeExporter.Result.Refused>(WearScreenCodeExporter.export(document)).reasons

  @Test
  fun `a column writes its alignment and spacing`() {
    val source =
      export(
        screenWith(
          listOf(
            UiBuilderNode(
              "hero-col",
              "layout/column",
              properties("horizontalAlignment" to "center", "verticalSpacingDp" to 4),
              slots = mapOf("children" to listOf("a", "b")),
            )
          ),
          text("a"),
          text("b"),
        )
      )

    assertTrue("verticalArrangement = Arrangement.spacedBy(4.dp)," in source, source)
    assertTrue("horizontalAlignment = Alignment.CenterHorizontally," in source, source)
    assertTrue("import androidx.compose.foundation.layout.Arrangement" in source, source)
    assertTrue("import androidx.compose.ui.Alignment" in source, source)
  }

  @Test
  fun `a row writes its arrangement, and the canvas's vertical centre`() {
    val source =
      export(
        screenWith(
          listOf(
            UiBuilderNode(
              "hero-scale",
              "layout/row",
              properties("horizontalArrangement" to "spaceBetween"),
              slots = mapOf("children" to listOf("lo", "hi")),
            ),
            UiBuilderNode(
              "lock-row",
              "layout/row",
              properties("verticalAlignment" to "center", "horizontalSpacingDp" to 10),
              slots = mapOf("children" to listOf("lock")),
            ),
            UiBuilderNode(
              "top-row",
              "layout/row",
              properties(
                "verticalAlignment" to "top",
                "horizontalArrangement" to "end",
                "horizontalSpacingDp" to 8,
              ),
              slots = mapOf("children" to listOf("x")),
            ),
          ),
          text("lo"),
          text("hi"),
          text("lock"),
          text("x"),
        )
      )

    assertTrue("horizontalArrangement = Arrangement.SpaceBetween," in source, source)
    // Unset is the canvas's centre, not Compose's top, so it is written either way.
    assertTrue(
      "Row(\n" +
        "                    horizontalArrangement = Arrangement.SpaceBetween,\n" +
        "                    verticalAlignment = Alignment.CenterVertically,\n" in source,
      source,
    )
    assertTrue("horizontalArrangement = Arrangement.spacedBy(10.dp)," in source, source)
    assertTrue(
      "Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {" in source,
      source,
    )
  }

  @Test
  fun `a box writes its content alignment`() {
    val source =
      export(
        screenWith(
          listOf(
            UiBuilderNode(
              "box",
              "layout/box",
              properties("contentAlignment" to "center"),
              slots = mapOf("children" to listOf("t")),
            )
          ),
          text("t"),
        )
      )

    assertTrue("Box(contentAlignment = Alignment.Center) {" in source, source)
  }

  @Test
  fun `a card's column that lays its lines out is not folded into the title slot`() {
    val source =
      export(
        screenWith(
          listOf(
            UiBuilderNode(
              "card",
              WearScreenCodeExporter.CARD,
              slots = mapOf("content" to listOf("col")),
            )
          ),
          UiBuilderNode(
            "col",
            "layout/column",
            properties("horizontalAlignment" to "center"),
            slots = mapOf("children" to listOf("t1", "t2")),
          ),
          text("t1"),
          text("t2"),
        )
      )

    assertTrue("horizontalAlignment = Alignment.CenterHorizontally" in source, source)
  }

  private fun modifier(type: String, vararg pairs: Pair<String, Any>) = buildJsonObject {
    put("type", type)
    pairs.forEach { (name, value) ->
      when (value) {
        is Number -> put(name, value)
        is JsonObject -> put(name, value)
        else -> put(name, value.toString())
      }
    }
  }

  private fun color(value: String) = buildJsonObject {
    put("type", if (value.startsWith("#")) "color" else "colorToken")
    put("value", value)
  }

  @Test
  fun `a card's background and border are written rather than refused`() {
    val source =
      export(
        screenWith(
          listOf(
            UiBuilderNode(
              "hero",
              WearScreenCodeExporter.CARD,
              properties("variant" to "plain"),
              modifiers =
                JsonArray(
                  listOf(
                    modifier("background", "color" to color("#102A2B"), "shape" to "large"),
                    modifier(
                      "border",
                      "color" to color("primary"),
                      "widthDp" to 1,
                      "shape" to "large",
                    ),
                  )
                ),
              slots = mapOf("content" to listOf("t")),
            ),
            UiBuilderNode(
              "lightbar",
              "layout/box",
              modifiers =
                JsonArray(
                  listOf(
                    modifier("size", "widthDp" to 40, "heightDp" to 4),
                    modifier("clip", "shape" to "8"),
                    modifier("background", "color" to color("#2BE4D3")),
                  )
                ),
            ),
          ),
          text("t"),
        )
      )

    assertTrue(
      ".background(Color(0xFF102A2B), RoundedCornerShape(26.dp))" in source,
      source,
    )
    assertTrue(
      ".border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(26.dp))" in source,
      source,
    )
    assertTrue(".clip(RoundedCornerShape(8.dp)).background(Color(0xFF2BE4D3))" in source, source)
    assertTrue("import androidx.compose.foundation.background" in source, source)
    assertTrue("import androidx.compose.foundation.border" in source, source)
    assertTrue("import androidx.compose.foundation.shape.RoundedCornerShape" in source, source)
    assertTrue("import androidx.compose.ui.draw.clip" in source, source)
  }

  @Test
  fun `a card takes its own colours, and an outlined one refuses a container`() {
    val source =
      export(
        screenWith(
          listOf(
            UiBuilderNode(
              "lock",
              WearScreenCodeExporter.CARD,
              properties(
                "variant" to "plain",
                "containerColor" to "#102A2B",
                "contentColor" to "#2BE4D3",
              ),
              slots = mapOf("content" to listOf("t")),
            )
          ),
          text("t"),
        )
      )
    assertTrue(
      "colors = CardDefaults.cardColors(containerColor = Color(0xFF102A2B), contentColor = Color(0xFF2BE4D3))," in
        source,
      source,
    )

    val refused =
      refusals(
        screenWith(
          listOf(
            UiBuilderNode(
              "outlined",
              WearScreenCodeExporter.CARD,
              properties("variant" to "outlined", "containerColor" to "#102A2B"),
              slots = mapOf("content" to listOf("t")),
            )
          ),
          text("t"),
        )
      )
    assertTrue(refused.any { "`outlined`" in it && "OutlinedCard" in it }, refused.toString())
  }

  @Test
  fun `the progress indicator, edge button and icon take colours`() {
    val source =
      export(
        screenWith(
          listOf(
            UiBuilderNode(
              "battery",
              WearScreenCodeExporter.PROGRESS_INDICATOR,
              properties(
                "variant" to "linear",
                "progress" to 0.8,
                "indicatorColor" to "#2BE4D3",
                "trackColor" to "#102A2B",
              ),
            ),
            UiBuilderNode(
              "lock-icon",
              WearScreenCodeExporter.ICON,
              properties("iconKey" to "playCircle", "color" to "#2BE4D3"),
            ),
          ),
          text("go-label"),
          edgeButton =
            UiBuilderNode(
              "go",
              WearScreenCodeExporter.EDGE_BUTTON,
              properties("containerColor" to "#2BE4D3", "contentColor" to "#00201E"),
              slots = mapOf("content" to listOf("go-label")),
            ),
        )
      )

    assertTrue(
      "colors = ProgressIndicatorDefaults.colors(indicatorColor = Color(0xFF2BE4D3), trackColor = Color(0xFF102A2B))," in
        source,
      source,
    )
    assertTrue("import androidx.wear.compose.material3.ProgressIndicatorDefaults" in source, source)
    assertTrue("tint = Color(0xFF2BE4D3)," in source, source)
    assertTrue(
      "colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2BE4D3), contentColor = Color(0xFF00201E))" in
        source,
      source,
    )
  }

  @Test
  fun `a screen theme wraps the screen in a re-skinned MaterialTheme`() {
    val themed =
      export(
        screenWith(
          listOf(text("t")),
          scaffold =
            properties("themePrimaryColor" to "#2BE4D3", "themeOnPrimaryColor" to "#00201E"),
        )
      )

    assertTrue(
      "    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = Color(0xFF2BE4D3), onPrimary = Color(0xFF00201E))) {\n" +
        "        ScreenScaffold(scrollState = listState," in themed,
      themed,
    )
    assertTrue("import androidx.wear.compose.material3.MaterialTheme" in themed, themed)

    val plain = export(screenWith(listOf(text("t"))))
    assertFalse("MaterialTheme(colorScheme" in plain, plain)
  }

  /**
   * Every case above in one screen, written where a compile check can pick it up: the assertions
   * here read text, and only a compiler against the real AndroidX library can say it builds.
   */
  @Test
  fun `one screen carrying all of it is written out for a compiler`() {
    val source =
      export(
        screenWith(
          listOf(
            UiBuilderNode(
              "hero",
              WearScreenCodeExporter.CARD,
              properties("variant" to "plain", "containerColor" to "#102A2B"),
              modifiers =
                JsonArray(
                  listOf(
                    modifier(
                      "border",
                      "color" to color("primary"),
                      "widthDp" to 1,
                      "shape" to "large",
                    ),
                    modifier("shadow", "elevationDp" to 2, "shape" to "small"),
                  )
                ),
              slots = mapOf("content" to listOf("hero-col")),
            ),
            UiBuilderNode(
              "box",
              "layout/box",
              properties("contentAlignment" to "center"),
              modifiers =
                JsonArray(
                  listOf(
                    modifier("background", "color" to color("surfaceContainer")),
                    modifier("wrapContentSize", "alignment" to "center"),
                  )
                ),
              slots = mapOf("children" to listOf("battery")),
            ),
          ),
          UiBuilderNode(
            "hero-col",
            "layout/column",
            properties("horizontalAlignment" to "center", "verticalSpacingDp" to 4),
            slots = mapOf("children" to listOf("scale", "lock-row")),
          ),
          UiBuilderNode(
            "scale",
            "layout/row",
            properties("horizontalArrangement" to "spaceBetween"),
            slots = mapOf("children" to listOf("lo", "hi")),
          ),
          UiBuilderNode(
            "lock-row",
            "layout/row",
            properties("horizontalSpacingDp" to 10),
            slots = mapOf("children" to listOf("lock-icon", "lock-text")),
          ),
          UiBuilderNode(
            "lock-icon",
            WearScreenCodeExporter.ICON,
            properties("iconKey" to "playCircle", "color" to "#2BE4D3"),
          ),
          UiBuilderNode(
            "battery",
            WearScreenCodeExporter.PROGRESS_INDICATOR,
            properties("variant" to "linear", "progress" to 0.8, "indicatorColor" to "#2BE4D3"),
          ),
          text("lo"),
          text("hi"),
          text("lock-text"),
          text("go-label"),
          scaffold =
            properties("themePrimaryColor" to "#2BE4D3", "themeOnPrimaryColor" to "#00201E"),
          edgeButton =
            UiBuilderNode(
              "go",
              WearScreenCodeExporter.EDGE_BUTTON,
              properties("contentColor" to "#00201E"),
              slots = mapOf("content" to listOf("go-label")),
            ),
        )
      )

    assertTrue("MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(" in source, source)
    val directory = Path.of("build", "generated-wear-screen-source")
    Files.createDirectories(directory)
    Files.writeString(directory.resolve("ReskinnedScreen.kt"), source)
  }
}
