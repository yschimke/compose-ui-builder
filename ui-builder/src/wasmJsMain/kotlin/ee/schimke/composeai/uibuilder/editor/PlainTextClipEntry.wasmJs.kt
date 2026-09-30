package ee.schimke.composeai.uibuilder.editor

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun plainTextClipEntry(text: String): ClipEntry = ClipEntry.withPlainText(text)
