package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.export.UiDrawing
import ee.schimke.composeai.uibuilder.protocol.ComponentCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.PropertyCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.SlotCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.SvgCapabilityV1
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * The drawing vocabulary on a Remote Compose palette: `draw/canvas` and the operations its `ops`
 * slot takes, declared from [UiDrawing] so the palette, the canvas stand-in and the Remote emitter
 * read one list.
 *
 * Derived from the packaged Box for the fields a palette entry needs and this file has no opinion
 * on — wasm support, adapter status — and narrowed the way every Remote-only component is: no
 * Compose `code` (the regular exporter has nothing to write), no SVG answer, and a canvas slot that
 * takes draw operations and nothing else, so a button dropped into a canvas is refused at the drop
 * rather than at export.
 */
internal fun remoteDrawComponent(
  componentId: String,
  declared: Map<String, ComponentCapabilityV1>,
  blockedSvg: SvgCapabilityV1? = declared["remote-compose/document"]?.svg,
): ComponentCapabilityV1? {
  val box = declared["layout/box"] ?: return null
  val opsSlot =
    box.slots
      .single()
      .newBuilder()
      .also {
        it.name = UiDrawing.OPS_SLOT
        it.cardinality =
          box.slots
            .single()
            .cardinality
            .newBuilder()
            .also { cardinality ->
              cardinality.min = 0
              cardinality.max = null
            }
            .build()
        it.acceptedTraits = listOf(UiDrawing.TRAIT)
      }
      .build()
  fun derived(
    displayName: String,
    notes: String,
    traits: List<String>,
    slots: List<SlotCapabilityV1>,
    properties: List<PropertyCapabilityV1>,
    modifiers: Boolean,
  ): ComponentCapabilityV1 =
    box
      .newBuilder()
      .also {
        it.componentId = componentId
        it.displayName = displayName
        it.role = if (slots.isEmpty()) "Leaf" else "Container"
        it.traits = traits
        it.slots = slots
        it.properties = properties
        it.modifierCapabilities =
          if (modifiers) box.modifierCapabilities.remoteAuthorableModifiers() else emptyList()
        it.wasm = box.wasm.newBuilder().also { wasm -> wasm.notes = notes }.build()
        it.code = null
        it.svg =
          blockedSvg
            ?.newBuilder()
            ?.also { svg -> svg.notes = "A Remote Compose drawing has no structured SVG answer." }
            ?.build()
      }
      .build()
  if (componentId == UiDrawing.CANVAS) {
    return derived(
      displayName = "Canvas",
      notes =
        "RemoteCanvas: draws its operations in order, in dp from its top-left. Give it a size; " +
          "an unsized canvas draws in whatever space its parent gives it.",
      traits = listOf("RemoteAuthorable"),
      slots = listOf(opsSlot),
      properties = emptyList(),
      modifiers = true,
    )
  }
  val operation = UiDrawing.BY_ID[componentId] ?: return null
  return derived(
    displayName = operation.displayName,
    notes = "Written as `${operation.remoteCall}` inside the canvas's RemoteDrawScope.",
    traits = listOf(UiDrawing.TRAIT),
    slots = if (operation.container) listOf(opsSlot) else emptyList(),
    properties = operation.properties.map(::drawProperty),
    modifiers = false,
  )
}

private fun drawProperty(property: UiDrawing.Property): PropertyCapabilityV1 {
  val type =
    when (property) {
      // A number may also be a state read or, once the wire carries one, a computed value.
      is UiDrawing.Property.Number ->
        JsonArray(listOf(JsonPrimitive("number"), JsonPrimitive("object")))
      is UiDrawing.Property.Flag -> JsonPrimitive("boolean")
      is UiDrawing.Property.Color,
      is UiDrawing.Property.Choice,
      is UiDrawing.Property.Text -> JsonPrimitive("string")
    }
  return PropertyCapabilityV1.Builder(property.name, type)
    .also {
      it.notes = property.notes
      if (property is UiDrawing.Property.Choice) {
        it.allowedValues = property.values.map(::JsonPrimitive)
      }
    }
    .build()
}

/** The drawing ids in palette order, the canvas first. */
internal val REMOTE_DRAW_IDS: List<String> = UiDrawing.COMPONENT_IDS
