package ee.schimke.composeai.uibuilder.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.EditorPane
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlImporter
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import ee.schimke.composeai.uibuilder.service.PublishedUiBuilderCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A watch face in the editor with its Preview pane open: the editing canvas carries the
 * Interactive/Ambient toggle and the preview clock (Live, or Scrub with play and pause), and the
 * Preview pane the same clock above its interactive and ambient frames.
 */
@Preview(widthDp = 1400, heightDp = 800)
@Composable
fun WatchFaceEditorPreviewClockPreview() {
  val document = remember {
    val imported =
      (WatchFaceXmlImporter.import(WATCH_FACE_EDITOR_XML.trimIndent())
          as WatchFaceXmlImporter.Result.Imported)
        .document
    imported.copy(
      id = "animated-face",
      title = "Animated face",
      catalogPin = JsonObject(imported.catalogPin + ("systemId" to JsonPrimitive("wff-watchface"))),
      environment =
        JsonObject(
          imported.environment +
            mapOf(
              "fixedTime" to JsonPrimitive("2024-05-16T10:08:32Z"),
              "exportDevices" to JsonArray(listOf(JsonPrimitive("id:wearos_large_round"))),
            ),
        ),
    )
  }
  UiBuilderEditor(
    document = document,
    catalog = wffPreviewCatalog,
    initialPanes = setOf(EditorPane.Editor, EditorPane.Preview),
  )
}

/** The watch-face catalog this repository carries, served as the editor receives it. */
private val wffPreviewCatalog by lazy {
  val published =
    checkNotNull(
        WatchFaceEditorClockResources::class
          .java
          .classLoader
          .getResource("wff-watchface-published-v1.json"),
      )
      .readText()
  val composed =
    PublishedUiBuilderCatalog.compose(
      published,
      null,
      ExportCapabilitiesV1.Builder().apply { composeCode = true }.build(),
    ) as PublishedUiBuilderCatalog.Result.Composed
  val served =
    CurrentM3UiBuilderCatalogExecutor.Builder()
      .also {
        it.catalogSystemIds = setOf("wff-watchface")
        it.published = mapOf("wff-watchface" to composed.catalog)
      }
      .build()
      .listCatalogs()
      .single { it.benchmark.catalogSystemId == "wff-watchface" }
  CapabilityCatalogParser.parse(Json.encodeToString(served))
}

private const val WATCH_FACE_EDITOR_XML =
  """
  <WatchFace width="450" height="450" clipShape="CIRCLE">
    <Scene backgroundColor="#ff0e1319">
      <Group name="dot" x="0" y="0" width="450" height="450" alpha="0">
        <Transform target="alpha" value="255"><Animation duration="1" interpolation="LINEAR"/></Transform>
        <PartDraw x="195" y="300" width="60" height="60">
          <Ellipse x="0" y="0" width="60" height="60"><Fill color="#fff6b26b"/></Ellipse>
        </PartDraw>
      </Group>
      <Group name="hour" x="0" y="0" width="450" height="450" pivotX="0.5" pivotY="0.5">
        <Transform target="angle" value="([HOUR_0_11]+[MINUTE]/60)*30"/>
        <PartDraw x="215" y="110" width="20" height="120">
          <Rectangle x="0" y="0" width="20" height="120"><Fill color="#ffffffff"/></Rectangle>
        </PartDraw>
      </Group>
      <Group name="minute" x="0" y="0" width="450" height="450" pivotX="0.5" pivotY="0.5">
        <Transform target="angle" value="([MINUTE]+[SECOND]/60)*6"/>
        <PartDraw x="219" y="60" width="12" height="170">
          <Rectangle x="0" y="0" width="12" height="170"><Fill color="#ffffffff"/></Rectangle>
        </PartDraw>
      </Group>
    </Scene>
  </WatchFace>
  """

/** An anchor for this module's resources. */
private object WatchFaceEditorClockResources
