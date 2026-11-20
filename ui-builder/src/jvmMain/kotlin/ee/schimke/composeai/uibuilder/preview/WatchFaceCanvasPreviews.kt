package ee.schimke.composeai.uibuilder.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.canvas.LocalWffPreviewClock
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.canvas.WffPreviewClockState
import ee.schimke.composeai.uibuilder.canvas.WffScrubTime
import ee.schimke.composeai.uibuilder.editor.WffPreviewClockControls
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlImporter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Watch Face Format faces drawn by the editor's canvas, one preview per behaviour a face relies on,
 * each from a small face's XML so what is drawn is what the importer reads. The time is fixed, or
 * scrubbed on an editor's preview clock.
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

/**
 * [xml] imported and drawn by the canvas, 227dp square as the templates are, at 10:08:32; or, with
 * [scrubbed], at that time on an editor's preview clock, its animations played that far.
 */
@Composable
internal fun WatchFaceXml(xml: String, scrubbed: WffScrubTime? = null) {
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
    val clock =
      remember(scrubbed) { scrubbed?.let { WffPreviewClockState().apply { this.scrubbed = it } } }
    CompositionLocalProvider(
      LocalUiBuilderCatalogPlatform provides "watchface",
      LocalWffPreviewClock provides clock,
    ) {
      Box(Modifier.size(227.dp)) { UiBuilderSurface(document) }
    }
  }
}

/**
 * A face whose parts animate: a ring that pulses for ever (`scaleX`/`scaleY` from 0.6, eased in and
 * out over two seconds, `repeat="-1"`), a dot that fades in once over the first second, and hands
 * driven by the clock. Drawn at scrubbed times by the previews below.
 */
private const val ANIMATED_FACE =
  """
  <WatchFace width="450" height="450" clipShape="CIRCLE">
    <Scene backgroundColor="#ff0e1319">
      <Group name="ring" x="0" y="0" width="450" height="450" pivotX="0.5" pivotY="0.5" scaleX="0.6" scaleY="0.6">
        <Transform target="scaleX" value="1"><Animation duration="2" interpolation="EASE_IN_OUT" repeat="-1"/></Transform>
        <Transform target="scaleY" value="1"><Animation duration="2" interpolation="EASE_IN_OUT" repeat="-1"/></Transform>
        <PartDraw x="0" y="0" width="450" height="450">
          <Ellipse x="25" y="25" width="400" height="400"><Stroke color="#ff4fc3f7" thickness="14"/></Ellipse>
        </PartDraw>
      </Group>
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
      <Group name="second" x="0" y="0" width="450" height="450" pivotX="0.5" pivotY="0.5">
        <Transform target="angle" value="[SECOND_MILLISECOND]*6"/>
        <PartDraw x="223" y="50" width="4" height="180">
          <Rectangle x="0" y="0" width="4" height="180"><Fill color="#fff6b26b"/></Rectangle>
        </PartDraw>
      </Group>
    </Scene>
  </WatchFace>
  """

/** The animated face at the top of the minute: the ring at its smallest, the dot not yet in. */
@Preview(name = "Animated face at 10:08:00.0", widthDp = 227, heightDp = 227)
@Composable
fun WatchFaceAnimatedAtStartPreview() {
  WatchFaceXml(ANIMATED_FACE, WffScrubTime(10, 8, 0.0))
}

/** Half a second in: the dot half faded in, the ring a quarter of the way through its pulse. */
@Preview(name = "Animated face at 10:08:00.5", widthDp = 227, heightDp = 227)
@Composable
fun WatchFaceAnimatedAtHalfSecondPreview() {
  WatchFaceXml(ANIMATED_FACE, WffScrubTime(10, 8, 0.5))
}

/** One second in: the dot in, the ring at its widest. */
@Preview(name = "Animated face at 10:08:01.0", widthDp = 227, heightDp = 227)
@Composable
fun WatchFaceAnimatedAtOneSecondPreview() {
  WatchFaceXml(ANIMATED_FACE, WffScrubTime(10, 8, 1.0))
}

/** Half a minute on, at 3:42: the hands moved, the dot stays in, the ring pulses on. */
@Preview(name = "Animated face at 03:42:30.5", widthDp = 227, heightDp = 227)
@Composable
fun WatchFaceAnimatedAtHalfMinutePreview() {
  WatchFaceXml(ANIMATED_FACE, WffScrubTime(3, 42, 30.5))
}

/** The same face as an export draws it: its fixed time, every animation settled at its end. */
@Preview(name = "Animated face, settled", widthDp = 227, heightDp = 227)
@Composable
fun WatchFaceAnimatedSettledPreview() {
  WatchFaceXml(ANIMATED_FACE)
}

/** The preview clock's controls, scrubbed to 10:08:00.5 and paused. */
@Preview(name = "Preview clock, scrubbed", widthDp = 300, heightDp = 200)
@Composable
fun WffPreviewClockControlsPreview() {
  val clock = remember { WffPreviewClockState().apply { scrubbed = WffScrubTime(10, 8, 0.5) } }
  MaterialTheme(darkColorScheme()) {
    Surface { WffPreviewClockControls(clock, Modifier.padding(12.dp)) }
  }
}
