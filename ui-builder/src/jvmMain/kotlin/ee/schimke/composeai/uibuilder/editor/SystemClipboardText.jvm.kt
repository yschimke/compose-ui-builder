package ee.schimke.composeai.uibuilder.editor

import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor

internal actual suspend fun readSystemClipboardText(): String? =
  if (GraphicsEnvironment.isHeadless()) null
  else
    runCatching {
      Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
    }
      .getOrNull()

internal actual val readsSystemClipboardOnEveryPaste: Boolean = true
