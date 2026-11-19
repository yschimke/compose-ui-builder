package ee.schimke.composeai.uibuilder.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.LocalUiBuilderAssetBitmaps
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlImporter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `<PartImage>`s whose `resource` is an uploaded design asset (`ui_builder_put_asset`): the dial
 * badge drawn in its own colours, and a white mark tinted by `tintColor`, as the watch draws them.
 */
@Preview(widthDp = 227, heightDp = 227)
@Composable
fun WatchFacePartImageAssetPreview() {
  val bitmaps = remember { mapOf("sha256:badge" to badge(), "sha256:mark" to mark()) }
  val document = remember {
    val imported =
      WatchFaceXmlImporter.import(
        """
        <WatchFace width="450" height="450" clipShape="CIRCLE">
          <Scene backgroundColor="#ff101418">
            <PartImage x="125" y="60" width="200" height="200">
              <Image resource="badge"/>
            </PartImage>
            <PartImage x="175" y="290" width="100" height="100" tintColor="#fff6b26b">
              <Image resource="mark"/>
            </PartImage>
          </Scene>
        </WatchFace>
        """
          .trimIndent(),
      ) as WatchFaceXmlImporter.Result.Imported
    imported.document.copy(
      environment =
        JsonObject(
          imported.document.environment + ("fixedTime" to JsonPrimitive("2024-05-16T10:08:32Z")),
        ),
      assets = JsonObject(mapOf("badge" to uploaded("badge"), "mark" to uploaded("mark"))),
    )
  }
  MaterialTheme(darkColorScheme()) {
    CompositionLocalProvider(
      LocalUiBuilderCatalogPlatform provides "watchface",
      LocalUiBuilderAssetBitmaps provides { digest -> bitmaps[digest] },
    ) {
      Box(Modifier.size(227.dp)) { UiBuilderSurface(document) }
    }
  }
}

private fun uploaded(key: String) =
  JsonObject(
    mapOf(
      "mediaType" to JsonPrimitive("image/png"),
      "contentDigest" to JsonPrimitive("sha256:$key"),
      "source" to
        JsonObject(
          mapOf("type" to JsonPrimitive("uploaded"), "storageKey" to JsonPrimitive(key)),
        ),
    ),
  )

private fun picture(draw: DrawScope.() -> Unit): ImageBitmap {
  val bitmap = ImageBitmap(128, 128)
  CanvasDrawScope()
    .draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(128f, 128f), block = draw)
  return bitmap
}

/** A full-colour badge: a gradient disc in a light ring. */
private fun badge() = picture {
  drawCircle(
    Brush.linearGradient(
      listOf(Color(0xFF4FC3F7), Color(0xFF7E57C2)),
      Offset.Zero,
      Offset(128f, 128f),
    ),
    radius = 60f,
  )
  drawCircle(Color(0xFFE0F7FA), radius = 60f, style = Stroke(6f))
  drawCircle(Color.White, radius = 14f)
}

/** A white mark on transparency, for a tint to colour. */
private fun mark() = picture {
  drawCircle(Color.White, radius = 56f, style = Stroke(12f))
  drawRect(Color.White, Offset(56f, 24f), Size(16f, 48f))
  drawRect(Color.White, Offset(56f, 56f), Size(40f, 16f))
}
