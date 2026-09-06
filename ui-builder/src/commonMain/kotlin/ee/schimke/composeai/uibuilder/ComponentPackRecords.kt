package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.discovery.ComponentCode
import ee.schimke.composeai.discovery.ComponentOrigin
import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.discovery.ComponentSlot
import ee.schimke.composeai.discovery.ComponentSymbol
import ee.schimke.composeai.discovery.TargetParameter
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.ComponentCapability
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The component record the editor's code pane and problems panel judge a **pack** component by.
 *
 * ## Why the editor needs one at all
 *
 * The pane runs the same `ScreenGenerator` the server's export does, against the record embedded at
 * build time — `m3-catalog`'s. A pack's components are not in it: they arrive with the catalog at
 * run time, projected by the server from the pack's own record, and the pane would report each one
 * as "no component in this catalog" while the export, generating from the real record, wrote its
 * call site perfectly well. Two answers to one question, which is the drift the pane exists to end.
 *
 * ## What it is, and what it is not
 *
 * The capability the server sent is the record's projection, so projecting it *back* recovers
 * exactly the parameters the server chose to offer — a `String` property is a `kotlin.String`
 * parameter, a slot is a `@Composable` lambda — and the call site is the callable the capability
 * names. That is enough for the pane to print the call the export will print, argument for
 * argument, for everything an author can set.
 *
 * It is not the record. Parameters the projection left out (a `Modifier`, a callback, a domain type
 * with a default) are not here, so where the export writes a placeholder the pane writes nothing —
 * which is also what the export writes when the parameter has a default, and the projection only
 * offers components whose call site was proven with the placeholder table. The export remains the
 * authority, and this is the pane's best reading of it rather than a second generator:
 * [ComponentRecordFile] is the one shape both consume.
 */
internal fun CapabilityCatalog.packComponentRecords(): List<ComponentRecord> =
  componentPacks.packs.flatMap { pack ->
    pack.componentIds.mapNotNull { componentId ->
      componentsById[componentId]?.let { packComponentRecord(pack.id, it) }
    }
  }

/** The embedded record with this catalog's pack components appended, or null with no record. */
internal fun CapabilityCatalog.exportRecord(embedded: ComponentRecordFile?): ComponentRecordFile? {
  val packs = packComponentRecords()
  if (packs.isEmpty()) return embedded
  return embedded?.copy(components = embedded.components + packs)
}

/**
 * The components this catalog declares and the canvas cannot draw as themselves — every pack's.
 *
 * The renderer draws each as a named placeholder rather than as an error, the same shape as
 * `wear-m3`'s native-only components: the component is in the catalog, it exports and it renders on
 * the native lane, just not here.
 */
internal val CapabilityCatalog.nativeOnlyComponentIds: Set<String>
  get() = componentPacks.packs.flatMapTo(mutableSetOf()) { it.componentIds }

private fun packComponentRecord(packId: String, component: ComponentCapability): ComponentRecord? {
  val callable = component.code?.symbol?.takeIf { '.' in it } ?: return null
  val name = callable.substringAfterLast('.')
  val slotNames = component.slots.map { it.name }.toSet()
  val parameters =
    component.properties.map { property ->
      val typeFqn = typeFqnOf(property.jsonType)
      TargetParameter(
        name = property.name,
        type = typeFqn?.substringAfterLast('.') ?: "Any",
        typeFqn = typeFqn,
        hasDefault = !property.required,
      )
    } +
      component.slots.map { slot ->
        TargetParameter(
          name = slot.name,
          type = "@Composable () -> Unit",
          hasDefault = true,
          composableSlot = true,
        )
      }
  return ComponentRecord(
    canonicalId = "$packId/$callable",
    componentIds = listOf(component.componentId),
    symbol =
      ComponentSymbol(
        jvmOwner = "${callable}Kt",
        callable = callable,
        name = name,
        origin = ComponentOrigin.PROJECT,
      ),
    parameters = parameters.filter { it.composableSlot || it.name !in slotNames },
    slots = component.slots.map { ComponentSlot(name = it.name, required = false) },
    code =
      ComponentCode(
        call = "$name()",
        imports = component.code.imports.ifEmpty { listOf(callable) },
      ),
    signatureKnown = true,
  )
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
