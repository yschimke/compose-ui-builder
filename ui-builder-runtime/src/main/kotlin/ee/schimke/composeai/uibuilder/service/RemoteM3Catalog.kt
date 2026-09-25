@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.export.RemoteMaterial3
import ee.schimke.composeai.uibuilder.protocol.ComponentCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.PropertyCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.SlotCapabilityV1
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The modifiers a `remote-m3` component may advertise: what `RemoteContentEmitter` can write.
 *
 * A copy, and it has to be one. The emitter lives in `:ui-builder-export` and this module's
 * `CheckUiBuilderRuntimeBoundary` keeps that classpath out on purpose, so the list cannot be
 * imported from the one place it is derived. `RemoteContentModifierParityTest` in `:server` — which
 * has both — fails when this set and `REMOTE_CONTENT_MODIFIERS` disagree, so the copy cannot rot
 * quietly the way the last one did.
 */
internal val REMOTE_M3_MODIFIERS =
  setOf(
    "align",
    "alignHorizontal",
    "alignVertical",
    "alpha",
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
 * The modifiers only Remote Compose has, offered on every node a widget body lays out.
 *
 * Neither is in the borrowed Compose vocabulary, so filtering a borrowed list by
 * [REMOTE_M3_MODIFIERS] can never produce them: they are appended instead. `sharedElement` matches
 * an element across the branches of a "Show by state" box and animates its bounds between them.
 * `collapsiblePriority` is a member of the collapsible scopes; the emitter refuses it anywhere
 * else.
 */
internal val REMOTE_ONLY_MODIFIERS: List<String> = listOf("collapsiblePriority", "sharedElement")

/**
 * A borrowed modifier list narrowed to what the Remote emitter writes, plus
 * [REMOTE_ONLY_MODIFIERS].
 */
internal fun List<String>.remoteAuthorableModifiers(): List<String> =
  filter { it in REMOTE_M3_MODIFIERS && it !in REMOTE_ONLY_MODIFIERS } + REMOTE_ONLY_MODIFIERS

/**
 * The note a component outside the Glance Wear widget profile carries on the palette.
 *
 * `remote-creation-compose` publishes it and the builder writes it, but `GlanceWearProfiles` admits
 * none of its operations, so a widget using it fails to capture on Android. Offered anyway — the
 * native lane is where that failure shows, and this note is where an author learns of it first.
 */
private fun outsideWidgetProfile(operation: String): String =
  "Not in the Glance Wear widget profile ($operation): the Native / Live render of a widget " +
    "using it fails while the document is captured."

/**
 * The layouts `remote-creation-compose` publishes that the packaged foundation does not declare.
 *
 * Each has a foundation sibling it is derived from — same slot, same arrangement vocabulary — and
 * differs in id, name and the call it writes. `layout/flow-row` is not here: the packaged catalog
 * declares it, and `RemoteFlowRow` takes the same arguments.
 *
 * - `layout/fit-box`: `RemoteFitBox`. Its children are alternatives, largest first; it shows the
 *   first one that fits. In the Glance Wear widget profile (`LAYOUT_FIT_BOX`).
 * - `layout/collapsible-column` / `layout/collapsible-row`: `RemoteCollapsibleColumn` and
 *   `RemoteCollapsibleRow`, which HIDE whole children, lowest `collapsiblePriority` first, rather
 *   than squeezing them. Outside the widget profile — see [outsideWidgetProfile].
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
 * A container slot narrowed to what `RemoteContentEmitter` can write inside it.
 *
 * The donor slots accept `AnyContent`, which would let a document, a gradient or a nested widget
 * host into a Remote layout — each refused at export. The widget content slots already accept only
 * `RemoteAuthorable`; the Remote-only layouts take the same rule.
 */
private fun SlotCapabilityV1.acceptingRemoteAuthorable(): SlotCapabilityV1 =
  newBuilder().also { it.acceptedTraits = listOf("RemoteAuthorable") }.build()

/** The ids [remoteOnlyLayout] answers, in palette order. */
internal val REMOTE_ONLY_LAYOUT_IDS: List<String> =
  listOf("layout/fit-box", "layout/collapsible-column", "layout/collapsible-row")

/**
 * `layout/flow-row` narrowed for a widget body: the packaged declaration, with the note that
 * `LAYOUT_FLOW` is outside the Glance Wear widget profile (it is an experimental-profile
 * operation).
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
 * A borrowed component, narrowed to what `RemoteContentEmitter` can write into a widget body.
 *
 * One function because two derivations apply it — [remoteM3Catalog] and the `remote-compose`
 * curation in [composeFoundationCatalog] — and `ComposeFoundationFaithfulnessTest` holds them equal
 * field for field. Every clause here moves a refusal from export time to the moment the author
 * acts, which is the only moment they can do anything about it.
 */
internal fun ComponentCapabilityV1.narrowedForRemoteAuthoring(): ComponentCapabilityV1 =
  newBuilder()
    .also {
      // The palette used to offer 28 modifiers on a widget node while the generator wrote three, so
      // `size`, `background` and `weight` were authorable, drawable, and unexportable
      // (yschimke/compose-preview-server#508).
      it.modifierCapabilities =
        // A brush can only sit in the container's background slot, and `WearWidgetBrush` has no
        // geometry to hang a modifier on — the generator refuses every one it finds there. So the
        // gradient offers none, rather than eighteen that each end in a refusal.
        if (componentId == "shape/linear-gradient") emptyList()
        else modifierCapabilities.remoteAuthorableModifiers()
      // `RemoteAuthorable` is a capability of the Remote Compose emitter, not a property inherited
      // from a mobile component. The reviewed vocabulary does have an emitter branch (or
      // component-record fallback) and may enter a widget body.
      it.traits =
        (traits - "RemoteAuthorable").let { traits ->
          if (componentId !in setOf("remote-compose/document", "shape/linear-gradient")) {
            traits + "RemoteAuthorable"
          } else {
            traits
          }
        }
    }
    .build()

/**
 * `remote-m3`'s palette shelves: the Lottie element under Content, then each Remote Material 3
 * component under the shelf the published catalog files it on.
 *
 * Folded over the whole status semantics rather than the menu alone, because [withMenuEntry] reads
 * the menu out of the semantics it is given and returns the menu: handed a menu, it finds none and
 * drops every shelf the base catalog already declared.
 *
 * The published-catalog foundation's `remote-compose` curation builds its menu with this too, so a
 * shelf the foundation injects into a published catalog lands where it lands here.
 */
internal fun remoteM3ComponentMenu(base: JsonObject): JsonObject =
  RemoteMaterial3.components
    .fold(
      REMOTE_ONLY_LAYOUT_IDS.fold(
        JsonObject(base + ("componentMenu" to base.withMenuEntry("remote-m3/lottie", "Content")))
      ) { semantics, id ->
        JsonObject(semantics + ("componentMenu" to semantics.withMenuEntry(id, "Layout")))
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
    "Containment",
    "Buttons",
    "Selection buttons",
    "Edge-hugging buttons",
    "Sliders",
    "Steppers",
    "Communication",
  )
