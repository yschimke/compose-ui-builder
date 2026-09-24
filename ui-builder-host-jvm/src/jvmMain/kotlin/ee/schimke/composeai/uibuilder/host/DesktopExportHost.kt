package ee.schimke.composeai.uibuilder.host

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.editor.EditorExportFormat
import ee.schimke.composeai.uibuilder.editor.UiBuilderExportHost
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.svg.JvmDocumentRasterizer
import ee.schimke.composeai.uibuilder.svg.JvmSkiaStructuredSvgRecorder
import ee.schimke.composeai.uibuilder.svg.SavedDocumentRevisionPin
import ee.schimke.composeai.uibuilder.svg.SavedDocumentSvgExportJob
import ee.schimke.composeai.uibuilder.svg.SavedDocumentSvgExportResult
import ee.schimke.composeai.uibuilder.svg.StructuredSvgRecorderKind
import ee.schimke.composeai.uibuilder.svg.executeSavedDocumentSvgExport
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The desktop half of [UiBuilderExportHost]: renders in this process, writes to the clipboard and
 * to a file the person picks.
 *
 * The browser host asks the server for its pictures. A desktop or IntelliJ session has no server to
 * ask, and before this host existed those hosts offered no Export menu at all. The JVM target
 * already carries the structured SVG recorder the server's export lane is built on, so SVG here is
 * the same Figma-pasteable markup, and PNG is the same surface drawn to a raster
 * ([JvmDocumentRasterizer]).
 *
 * There are no links: a local design has no URL for anyone else to open.
 */
class DesktopExportHost(
  private val catalog: CapabilityCatalog,
  /** The design as the editor currently shows it, including edits still being saved. */
  private val document: () -> UiBuilderDocument?,
  private val files: ExportFileTarget = AwtSaveDialog,
  private val clipboard: ExportClipboard = SystemClipboard,
) : UiBuilderExportHost {
  override val formats: List<EditorExportFormat> =
    listOf(EditorExportFormat.Svg, EditorExportFormat.Png)

  override val supportsLinks: Boolean
    get() = false

  override suspend fun copyPicture(format: EditorExportFormat): String {
    val rendered =
      when (val result = render(format)) {
        is Rendered.Refused -> return result.sentence
        is Rendered.Bytes -> result
      }
    return runCatching {
        when (format) {
          EditorExportFormat.Png -> clipboard.putImage(ImageIO.read(rendered.bytes.inputStream()))
          else -> clipboard.putText(rendered.bytes.decodeToString())
        }
      }
      .fold(
        onSuccess = {
          if (format == EditorExportFormat.Svg) "SVG copied — paste it into Figma"
          else "${format.label} copied"
        },
        onFailure = { "Copy ${format.label} failed: ${it.message ?: it::class.simpleName}" },
      )
  }

  override suspend fun copyLink(format: EditorExportFormat): String =
    "A local design has no export link; download the file instead"

  override suspend fun download(format: EditorExportFormat): String {
    val current = document() ?: return "The design is still opening"
    val rendered =
      when (val result = render(format)) {
        is Rendered.Refused -> return result.sentence
        is Rendered.Bytes -> result
      }
    val target =
      files.choose("${current.id}.${format.extension}", format.extension)
        ?: return "Export cancelled"
    return runCatching { withContext(Dispatchers.IO) { Files.write(target, rendered.bytes) } }
      .fold(
        onSuccess = { "Saved ${target.fileName}" },
        onFailure = { "Saving ${target.fileName} failed: ${it.message ?: it::class.simpleName}" },
      )
  }

  private suspend fun render(format: EditorExportFormat): Rendered {
    val current = document() ?: return Rendered.Refused("The design is still opening")
    return withContext(Dispatchers.Default) {
      runCatching {
        when (format) {
          EditorExportFormat.Svg -> renderSvg(current)
          EditorExportFormat.Png ->
            Rendered.Bytes(JvmDocumentRasterizer.renderPng(current, catalog))
          else -> Rendered.Refused("${format.label} export needs a preview server")
        }
      }
        .getOrElse {
          Rendered.Refused("${format.label} export failed: ${it.message ?: it::class.simpleName}")
        }
    }
  }

  private fun renderSvg(document: UiBuilderDocument): Rendered =
    when (
      val result =
        executeSavedDocumentSvgExport(
          SavedDocumentSvgExportJob(
            pin = SavedDocumentRevisionPin.from(document),
            documentSnapshot = document,
            recorderKind = StructuredSvgRecorderKind.JVM_SKIA_SVG_CANVAS,
          ),
          catalog,
          JvmSkiaStructuredSvgRecorder,
        )
    ) {
      is SavedDocumentSvgExportResult.Ok -> Rendered.Bytes(result.svg.encodeToByteArray())
      is SavedDocumentSvgExportResult.Rejected ->
        Rendered.Refused("SVG export refused: " + result.blockers.joinToString("; ") { it.message })
      is SavedDocumentSvgExportResult.Failed ->
        Rendered.Refused("SVG export failed: ${result.message}")
    }

  private sealed interface Rendered {
    class Bytes(val bytes: ByteArray) : Rendered

    class Refused(val sentence: String) : Rendered
  }
}

/** Where a download goes. Null means the person cancelled. */
fun interface ExportFileTarget {
  suspend fun choose(suggestedName: String, extension: String): Path?
}

/** The system clipboard, behind a seam so tests do not need a display. */
interface ExportClipboard {
  fun putText(text: String)

  fun putImage(image: Image)
}

/** AWT's native save dialog, shown on the event thread. */
object AwtSaveDialog : ExportFileTarget {
  override suspend fun choose(suggestedName: String, extension: String): Path? =
    withContext(Dispatchers.Main) {
      val dialog = FileDialog(null as Frame?, "Export design", FileDialog.SAVE)
      dialog.file = suggestedName
      dialog.isVisible = true
      val name = dialog.file ?: return@withContext null
      val chosen = File(dialog.directory, name)
      (if (chosen.extension.equals(extension, ignoreCase = true)) chosen
        else File(chosen.parentFile, "${chosen.name}.$extension"))
        .toPath()
    }
}

/** AWT's system clipboard. Throws where there is none; the host reports that as a sentence. */
object SystemClipboard : ExportClipboard {
  override fun putText(text: String) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
  }

  override fun putImage(image: Image) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(ImageSelection(image), null)
  }
}

private class ImageSelection(private val image: Image) : Transferable {
  override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)

  override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor

  override fun getTransferData(flavor: DataFlavor): Any =
    if (flavor == DataFlavor.imageFlavor) image else throw UnsupportedFlavorException(flavor)
}
