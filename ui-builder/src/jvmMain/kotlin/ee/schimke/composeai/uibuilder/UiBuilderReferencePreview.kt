package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The reference overlay, drawn over a real design, with the panel that drives it.
 *
 * A new visual surface is wired into the preview workflow so the next change to it is diffed
 * without anyone remembering to — the same reason [UiBuilderEditorChromePreview] exists. What it
 * has to keep honest is a lot: the overlay itself, its layout boxes, the markup shapes, a placed
 * piece, and the Reference section of the Screen inspector, none of which a state test can see.
 *
 * Everything is fixed. The reference is an SVG built here as a string rather than a checked-in
 * picture, so the render depends on no binary fixture and the SVG lane — which is the one with a
 * parser behind it — is the lane being exercised. Nothing reads a clock, the network or a random
 * source.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderReferenceOverlayPreview() {
  UiBuilderEditor(
    document = referencePreviewDocument,
    catalog = editorChromePreviewCatalog,
    initialSelectedNodeId = "discover-grid",
    initialInspectorMode = EditorInspectorMode.Screen,
    restoredReference = referencePreviewOverlay,
  )
}

/**
 * The same overlay in difference mode, hiding nothing behind an opacity.
 *
 * A second preview rather than a second mode on the first, because the two are drawn by different
 * paths — one blends, one subtracts — and a regression in either would be invisible in the other.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderReferenceDifferencePreview() {
  UiBuilderEditor(
    document = referencePreviewDocument,
    catalog = editorChromePreviewCatalog,
    initialSelectedNodeId = "discover-grid",
    initialInspectorMode = EditorInspectorMode.Screen,
    restoredReference =
      referencePreviewOverlay.copy(
        settings =
          referencePreviewOverlay.settings.copy(
            mode = ReferenceDiffMode.Difference,
            alwaysShowBoxes = false,
          )
      ),
  )
}

private val referencePreviewDocument: UiBuilderDocument by lazy {
  UiBuilderReducer.replay(
      Json.parseToJsonElement(previewResource("/jetcaster-discover-operations-v1.json")).jsonObject
    )
    .document
}

/**
 * A mock, a piece dropped into place, and one mark of every kind that draws differently.
 *
 * Deliberately more than a screenshot would need: this render is the only place the arrow head, the
 * rounded corner, the erase fill and the centred label are checked at all, so all of them are on
 * the canvas at once.
 */
private val referencePreviewOverlay: RestoredReference by lazy {
  val svg =
    """
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 360 640">
      <rect data-name="Screen" width="360" height="640" fill="#101216"/>
      <rect data-name="Header" x="16" y="24" width="328" height="72" fill="#2b3040"/>
      <rect data-name="Card" x="16" y="120" width="156" height="200" fill="#3a4152"/>
      <rect data-name="Card 2" x="188" y="120" width="156" height="200" fill="#3a4152"/>
      <rect data-name="Row" x="16" y="344" width="328" height="88" fill="#242a36"/>
    </svg>
    """
      .trimIndent()
  val encoded = Base64.Default.encode(svg.encodeToByteArray())
  RestoredReference(
    image =
      ReferenceImage(
        id = "preview-reference",
        name = "discover-mock.svg",
        mediaType = ReferenceImage.SVG_MEDIA_TYPE,
        base64 = encoded,
        widthPx = 360,
        heightPx = 640,
        sourceUrl = "https://www.figma.com/design/preview?node-id=1-2",
      ),
    settings =
      ReferenceOverlaySettings(
        mode = ReferenceDiffMode.Overlay,
        opacityPercent = 45,
        alwaysShowBoxes = true,
      ),
    pieces =
      listOf(
        ReferencePiece(
          id = "piece-1",
          image =
            ReferenceImage(
              id = "preview-piece",
              name = "button.svg",
              mediaType = ReferenceImage.SVG_MEDIA_TYPE,
              base64 =
                Base64.Default.encode(
                  ("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 160 48">""" +
                      """<rect width="160" height="48" rx="24" fill="#b9c3ff"/></svg>""")
                    .encodeToByteArray()
                ),
              widthPx = 160,
              heightPx = 48,
            ),
          left = 0.32f,
          top = 0.74f,
          right = 0.68f,
          bottom = 0.82f,
          componentId = "m3/button",
        )
      ),
    marks =
      listOf(
        ReferenceMark(
          id = "mark-fill",
          kind = ReferenceMarkupKind.Fill,
          points = listOf(0.06f, 0.55f, 0.94f, 0.68f),
          colorArgb = 0xFF111318,
        ),
        ReferenceMark(
          id = "mark-box",
          kind = ReferenceMarkupKind.RoundedRectangle,
          points = listOf(0.06f, 0.18f, 0.47f, 0.5f),
          colorArgb = 0xFF00E676,
        ),
        ReferenceMark(
          id = "mark-arrow",
          kind = ReferenceMarkupKind.Arrow,
          points = listOf(0.5f, 0.12f, 0.86f, 0.3f),
          colorArgb = 0xFFFF5252,
        ),
        ReferenceMark(
          id = "mark-text",
          kind = ReferenceMarkupKind.Text,
          points = listOf(0.5f, 0.05f, 0.95f, 0.11f),
          colorArgb = 0xFFFFC400,
          text = "Header is 8dp short",
        ),
        ReferenceMark(
          id = "mark-image",
          kind = ReferenceMarkupKind.ImagePlaceholder,
          points = listOf(0.06f, 0.85f, 0.28f, 0.95f),
          colorArgb = 0xFF40C4FF,
          text = "Art",
        ),
      ),
  )
}
