package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.discovery.ComponentCode
import ee.schimke.composeai.discovery.ComponentOrigin
import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.discovery.ComponentSlot
import ee.schimke.composeai.discovery.ComponentSymbol
import ee.schimke.composeai.discovery.TargetParameter
import ee.schimke.composeai.uibuilder.canvas.UiBuilderFrameGeometry
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.ComponentCapability
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The component record the editor's code pane and problems panel judge a **pack** component by. The
 * embedded record is `m3-catalog`'s, which lacks pack components (they arrive with the catalog at
 * run time).
 *
 * This projects the capability back into a record: `String` properties become `kotlin.String`
 * parameters, slots `@Composable` lambdas, and the call site is the callable the capability names,
 * enough to print the call the export prints. Parameters the projection left out (a `Modifier`, a
 * callback, a defaulted domain type) are absent, so where the export writes a placeholder the pane
 * writes nothing. The export stays the authority; [ComponentRecordFile] is the shape both consume.
 */
internal fun CapabilityCatalog.packComponentRecords(): List<ComponentRecord> =
  componentPacks.packs.flatMap { pack ->
    pack.componentIds.mapNotNull { componentId ->
      componentsById[componentId]?.let { packComponentRecord(pack.id, it) }
    }
  }

/**
 * The same projection keyed by design id, as the record-free Wear screen emitter takes it (e.g.
 * `confetti-wear/section-header` in a `wear-m3` screen).
 */
internal fun CapabilityCatalog.packComponentsById(): Map<String, ComponentRecord> =
  packComponentRecords().flatMap { record -> record.componentIds.map { it to record } }.toMap()

/** The embedded record with this catalog's pack components appended, or null with no record. */
internal fun CapabilityCatalog.exportRecord(embedded: ComponentRecordFile?): ComponentRecordFile? {
  val packs = packComponentRecords()
  if (packs.isEmpty()) return embedded
  return embedded?.newBuilder()?.also { b -> b.components = embedded.components + packs }?.build()
}

/**
 * The components this catalog declares that the canvas can't draw as themselves (every pack's):
 * drawn as named placeholders, since they export and render on the native lane.
 */
/**
 * Which canvas adapter each component wants, for ids whose catalog names one; an empty map keys on
 * component ids. See [WasmCapability.canvas].
 */
internal val CapabilityCatalog.canvasAdapterIds: Map<String, String>
  get() =
    components
      .mapNotNull { component ->
        component.wasm.canvas?.takeIf { it.isNotBlank() }?.let { component.componentId to it }
      }
      .toMap()

/** Vocabulary projections carried beside [canvasAdapterIds], keyed by source component id. */
internal val CapabilityCatalog.canvasAdapterMappings:
  Map<String, ee.schimke.composeai.uibuilder.protocol.CanvasAdapterMappingV1>
  get() =
    components
      .mapNotNull { component -> component.wasm.canvasMapping?.let { component.componentId to it } }
      .toMap()

internal val CapabilityCatalog.nativeOnlyComponentIds: Set<String>
  get() = componentPacks.packs.flatMapTo(mutableSetOf()) { it.componentIds }

/**
 * The frame this catalog declares for its screens (see [UiBuilderFrameGeometry]), read from
 * `statusSemantics` like [CapabilityCatalog.previewSurfaces].
 */
internal val CapabilityCatalog.frameGeometry: UiBuilderFrameGeometry
  get() = UiBuilderFrameGeometry.from(statusSemantics)

private fun packComponentRecord(packId: String, component: ComponentCapability): ComponentRecord? {
  val callable = component.code?.symbol?.takeIf { '.' in it } ?: return null
  val name = callable.substringAfterLast('.')
  val slotNames = component.slots.map { it.name }.toSet()
  val parameters =
    component.properties.map { property ->
      val typeFqn = typeFqnOf(property.jsonType)
      TargetParameter.Builder(
          name = property.name,
          type = typeFqn?.substringAfterLast('.') ?: "Any",
        )
        .also { b ->
          b.typeFqn = typeFqn
          b.hasDefault = !property.required
        }
        .build()
    } +
      component.slots.map { slot ->
        TargetParameter.Builder(name = slot.name, type = "@Composable () -> Unit")
          .also { b ->
            b.hasDefault = true
            b.composableSlot = true
          }
          .build()
      }
  return ComponentRecord.Builder(
      canonicalId = "$packId/$callable",
      symbol =
        ComponentSymbol.Builder(
            jvmOwner = "${callable}Kt",
            callable = callable,
            name = name,
            origin = ComponentOrigin.PROJECT,
          )
          .build(),
    )
    .also { builder_ ->
      builder_.componentIds = listOf(component.componentId)
      builder_.parameters = parameters.filter { it.composableSlot || it.name !in slotNames }
      builder_.slots =
        component.slots.map { ComponentSlot.Builder(name = it.name, required = false).build() }
      builder_.code =
        ComponentCode.Builder()
          .also { b ->
            b.call = "$name()"
            b.imports = component.code.imports.ifEmpty { listOf(callable) }
          }
          .build()
      builder_.signatureKnown = true
    }
    .build()
}

/** The Kotlin classifier a projected property's JSON type came from. */
private fun typeFqnOf(jsonType: kotlinx.serialization.json.JsonElement): String? {
  val word =
    when (jsonType) {
      is JsonPrimitive -> jsonType.contentOrNull
      is JsonArray -> jsonType.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull }
      else -> null
    }
  return when (word) {
    "string" -> "kotlin.String"
    "boolean" -> "kotlin.Boolean"
    "integer" -> "kotlin.Int"
    "number" -> "kotlin.Float"
    else -> null
  }
}
