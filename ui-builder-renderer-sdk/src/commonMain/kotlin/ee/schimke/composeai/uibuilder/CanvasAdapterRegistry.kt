package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextLayoutResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull

/** The presentation the host asks a catalog adapter to draw. */
enum class CanvasMode {
  /** The bounded component or device surface. */
  Device,

  /** An authoring surface where scrolling structures expose their complete content. */
  AuthoringUnrolled,
}

/** One catalog-owned implementation of a canvas adapter id. */
typealias CanvasAdapter = @Composable CanvasNodeScope.() -> Unit

/**
 * An immutable set of catalog-owned canvas adapters.
 *
 * The generic interpreter resolves bindings, state and authored modifiers before invoking an
 * adapter. A registry therefore contains component calls, not another document interpreter.
 */
class CanvasAdapterRegistry private constructor(private val adapters: Map<String, CanvasAdapter>) {
  operator fun get(adapterId: String): CanvasAdapter? = adapters[adapterId]

  operator fun plus(other: CanvasAdapterRegistry): CanvasAdapterRegistry {
    val duplicate = adapters.keys intersect other.adapters.keys
    require(duplicate.isEmpty()) {
      "canvas adapter ids registered more than once: ${duplicate.sorted()}"
    }
    return CanvasAdapterRegistry(adapters + other.adapters)
  }

  companion object {
    val Empty = CanvasAdapterRegistry(emptyMap())
  }

  class Builder {
    private val adapters = linkedMapOf<String, CanvasAdapter>()

    fun register(adapterId: String, adapter: CanvasAdapter) {
      require(adapterId.isNotBlank()) { "canvas adapter id must not be blank" }
      require(adapters.put(adapterId, adapter) == null) {
        "canvas adapter id registered more than once: $adapterId"
      }
    }

    fun build(): CanvasAdapterRegistry = CanvasAdapterRegistry(adapters.toMap())
  }
}

fun canvasAdapterRegistry(block: CanvasAdapterRegistry.Builder.() -> Unit): CanvasAdapterRegistry =
  CanvasAdapterRegistry.Builder().apply(block).build()

/** Registry selected for the current catalog renderer. */
val LocalCanvasAdapterRegistry = staticCompositionLocalOf { CanvasAdapterRegistry.Empty }

/**
 * Dispatch this resolved tree entry to its catalog adapter.
 *
 * Slot and item recursion stay here in the SDK. [renderChild] only supplies the host operation that
 * draws an already-resolved descendant, which lets the transitional renderer retain its fallback
 * table without teaching a catalog adapter how to walk the document.
 *
 * @return whether the registry contained [CanvasRenderNode.adapterId].
 */
@Composable
fun CanvasRenderNode.renderAdapter(
  registry: CanvasAdapterRegistry,
  modifier: Modifier,
  mode: CanvasMode,
  renderChild: @Composable (CanvasRenderNode, Modifier) -> Unit,
  dispatchEvent: (String) -> Unit,
  updateState: (String, String?) -> Unit,
  recordText: (TextLayoutResult) -> Unit,
): Boolean {
  val adapter = registry[adapterId] ?: return false
  val scope =
    CanvasNodeScope(
      node = node,
      modifier = modifier,
      mode = mode,
      renderSlot = { name, next -> slot(name).forEach { renderChild(it, next) } },
      renderItems = { name, content ->
        slot(name).forEach { item ->
          val itemScope = CanvasItemScope { next -> renderChild(item, next) }
          content(itemScope, item.node)
        }
      },
      countItems = { name -> slot(name).size },
      renderItem = { name, index, next ->
        slot(name).getOrNull(index)?.let { item -> renderChild(item, next) }
      },
      dispatchEvent = dispatchEvent,
      updateState = updateState,
      recordText = recordText,
    )
  adapter(scope)
  return true
}

/** A child emitted from an items slot, retaining the receiver scope supplied by the real API. */
class CanvasItemScope(private val render: @Composable (Modifier) -> Unit) {
  @Composable fun Content(modifier: Modifier = Modifier) = render(modifier)
}

/**
 * The generic interpreter's resolved view of one authored node.
 *
 * [node] has already had component arguments, preview state and catalog property/slot mappings
 * applied. [modifier] already includes authored modifiers, bounds collection and the node's click
 * action. Catalog adapters should pass it to the real root composable they invoke.
 */
class CanvasNodeScope(
  val node: UiBuilderNode,
  val modifier: Modifier,
  val mode: CanvasMode,
  private val renderSlot: @Composable (String, Modifier) -> Unit,
  private val renderItems:
    @Composable
    (String, @Composable CanvasItemScope.(UiBuilderNode) -> Unit) -> Unit,
  private val countItems: (String) -> Int,
  private val renderItem: @Composable (String, Int, Modifier) -> Unit,
  private val dispatchEvent: (String) -> Unit,
  private val updateState: (String, String?) -> Unit,
  private val recordText: (TextLayoutResult) -> Unit,
) {
  @Composable
  fun Slot(name: String, modifier: Modifier = Modifier) {
    renderSlot(name, modifier)
  }

  @Composable
  fun Items(name: String, content: @Composable CanvasItemScope.(UiBuilderNode) -> Unit) {
    renderItems(name, content)
  }

  /** Number of resolved descendants in [name], for receiver-scoped lazy layout APIs. */
  fun itemCount(name: String): Int = countItems(name)

  /**
   * Render one resolved descendant from [name].
   *
   * This is the indexed counterpart to [Items]: a catalog adapter can let a real lazy-layout
   * receiver decide when to compose each item while traversal, instance paths and inspection remain
   * in the SDK. An out-of-range index emits nothing so a half-authored document cannot take down
   * the canvas.
   */
  @Composable
  fun Item(name: String, index: Int, modifier: Modifier = Modifier) {
    renderItem(name, index, modifier)
  }

  fun dispatch(event: String) = dispatchEvent(event)

  /**
   * Update the state variable named by a resolved `state` property wrapper.
   *
   * Binding lookup remains in the SDK: adapters name only their API parameter and provide its new
   * scalar value. A literal or non-state property is intentionally a no-op.
   */
  fun updateBoundState(property: String, value: String?) {
    val wrapper = node.properties[property] as? JsonObject ?: return
    if ((wrapper["type"] as? JsonPrimitive)?.contentOrNull != "state") return
    val variable = (wrapper["variable"] as? JsonPrimitive)?.contentOrNull ?: return
    updateState(variable, value)
  }

  fun recordTextLayout(result: TextLayoutResult) = recordText(result)

  fun value(name: String): JsonPrimitive? =
    (node.properties[name] as? JsonObject)?.get("value") as? JsonPrimitive

  fun string(name: String, fallback: String = ""): String = value(name)?.contentOrNull ?: fallback

  fun boolean(name: String, fallback: Boolean = false): Boolean =
    value(name)?.booleanOrNull ?: fallback

  fun float(name: String, fallback: Float = 0f): Float = value(name)?.floatOrNull ?: fallback

  fun integer(name: String, fallback: Int = 0): Int = value(name)?.intOrNull ?: fallback
}
