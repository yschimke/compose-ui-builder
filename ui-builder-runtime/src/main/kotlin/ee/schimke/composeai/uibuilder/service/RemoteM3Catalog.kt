@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.discovery.TargetParameter
import ee.schimke.composeai.uibuilder.export.AdaptiveWearWidget
import ee.schimke.composeai.uibuilder.export.RemoteMaterial3
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ComponentCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.PropertyCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.SlotCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.SvgCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.WasmCapabilityV1
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

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

/**
 * `androidx.wear.compose.remote.material3` on the widget palette: every component in
 * [RemoteMaterial3], its properties and slots read off its own signature in the embedded record.
 *
 * - **Properties** are the parameters `RemoteContentEmitter`'s record fallback can write: Remote
 *   and Kotlin scalars and colours. A required one — `checked`, `progress`, `value` — is required
 *   here too, and `StarterContent` seeds it, so a component arrives exporting. The rest (colours
 *   objects, shapes, padding) keep their library defaults.
 * - **Slots** are its `@Composable` parameters under their own names, which is what the record
 *   fallback fills them from. They take what a widget body takes, and none is required: an empty
 *   required slot is written as an empty lambda, which is what an unfilled one means.
 * - **The canvas** draws each with the Wear Material 3 adapter the published catalog names, through
 *   its mapping, so a widget shows a Wear button rather than a placeholder.
 * - **Actions** (`onClick`, `onCheckedChange`) are not properties. Unbound, they are written as
 *   `lambdaAction {}`, which is what an action nobody has wired yet is.
 */
private fun remoteMaterial3Components(
  template: ComponentCapabilityV1,
  bodySlot: SlotCapabilityV1,
  supportedWasm: WasmCapabilityV1,
  blockedSvg: SvgCapabilityV1?,
): List<ComponentCapabilityV1> =
  RemoteMaterial3.components.mapNotNull { component ->
    val record = RemoteMaterial3.records[component.componentId] ?: return@mapNotNull null
    val slotParameters = record.parameters.filter { it.composableSlot }
    template
      .newBuilder()
      .also {
        it.componentId = component.componentId
        it.displayName = component.displayName
        it.role = if (slotParameters.isEmpty()) "Leaf" else "Container"
        it.traits = listOf("RemoteContent", "RemoteAuthorable")
        it.slots = slotParameters.map { parameter ->
          bodySlot
            .newBuilder()
            .also { slot ->
              slot.name = parameter.name
              slot.cardinality =
                bodySlot.cardinality
                  .newBuilder()
                  .also { cardinality ->
                    cardinality.min = 0
                    cardinality.max = null
                  }
                  .build()
            }
            .build()
        }
        it.properties =
          record.parameters.mapNotNull { parameter ->
            remoteMaterial3Property(record.symbol.name, parameter)
          }
        it.modifierCapabilities = template.modifierCapabilities.remoteAuthorableModifiers()
        it.wasm =
          supportedWasm
            .newBuilder()
            .also { wasm ->
              wasm.canvas = component.canvas
              wasm.canvasMapping = component.canvasMapping
              wasm.notes =
                "Drawn with Wear Material 3's `${component.canvas}`, the adapter the published " +
                  "catalog names; the widget plays `${record.symbol.name}` itself."
            }
            .build()
        it.code = null
        it.svg =
          blockedSvg
            ?.newBuilder()
            ?.also { svg ->
              svg.notes = "Remote Material 3 inside a widget body has no structured SVG answer."
            }
            ?.build()
      }
      .build()
  }

/** One parameter of a Remote Material 3 component as a property, or null for one it cannot be. */
private fun remoteMaterial3Property(
  symbol: String,
  parameter: TargetParameter,
): PropertyCapabilityV1? {
  if (parameter.composableSlot || parameter.name == "modifier") return null
  fun types(vararg names: String) = JsonArray(names.map(::JsonPrimitive))
  val type =
    when (parameter.typeFqn) {
      "androidx.compose.remote.creation.compose.state.RemoteString" -> types("string", "object")
      "androidx.compose.remote.creation.compose.state.RemoteBoolean" -> types("boolean", "object")
      "androidx.compose.remote.creation.compose.state.RemoteFloat" -> types("number", "object")
      "androidx.compose.remote.creation.compose.state.RemoteColor" -> JsonPrimitive("string")
      "androidx.compose.remote.creation.compose.state.RemoteTextUnit" -> JsonPrimitive("number")
      "kotlin.String" -> JsonPrimitive("string")
      "kotlin.Boolean" -> JsonPrimitive("boolean")
      "kotlin.Int" -> JsonPrimitive("integer")
      "kotlin.Float" -> JsonPrimitive("number")
      else -> return null
    }
  return PropertyCapabilityV1.Builder(parameter.name, type)
    .also {
      it.required = !parameter.hasDefault && !parameter.nullable
      it.notes = "`$symbol`'s `${parameter.name}: ${parameter.type}`."
    }
    .build()
}

internal fun remoteM3Catalog(base: CatalogCapabilityV1): CatalogCapabilityV1 {
  val components = base.components.associateBy { it.componentId }
  val box = components.getValue("layout/box")
  val supportedWasm = components.getValue("m3/text").wasm
  val blockedSvg = components.getValue("remote-compose/document").svg
  // A widget body is Remote Compose, not arbitrary Compose UI. Keeping this narrower than
  // `AnyContent` makes the palette refuse a component at insertion time when the Remote Compose
  // emitter has no lowering for it. This is the same catalogue discipline Wear M3 uses: foundation
  // is shared, but a component from a different Material library is not a stand-in for one here.
  val contentSlot =
    box.slots
      .single()
      .newBuilder()
      .also {
        it.name = "content"
        it.cardinality =
          box.slots
            .single()
            .cardinality
            .newBuilder()
            .also {
              it.min = 0
              it.max = 1
            }
            .build()
        it.acceptedTraits = listOf("RemoteAuthorable")
      }
      .build()
  // `WearWidgetBrush` is a CHAIN of drawing elements, and `WearWidgetContainer` folds over it,
  // drawing a round rect per element before the content. A slot is an ordered list of nodes, so the
  // chain models exactly as one — which is why gradients and images are a slot rather than more
  // properties: `background` covers the one-element solid-colour case that a string can carry, and
  // anything the wire cannot say in a string goes in here as a node that already knows how to draw
  // itself.
  //
  // Narrowed to the two traits that ARE brushes. `AnyContent` would let a Text be dropped in as a
  // "background", which upstream has no way to express.
  val backgroundSlot =
    contentSlot
      .newBuilder()
      .also {
        it.name = "background"
        it.cardinality =
          contentSlot.cardinality
            .newBuilder()
            .also {
              it.min = 0
              it.max = null
            }
            .build()
        it.acceptedRoles = listOf("Leaf")
        it.acceptedTraits = listOf("DrawLayer", "ImageContent")
      }
      .build()
  fun widget(componentId: String, displayName: String) =
    box
      .newBuilder()
      .also {
        it.componentId = componentId
        it.displayName = displayName
        it.role = "Scaffold"
        it.traits = listOf("ScreenContent", "WearWidgetHost", "RemoteContentHost")
        it.slots = listOf(backgroundSlot, contentSlot)
        it.properties = widgetContainerProperties()
        it.modifierCapabilities = emptyList()
        it.wasm =
          supportedWasm
            .newBuilder()
            .also {
              it.notes =
                "Compose UI recreation of the Glance Wear squircle host preview; its content slot may host ordinary or nested Remote Compose content, and its background slot the gradient and image brushes WearWidgetBrush chains."
            }
            .build()
        it.code = null
        it.svg =
          blockedSvg
            ?.newBuilder()
            ?.also {
              it.notes =
                "The copied Wear widget host geometry has not yet passed structured SVG parity."
            }
            ?.build()
      }
      .build()
  // **Experimental.** One widget for both container sizes (`AdaptiveWearWidget`). The body is
  // three named slots rather than one, because the template decides where each goes: a Small
  // widget keeps the headline and the action and drops the supporting line. Each takes any number
  // of Remote Compose nodes, stacked in order.
  fun contentSlotNamed(name: String) =
    contentSlot
      .newBuilder()
      .also {
        it.name = name
        it.cardinality = contentSlot.cardinality.newBuilder().also { it.max = null }.build()
      }
      .build()
  val adaptiveWidget =
    widget(AdaptiveWearWidget.COMPONENT_ID, "Wear widget · ${AdaptiveWearWidget.LABEL}")
      .newBuilder()
      .also {
        it.slots = listOf(backgroundSlot) + AdaptiveWearWidget.CONTENT_SLOTS.map(::contentSlotNamed)
        it.wasm =
          supportedWasm
            .newBuilder()
            .also {
              it.notes =
                "Experimental. Edited at Large, where every slot shows; the preview draws the resolved Small and Large widgets in each host shape, and the export branches on WearWidgetParams.containerType."
            }
            .build()
      }
      .build()
  // The reviewed `remote-m3` subset. The last two are brushes, and they are here because the
  // background slot above declares `DrawLayer` and `ImageContent` and nothing else in this list
  // carries either — a slot narrowed to traits no component in its own catalog has is a slot no
  // author can fill from the palette, from a drop, or from a document the validator would accept
  // (yschimke/compose-preview-server#428).
  //
  // `shape/radial-gradient` is deliberately not among them. `RemoteContentEmitter` writes
  // `horizontalGradient`/`verticalGradient` chains from `shape/linear-gradient` and has an authored
  // refusal for `asset/image` that names what to add by hand; a radial gradient would fall through
  // to the generic "is not a widget background brush", which is a worse answer than not offering
  // it. It joins the list when `WearWidgetBrush` gains the chain element it needs.
  val authoringIds =
    listOf(
      "layout/box",
      "layout/column",
      "layout/row",
      "layout/for-each",
      "layout/flow-row",
      *REMOTE_ONLY_LAYOUT_IDS.toTypedArray(),
      "m3/text",
      "remote-compose/document",
      // The way host content gets inside a widget body. A `@RemoteComposable` body cannot call an
      // application's composables — that is the rule this catalog exists to keep — so a custom
      // component is not a hole in it: the document carries an operation naming a renderer, and the
      // application registers Compose under that name. `remote-compose/inline` is deliberately
      // absent, because this catalog's whole body is already a document; an inline switch inside it
      // would be a second answer to a question the container already answered.
      REMOTE_COMPOSE_CUSTOM_COMPONENT_ID,
      "shape/linear-gradient",
      "asset/image",
    )
  return base
    .newBuilder()
    .also {
      it.statusSemantics =
        JsonObject(
          base.statusSemantics +
            (CurrentM3UiBuilderCatalogExecutor.PLATFORM_KEY to JsonPrimitive("remote-compose")) +
            ("componentMenu" to remoteM3ComponentMenu(base.statusSemantics)) +
            ("previewSurfaces" to
              buildJsonObject {
                putJsonObject("native") {
                  put("fidelity", JsonPrimitive("authoritative"))
                  put("backend", JsonPrimitive("android"))
                }
              })
        )
      it.benchmark =
        base.benchmark
          .newBuilder()
          .also {
            it.id = "remote-m3-wear-widget-scaffolds"
            it.sourceRevision = "wear-m3-catalog@d4e4e684e61d0657aad4ccb7752b8c0ab5d9dedf"
            it.catalogSystemId = CurrentM3UiBuilderCatalogExecutor.REMOTE_M3_CATALOG_SYSTEM_ID
            it.catalogRevision = "wear-widget-scaffolds-v1"
          }
          .build()
      it.components =
        listOf(
          widget("remote-m3/widget-container-small", "Wear widget · Small (216×76dp)"),
          widget("remote-m3/widget-container-large", "Wear widget · Large (216×124dp)"),
          adaptiveWidget,
          lottie(components.getValue("asset/image"), supportedWasm, blockedSvg),
        ) +
          authoringIds.map {
            // Narrowed to what the generator can write; the published-catalog foundation applies
            // the
            // same function.
            (components[it] ?: remoteOnlyLayout(it, components) ?: components.getValue(it))
              .narrowedForRemoteAuthoring()
              .withWidgetProfileNote()
          } +
          remoteMaterial3Components(box, contentSlot, supportedWasm, blockedSvg)
    }
    .build()
}

/**
 * `remote-m3/lottie` — a Lottie animation, **compiled into the document** rather than played from
 * it.
 *
 * This is the whole reason it can exist here and nowhere else. Horologist's `remotecompose/lottie`
 * (vendored at `yschimke/rc-players`'s `third_party/horologist-lottie`, whose PROVENANCE.md carries
 * the pinned commit) is not a Lottie player: `LottieAnimation(json = …)` is a `@RemoteComposable`
 * that parses the animation once, at document-build time, and re-emits every layer, shape and
 * keyframe as Remote Compose operations over the document's own animation clock. What ships to the
 * watch is a `.rc` document that draws the animation — no Lottie runtime on the device, no JSON, no
 * fetch.
 *
 * So it belongs to the catalog whose export *is* a Remote Compose document, and to no other. A
 * `wear-m3` screen or an `m3-catalog` phone screen exports ordinary Compose, where the answer to
 * "play a Lottie" is `lottie-compose`, a different library with a different API that this element
 * would misdescribe.
 *
 * ## Two sources, one compiled thing
 *
 * `url` is where the animation came from and `json` is what gets compiled. The builder resolves the
 * first into the second once, at authoring time (`UiBuilderEditor`'s Lottie fetch), and keeps both:
 * the URL because "which animation is this?" is a question an author asks of a design six months
 * later, and the JSON because a generated widget cannot reach the network while it is being built.
 * An element carrying only a URL is authored-but-unresolved — the canvas says so and
 * [RemoteContentEmitter] refuses it by name rather than writing source that would not compile.
 */
private fun lottie(
  borrowed: ComponentCapabilityV1,
  supportedWasm: WasmCapabilityV1,
  blockedSvg: SvgCapabilityV1?,
): ComponentCapabilityV1 =
  borrowed
    .newBuilder()
    .also {
      it.componentId = "remote-m3/lottie"
      it.displayName = "Lottie animation"
      it.role = "Leaf"
      it.traits = listOf("RemoteContent", "RemoteAuthorable")
      it.slots = emptyList()
      it.properties = lottieProperties()
      it.modifierCapabilities = borrowed.modifierCapabilities.remoteAuthorableModifiers()
      it.wasm =
        supportedWasm
          .newBuilder()
          .also {
            it.notes =
              "Drawn as a named placeholder carrying the animation's source and size. The canvas has no Lottie renderer, and compiling the animation the way the export does — into Remote Compose operations — is Horologist's Android-only creation API, which a Wasm build cannot link. A lookalike would be an impression of an animation nobody could check; the picture comes from the native lane, which builds this design's own generated document."
          }
          .build()
      it.code = null
      it.svg =
        blockedSvg
          ?.newBuilder()
          ?.also {
            it.notes =
              "A placeholder on the canvas must not claim structured SVG parity with an animation."
          }
          ?.build()
    }
    .build()

/**
 * `url`, `json` and `progress` — the animation, where it came from, and whether it runs.
 *
 * `progress` is the one that is not obvious. `LottieAnimation`'s `progress` argument is a
 * `RemoteFloat?`, and leaving it out is what makes the compiled document drive the animation from
 * its own clock: `floor(ANIMATION_TIME * frameRate) % frames`, looping forever. Setting it pins the
 * animation to one frame — 0 is the first, 1 the last — which is what a widget that must not
 * animate (a glanceable state, a still icon) wants. Unset means "run", which is why it has no
 * default rather than defaulting to 0.
 */
private fun lottieProperties(): List<PropertyCapabilityV1> =
  listOf(
    PropertyCapabilityV1.Builder("url", JsonPrimitive("string"))
      .also {
        it.notes =
          "Where the animation was fetched from. Resolved into `json` once, in the builder; the " +
            "generated widget never reaches the network."
      }
      .build(),
    PropertyCapabilityV1.Builder("json", JsonPrimitive("string"))
      .also {
        it.notes =
          "The Lottie animation itself, as JSON text. This is what is compiled into the Remote " +
            "Compose document, so it is what the export needs."
      }
      .build(),
    PropertyCapabilityV1.Builder(
        "progress",
        JsonArray(listOf(JsonPrimitive("number"), JsonPrimitive("object"))),
      )
      .also {
        it.notes =
          "Pins the animation to one frame, 0 (first) to 1 (last). Unset — the default — lets the " +
            "document's animation clock run it in a loop."
      }
      .build(),
  )
