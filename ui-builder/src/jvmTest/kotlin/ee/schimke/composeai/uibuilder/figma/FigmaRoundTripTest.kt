package ee.schimke.composeai.uibuilder.figma

import ee.schimke.composeai.uibuilder.CapabilityDocumentWriteValidator
import ee.schimke.composeai.uibuilder.CapabilityPropertyWriteValidator
import ee.schimke.composeai.uibuilder.CollaborationReducer
import ee.schimke.composeai.uibuilder.CollaborationState
import ee.schimke.composeai.uibuilder.CommandOutcome
import ee.schimke.composeai.uibuilder.DesignCommand
import ee.schimke.composeai.uibuilder.DesignOperation
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import ee.schimke.composeai.uibuilder.export.optionalString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A designer's edits to an exported frame come back as one command at the exported revision, and
 * the reducer applies it like any other collaborator's.
 */
class FigmaRoundTripTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val map = FigmaComponentMap.parse(resource("/m3-catalog-figma-map-v1.json"))
  private val roundTrip = FigmaRoundTrip(catalog, map)
  private val properties = CapabilityPropertyWriteValidator(CapabilityValidator(catalog))
  private val documents = CapabilityDocumentWriteValidator(CapabilityValidator(catalog))

  private val base: UiBuilderDocument =
    UiBuilderReducer.replay(
        FigmaSnapshotImporter(catalog, map)
          .import(FigmaSnapshot.parse(resource("/figma/checkout-snapshot-v1.json")), "checkout")
          .operations
      )
      .document
  private val scene = FigmaSceneExporter(map).export(base)

  @Test
  fun `an untouched frame changes nothing`() {
    val result = reconcile(scene.asSnapshot())
    assertNull(result.command)
  }

  @Test
  fun `a designer's edits land on the same nodes`() {
    val edited =
      scene.root
        .edit("figma-10-13") { pay ->
          pay.copy(
            instance =
              pay.instance!!.copy(
                properties = pay.instance!!.properties + ("Label text" to JsonPrimitive("Pay now"))
              )
          )
        }
        .edit("figma-10-2") { it.copy(text = it.text!!.copy(characters = "Your order")) }
        .edit("figma-10-3") { it.copy(layout = it.layout!!.copy(itemSpacing = 24.0)) }
        .edit("figma-10-8") { box ->
          box.copy(
            instance =
              box.instance!!.copy(
                properties = box.instance!!.properties + ("Type" to JsonPrimitive("Unselected"))
              )
          )
        }
        .let { root ->
          // Delete the divider, move the promo to the end, and add a footnote after the title.
          val children = root.children.toMutableList()
          children.removeAll { it.id == "figma-10-6" }
          val promo = children.first { it.id == "figma-10-10" }
          children.remove(promo)
          children += promo
          children.add(
            1,
            FigmaSnapshotNode(
              id = "20:1",
              type = "TEXT",
              name = "Footnote",
              sizing = FigmaSizing(FigmaSizing.HUG, FigmaSizing.HUG),
              text = FigmaText(characters = "Prices include tax", style = "M3/body/small"),
            ),
          )
          root.copy(children = children)
        }

    val command = checkNotNull(reconcile(FigmaSnapshot(root = edited)).command)
    assertEquals(base.revision, command.baseRevision)

    val applied = apply(base, command)
    val document = applied.document
    assertEquals("Pay now", document.text("figma-10-13-label"))
    assertEquals("Your order", document.text("figma-10-2"))
    assertEquals("24.0", document.value("figma-10-3", "horizontalSpacingDp"))
    assertEquals("false", document.value("figma-10-8", "checked"))
    assertTrue("figma-10-6" !in document.nodes)
    val children = document.nodes.getValue("figma-10-1").slots.getValue("children")
    assertEquals("figma-10-10", children.last())
    assertEquals(listOf("figma-10-2", "figma-20-1"), children.take(2))
    assertEquals("Prices include tax", document.text("figma-20-1"))
    assertEquals(emptyList(), CapabilityValidator(catalog).validate(document).issues)
  }

  @Test
  fun `a modifier Figma cannot express keeps its place in the chain`() {
    val root = base.nodes.getValue("figma-10-1")
    val faded =
      base.copy(
        nodes =
          base.nodes +
            ("figma-10-1" to
              root.copy(
                modifiers =
                  JsonArray(
                    root.modifiers +
                      buildJsonObject {
                        put("type", "alpha")
                        put("alpha", 0.5)
                      }
                  )
              ))
      )
    val fadedScene = FigmaSceneExporter(map).export(faded)
    val edited =
      fadedScene.root.copy(
        layout = fadedScene.root.layout!!.copy(padding = FigmaPadding(8.0, 8.0, 8.0, 8.0))
      )
    val command =
      checkNotNull(
        roundTrip
          .reconcile(faded, fadedScene, FigmaSnapshot(root = edited), "figma", "plugin", "rt-1")
          .command
      )
    val modifiers =
      command.operations.filterIsInstance<DesignOperation.SetModifiers>().single().modifiers
    assertEquals(
      listOf("fillMaxSize", "background", "padding", "alpha"),
      modifiers.map { it.jsonObject.optionalString("type") },
    )
    assertEquals("8", modifiers[2].jsonObject["startDp"]?.jsonPrimitive?.content)
  }

  @Test
  fun `an edit the design has overtaken is applied with a conflict notice`() {
    val concurrent =
      DesignCommand(
        designId = base.id,
        operationId = "browser-edit",
        actorId = "someone",
        clientId = "browser",
        baseRevision = base.revision,
        operations =
          listOf(
            DesignOperation.SetProperty(
              "figma-10-2",
              "text",
              buildJsonObject {
                put("type", "string")
                put("value", "Basket")
              },
            )
          ),
      )
    val first =
      CollaborationReducer.apply(CollaborationState(base), concurrent, properties, documents)
    assertIs<CommandOutcome.Accepted>(first.outcome)

    val edited =
      scene.root.edit("figma-10-2") { it.copy(text = it.text!!.copy(characters = "Your order")) }
    val command = checkNotNull(reconcile(FigmaSnapshot(root = edited)).command)
    val second = CollaborationReducer.apply(first.state, command, properties, documents)
    val outcome = assertIs<CommandOutcome.Accepted>(second.outcome)
    assertTrue(outcome.conflicts.any { it.nodeId == "figma-10-2" }, outcome.toString())
    assertEquals("Your order", second.state.document.text("figma-10-2"))
  }

  @Test
  fun `a frame from another design is refused`() {
    val foreign = scene.root.copy(stamp = scene.root.stamp!!.copy(designId = "someone-else"))
    val refused = runCatching { reconcile(FigmaSnapshot(root = foreign)) }
    assertTrue(refused.isFailure)
  }

  private fun reconcile(snapshot: FigmaSnapshot) =
    roundTrip.reconcile(base, scene, snapshot, "figma", "plugin", "round-trip-1")

  private fun apply(document: UiBuilderDocument, command: DesignCommand): CollaborationState {
    val application =
      CollaborationReducer.apply(CollaborationState(document), command, properties, documents)
    assertIs<CommandOutcome.Accepted>(application.outcome, application.outcome.toString())
    return application.state
  }

  private fun FigmaSnapshotNode.edit(
    id: String,
    change: (FigmaSnapshotNode) -> FigmaSnapshotNode,
  ): FigmaSnapshotNode =
    if (this.id == id) change(this) else copy(children = children.map { it.edit(id, change) })

  private fun UiBuilderDocument.value(id: String, property: String): String? =
    (nodes.getValue(id).properties[property] as? JsonObject)?.get("value")?.jsonPrimitive?.content

  private fun UiBuilderDocument.text(id: String): String? = value(id, "text")

  private fun resource(path: String): String =
    checkNotNull(javaClass.getResource(path)) { "missing resource $path" }.readText()
}
