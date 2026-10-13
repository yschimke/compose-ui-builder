package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.discovery.ComponentOrigin
import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.discovery.TargetParameter
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.UiBuilderComponentPack
import ee.schimke.composeai.uibuilder.protocol.CodeCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ComponentCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.PropertyCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.SlotCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.SlotCardinalityV1
import ee.schimke.composeai.uibuilder.protocol.SvgCapabilityStatusV1
import ee.schimke.composeai.uibuilder.protocol.SvgCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.SvgFallbackV1
import ee.schimke.composeai.uibuilder.protocol.WasmAdapterStatusV1
import ee.schimke.composeai.uibuilder.protocol.WasmCapabilityV1
import kotlinx.serialization.json.JsonPrimitive

/**
 * A served catalog's component record, projected onto a component pack the builder can merge: an
 * application's own composables offered as extra components, using only rules that need no authored
 * policy (see `docs/design/UI_BUILDER_ON_THE_COMPONENT_RECORD.md`):
 *
 * - Only the project's own symbols ([ComponentOrigin.PROJECT]), not the library composables its
 *   previews call.
 * - Only components with a proven call site (`code.call`).
 * - A property is a literal-typed parameter (`String`, `Boolean`, `Int`/`Long`, `Float`/`Double`),
 *   required when it has no default and is not nullable; everything else uses the generator's
 *   placeholders.
 * - A slot is a `@Composable` lambda and accepts anything; the compiler checks it on the native
 *   lane.
 * - The canvas draws a named placeholder (`wasm.adapterStatus` is `unsupported`).
 *
 * [UiBuilderComponentPack.componentId] and [aliasedRecord] share one id function, so the editor's
 * capability and the export's record agree.
 */
internal object ComponentRecordPacks {

  /** A pack as the runtime merges it, plus what was left out and why. */
  data class Derived(
    val source: UiBuilderComponentPackSource,
    /** `<component id> — <reason>`, for the startup log. */
    val skipped: List<String>,
  )

  /** Derive the pack [packId] from [record], for the catalogs of [platform]. */
  fun derive(
    packId: String,
    platform: UiBuilderCatalogPlatform,
    record: ComponentRecordFile,
    label: String = labelFor(packId),
  ): Derived {
    val skipped = mutableListOf<String>()
    val taken = mutableSetOf<String>()
    val components =
      record.components.mapNotNull { component ->
        val id = UiBuilderComponentPack.componentId(packId, component.symbol.name)
        val reason = exclusionReason(component)
        when {
          reason != null -> {
            skipped += "$id — $reason"
            null
          }
          !taken.add(id) -> {
            skipped += "$id — a component of the same name was already taken from this record"
            null
          }
          else -> capability(id, component, packId)
        }
      }
    return Derived(
      source =
        UiBuilderComponentPackSource(
          id = packId,
          label = label,
          platform = platform.wireValue,
          nativeCatalog = packId,
          components = components,
          notes =
            "${components.size} of ${record.components.size} components in $packId's record, " +
              "drawn on the canvas as placeholders and rendered natively against the $packId bundle.",
        ),
      skipped = skipped,
    )
  }

  /**
   * [record] with every component this pack offers aliased by its pack id, and every other
   * component removed, so a declined library symbol cannot create an ambiguous duplicate record.
   */
  fun aliasedRecord(packId: String, record: ComponentRecordFile): ComponentRecordFile {
    val taken = mutableSetOf<String>()
    return record
      .newBuilder()
      .also { builder_ ->
        builder_.components =
          record.components.mapNotNull { component ->
            if (exclusionReason(component) != null) return@mapNotNull null
            val id = UiBuilderComponentPack.componentId(packId, component.symbol.name)
            if (!taken.add(id)) return@mapNotNull null
            component.newBuilder().also { b -> b.componentIds = listOf(id) }.build()
          }
      }
      .build()
  }

  /** `confetti-mobile` → `Confetti Mobile`. */
  fun labelFor(packId: String): String =
    packId.split('-', '_', '.').filter(String::isNotEmpty).joinToString(" ") { word ->
      word.replaceFirstChar(Char::uppercaseChar)
    }

  private fun exclusionReason(component: ComponentRecord): String? =
    when {
      component.symbol.origin != ComponentOrigin.PROJECT ->
        "a library symbol, not the project's own"
      !component.signatureKnown -> "its signature was not recovered"
      component.overloadsCollided -> "overloads collided into one record"
      component.hasTypeParameters -> "it declares type parameters"
      component.hasContextReceivers -> "it declares a context receiver"
      component.symbol.receiver != null -> "it is declared on `${component.symbol.receiver}`"
      !component.callableFromAnotherFile -> "it is not callable from another file"
      component.code?.call == null ->
        "no call site: ${component.code?.refusedReason ?: "none was recorded"}"
      else -> null
    }

  private fun capability(
    id: String,
    component: ComponentRecord,
    packId: String,
  ): ComponentCapabilityV1 {
    val slots =
      component.slots.map { slot ->
        SlotCapabilityV1.Builder(
            slot.name,
            SlotCardinalityV1.Builder()
              .also {
                it.min = 0
                it.max = null
              }
              .build(),
            true,
          )
          .build()
      }
    val slotNames = slots.map { it.name }.toSet()
    val properties =
      component.parameters
        .filterNot { it.composableSlot || it.name in slotNames }
        .mapNotNull { parameter ->
          val jsonType = jsonTypeOf(parameter) ?: return@mapNotNull null
          PropertyCapabilityV1.Builder(propertyNameOf(parameter), JsonPrimitive(jsonType))
            .also {
              it.required = !parameter.hasDefault && !parameter.nullable
              it.notes = "`${parameter.name}: ${parameter.type}` on `${component.symbol.callable}`."
            }
            .build()
        }
    val container = slots.isNotEmpty()
    return ComponentCapabilityV1.Builder(
        id,
        displayName(component.symbol.name),
        if (container) "Container" else "Leaf",
        WasmCapabilityV1.Builder(
            platformSupported = JsonPrimitive(false),
            adapterStatus = WasmAdapterStatusV1.UNSUPPORTED,
          )
          .also {
            it.notes =
              "Drawn on the canvas as a named placeholder: the browser cannot link $packId's " +
                "classes. The native preview compiles `${component.symbol.callable}` against the " +
                "served $packId bundle and renders the real component."
          }
          .build(),
      )
      .also {
        it.traits = listOf(PACK_TRAIT)
        it.slots = slots
        it.properties = properties
        it.modifierCapabilities = structuralModifiers(container)
        it.code =
          CodeCapabilityV1.Builder(component.symbol.callable)
            .also {
              it.imports =
                component.code?.imports.orEmpty().ifEmpty { listOf(component.symbol.callable) }
            }
            .build()
        it.svg =
          SvgCapabilityV1.Builder(
              SvgCapabilityStatusV1.UNSUPPORTED,
              SvgFallbackV1.EMBEDDED_RASTER,
              false,
            )
            .also {
              it.notes =
                "A pack component has no vector adapter; SVG export embeds the native raster."
            }
            .build()
      }
      .build()
  }

  /** The JSON Schema type a parameter's literal has, or null for a parameter with no literal. */
  /** Shared with [PublishedUiBuilderCatalog]: one rule for which parameters become properties. */
  internal fun jsonTypeOf(parameter: TargetParameter): String? =
    when (parameter.typeFqn) {
      "kotlin.String" -> "string"
      "kotlin.Boolean" -> "boolean"
      "kotlin.Int",
      "kotlin.Long" -> "integer"
      "kotlin.Float",
      "kotlin.Double" -> "number"
      "androidx.compose.ui.graphics.Color",
      "androidx.compose.ui.graphics.Shape",
      "androidx.compose.ui.text.font.FontStyle",
      "androidx.compose.ui.text.font.FontWeight",
      "androidx.compose.ui.text.style.TextAlign",
      "androidx.compose.ui.text.style.TextDecoration",
      "androidx.compose.ui.text.style.TextOverflow" -> "string"
      "androidx.compose.ui.unit.Dp",
      "androidx.compose.ui.unit.TextUnit" -> "number"
      // Remote Compose value types: a Remote catalog's components take these instead of Kotlin
      // ones. The JSON is the same; the emitter writes the wrapping expression (`"…".rs`).
      "androidx.compose.remote.creation.compose.state.RemoteString",
      // A colour travels as a string in both vocabularies — `#RRGGBB` or a token name — which is
      // what the frozen catalogs already say for every `color` property they carry.
      "androidx.compose.remote.creation.compose.state.RemoteColor" -> "string"
      "androidx.compose.remote.creation.compose.state.RemoteBoolean" -> "boolean"
      "androidx.compose.remote.creation.compose.state.RemoteInt" -> "integer"
      "androidx.compose.remote.creation.compose.state.RemoteFloat" -> "number"
      // A text size. `number` like `RemoteFloat`, but only whole numbers have a Kotlin form
      // (`22.rsp`; there is no `Float.rsp`); `RemoteContentEmitter` refuses fractional sizes.
      "androidx.compose.remote.creation.compose.state.RemoteTextUnit" -> "number"
      // A length (`strokeWidth`, a button's `border`), written `N.rdp`. Unsuffixed like the text
      // size above: the property is the parameter's own name.
      "androidx.compose.remote.creation.compose.state.RemoteDp" -> "number"
      else -> null
    }

  /**
   * The document spelling for a typed parameter: units are suffixed (`fontSize` -> `fontSizeSp`,
   * `tonalElevation` -> `tonalElevationDp`); other names are kept.
   */
  internal fun propertyNameOf(parameter: TargetParameter): String =
    when (parameter.typeFqn) {
      "androidx.compose.ui.unit.Dp" -> "${parameter.name}Dp"
      "androidx.compose.ui.unit.TextUnit" -> "${parameter.name}Sp"
      else -> parameter.name
    }

  /** `SessionCard` → `Session Card`. */
  private fun displayName(name: String): String =
    UiBuilderComponentPack.kebabCase(name).split('-').joinToString(" ") { word ->
      word.replaceFirstChar(Char::uppercaseChar)
    }

  /** The trait every pack component carries, so a slot policy can name pack content. */
  const val PACK_TRAIT: String = "PackContent"

  /** What `m3/text` may carry: the modifiers a leaf can take without a scope it may not have. */
  private val LEAF_MODIFIERS =
    listOf(
      "size",
      "fillMaxWidth",
      "padding",
      "alpha",
      "offset",
      "rotate",
      "scale",
      "zIndex",
      "testTag",
      "width",
      "height",
      "widthIn",
      "heightIn",
      "aspectRatio",
      "align",
      "alignHorizontal",
      "alignVertical",
      "weight",
    )

  /** What `layout/column` may carry: the leaf set plus what a container that fills does. */
  /** The container/leaf split, shared with `PublishedUiBuilderCatalog` so both fallbacks agree. */
  internal fun structuralModifiers(container: Boolean): List<String> =
    if (container) CONTAINER_MODIFIERS else LEAF_MODIFIERS

  private val CONTAINER_MODIFIERS =
    LEAF_MODIFIERS +
      listOf(
        "fillMaxSize",
        "fillMaxHeight",
        "background",
        "border",
        "shadow",
        "wrapContentSize",
        "verticalScroll",
      )
}
