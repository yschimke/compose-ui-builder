package ee.schimke.composeai.uibuilder.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect

/**
 * The editor's toolbar and rails, handed to a host that draws them in its own chrome (e.g. a VS
 * Code webview's title bar and overflow menu). Unlike [UiBuilderChrome], which restyles in-editor
 * controls, the editor then draws no top toolbar or rails; it publishes [UiBuilderHostAction]s and
 * the host invokes them by [UiBuilderHostAction.id].
 *
 * Canvas-bound parts stay inside: the selection bar, zoom controls and the panels, which the host
 * toggles through `navigator.*` and `dock.*` actions.
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
 * [id] is stable across releases (`undo`, `redo`, `code`, `reference`, `navigator.<tab>`,
 * `dock.<panel>`, `overflow.*`). [group] is where it would have gone: `toolbar`, `navigator`,
 * `dock` or `overflow`. [icon] names a [UiBuilderChromeIcon]. [checked] is null for a plain action,
 * else the switch state.
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
