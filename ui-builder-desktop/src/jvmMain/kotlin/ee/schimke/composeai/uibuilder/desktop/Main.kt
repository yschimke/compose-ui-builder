package ee.schimke.composeai.uibuilder.desktop

import androidx.compose.ui.window.application
import ee.schimke.composeai.uibuilder.host.OfflineCatalog
import java.nio.file.Path

/** Launches the native UI Builder desktop host; see [DesktopLaunchOptions] for arguments. */
fun main(args: Array<String>) {
  val options = runCatching {
    DesktopLaunchOptions.parse(args)
  }
    .getOrElse {
      System.err.println(it.message)
      kotlin.system.exitProcess(2)
    }
  application { DesktopApp(options, designStoreRoot()) }
}

/** The desktop app's own store; each catalog's scratch workspace lives under it. */
internal fun designStoreRoot(): Path =
  Path.of(System.getProperty("user.home"), ".compose-preview", "ui-builder-desktop")

/**
 * Where [catalog]'s workspace is kept.
 *
 * One workspace per catalog, because the workspace is one design under a fixed id and a design is
 * pinned to the catalog it was created in: opening a Material 3 workspace as a Wear widget would
 * hand the widget catalog a document full of components it does not declare. Material 3 keeps the
 * directory it has always had, so an existing workspace is still where its owner left it.
 */
internal fun designStorePath(
  catalog: OfflineCatalog,
  root: Path = designStoreRoot(),
  template: String? = null,
): Path =
  (if (catalog == OfflineCatalog.M3) root else root.resolve(catalog.systemId))
    // A named template is a workspace of its own for the same reason a catalog is: the workspace
    // is one design, so asking for the Weather sample must not open last week's blank widget —
    // and reopening the sample returns to the edits made to it.
    .let { if (template == null) it else it.resolve("template-$template") }
