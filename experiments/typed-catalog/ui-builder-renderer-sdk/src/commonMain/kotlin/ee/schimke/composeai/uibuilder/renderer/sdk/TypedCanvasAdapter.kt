package ee.schimke.composeai.uibuilder.renderer.sdk

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ee.schimke.composeai.discovery.AdapterEvent
import ee.schimke.composeai.discovery.AdapterProperty
import ee.schimke.composeai.discovery.AdapterSlot
import ee.schimke.composeai.discovery.AdapterStateChange
import ee.schimke.composeai.discovery.TypedComponentAdapter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Register the exact adapter id that the same definition publishes in ui-builder.json. */
fun <P> CanvasAdapterRegistry.Builder.register(
  definition: TypedComponentAdapter<P>,
  render: @Composable TypedCanvasNodeScope<P>.() -> Unit,
) {
  definition.freeze()
  register(definition.id) {
    val typed = TypedCanvasNodeScope(definition, this)
    val problems = typed.prepare()
    if (problems.isEmpty()) typed.render()
    else BasicText("${definition.id}: invalid properties\n${problems.joinToString("\n")}", modifier)
  }
}

/** Typed handles over the SDK's resolved node; traversal, state and inspection stay in the SDK. */
class TypedCanvasNodeScope<P>(
  val definition: TypedComponentAdapter<P>,
  val canvas: CanvasNodeScope,
) {
  private val decoded = mutableMapOf<AdapterProperty<P, *>, Any?>()

  /** Decode every declared value before entering the component's composable renderer. */
  internal fun prepare(): List<String> = buildList {
    for (property in definition.properties) {
      try {
        decoded[property] = decode(property)
      } catch (failure: IllegalArgumentException) {
        add("${property.name}: ${failure.message ?: "invalid value"}")
      }
    }
  }

  val modifier: Modifier
    get() = canvas.modifier

  val mode: CanvasMode
    get() = canvas.mode

  fun <T> value(property: AdapterProperty<P, T>): T {
    require(definition.owns(property)) { "property belongs to another adapter" }
    if (decoded.containsKey(property)) {
      @Suppress("UNCHECKED_CAST")
      return decoded[property] as T
    }
    return decode(property)
  }

  private fun <T> decode(property: AdapterProperty<P, T>): T {
    val encoded = canvas.node.properties[property.name] ?: return property.default
    require(encoded is JsonObject) {
      "${definition.id}.${property.name}: expected a property wrapper"
    }
    val raw =
      requireNotNull(encoded["value"]) { "${definition.id}.${property.name}: unresolved property" }
    val wrapperType = (encoded["type"] as? JsonPrimitive)?.contentOrNull
    require(
      wrapperType == property.codec.jsonType || (wrapperType == "state" && property.bindable)
    ) {
      "${definition.id}.${property.name}: unsupported property type $wrapperType"
    }
    // Preview state is stored as text by the interpreter. Normalize only state scalars, keeping
    // ordinary string literals strict and avoiding a second binding interpreter in adapters.
    val resolved =
      if (
        (encoded["type"] as? JsonPrimitive)?.contentOrNull == "state" &&
          property.codec.jsonType != "string" &&
          raw is JsonPrimitive &&
          raw.isString
      ) {
        Json.parseToJsonElement(raw.content)
      } else raw
    return property.decode(resolved)
  }

  @Composable
  fun Slot(slot: AdapterSlot<P>, modifier: Modifier = Modifier) {
    require(definition.owns(slot)) { "slot belongs to another adapter" }
    canvas.Slot(slot.name, modifier)
  }

  /** Callbacks remain real, compiled Kotlin functions passed to the component. */
  fun callback(event: AdapterEvent<P>): () -> Unit {
    require(definition.owns(event)) { "event belongs to another adapter" }
    return { canvas.dispatch(event.eventName) }
  }

  fun <T> callback(change: AdapterStateChange<P, T>): (T) -> Unit {
    require(definition.owns(change)) { "state change belongs to another adapter" }
    return { value ->
      val encoded = change.property.codec.encode(value)
      require(encoded is JsonPrimitive) { "state changes require a scalar codec" }
      canvas.changeBoundState(change.property.name, encoded.content)
    }
  }
}
