package ee.schimke.composeai.uibuilder.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect

/**
 * The editor's toolbar and rails, handed to a host that draws them in its own chrome.
 *
 * [UiBuilderChrome] restyles the editor's controls but still draws them inside the editor: the
 * IntelliJ plugin gets Jewel buttons in a Compose toolbar. A host whose controls live outside the
 * page cannot take that route. A VS Code webview is one: its editor tab has a title bar with
 * actions and an overflow menu, and a second toolbar drawn inside the tab under it reads as a web
 * page pretending to be an editor. So with one of these, the editor draws no top toolbar and no
 * rails. It publishes what they would have held as [UiBuilderHostAction]s instead, and the host
 * invokes them back by [UiBuilderHostAction.id].
 *
 * What stays inside is what belongs to the canvas: the selection bar that appears with a selection,
 * the zoom controls, and the panels themselves, which a host opens and closes through the
 * `navigator.*` and `dock.*` actions.
 */
class UiBuilderHostChrome(private val onActionsChanged: (List<UiBuilderHostAction>) -> Unit) {
  private val sections = linkedMapOf<String, List<HostChromeEntry>>()
  private var handlers: Map<String, () -> Unit> = emptyMap()
  private var published: List<UiBuilderHostAction>? = null

  /** Runs the action the host's control for [id] stands for. False for an id not published now. */
  fun invoke(id: String): Boolean {
    val handler = handlers[id] ?: return false
    handler()
    return true
  }

  internal fun section(key: String, entries: List<HostChromeEntry>) {
    if (entries.isEmpty()) sections.remove(key) else sections[key] = entries
    val all = sections.values.flatten()
    handlers = all.associate { it.action.id to it.onInvoke }
    val actions = all.map { it.action }
    if (actions != published) {
      published = actions
      onActionsChanged(actions)
    }
  }
}

/**
 * One control the editor would have drawn itself.
 *
 * [id] is stable across releases: `undo`, `redo`, `code`, `reference`, `navigator.<tab>`,
 * `dock.<panel>`, and the `overflow.*` menu rows. A host keys its native controls on it. [group] is
 * where the editor would have put it: `toolbar`, `navigator` (the left rail), `dock` (the right
 * rail) or `overflow` (the ⋮ menu). [icon] is the [UiBuilderChromeIcon] name, for a host that maps
 * icons to its own set. [checked] is null for a plain action, and whether the panel or mode is on
 * for a switch.
 */
data class UiBuilderHostAction(
  val id: String,
  val label: String,
  val group: String,
  val icon: String,
  val enabled: Boolean = true,
  val checked: Boolean? = null,
  val badge: Int = 0,
  val shortcut: String = "",
)

internal class HostChromeEntry(val action: UiBuilderHostAction, val onInvoke: () -> Unit)

/** Publishes one group of the editor's controls for as long as the caller is composed. */
@Composable
internal fun PublishHostChrome(
  chrome: UiBuilderHostChrome,
  key: String,
  entries: List<HostChromeEntry>,
) {
  SideEffect { chrome.section(key, entries) }
  DisposableEffect(chrome, key) { onDispose { chrome.section(key, emptyList()) } }
}
