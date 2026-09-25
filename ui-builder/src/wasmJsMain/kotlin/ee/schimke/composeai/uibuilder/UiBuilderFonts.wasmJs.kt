package ee.schimke.composeai.uibuilder

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.MainScope

internal actual fun platformFont(identity: String, data: ByteArray, weight: FontWeight): Font =
  androidx.compose.ui.text.platform.Font(identity, data, weight)

/**
 * The vendored families as this page serves them: `fonts/fonts.json` and its files, next to the
 * page, which is where both the editor's and the renderer runtime's builds copy `assets/rc-fonts`.
 *
 * Same-origin and token-carrying like every other request the page makes ([sameOriginRequestUrl]),
 * and cached by the browser like any static file, so a family is downloaded once per browser, not
 * once per design.
 */
@OptIn(ExperimentalEncodingApi::class)
fun browserFontRegistry(baseUrl: String = "fonts/"): UiBuilderFontRegistry =
  UiBuilderFontRegistry(
    scope = MainScope(),
    readManifest = { fetchText("${baseUrl}fonts.json") },
    readFont = { file -> Base64.decode(fetchBase64("$baseUrl$file")) },
  )
