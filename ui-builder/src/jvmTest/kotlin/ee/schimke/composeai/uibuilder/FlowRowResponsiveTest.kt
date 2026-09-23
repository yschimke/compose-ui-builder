package ee.schimke.composeai.uibuilder

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.codegen.CapabilityComposeCodeExporter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `layout/flow-row`: the one layout primitive that responds to a narrow window on its own.
 *
 * ## What it is for
 *
 * `docs/design/UI_BUILDER_GOOGLE_APP_SAMPLES.md`, gap 3: *"Material chip groups wrap. `layout/row`
 * does not, and there is no `FlowRow`. At 411 dp Gmail's four filter chips and Photos' five squeeze
 * until 'Attachments' is one letter per line — the single worst thing in the compact renders, and
 * there is no way to author around it short of branching on width, which is what an adaptive layout
 * exists to avoid."*
 *
 * That is the whole argument for the component, and it is why this lives in the **foundation**
 * rather than in m3-catalog: `FlowRow` is `androidx.compose.foundation.layout`, one declaration
 * shared by every platform, exactly like `layout/row` beside it. Nothing about it is Material.
 *
 * ## What is asserted
 *
 * The wrap itself, at a width narrow enough to force it, and then the generated Kotlin. The first
 * is the point; the second is what makes the point survive the export, which is where gap 3 would
 * otherwise reappear — a canvas that wraps and a generated screen that does not is the
 * "wrong-picture-that-compiles" failure this repository keeps naming.
 */
@OptIn(ExperimentalTestApi::class)
class FlowRowResponsiveTest {
  private val catalog by lazy {
    CapabilityCatalogParser.parse(
      checkNotNull(javaClass.getResource("/m3-catalog-capabilities-v1.json")).readText()
    )
  }

  private fun string(value: String) =
    JsonObject(mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive(value)))

  // The wrapper vocabulary the reducer actually knows — `number` is not one of its types, which
  // is the whole of yschimke/compose-preview-server#901.
  private fun float(value: Number) =
    JsonObject(mapOf("type" to JsonPrimitive("float"), "value" to JsonPrimitive(value)))

  private fun int(value: Int) =
    JsonObject(mapOf("type" to JsonPrimitive("int"), "value" to JsonPrimitive(value)))

  /** Four labelled chips in one container, which is the shape the samples squeeze. */
  private fun chipRow(
    componentId: String,
    properties: Map<String, JsonObject> = emptyMap(),
  ): UiBuilderDocument {
    val labels = listOf("Unread", "Starred", "Attachments", "Calendar")
    return UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "flow-row",
      // Not "Flow row": the generated composable is named after the design, and a screen called
      // `FlowRow` would make every assertion below pass for the wrong reason.
      title = "Filter bar",
      revision = 0,
      // The pin the exporter checks against the catalog it was handed; without it every export
      // refuses before it reaches a single node.
      catalogPin =
        JsonObject(
          mapOf(
            "systemId" to JsonPrimitive("m3-catalog"),
            "catalogRevision" to JsonPrimitive("candidate"),
            "capabilityDigest" to JsonPrimitive("candidate"),
            "nativeRuntimeId" to JsonPrimitive("candidate"),
          )
        ),
      // The frame the export records as provenance; an empty one is refused outright.
      environment =
        JsonObject(
          mapOf(
            "widthDp" to JsonPrimitive(411),
            "heightDp" to JsonPrimitive(891),
            "density" to JsonPrimitive(1.0),
            "theme" to JsonPrimitive("dark"),
            "fontScale" to JsonPrimitive(1.0),
          )
        ),
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("bar"),
      nodes =
        mapOf(
          "bar" to
            UiBuilderNode(
              id = "bar",
              componentId = componentId,
              properties = JsonObject(properties),
              slots = mapOf("children" to labels.indices.map { "chip-$it" }),
            )
        ) +
          labels.withIndex().associate { (index, label) ->
            "chip-$index" to
              UiBuilderNode(
                id = "chip-$index",
                componentId = "m3/text",
                properties = JsonObject(mapOf("text" to string(label))),
              )
          },
    )
  }

  /**
   * The top of each label, in the order the labels were declared.
   *
   * Both containers are aligned to the top of their line, because the renderer centres a row's
   * children by default and four labels of different heights would then have four different tops
   * for reasons that have nothing to do with wrapping.
   */
  private fun labelTops(componentId: String): List<Float> {
    val alignment =
      if (componentId == "layout/row") mapOf("verticalAlignment" to string("top"))
      else mapOf("verticalArrangement" to string("top"))
    var tops = emptyList<Float>()
    runDesktopComposeUiTest(width = 200, height = 400) {
      setContent {
        UiBuilderSurface(chipRow(componentId, alignment + ("horizontalSpacingDp" to float(8))))
      }
      tops =
        listOf("Unread", "Starred", "Attachments", "Calendar").map { label ->
          onAllNodesWithText(label, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .single()
            .boundsInRoot
            .top
        }
    }
    return tops
  }

  @Test
  fun `a flow row puts what does not fit on the next line, and a row does not`() {
    // The comparison is the assertion. A single-line row and a wrapping one draw the same four
    // children at the same width, and the only difference that matters is how many lines they
    // took — so this cannot pass by the labels having been laid out some other way.
    val rowTops = labelTops("layout/row")
    val flowTops = labelTops("layout/flow-row")

    assertEquals(
      1,
      rowTops.distinct().size,
      "a plain row is one line by construction; if this ever wraps the comparison below means " +
        "nothing. Tops: $rowTops",
    )
    assertTrue(
      flowTops.distinct().size > 1,
      "220dp is not wide enough for four chips, so a flow row must have wrapped. Tops: $flowTops",
    )
    // Wrapped, not reordered: the first child is still on the first line and a later one moved
    // down, rather than the whole set shifting.
    assertEquals(flowTops.first(), flowTops.min(), "the first child left the first line")
  }

  @Test
  fun `the generated Kotlin is a FlowRow, with both axes and the opt-in it needs`() {
    val exported =
      CapabilityComposeCodeExporter.export(
        chipRow(
          "layout/flow-row",
          mapOf(
            "horizontalSpacingDp" to float(8),
            "verticalSpacingDp" to float(4),
            "maxItemsInEachRow" to int(3),
          ),
        ),
        catalog,
      )
    val source = assertNotNull(exported.source, exported.diagnostics.joinToString("\n"))

    assertTrue("FlowRow(" in source, source)
    // Both axes: along a line it arranges like a row, down the lines like a column. Writing only
    // the first is how a design's line spacing gets silently dropped.
    assertTrue("horizontalArrangement = Arrangement.spacedBy(8.dp)" in source, source)
    assertTrue("verticalArrangement = Arrangement.spacedBy(4.dp)" in source, source)
    assertTrue("maxItemsInEachRow = 3" in source, source)
    // No import to assert: `androidx.compose.foundation.layout.*` is unconditional and carries
    // both `FlowRow` and its annotation. The opt-in is the part that is conditional, and without
    // it the file does not compile in the project that receives it.
    assertTrue("ExperimentalLayoutApi::class" in source, source)
  }

  @Test
  fun `a design with no flow row carries neither the opt-in nor the import`() {
    // The same gate the adaptive imports get, for the same reason: an opt-in nobody asked for is a
    // warning in somebody else's project, on a file they did not write.
    val source =
      assertNotNull(CapabilityComposeCodeExporter.export(chipRow("layout/row"), catalog).source)

    assertTrue("ExperimentalLayoutApi" !in source, source)
    assertTrue("FlowRow" !in source, source)
  }
}
