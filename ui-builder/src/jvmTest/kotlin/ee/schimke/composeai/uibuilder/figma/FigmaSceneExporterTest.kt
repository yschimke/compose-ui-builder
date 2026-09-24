package ee.schimke.composeai.uibuilder.figma

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * A design exported as a Figma scene, read back as though Figma built it unchanged, is the same
 * design: every node keeps its id through the stamp, and everything the map covers survives.
 */
class FigmaSceneExporterTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val map = FigmaComponentMap.parse(resource("/m3-catalog-figma-map-v1.json"))
  private val importer = FigmaSnapshotImporter(catalog, map)
  private val exporter = FigmaSceneExporter(map)

  private val checkout =
    UiBuilderReducer.replay(
        importer
          .import(FigmaSnapshot.parse(resource("/figma/checkout-snapshot-v1.json")), "checkout")
          .operations
      )
      .document

  @Test
  fun `export then import is the identity on the mapped vocabulary`() {
    val scene = exporter.export(checkout)
    val back =
      UiBuilderReducer.replay(importer.import(scene.asSnapshot(), checkout.id).operations).document
    assertEquals(checkout.roots, back.roots)
    assertEquals(checkout.environment, back.environment)
    assertEquals(checkout.title, back.title)
    checkout.nodes.forEach { (id, node) -> assertEquals(node, back.nodes[id], "node $id") }
    assertEquals(checkout.nodes.keys, back.nodes.keys)
  }

  @Test
  fun `every scene node is stamped with the design, the revision and its builder id`() {
    val scene = exporter.export(checkout)
    fun walk(node: FigmaSnapshotNode): List<FigmaSnapshotNode> =
      listOf(node) + node.children.flatMap(::walk)
    val nodes = walk(scene.root)
    assertEquals(checkout.nodes.size - 1, nodes.size, "the button's label rides on its instance")
    nodes.forEach { node ->
      val stamp = checkNotNull(node.stamp) { "${node.id} is not stamped" }
      assertEquals(checkout.id, stamp.designId)
      assertEquals(checkout.revision, stamp.revision)
      assertEquals(node.id, stamp.nodeId)
    }
  }

  @Test
  fun `a mapped component becomes a kit instance with its label as a property`() {
    val pay = exporter.export(checkout).root.children.single { it.id == "figma-10-13" }
    assertEquals("INSTANCE", pay.type)
    val instance = checkNotNull(pay.instance)
    assertEquals("Button", instance.componentSet)
    assertEquals("Enabled", instance.properties["State"]?.content)
    assertEquals("Pay $42.00", instance.properties["Label text"]?.content)
    assertEquals("figma-10-13-label", pay.stamp?.labelNodeId)
    assertEquals(FigmaSizing.FILL, pay.sizing.horizontal)
  }

  @Test
  fun `tokens go out as the variables and styles the map names`() {
    val root = exporter.export(checkout).root
    assertEquals("Schemes/Surface", root.fill?.variable)
    val title = root.children.first()
    assertEquals("M3/headline/medium", title.text?.style)
    assertEquals("Schemes/On Surface", title.fill?.variable)
  }

  @Test
  fun `a committed design exports, names what Figma has no counterpart for, and comes back valid`() {
    val gmail =
      UiBuilderReducer.replay(
          Json.parseToJsonElement(resource("/designs/google-gmail-tablet.json")).jsonObject
        )
        .document
    val scene = exporter.export(gmail)
    assertTrue(
      scene.diagnostics.any {
        it.code == FigmaSceneDiagnostic.UNMAPPED_COMPONENT && "m3/icon " in it.message
      },
      scene.diagnostics.joinToString("\n"),
    )
    assertEquals(scene, FigmaScene.parse(scene.encode()), "the scene survives its own encoding")
    assertTrue(
      scene.encode().startsWith("{\"schema\":\"${FigmaScene.SCHEMA}\""),
      "the schema is written, so a plugin can check it",
    )

    val back =
      UiBuilderReducer.replay(importer.import(scene.asSnapshot(), gmail.id).operations).document
    assertEquals(emptyList(), CapabilityValidator(catalog).validate(back).issues)

    assertEquals(gmail.nodes.keys, back.nodes.keys, "nothing is dropped")
    assertTrue(scene.diagnostics.any { it.code == FigmaSceneDiagnostic.NOT_REPRESENTABLE })
  }

  private fun resource(path: String): String =
    checkNotNull(javaClass.getResource(path)) { "missing resource $path" }.readText()
}
