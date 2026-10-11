@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.discovery.TargetParameter
import ee.schimke.composeai.uibuilder.export.AdaptiveWearWidget
import ee.schimke.composeai.uibuilder.export.REMOTE_CONTAINER_IMAGE_PROPERTY
import ee.schimke.composeai.uibuilder.export.REMOTE_CONTAINER_PAINTER_IDS
import ee.schimke.composeai.uibuilder.export.REMOTE_HORIZONTAL_PAGE_INDICATOR_ID
import ee.schimke.composeai.uibuilder.export.REMOTE_ICON_COMPONENT_ID
import ee.schimke.composeai.uibuilder.export.REMOTE_ICON_DEFAULT_KEY
import ee.schimke.composeai.uibuilder.export.REMOTE_TEXT_COMPONENT_ID
import ee.schimke.composeai.uibuilder.export.REMOTE_VERTICAL_PAGE_INDICATOR_ID
import ee.schimke.composeai.uibuilder.export.RemoteMaterial3
import ee.schimke.composeai.uibuilder.export.UiDrawing
import ee.schimke.composeai.uibuilder.export.UiRemoteTheme
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ComponentCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.PropertyCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.SlotCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.SvgCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.WasmCapabilityV1
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

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
 * refusals happen when the author acts rather than at export. Shared by [remoteM3Catalog] and
 * [composeFoundationCatalog]'s `remote-compose` curation (held equal by
 * `ComposeFoundationFaithfulnessTest`).
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

/**
 * `androidx.wear.compose.remote.material3` on the widget palette: every [RemoteMaterial3]
 * component, with properties and slots read off its embedded record.
 *
 * - Properties: parameters the record fallback can write (scalars, colours). Required ones stay
 *   required and `StarterContent` seeds them; the rest keep library defaults.
 * - Slots: `@Composable` parameters under their own names, none required (empty is `{}`).
 * - Canvas: drawn by the Wear Material 3 adapter the published catalog names.
 * - Actions (`onClick`, …) are not properties; unbound they are `lambdaAction {}`.
 */
private fun remoteMaterial3Components(
  template: ComponentCapabilityV1,
  bodySlot: SlotCapabilityV1,
  supportedWasm: WasmCapabilityV1,
  blockedSvg: SvgCapabilityV1?,
  iconKeys: List<JsonElement>?,
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
          } + statedRemoteProperties(component.componentId, iconKeys)
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

/**
 * Properties a published component states beyond its derived signature, transcribed from
 * wear-m3-catalog's `remote-catalog/ui-builder.policy.json` (e.g. `RemoteText`'s `style` as a
 * `RemoteTypography` role).
 */
private fun statedRemoteProperties(
  componentId: String,
  iconKeys: List<JsonElement>?,
): List<PropertyCapabilityV1> =
  when (componentId) {
    // The picture behind a button or card: `containerPainter`, which the record's overload lacks.
    in REMOTE_CONTAINER_PAINTER_IDS ->
      listOf(
        PropertyCapabilityV1.Builder(REMOTE_CONTAINER_IMAGE_PROPERTY, JsonPrimitive("string"))
          .also {
            it.required = false
            it.notes =
              "The key of an asset to fill the container with, as `asset/image`'s `assetKey`: " +
                "written as the `containerPainter` overload, " +
                "`containerPainter = painterRemoteImageBitmap(…)`. A plain container when absent."
          }
          .build()
      )
    // `imageVector` cannot be derived, so the icon takes the same Material icon keys as `m3/icon`.
    REMOTE_ICON_COMPONENT_ID ->
      listOf(
        PropertyCapabilityV1.Builder("imageVector", JsonPrimitive("string"))
          .also {
            it.required = false
            iconKeys?.let { keys -> it.allowedValues = keys }
            it.notes =
              "A Material icon key, written as `Icons.<Style>.<Name>.toRemoteImageVector()`. " +
                "Unset draws `$REMOTE_ICON_DEFAULT_KEY`, the published catalog's default."
          }
          .build()
      )
    // The two inputs of the `RemotePageIndicatorState` the record cannot carry; see
    // `RemoteMaterial3`. `selectedPage` takes Int state, which is how a pager drives the dots.
    REMOTE_HORIZONTAL_PAGE_INDICATOR_ID,
    REMOTE_VERTICAL_PAGE_INDICATOR_ID ->
      listOf(
        PropertyCapabilityV1.Builder("pageCount", JsonPrimitive("integer"))
          .also { it.notes = "How many pages there are; 4 when absent." }
          .build(),
        PropertyCapabilityV1.Builder(
            "selectedPage",
            JsonArray(listOf(JsonPrimitive("integer"), JsonPrimitive("object"))),
          )
          .also {
            it.notes =
              "The page shown, from zero; a literal or the design's Int state. The first when " +
                "absent."
          }
          .build(),
      )
    REMOTE_TEXT_COMPONENT_ID ->
      listOf(
        PropertyCapabilityV1.Builder("style", JsonPrimitive("string"))
          .also {
            it.required = false
            it.allowedValues = REMOTE_TYPOGRAPHY_ROLES.map(::JsonPrimitive)
            it.notes =
              "A role on `RemoteTypography`'s Wear type scale, written as " +
                "`style = RemoteMaterialTheme.typography.<role>`."
          }
          .build(),
        PropertyCapabilityV1.Builder("textAlign", JsonPrimitive("string"))
          .also {
            it.required = false
            it.allowedValues = listOf("start", "center", "end", "justify").map(::JsonPrimitive)
            it.notes = "Written as `textAlign = TextAlign.<Value>`."
          }
          .build(),
      )
    else -> emptyList()
  }

/** `RemoteTypography`'s roles, as the published `remote-m3/remote-text` lists them. */
private val REMOTE_TYPOGRAPHY_ROLES =
  listOf(
    "displayLarge",
    "displayMedium",
    "displaySmall",
    "titleLarge",
    "titleMedium",
    "titleSmall",
    "labelLarge",
    "labelMedium",
    "labelSmall",
    "bodyLarge",
    "bodyMedium",
    "bodySmall",
    "bodyExtraSmall",
    "numeralExtraLarge",
    "numeralLarge",
    "numeralMedium",
    "numeralSmall",
    "numeralExtraSmall",
  )

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
  // Narrower than `AnyContent`, so a component the Remote emitter cannot lower is refused at
  // insertion time.
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
  // `WearWidgetBrush` is a chain of drawing elements, modelled as an ordered slot; `background`
  // covers the solid-colour case. Narrowed to the brush traits.
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
  // Experimental: one widget for both sizes (`AdaptiveWearWidget`). Three named slots because the
  // template decides placement; Small drops the supporting line.
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
  // The reviewed `remote-m3` subset. The last two are brushes, so the background slot's traits
  // have something to accept. `shape/radial-gradient` is excluded until `WearWidgetBrush` gains a
  // radial chain element.
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
      // Host content enters a widget body as a custom component the app renders by name.
      // `remote-compose/inline` is absent: the body is already a document.
      REMOTE_COMPOSE_CUSTOM_COMPONENT_ID,
      "shape/linear-gradient",
      "asset/image",
      *REMOTE_DRAW_IDS.toTypedArray(),
      *REMOTE_HAND_DECLARED_IDS.toTypedArray(),
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
            // Narrowed to what the generator can write, as the published-catalog foundation does.
            (components[it]
                ?: remoteOnlyLayout(it, components)
                ?: remoteDrawComponent(it, components)
                ?: components.getValue(it))
              .narrowedForRemoteAuthoring()
              .withWidgetProfileNote()
          } +
          remoteMaterial3Components(
            box,
            contentSlot,
            supportedWasm,
            blockedSvg,
            iconKeys =
              components["m3/icon"]
                ?.properties
                ?.singleOrNull { it.name == "iconKey" }
                ?.allowedValues,
          )
    }
    .build()
}

/**
 * `remote-m3/lottie` — a Lottie animation compiled into the document rather than played.
 *
 * Horologist's `remotecompose/lottie` (vendored in `yschimke/rc-players`) parses the JSON at
 * document-build time and re-emits it as Remote Compose operations on the document clock, so no
 * Lottie runtime ships. Only this catalog exports a Remote Compose document, so it belongs here
 * alone.
 *
 * `url` is the source and `json` what gets compiled; the editor resolves one into the other at
 * authoring time. URL-only elements are refused by [RemoteContentEmitter].
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
 * `url`, `json` and `progress`. Unset `progress` lets the document clock loop the animation; a
 * value (0 first frame, 1 last) pins one frame. Hence no default.
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
