package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.export.RemoteMaterial3
import ee.schimke.composeai.uibuilder.export.UiDrawing
import ee.schimke.composeai.uibuilder.export.UiRemoteTheme
import ee.schimke.composeai.uibuilder.protocol.ComponentCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.PropertyCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.SlotCapabilityV1
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The modifiers a `remote-m3` component may advertise: what `RemoteContentEmitter` can write. A
 * copy because `CheckUiBuilderRuntimeBoundary` keeps the emitter off this classpath;
 * `RemoteContentModifierParityTest` in `:server` keeps them in sync.
 */
internal val REMOTE_M3_MODIFIERS =
  setOf(
    "align",
    "alignHorizontal",
    "alignVertical",
    "alpha",
    "animateEnterExit",
    "background",
    "border",
    "clip",
    "collapsiblePriority",
    "fillMaxHeight",
    "fillMaxSize",
    "fillMaxWidth",
    "height",
    "heightIn",
    "horizontalScroll",
    "offset",
    "padding",
    "remoteCall",
    "rotate",
    "scale",
    "sharedElement",
    "size",
    "verticalScroll",
    "weight",
    "width",
    "widthIn",
    "wrapContentSize",
    "zIndex",
  )

/**
 * The modifiers only Remote Compose has, appended to every widget-body node (filtering a borrowed
 * list can never produce them): `sharedElement`, `animateEnterExit`, `remoteCall` (any released
 * `RemoteModifier` call, from `RemoteModifierVocabulary`) and `collapsiblePriority` (collapsible
 * scopes only).
 */
internal val REMOTE_ONLY_MODIFIERS: List<String> =
  listOf("animateEnterExit", "collapsiblePriority", "remoteCall", "sharedElement")

/**
 * A borrowed modifier list narrowed to what the Remote emitter writes, plus
 * [REMOTE_ONLY_MODIFIERS].
 */
internal fun List<String>.remoteAuthorableModifiers(): List<String> =
  filter { it in REMOTE_M3_MODIFIERS && it !in REMOTE_ONLY_MODIFIERS } + REMOTE_ONLY_MODIFIERS

/**
 * The note for a component outside the Glance Wear widget profile: the builder writes it, but a
 * widget using it fails to capture on Android. Offered anyway; the native lane shows the failure.
 */
private fun outsideWidgetProfile(operation: String): String =
  "Not in the Glance Wear widget profile ($operation): the Native / Live render of a widget " +
    "using it fails while the document is captured."

/**
 * The layouts `remote-creation-compose` publishes that the packaged foundation does not declare,
 * each derived from a foundation sibling (same slot and arrangement vocabulary).
 *
 * - `layout/fit-box`: `RemoteFitBox`, which shows the first (largest-first) child that fits. In the
 *   Glance Wear widget profile (`LAYOUT_FIT_BOX`).
 * - `layout/collapsible-column` / `layout/collapsible-row`: hide whole children, lowest
 *   `collapsiblePriority` first. Outside the widget profile — see [outsideWidgetProfile].
 */
internal fun remoteOnlyLayout(
  componentId: String,
  declared: Map<String, ComponentCapabilityV1>,
): ComponentCapabilityV1? {
  fun derived(
    from: String,
    displayName: String,
    notes: String,
    properties: List<PropertyCapabilityV1>? = null,
  ): ComponentCapabilityV1 {
    val donor = declared.getValue(from)
    return donor
      .newBuilder()
      .also {
        it.componentId = componentId
        it.displayName = displayName
        it.slots = donor.slots.map { slot -> slot.acceptingRemoteAuthorable() }
        properties?.let { declaredProperties -> it.properties = declaredProperties }
        it.wasm = donor.wasm.newBuilder().also { wasm -> wasm.notes = notes }.build()
        // Not a Compose call: the regular Compose exporter has no counterpart to write, and the
        // Remote emitter writes these by id.
        it.code = null
      }
      .build()
  }
  return when (componentId) {
    "layout/fit-box" ->
      derived(
        "layout/box",
        "Fit box",
        "RemoteFitBox: its children are alternatives, largest first, and it shows the first one that fits.",
        listOf(
          PropertyCapabilityV1.Builder("horizontalAlignment", JsonPrimitive("string"))
            .also {
              it.allowedValues =
                listOf(JsonPrimitive("start"), JsonPrimitive("center"), JsonPrimitive("end"))
              it.notes = "Where the chosen child sits across the box. Centred when absent."
            }
            .build(),
          PropertyCapabilityV1.Builder("verticalArrangement", JsonPrimitive("string"))
            .also {
              it.allowedValues =
                listOf(JsonPrimitive("top"), JsonPrimitive("center"), JsonPrimitive("bottom"))
              it.notes = "Where the chosen child sits down the box. Centred when absent."
            }
            .build(),
        ),
      )
    "layout/collapsible-column" ->
      derived(
        "layout/column",
        "Collapsible column",
        "RemoteCollapsibleColumn: hides whole children, lowest collapsiblePriority first, when it " +
          "runs out of height. " +
          outsideWidgetProfile("LAYOUT_COLLAPSIBLE_COLUMN"),
      )
    "layout/collapsible-row" ->
      derived(
        "layout/row",
        "Collapsible row",
        "RemoteCollapsibleRow: hides whole children, lowest collapsiblePriority first, when it " +
          "runs out of width. " +
          outsideWidgetProfile("LAYOUT_COLLAPSIBLE_ROW"),
      )
    else -> null
  }
}

/**
 * A container slot narrowed to `RemoteAuthorable`, so content the emitter cannot write inside a
 * Remote layout is refused at insertion rather than export.
 */
private fun SlotCapabilityV1.acceptingRemoteAuthorable(): SlotCapabilityV1 =
  newBuilder().also { it.acceptedTraits = listOf("RemoteAuthorable") }.build()

/** The ids [remoteOnlyLayout] answers, in palette order. */
internal val REMOTE_ONLY_LAYOUT_IDS: List<String> =
  listOf("layout/fit-box", "layout/collapsible-column", "layout/collapsible-row")

/**
 * `layout/flow-row` for a widget body, noting that `LAYOUT_FLOW` is outside the Glance Wear widget
 * profile.
 */
internal fun ComponentCapabilityV1.withWidgetProfileNote(): ComponentCapabilityV1 =
  if (componentId != "layout/flow-row") this
  else
    newBuilder()
      .also {
        it.slots = slots.map { slot -> slot.acceptingRemoteAuthorable() }
        it.wasm =
          wasm
            .newBuilder()
            .also { wasm -> wasm.notes = "RemoteFlowRow. " + outsideWidgetProfile("LAYOUT_FLOW") }
            .build()
      }
      .build()

/**
 * A borrowed component, narrowed to what `RemoteContentEmitter` can write into a widget body, so
 * refusals happen when the author acts rather than at export. Used by [composeFoundationCatalog]'s
 * `remote-compose` curation.
 */
internal fun ComponentCapabilityV1.narrowedForRemoteAuthoring(): ComponentCapabilityV1 =
  newBuilder()
    .also {
      // Only modifiers the generator can write.
      it.modifierCapabilities =
        // No modifiers on a brush (no geometry), a draw operation (not a layout node) or a theme
        // (lays nothing out); they would only be refused or dropped at export.
        if (
          componentId == "shape/linear-gradient" ||
            componentId == UiRemoteTheme.ID ||
            componentId in UiDrawing.BY_ID
        )
          emptyList()
        else modifierCapabilities.remoteAuthorableModifiers()
      // `RemoteAuthorable` is an emitter capability, not inherited from a mobile component.
      it.traits =
        (traits - "RemoteAuthorable").let { traits ->
          // A draw operation enters only a canvas's `ops` slot, which asks for DrawOperation.
          if (
            componentId !in setOf("remote-compose/document", "shape/linear-gradient") &&
              componentId !in UiDrawing.BY_ID
          ) {
            traits + "RemoteAuthorable"
          } else {
            traits
          }
        }
    }
    .build()

/**
 * `remote-m3`'s palette shelves: Lottie under Content, then each Remote Material 3 component under
 * its published shelf. Folded over the whole status semantics because [withMenuEntry] reads the
 * menu out of them. The foundation's `remote-compose` curation uses this too.
 */
internal fun remoteM3ComponentMenu(base: JsonObject): JsonObject =
  RemoteMaterial3.components
    .fold(
      REMOTE_DRAW_IDS.fold(
        REMOTE_ONLY_LAYOUT_IDS.fold(
          REMOTE_HAND_DECLARED_IDS.fold(
            JsonObject(
              base + ("componentMenu" to base.withMenuEntry("remote-m3/lottie", "Content"))
            )
          ) { semantics, id ->
            // A theme wraps a subtree, so it shelves with the layouts; the rest are content.
            val shelf = if (id == UiRemoteTheme.ID) "Layout" else "Content"
            JsonObject(semantics + ("componentMenu" to semantics.withMenuEntry(id, shelf)))
          }
        ) { semantics, id ->
          JsonObject(semantics + ("componentMenu" to semantics.withMenuEntry(id, "Layout")))
        }
      ) { semantics, id ->
        JsonObject(semantics + ("componentMenu" to semantics.withMenuEntry(id, "Drawing")))
      }
    ) { semantics, component ->
      JsonObject(
        semantics +
          ("componentMenu" to
            semantics.withMenuEntry(
              component.componentId,
              component.group,
              REMOTE_MATERIAL_3_SHELVES,
            ))
      )
    }
    .getValue("componentMenu") as JsonObject

/**
 * The published catalog's shelf order, so the Remote Material 3 shelves land after this catalog's
 * own and in the order wear-m3-catalog files them.
 */
private val REMOTE_MATERIAL_3_SHELVES =
  listOf(
    "Content",
    "Iconography",
    "Containment",
    "Buttons",
    "Selection buttons",
    "Edge-hugging buttons",
    "Sliders",
    "Steppers",
    "Communication",
  )
