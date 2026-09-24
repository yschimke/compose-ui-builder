package ee.schimke.composeai.uibuilder.figma

import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import ee.schimke.composeai.uibuilder.svg.JvmSkiaStructuredSvgRecorder
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Writes a design's Figma scene, sized from its measured layout: the file a plugin, or an agent
 * through the Figma MCP, builds from. `./gradlew :ui-builder:exportFigmaScene
 * -PuiBuilderDesign=<operations.json>`.
 */
object ExportFigmaScene {
  @JvmStatic
  fun main(args: Array<String>) {
    require(args.size == 2) { "usage: ExportFigmaScene <operations.json> <scene.json>" }
    val document =
      UiBuilderReducer.replay(Json.parseToJsonElement(File(args[0]).readText()).jsonObject).document
    val map =
      FigmaComponentMap.parse(
        checkNotNull(ExportFigmaScene::class.java.getResource("/m3-catalog-figma-map-v1.json"))
          .readText()
      )
    val scene =
      FigmaSceneExporter(map)
        .export(document, JvmSkiaStructuredSvgRecorder.measuredBoundsDp(document))
    File(args[1]).apply {
      parentFile.mkdirs()
      writeText(scene.encode())
    }
    scene.diagnostics.forEach { println("${it.code} ${it.nodeId}: ${it.message}") }
  }
}
