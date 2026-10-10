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
import ee.schimke.composeai.uibuilder.UidDesignCollection
import ee.schimke.composeai.uibuilder.editor.fileDesigns
import ee.schimke.composeai.uibuilder.export.AdaptiveWearWidget
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import ee.schimke.composeai.uibuilder.host.CatalogOverride
import ee.schimke.composeai.uibuilder.host.DesignFileGuard
import ee.schimke.composeai.uibuilder.host.DesignFiles
import ee.schimke.composeai.uibuilder.host.JvmReferenceHost
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

  /** [reopen] is bumped to read [path] again after the file's active design changes. */
  data class File(val path: Path, val reopen: Int = 0) : DesktopDesign {
    override val title: String
      get() = path.fileName.toString()
  }
}

/**
 * Opens [design] as a session. A scratch design lives in its catalog's workspace under
 * [storageRoot] (see [designStorePath]); a file design is read from disk and every accepted edit is
 * written back to it, unless the file changed underneath it (see [DesignFileGuard]).
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
    is DesktopDesign.File -> {
      // Taken before the read, so a change landing between the two is refused rather than lost.
      val guard = DesignFileGuard(design.path)
      OfflineUiBuilderSession.projectDocument(
        DesignFiles.read(design.path),
        remoteServer,
        catalogOverride,
      ) { committed ->
        guard.write(committed)
      }
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
          val original = (design as? DesktopDesign.File)?.path?.let(java.nio.file.Files::readString)
          val session =
            openDesktopSession(design, storageRoot, options.remoteServer, options.catalogOverride)
          val path = (design as? DesktopDesign.File)?.path
          if (path != null && java.nio.file.Files.readString(path) != original) {
            session.close()
            error("${path.fileName} changed while opening; reopen it before editing")
          }
          session to original
        }
      }
    val session = opened.getOrNull()?.first
    val collection =
      remember(design, session) {
        (design as? DesktopDesign.File)?.let {
          runCatching { DesignFiles.readCollection(it.path) }.getOrNull()
        }
      }
    /** Rewrites the open file's designs, then reads it again so the new active design opens. */
    fun updateDesigns(update: (UidDesignCollection) -> UidDesignCollection) {
      val file = design as? DesktopDesign.File ?: return
      runCatching {
        // The session's own pending writes land first; its guard is closed with it.
        session?.close()
        DesignFiles.updateCollection(file.path, update)
      }
        .onFailure { showError("Cannot change the designs in ${file.title}", it) }
      design = file.copy(reopen = file.reopen + 1)
    }
    val selectDesign: (String) -> Unit = { id -> updateDesigns { it.withActive(id) } }
    val addDesign: () -> Unit = {
      updateDesigns { designs ->
        val catalog = OfflineCatalog.forSystem(requireNotNull(designs.catalogSystemId))
        val capabilities = catalog.capabilityCatalog().benchmark
        val id = nextDesignId(designs)
        designs.plus(
          catalog
            .seed(
              designId = id,
              catalogRevision = capabilities.catalogRevision,
              nativeRuntimeId = capabilities.nativeRuntimeId,
            )
            .copy(title = templateLabel(id))
        )
      }
    }
    val removeDesign: () -> Unit = { updateDesigns { it.minus(it.active) } }
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
          runCatching { DesignFiles.write(target, current, opened.getOrNull()?.second) }
            .onSuccess { design = DesktopDesign.File(target) }
            .onFailure { showError("Cannot save ${target.fileName}", it) }
        },
      onQuit = ::exitApplication,
      designs = collection,
      onSelectDesign = selectDesign,
      onAddDesign = addDesign,
      onRemoveDesign = removeDesign,
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
              // Beside the workspaces rather than beside a design file: a reference is the
              // operator's scaffolding, and writing it next to a file somebody shares would put
              // a multi-megabyte mock into their repository.
              referenceStore = referenceStoreFor(design, storageRoot),
              localAgentStore =
                storageRoot
                  .resolve("agents")
                  .resolve(
                    referenceStoreFor(design, storageRoot).fileName.toString().removeSuffix(".json")
                  ),
              // The same three verbs as the Designs menu, where the design is.
              fileDesigns =
                collection?.fileDesigns(
                  onSelect = selectDesign,
                  onAdd = addDesign,
                  onRemove = removeDesign,
                ),
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
  designs: UidDesignCollection?,
  onSelectDesign: (String) -> Unit,
  onAddDesign: () -> Unit,
  onRemoveDesign: () -> Unit,
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
              "${catalog.displayName} · ${templateMenuLabel(template)}",
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
    // A design file can hold several top-level designs; the active one is what the canvas edits
    // and every preview renders.
    if (designs != null) {
      Menu("Designs", mnemonic = 'D') {
        designs.designs.forEach { document ->
          CheckboxItem(
            document.title.ifBlank { document.id },
            checked = document.id == designs.active,
            onCheckedChange = { if (document.id != designs.active) onSelectDesign(document.id) },
          )
        }
        Separator()
        Item("Add design", onClick = onAddDesign)
        Item(
          "Remove active design",
          onClick = onRemoveDesign,
          enabled = designs.designs.size > 1,
        )
      }
    }
  }
}

/** The first design id not yet used in [designs]: `design-2`, `design-3`, … */
internal fun nextDesignId(designs: UidDesignCollection): String {
  val taken = designs.designs.map { it.id }.toSet()
  return generateSequence(2) { it + 1 }.map { "design-$it" }.first { it !in taken }
}

/** Where [design]'s reference overlay is kept under [storageRoot]. */
internal fun referenceStoreFor(design: DesktopDesign, storageRoot: Path): Path =
  JvmReferenceHost.storeFor(
    storageRoot.resolve("references"),
    when (design) {
      is DesktopDesign.Scratch -> "scratch:${design.catalog.systemId}:${design.template.orEmpty()}"
      is DesktopDesign.File -> "file:${design.path.toAbsolutePath().normalize()}"
    },
  )

/** How the menu and title name a template: `weather-widget` is "Weather widget". */
internal fun templateLabel(template: String): String =
  template.replace('-', ' ').replaceFirstChar { it.uppercaseChar() }

/**
 * The menu's name for a template, saying which ones are empty scaffolds. "Wear screen" and "Wear
 * widget adaptive" are a clock over an empty list and three bare slots, and read as showcases that
 * had forgotten their content until the menu said they were starting points (#359).
 */
internal fun templateMenuLabel(template: String): String =
  if (template in BLANK_STARTER_TEMPLATES) "${templateLabel(template)} (blank starter)"
  else templateLabel(template)

private val BLANK_STARTER_TEMPLATES =
  setOf(
    UiBuilderNewDesignSeed.WEAR_SCREEN_TEMPLATE,
    "wear-widget-small",
    "wear-widget-large",
    AdaptiveWearWidget.TEMPLATE_ID,
  )

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
