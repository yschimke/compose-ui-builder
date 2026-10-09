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
 * A served catalog's component record, projected onto a component pack the builder can merge.
 *
 * ## What this is the smallest form of
 *
 * `docs/design/UI_BUILDER_ON_THE_COMPONENT_RECORD.md` plans to *generate* the builder's capability
 * catalog from `components.json` plus authored policy, and lists the corrections that separate a
 * generator from a regression. This is the generator for the case that needs no authored policy at
 * all: an application's own composables, offered inside a Material 3 screen as **extra** components
 * rather than as the catalog. Nothing here replaces `m3-catalog`'s hand-authored declaration — that
 * one carries editor hints, variant selectors and slot policy a record cannot say — so the rules
 * below are deliberately the safe subset:
 *
 * - **Only the project's own symbols.** A Confetti record also lists every `androidx.compose`
 *   composable its previews render, because the record describes what the previews call. Offering
 *   those as `confetti-mobile/text` would be Material 3 twice, under an id that lies about where it
 *   came from. [ComponentOrigin.PROJECT] is the line.
 * - **Only what the producer proved a call site for.** `code.call` is the licence: a component the
 *   producer refused (a required parameter with no literal, a collided overload, a receiver scope)
 *   is left out rather than offered and refused at export.
 * - **A property is a parameter with a literal.** `String`, `Boolean`, `Int`/`Long` and
 *   `Float`/`Double` become properties, required when the parameter has no default and is not
 *   nullable. Everything else — `Modifier`, callbacks, domain types, enums whose constants the
 *   record does not list — is left for the generator's placeholder table, which is what `code.call`
 *   proved works. The role table in the plan (event, state callback) is authored knowledge and is
 *   not guessed at here.
 * - **A slot is a `@Composable` lambda, and accepts anything.** Which roles and traits a slot
 *   accepts is authored policy the record does not carry, so a pack slot constrains nothing: a
 *   record-derived component can hold whatever the author drops in it, and the compiler is the
 *   check, on the native lane.
 * - **The canvas draws a placeholder, and says so.** The browser cannot link an application's
 *   classes, so `wasm.adapterStatus` is `unsupported` and the editor draws the node as a named
 *   outline — the same honest shape `wear-m3`'s native-only components use.
 *
 * ## One rule for the id, both ways
 *
 * [UiBuilderComponentPack.componentId] names the component; [aliasedRecord] writes the same id back
 * onto the record as a catalog alias so the export resolves it. Both call the one function, which
 * is what keeps the capability the editor offers and the record the export generates from agreeing
 * about what `confetti-mobile/session-card` is.
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
   * [record] with every component this pack offers carrying its pack id as a catalog alias, and
   * every component it does not offer removed.
   *
   * Removed rather than kept, because the merged record an export generates from is the design's
   * catalog record plus this one, and a library symbol the pack declined to offer would still
   * resolve by canonical id — reachable from nothing in the document, but two records for
   * `androidx.compose.material3.Text` is the ambiguity `ScreenGenerator` refuses by alias, and
   * keeping one around for no caller is how that starts.
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
      // The Remote Compose value types, which a Remote catalog's components take instead of the
      // Kotlin ones: `RemoteText(text: RemoteString)` rather than `Text(text: String)`. Left out,
      // every component of such a catalog was served with NO editable properties at all — a text
      // with no `text` — so a design could not author one and the export then reported the value
      // missing. What a design carries is the same JSON either way; the difference is only the
      // expression the emitter writes around it (`"…".rs`), which is the emitter's business.
      "androidx.compose.remote.creation.compose.state.RemoteString",
      // A colour travels as a string in both vocabularies — `#RRGGBB` or a token name — which is
      // what the frozen catalogs already say for every `color` property they carry.
      "androidx.compose.remote.creation.compose.state.RemoteColor" -> "string"
      "androidx.compose.remote.creation.compose.state.RemoteBoolean" -> "boolean"
      "androidx.compose.remote.creation.compose.state.RemoteInt" -> "integer"
      "androidx.compose.remote.creation.compose.state.RemoteFloat" -> "number"
      // A text size, and the property a design most visibly loses without it. Left out, a
      // published Remote Compose text component offered a builder `text`, `color` and `maxLines`
      // out of twelve parameters -- every size, weight and alignment dropped -- so a design moving
      // onto it lost the size it was authored with.
      //
      // `number` the way `RemoteFloat` is, with one caveat this map cannot express and the emitter
      // enforces: the spelling is `22.rsp`, and `RemoteTextUnitKt` publishes no `Float.rsp`, so
      // only a whole number has a Kotlin form at all. `RemoteContentEmitter` refuses a fractional
      // size by name rather than rounding one. Compiled rather than assumed, by the value
      // vocabulary probe in the catalog repository that publishes the component.
      "androidx.compose.remote.creation.compose.state.RemoteTextUnit" -> "number"
      else -> null
    }

  /**
   * The document spelling for a typed parameter.
   *
   * Units are bare numbers in JSON and typed values in Kotlin, so the suffix makes the unit part of
   * the saved vocabulary (`fontSize` -> `fontSizeSp`, `tonalElevation` -> `tonalElevationDp`).
   * Other conventions retain the callable's own parameter name.
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
  /**
   * The container/leaf split, shared with `PublishedUiBuilderCatalog`.
   *
   * Exposed rather than duplicated so a published catalog that states no `modifiers` gets the same
   * answer a pack component does — two fallbacks that disagreed would be two different ideas of
   * what "the default" means.
   */
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
