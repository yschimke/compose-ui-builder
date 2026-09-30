package ee.schimke.composeai.uibuilder.editor

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import java.awt.datatransfer.StringSelection

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun plainTextClipEntry(text: String): ClipEntry = ClipEntry(StringSelection(text))
