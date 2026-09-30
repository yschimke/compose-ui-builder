package ee.schimke.composeai.uibuilder.editor

import androidx.compose.ui.platform.ClipEntry

/**
 * [text] as something `LocalClipboard` can hold. `ClipEntry` is built differently on each platform
 * — an AWT transferable on the JVM, a browser clipboard item on the web — and this is the only part
 * of copying that is not common code.
 */
internal expect fun plainTextClipEntry(text: String): ClipEntry
