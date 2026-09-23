package ee.schimke.composeai.uibuilder.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Path
import javax.swing.JOptionPane

/** What the desktop window is editing: a scratch workspace per catalog, or one design file. */
sealed interface DesktopDesign {
  val title: String

  data class Scratch(val catalog: OfflineCatalog) : DesktopDesign {
    override val title: String
      get() = "${catalog.displayName} scratch"
  }

  data class File(val path: Path) : DesktopDesign {
    override val title: String
      get() = path.fileName.toString()
  }
}

/**
 * Opens [design] as a session. A scratch design lives in the app's own store under [storageRoot];
 * Material 3 keeps the root itself, where the single workspace always lived, so an existing one
 * still opens. A file design is read from disk and every accepted edit is written back to it.
 */
internal fun openDesktopSession(
  design: DesktopDesign,
  storageRoot: Path,
  remoteServer: String?,
): UiBuilderSession =
  when (design) {
    is DesktopDesign.Scratch ->
      OfflineUiBuilderSession(
        storagePath =
          if (design.catalog == OfflineCatalog.M3) storageRoot
          else storageRoot.resolve(design.catalog.systemId),
        catalogSystemId = design.catalog.systemId,
        remoteServer = remoteServer,
      )
    is DesktopDesign.File ->
      OfflineUiBuilderSession.projectDocument(DesignFiles.read(design.path), remoteServer) {
        committed ->
        DesignFiles.write(design.path, committed)
      }
  }

/** The desktop application: one window, a File menu, and the design it currently edits. */
@Composable
internal fun ApplicationScope.DesktopApp(options: DesktopLaunchOptions, storageRoot: Path) {
  var design by remember {
    mutableStateOf<DesktopDesign>(
      options.designFile?.let { DesktopDesign.File(it) } ?: DesktopDesign.Scratch(OfflineCatalog.M3)
    )
  }
  Window(onCloseRequest = ::exitApplication, title = "Compose UI Builder — ${design.title}") {
    val opened =
      remember(design) {
        runCatching { openDesktopSession(design, storageRoot, options.remoteServer) }
      }
    val session = opened.getOrNull()
    DisposableEffect(session) { onDispose { session?.close() } }
    // A file that no longer opens falls back to the scratch workspace rather than a blank window.
    opened.exceptionOrNull()?.let { failure ->
      LaunchedEffect(failure) {
        showError("Cannot open ${design.title}", failure)
        design = DesktopDesign.Scratch(OfflineCatalog.M3)
      }
    }
    DesktopMenuBar(
      onNew = { design = DesktopDesign.Scratch(it) },
      onOpen = {
        chooseDesignFile(window, FileDialog.LOAD)?.let { design = DesktopDesign.File(it) }
      },
      onSaveAs = saveAs@{
          val current = session?.snapshot?.value?.snapshot?.state?.document ?: return@saveAs
          val target =
            chooseDesignFile(window, FileDialog.SAVE, "${current.id}.uid") ?: return@saveAs
          runCatching { DesignFiles.write(target, current) }
            .onSuccess { design = DesktopDesign.File(target) }
            .onFailure { showError("Cannot save ${target.fileName}", it) }
        },
      onQuit = ::exitApplication,
    )
    MaterialTheme {
      Surface(Modifier.fillMaxSize()) {
        if (session != null) {
          key(session) {
            OfflineUiBuilderSessionView(
              session = session,
              sessionLabel =
                when (val current = design) {
                  is DesktopDesign.Scratch -> "Desktop offline · saved locally"
                  is DesktopDesign.File -> "Design file · ${current.path}"
                },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun FrameWindowScope.DesktopMenuBar(
  onNew: (OfflineCatalog) -> Unit,
  onOpen: () -> Unit,
  onSaveAs: () -> Unit,
  onQuit: () -> Unit,
) {
  MenuBar {
    Menu("File", mnemonic = 'F') {
      OfflineCatalog.entries.forEach { catalog ->
        Item("New ${catalog.displayName} design", onClick = { onNew(catalog) })
      }
      Separator()
      Item("Open…", onClick = onOpen, shortcut = KeyShortcut(Key.O, ctrl = true))
      Item(
        "Save As…",
        onClick = onSaveAs,
        shortcut = KeyShortcut(Key.S, ctrl = true, shift = true),
      )
      Separator()
      Item("Quit", onClick = onQuit, shortcut = KeyShortcut(Key.Q, ctrl = true))
    }
  }
}

/** How the menu names a catalog. */
internal val OfflineCatalog.displayName: String
  get() =
    when (this) {
      OfflineCatalog.M3 -> "Material 3"
      OfflineCatalog.WEAR_M3 -> "Wear M3"
      OfflineCatalog.REMOTE_M3 -> "Wear widgets"
    }

private fun chooseDesignFile(owner: Frame, mode: Int, suggested: String? = null): Path? {
  val dialog =
    FileDialog(owner, if (mode == FileDialog.SAVE) "Save design as" else "Open design", mode)
  dialog.setFilenameFilter { _, name -> name.substringAfterLast('.') in DesignFiles.extensions }
  suggested?.let { dialog.file = it }
  dialog.isVisible = true
  val name = dialog.file ?: return null
  val chosen = File(dialog.directory, name)
  return if (mode == FileDialog.SAVE && chosen.extension !in DesignFiles.extensions) {
      File(chosen.parentFile, "${chosen.name}.uid")
    } else {
      chosen
    }
    .toPath()
}

private fun showError(title: String, failure: Throwable) {
  JOptionPane.showMessageDialog(
    null,
    failure.message ?: failure::class.simpleName,
    title,
    JOptionPane.ERROR_MESSAGE,
  )
}

/** `ui-builder-desktop [--server <origin>] [design.uid]` */
internal data class DesktopLaunchOptions(val remoteServer: String?, val designFile: Path?) {
  companion object {
    const val USAGE = "usage: Compose UI Builder [--server https://preview.coo.ee] [design.uid]"

    fun parse(args: Array<String>): DesktopLaunchOptions {
      var server: String? = null
      var file: Path? = null
      var index = 0
      while (index < args.size) {
        val arg = args[index]
        when {
          arg == "--server" -> {
            require(index + 1 < args.size) { USAGE }
            server = validatedServerOrigin(args[index + 1]).toString()
            index++
          }
          arg.startsWith("-") -> throw IllegalArgumentException(USAGE)
          else -> {
            require(file == null) { USAGE }
            file = Path.of(arg)
          }
        }
        index++
      }
      return DesktopLaunchOptions(server, file)
    }
  }
}
