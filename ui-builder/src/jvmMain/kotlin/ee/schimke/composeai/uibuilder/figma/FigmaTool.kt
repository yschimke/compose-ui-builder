package ee.schimke.composeai.uibuilder.figma

import ee.schimke.composeai.uibuilder.DesignCommand
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import ee.schimke.composeai.uibuilder.svg.JvmSkiaStructuredSvgRecorder
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The Figma lane from a terminal, for a host or an agent driving Figma through its MCP:
 *
 * - `import <snapshot.json> <designId> <operations.json>` — a frame as a design;
 * - `export <operations.json> <scene.json>` — a design as a scene, sized from its layout;
 * - `reconcile <operations.json> <scene.json> <snapshot.json> <command.json>` — Figma edits as a
 *   command at the scene's revision.
 *
 * Diagnostics go to standard output. `./gradlew :ui-builder:figmaTool -PfigmaArgs="…"`.
 */
object FigmaTool {
  private val json = Json {
    prettyPrint = true
    encodeDefaults = false
  }

  @JvmStatic
  fun main(args: Array<String>) {
    val loader = FigmaTool::class.java
    val map =
      FigmaComponentMap.parse(
        checkNotNull(loader.getResource("/m3-catalog-figma-map-v1.json")).readText()
      )
    val catalog =
      CapabilityCatalogParser.parse(
        checkNotNull(loader.getResource("/${map.catalog}-capabilities-v1.json")).readText()
      )
    fun design(path: String) =
      UiBuilderReducer.replay(Json.parseToJsonElement(File(path).readText()).jsonObject).document

    when (args.firstOrNull()) {
      "import" -> {
        require(args.size == 4) { "usage: import <snapshot.json> <designId> <operations.json>" }
        val result =
          FigmaSnapshotImporter(catalog, map)
            .import(FigmaSnapshot.parse(File(args[1]).readText()), args[2])
        File(args[3]).write(json.encodeToString(result.operations))
        result.diagnostics.forEach { println("${it.code} ${it.figmaNodeId}: ${it.message}") }
      }
      "export" -> {
        require(args.size == 3) { "usage: export <operations.json> <scene.json>" }
        val document = design(args[1])
        val scene =
          FigmaSceneExporter(map)
            .export(document, JvmSkiaStructuredSvgRecorder.measuredBoundsDp(document))
        File(args[2]).write(scene.encode())
        scene.diagnostics.forEach { println("${it.code} ${it.nodeId}: ${it.message}") }
      }
      "reconcile" -> {
        require(args.size == 5) {
          "usage: reconcile <operations.json> <scene.json> <snapshot.json> <command.json>"
        }
        val result =
          FigmaRoundTrip(catalog, map)
            .reconcile(
              base = design(args[1]),
              scene = FigmaScene.parse(File(args[2]).readText()),
              snapshot = FigmaSnapshot.parse(File(args[3]).readText()),
              actorId = "figma",
              clientId = "figma-tool",
              operationId = "figma-${File(args[3]).nameWithoutExtension}",
            )
        File(args[4])
          .write(
            result.command?.let { json.encodeToString(DesignCommand.serializer(), it) } ?: "null"
          )
        result.diagnostics.forEach { println("${it.code} ${it.figmaNodeId}: ${it.message}") }
        println("${result.command?.operations?.size ?: 0} operations")
        result.deletions.forEach {
          println(
            "held back (the design moved on; submit at its current revision): delete ${it.nodeId}"
          )
        }
      }
      else -> error("usage: figmaTool import|export|reconcile …")
    }
  }

  private fun File.write(text: String) {
    absoluteFile.parentFile.mkdirs()
    writeText(text)
  }
}
