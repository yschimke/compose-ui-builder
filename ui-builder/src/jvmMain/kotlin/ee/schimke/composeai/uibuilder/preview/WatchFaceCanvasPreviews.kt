package ee.schimke.composeai.uibuilder.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlImporter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Watch Face Format faces drawn by the editor's canvas, one preview per behaviour a face relies on,
 * each from a small face's XML so what is drawn is what the importer reads. The time is fixed.
 */

/**
 * A slot with no provider chosen: its `<Complication type="EMPTY">` (a "+" to tap) is drawn, as the
 * watch draws it, beside a slot showing its text.
 */
@Preview(widthDp = 227, heightDp = 227)
@Composable
fun WatchFaceEmptyComplicationPreview() {
  WatchFaceXml(
    """
    <WatchFace width="450" height="450" clipShape="CIRCLE">
      <Scene backgroundColor="#ff101418">
        <ComplicationSlot slotId="0" supportedTypes="SHORT_TEXT EMPTY" x="60" y="165" width="120" height="120">
          <DefaultProviderPolicy defaultSystemProvider="EMPTY" defaultSystemProviderType="EMPTY"/>
          <BoundingOval x="0" y="0" width="120" height="120"/>
          <Complication type="EMPTY">
            <PartDraw x="0" y="0" width="120" height="120">
              <Ellipse x="4" y="4" width="112" height="112"><Stroke color="#ff4fc3f7" thickness="4"/></Ellipse>
              <Rectangle x="56" y="30" width="8" height="60"><Fill color="#ff4fc3f7"/></Rectangle>
              <Rectangle x="30" y="56" width="60" height="8"><Fill color="#ff4fc3f7"/></Rectangle>
            </PartDraw>
          </Complication>
        </ComplicationSlot>
        <ComplicationSlot slotId="1" supportedTypes="SHORT_TEXT EMPTY" x="270" y="165" width="120" height="120">
          <DefaultProviderPolicy defaultSystemProvider="WATCH_BATTERY" defaultSystemProviderType="SHORT_TEXT"/>
          <BoundingOval x="0" y="0" width="120" height="120"/>
          <Complication type="SHORT_TEXT">
            <PartText x="0" y="0" width="120" height="120">
              <Text><Font family="SYNC_TO_DEVICE" size="36" color="#ffffffff"><Template>%s<Parameter expression="[COMPLICATION.TEXT]"/></Template></Font></Text>
            </PartText>
          </Complication>
        </ComplicationSlot>
      </Scene>
    </WatchFace>
    """,
  )
}

/** [xml] imported and drawn by the canvas, 227dp square as the templates are, at 10:08:32. */
@Composable
internal fun WatchFaceXml(xml: String) {
  val document =
    remember(xml) {
      val imported =
        WatchFaceXmlImporter.import(xml.trimIndent()) as WatchFaceXmlImporter.Result.Imported
      imported.document.let {
        it.copy(
          environment =
            JsonObject(it.environment + ("fixedTime" to JsonPrimitive("2024-05-16T10:08:32Z"))),
        )
      }
    }
  MaterialTheme(darkColorScheme()) {
    CompositionLocalProvider(LocalUiBuilderCatalogPlatform provides "watchface") {
      Box(Modifier.size(227.dp)) { UiBuilderSurface(document) }
    }
  }
}
