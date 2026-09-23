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
import ee.schimke.composeai.uibuilder.host.CatalogOverride
import ee.schimke.composeai.uibuilder.host.DesignFiles
import ee.schimke.composeai.uibuilder.host.OfflineCatalog
import ee.schimke.composeai.uibuilder.host.OfflineUiBuilderSession
import ee.schimke.composeai.uibuilder.host.OfflineUiBuilderSessionView
import ee.schimke.composeai.uibuilder.host.UiBuilderSession
import ee.schimke.composeai.uibuilder.host.validatedServerOrigin
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Path
import javax.swing.JOptionPane

/** What the desktop window is editing: a scratch workspace per catalog, or one design file. */
sealed interface DesktopDesign {
  val title: String

  /** [template] names what a new workspace starts as; null for the catalog's own starter. */
  data class Scratch(val catalog: OfflineCatalog, val template: String? = null) : DesktopDesign {
    override val title: String
      get() = "${catalog.displayName} ${template?.let(::templateLabel) ?: "scratch"}"
  }

  data class File(val path: Path) : DesktopDesign {
    override val title: String
      get() = path.fileName.toString()
  }
}

/**
 * Opens [design] as a session. A scratch design lives in its catalog's workspace under
 * [storageRoot] (see [designStorePath]); a file design is read from disk and every accepted edit is
 * written back to it.
 */
internal fun openDesktopSession(
  design: DesktopDesign,
  storageRoot: Path,
  remoteServer: String?,
  catalogOverride: CatalogOverride? = null,
): UiBuilderSession =
  when (design) {
    is DesktopDesign.Scratch ->
      OfflineUiBuilderSession(
        storagePath = designStorePath(design.catalog, storageRoot, design.template),
        catalogSystemId = design.catalog.systemId,
        remoteServer = remoteServer,
        templateId = design.template,
        catalogOverride = catalogOverride,
      )
    is DesktopDesign.File ->
      OfflineUiBuilderSession.projectDocument(
        DesignFiles.read(design.path),
        remoteServer,
        catalogOverride,
      ) { committed ->
        DesignFiles.write(design.path, committed)
      }
  }

/** The desktop application: one window, a File menu, and the design it currently edits. */
@Composable
internal fun ApplicationScope.DesktopApp(options: DesktopLaunchOptions, storageRoot: Path) {
  var design by remember {
    mutableStateOf<DesktopDesign>(
      options.designFile?.let { DesktopDesign.File(it) }
        ?: DesktopDesign.Scratch(options.catalog, options.template)
    )
  }
  Window(onCloseRequest = ::exitApplication, title = "Compose UI Builder — ${design.title}") {
    val opened =
      remember(design) {
        runCatching {
          openDesktopSession(design, storageRoot, options.remoteServer, options.catalogOverride)
        }
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
      onNew = { catalog, template -> design = DesktopDesign.Scratch(catalog, template) },
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
  onNew: (OfflineCatalog, String?) -> Unit,
  onOpen: () -> Unit,
  onSaveAs: () -> Unit,
  onQuit: () -> Unit,
) {
  MenuBar {
    Menu("File", mnemonic = 'F') {
      OfflineCatalog.entries.forEach { catalog ->
        Item("New ${catalog.displayName} design", onClick = { onNew(catalog, null) })
      }
      // Every other template the web host's New design form offers — the worked Hello and Weather
      // widget samples among them — each opening a workspace of its own.
      Menu("New from template") {
        OfflineCatalog.entries.forEach { catalog ->
          (catalog.templateIds - catalog.defaultTemplateId).sorted().forEach { template ->
            Item(
              "${catalog.displayName} · ${templateLabel(template)}",
              onClick = { onNew(catalog, template) },
            )
          }
        }
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

/** How the menu and title name a template: `weather-widget` is "Weather widget". */
internal fun templateLabel(template: String): String =
  template.replace('-', ' ').replaceFirstChar { it.uppercaseChar() }

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

/**
 * `ui-builder-desktop [--catalog <id>] [--catalog-file <capabilities.json>] [--template <id>]
 * [--server <origin>] [design.uid]`, in any order.
 */
internal data class DesktopLaunchOptions(
  val remoteServer: String?,
  /** The scratch workspace to open when no [designFile] is named. */
  val catalog: OfflineCatalog = OfflineCatalog.M3,
  val designFile: Path? = null,
  /** The template that scratch workspace starts as, or null for the catalog's own starter. */
  val template: String? = null,
  /** Capabilities read from `--catalog-file`, replacing the packaged catalog they name. */
  val catalogOverride: CatalogOverride? = null,
) {
  companion object {
    private val FLAGS = setOf("--catalog", "--catalog-file", "--template", "--server")

    private val USAGE =
      "usage: Compose UI Builder " +
        "[--catalog ${OfflineCatalog.entries.joinToString("|") { it.systemId }}] " +
        "[--catalog-file <capabilities.json>] [--template <id>] " +
        "[--server https://preview.coo.ee] [design.uid]"

    fun parse(args: Array<String>): DesktopLaunchOptions {
      val flags = mutableMapOf<String, String>()
      var file: Path? = null
      var index = 0
      while (index < args.size) {
        val arg = args[index]
        if (arg.startsWith("-")) {
          require(arg in FLAGS && arg !in flags) { USAGE }
          flags[arg] = requireNotNull(args.getOrNull(index + 1)) { USAGE }
          index += 2
        } else {
          require(file == null) { USAGE }
          file = Path.of(arg)
          index++
        }
      }
      val catalogOverride = flags["--catalog-file"]?.let { CatalogOverride.read(Path.of(it)) }
      val named =
        flags["--catalog"]?.let { id ->
          requireNotNull(OfflineCatalog.entries.firstOrNull { it.systemId == id }) {
            "unknown catalog '$id'. $USAGE"
          }
        }
      // A catalog file names its own system id, which picks the scratch workspace when `--catalog`
      // does not; naming both is fine only when they agree.
      require(
        named == null || catalogOverride == null || named.systemId == catalogOverride.systemId
      ) {
        "--catalog ${named?.systemId} and --catalog-file ${catalogOverride?.systemId} disagree"
      }
      val catalog =
        named ?: catalogOverride?.let { OfflineCatalog.forSystem(it.systemId) } ?: OfflineCatalog.M3
      // Checked here rather than when the workspace is seeded, so a typo names the templates that
      // exist before a window opens — and so an existing workspace cannot hide it.
      val template =
        flags["--template"]?.also { id ->
          require(id in catalog.templateIds) {
            "catalog '${catalog.systemId}' has no template '$id'; it has " +
              catalog.templateIds.sorted().joinToString()
          }
        }
      // A template seeds a scratch workspace, so naming a design file as well asks for two things.
      require(template == null || file == null) { "--template opens a new design, not $file" }
      return DesktopLaunchOptions(
        remoteServer = flags["--server"]?.let { validatedServerOrigin(it).toString() },
        catalog = catalog,
        designFile = file,
        template = template,
        catalogOverride = catalogOverride,
      )
    }
  }
}
