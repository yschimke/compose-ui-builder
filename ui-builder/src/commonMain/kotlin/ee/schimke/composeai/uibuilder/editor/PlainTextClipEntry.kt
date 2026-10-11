package ee.schimke.composeai.uibuilder.editor

import androidx.compose.ui.platform.ClipEntry

/**
 * [text] as a `LocalClipboard` entry; `ClipEntry` is platform-specific (AWT transferable, browser
 * clipboard item).
 */
internal expect fun plainTextClipEntry(text: String): ClipEntry
