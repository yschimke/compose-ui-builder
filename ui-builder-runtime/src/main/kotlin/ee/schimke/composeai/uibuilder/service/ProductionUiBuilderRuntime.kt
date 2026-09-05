@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.ComponentCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DiagnosticSeverityV1
import ee.schimke.composeai.uibuilder.protocol.ExportArtifactV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.protocol.ExportDiagnosticV1
import ee.schimke.composeai.uibuilder.protocol.ExportEncodingV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.protocol.PropertyCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.SlotCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.SlotCardinalityV1
import ee.schimke.composeai.uibuilder.protocol.SvgCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.WasmCapabilityV1
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The explicitly enabled production catalogs admitted by the v1 service.
 *
 * Resolution is exact across all four pin fields. The packaged catalog is parsed strictly and its
 * invariants are checked before it is exposed; there is no "closest" revision or permissive
 * component fallback. Export capabilities are supplied by the renderer adapter at startup, so a
 * host without the packaged daemon lane advertises Compose only instead of claiming artifacts it
 * cannot produce.
 */
public class CurrentM3UiBuilderCatalogExecutor(
  source: String = packagedM3CatalogSource(),
  catalogSystemIds: Set<String> = setOf(DEFAULT_CATALOG_SYSTEM_ID),
  exportCapabilities: ExportCapabilitiesV1 =
    ExportCapabilitiesV1(composeCode = true, svg = false, png = false),
  /**
   * Whether a given catalog can export Compose, asked per catalog rather than once.
   *
   * `exportCapabilities` is a field of `CatalogCapabilityV1` — one per catalog on the wire — and
   * the host used to compute a single boolean and copy it onto every enabled catalog. So a
   * deployment serving `m3-catalog` (which has a component record) alongside `remote-m3` (which
   * deliberately does not, Remote Compose being outside the Compose exporter) advertised no Compose
   * export **anywhere**, and the builder withdrew the action from the catalog that could have used
   * it. Defaults to the flat value, so a caller that does not care is unaffected.
   */
  composeExportFor: (String) -> Boolean = { exportCapabilities.composeCode },
) : UiBuilderCatalogExecutor {
  private val baseCatalog =
    json
      .decodeFromString<CatalogCapabilityV1>(source)
      .let(::validateCatalog)
      .copy(exportCapabilities = exportCapabilities)
  private val availableCatalogs =
    mapOf(
      DEFAULT_CATALOG_SYSTEM_ID to baseCatalog,
      REMOTE_M3_CATALOG_SYSTEM_ID to remoteM3Catalog(baseCatalog),
      WEAR_M3_CATALOG_SYSTEM_ID to wearM3Catalog(baseCatalog),
    )
  private val catalogs =
    catalogSystemIds
      .also { require(it.isNotEmpty()) { "at least one UI-builder catalog must be enabled" } }
      .associateWith { systemId ->
        require(SAFE_SYSTEM_ID.matches(systemId)) { "invalid UI-builder catalog id: $systemId" }
        val catalog =
          requireNotNull(availableCatalogs[systemId]) {
            "UI-builder catalog $systemId has no packaged adapter"
          }
        catalog.copy(
          exportCapabilities =
            catalog.exportCapabilities.copy(composeCode = composeExportFor(systemId))
        )
      }
  private val references = catalogs.mapValues { (_, catalog) ->
    CatalogReferenceV1(
      systemId = catalog.benchmark.catalogSystemId,
      catalogRevision = catalog.benchmark.catalogRevision,
      // The frozen v1 fixture names this pin explicitly. When the catalog wire shape grows a
      // digest field, this becomes the digest read from the signed catalog rather than a
      // convention.
      capabilityDigest = CURRENT_CAPABILITY_DIGEST,
      nativeRuntimeId = catalog.benchmark.nativeRuntimeId,
    )
  }
  private val components = catalogs.mapValues { (_, catalog) ->
    catalog.components.associateBy { it.componentId }
  }

  override fun listCatalogs(): List<CatalogCapabilityV1> = catalogs.values.toList()

  override fun resolve(reference: CatalogReferenceV1): CatalogCapabilityV1? =
    catalogs[reference.systemId]?.takeIf { reference == references[reference.systemId] }

  override fun validate(
    document: DesignDocumentV1,
    catalog: CatalogCapabilityV1,
  ): UiBuilderCatalogIssue? {
    val systemId = catalog.benchmark.catalogSystemId
    if (catalog != catalogs[systemId])
      return issue("CATALOG_MISMATCH", "catalog is not an enabled UI-builder catalog")
    if (document.catalogPin != references[systemId]) {
      return issue("CATALOG_PIN_MISMATCH", "document catalog pin does not resolve exactly")
    }
    val catalogComponents = components.getValue(systemId)
    val encodedDocument = json.encodeToJsonElement(document).jsonObject
    val encodedNodes = encodedDocument.getValue("nodes").jsonObject
    for ((nodeId, nodeElement) in encodedNodes.entries.sortedBy { it.key }) {
      val node = nodeElement.jsonObject
      val componentId = node.requiredString("componentId")
      val component =
        catalogComponents[componentId]
          ?: return issue(
            "UNKNOWN_COMPONENT",
            "component $componentId is not in $systemId",
            nodeId,
          )
      val properties = node.objectOrEmpty("properties")
      val declaredProperties = component.properties.associateBy { it.name }
      for ((name, value) in properties) {
        val capability =
          declaredProperties[name]
            ?: return issue(
              "UNKNOWN_PROPERTY",
              "property $name is not declared by $componentId",
              nodeId,
              name,
            )
        val unwrapped = value.unwrapTypedValue()
        if (!capability.jsonType.accepts(unwrapped)) {
          return issue(
            "INVALID_PROPERTY_TYPE",
            "property $name does not match its catalog JSON type",
            nodeId,
            name,
          )
        }
        if (capability.allowedValues.isNotEmpty() && unwrapped !in capability.allowedValues) {
          return issue(
            "INVALID_PROPERTY_VALUE",
            "property $name is outside its catalog allowed values",
            nodeId,
            name,
          )
        }
      }
      component.properties
        .filter { it.required }
        .forEach { property ->
          if (property.name !in properties) {
            return issue(
              "MISSING_REQUIRED_PROPERTY",
              "required property ${property.name} is missing",
              nodeId,
              property.name,
            )
          }
        }

      val allowedModifiers = component.modifierCapabilities.toSet()
      node.arrayOrEmpty("modifiers").forEachIndexed { index, modifier ->
        val type = (modifier as? JsonObject)?.optionalString("type")
        if (type == null || type !in allowedModifiers) {
          return issue(
            "UNKNOWN_MODIFIER",
            "modifier ${type ?: "at index $index"} is not declared by $componentId",
            nodeId,
            "modifiers[$index]",
          )
        }
      }

      val declaredSlots = component.slots.associateBy { it.name }
      val acceptsDynamicSlots = "DynamicSlots" in component.traits
      val slots = node.objectOrEmpty("slots")
      for ((name, childrenElement) in slots) {
        val slot =
          declaredSlots[name]
            ?: if (acceptsDynamicSlots) null
            else
              return issue(
                "UNKNOWN_SLOT",
                "slot $name is not declared by $componentId",
                nodeId,
                name,
              )
        val children = childrenElement.jsonArray.map { it.jsonPrimitive.content }
        val maximum = slot?.cardinality?.max
        if (
          slot != null &&
            (children.size < slot.cardinality.min || (maximum != null && children.size > maximum))
        ) {
          return issue(
            "SLOT_CARDINALITY",
            "slot $name has ${children.size} children; expected ${slot.cardinality.min}..${maximum ?: "unbounded"}",
            nodeId,
            name,
          )
        }
        for (childId in children) {
          val child =
            encodedNodes[childId]?.jsonObject
              ?: return issue(
                "UNKNOWN_CHILD",
                "slot $name references missing node $childId",
                nodeId,
                name,
              )
          val childCapability =
            catalogComponents[child.requiredString("componentId")]
              ?: return issue(
                "UNKNOWN_COMPONENT",
                "child $childId has an unknown component",
                childId,
              )
          val roleAccepted =
            slot == null ||
              slot.acceptedRoles.isEmpty() ||
              childCapability.role in slot.acceptedRoles
          val traitAccepted =
            slot == null ||
              slot.acceptedTraits.isEmpty() ||
              "AnyContent" in slot.acceptedTraits ||
              childCapability.traits.any(slot.acceptedTraits::contains)
          if (!roleAccepted && !traitAccepted) {
            return issue(
              "INCOMPATIBLE_SLOT_CHILD",
              "child $childId is not compatible with slot $name",
              nodeId,
              name,
            )
          }
        }
      }
      component.slots
        .filter { it.name !in slots }
        .forEach { slot ->
          if (slot.cardinality.min > 0) {
            return issue(
              "SLOT_CARDINALITY",
              "required slot ${slot.name} is missing",
              nodeId,
              slot.name,
            )
          }
        }
    }
    return null
  }

  public companion object {
    public const val RESOURCE: String =
      "/ee/schimke/composeai/uibuilder/catalogs/m3-catalog-v1.json"
    public const val CURRENT_CAPABILITY_DIGEST: String = "candidate"
    public const val DEFAULT_CATALOG_SYSTEM_ID: String = "m3-catalog"
    public const val REMOTE_M3_CATALOG_SYSTEM_ID: String = "remote-m3"
    public const val WEAR_M3_CATALOG_SYSTEM_ID: String = "wear-m3"
    private val SAFE_SYSTEM_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

    private fun packagedM3CatalogSource(): String =
      checkNotNull(CurrentM3UiBuilderCatalogExecutor::class.java.getResourceAsStream(RESOURCE)) {
          "packaged M3 UI-builder catalog is missing"
        }
        .bufferedReader(Charsets.UTF_8)
        .use { it.readText() }
  }
}

/**
 * The first Remote M3 authoring surface is deliberately a reviewed subset, not an alias for the
 * complete Material 3 catalog. The host dimensions copy the stable 240dp-screen squircle preview
 * contract from wear-m3-catalog: 200x60dp or 200x108dp content, 8dp padding on every edge and a
 * 26dp corner radius, producing 216x76dp and 216x124dp canvases.
 */
/**
 * The Wear widget host frame's authored parameters.
 *
 * These are `androidx.glance.wear.composable.WearWidgetContainer`'s four arguments. The one that
 * matters most is `background`: on-device it is the *widget's* own background — the brush passed to
 * `WearWidgetDocument` — and the container paints it as the round rect, so the coloured squircle IS
 * the widget. A scaffold without it forced an author to fake the background with a filled surface
 * inside the content slot, which draws a coloured rectangle inside a differently-coloured frame:
 * not what any widget looks like.
 *
 * Defaults match upstream. `background` defaults to the literal `#FF272430` that
 * `WearWidgetContainer` forks from
 * `androidx.wear.compose.material3.ColorScheme.surfaceContainerLow` and applies when a widget
 * declares no background of its own; padding and radius default to the 8dp/26dp squircle spec every
 * shipped `WidgetPreviewParams` provider carries.
 */
private fun widgetContainerProperties(): List<PropertyCapabilityV1> =
  listOf(
    PropertyCapabilityV1(
      name = "background",
      jsonType = JsonPrimitive("string"),
      notes =
        "The widget's own background, painted by the host as the rounded rect. Defaults to " +
          "#FF272430, the colour WearWidgetContainer applies to a widget that declares none.",
    ),
    PropertyCapabilityV1(
      name = "horizontalPaddingDp",
      jsonType = JsonPrimitive("number"),
      notes = "WearWidgetParams.horizontalPaddingDp; 8 in every shipped preview spec.",
    ),
    PropertyCapabilityV1(
      name = "verticalPaddingDp",
      jsonType = JsonPrimitive("number"),
      notes = "WearWidgetParams.verticalPaddingDp; 8 in every shipped preview spec.",
    ),
    PropertyCapabilityV1(
      name = "cornerRadiusDp",
      jsonType = JsonPrimitive("number"),
      notes =
        "WearWidgetParams.cornerRadiusDp: 26 squircle, 999 round, 0 rectangular. The host draws " +
          "this radius behind the content rather than clipping to it.",
    ),
  )

private fun remoteM3Catalog(base: CatalogCapabilityV1): CatalogCapabilityV1 {
  val components = base.components.associateBy { it.componentId }
  val box = components.getValue("layout/box")
  val supportedWasm = components.getValue("m3/text").wasm
  val blockedSvg = components.getValue("remote-compose/document").svg
  val contentSlot =
    box.slots
      .single()
      .copy(
        name = "content",
        cardinality = box.slots.single().cardinality.copy(min = 0, max = 1),
      )
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
    contentSlot.copy(
      name = "background",
      cardinality = contentSlot.cardinality.copy(min = 0, max = null),
      acceptedRoles = listOf("Leaf"),
      acceptedTraits = listOf("DrawLayer", "ImageContent"),
    )
  fun widget(componentId: String, displayName: String) =
    box.copy(
      componentId = componentId,
      displayName = displayName,
      role = "Scaffold",
      traits = listOf("ScreenContent", "WearWidgetHost", "RemoteContentHost"),
      slots = listOf(backgroundSlot, contentSlot),
      // `WearWidgetContainer`'s own parameters, and only those. The container composable takes
      // (horizontalPadding, verticalPadding, cornerRadius, background); the content size comes from
      // `WearWidgetParams` and is what picks Small over Large, so it stays the component id rather
      // than becoming a fifth property nobody could set to a legal value.
      properties = widgetContainerProperties(),
      modifierCapabilities = emptyList(),
      wasm =
        supportedWasm.copy(
          notes =
            "Compose UI recreation of the Glance Wear squircle host preview; its content slot may host ordinary or nested Remote Compose content, and its background slot the gradient and image brushes WearWidgetBrush chains."
        ),
      code = null,
      svg =
        blockedSvg?.copy(
          notes = "The copied Wear widget host geometry has not yet passed structured SVG parity."
        ),
    )
  val authoringIds =
    listOf(
      "layout/box",
      "layout/column",
      "layout/row",
      "m3/surface",
      "m3/text",
      "remote-compose/document",
    )
  return base.copy(
    benchmark =
      base.benchmark.copy(
        id = "remote-m3-wear-widget-scaffolds",
        sourceRevision = "wear-m3-catalog@d4e4e684e61d0657aad4ccb7752b8c0ab5d9dedf",
        catalogSystemId = CurrentM3UiBuilderCatalogExecutor.REMOTE_M3_CATALOG_SYSTEM_ID,
        catalogRevision = "wear-widget-scaffolds-v1",
      ),
    components =
      listOf(
        widget("remote-m3/widget-container-small", "Wear widget · Small (216×76dp)"),
        widget("remote-m3/widget-container-large", "Wear widget · Large (216×124dp)"),
      ) + authoringIds.map(components::getValue),
  )
}

/**
 * The Wear screen host's authored parameters.
 *
 * `androidx.wear.compose.material3.ScreenScaffold` is NOT the widget container's kind of stand-in.
 * The widget frame is drawn by the launcher, so `WearWidgetCodeExporter` erases it;
 * `ScreenScaffold` is a composable the author calls, so this scaffold is *emitted* rather than
 * erased. What it fakes is only the drawing: the browser has no Wear Compose to draw with, so the
 * canvas approximates the frame and the generated Kotlin names the real one.
 *
 * The screen's diameter is deliberately absent. It is the document's own frame — the Screen
 * inspector's Wear OS device presets already carry 192/227/240dp at the right density — and a fifth
 * property would be a second answer to a question the environment already answers.
 */
private fun wearScreenScaffoldProperties(): List<PropertyCapabilityV1> =
  listOf(
    PropertyCapabilityV1(
      name = "timeText",
      jsonType = JsonPrimitive("string"),
      notes =
        "The curved status strip's text. Frozen rather than live: a design whose render changed " +
          "every minute could not be diffed. Empty draws no strip, which is `ScreenScaffold` " +
          "without a `timeText` argument.",
    ),
    PropertyCapabilityV1(
      name = "scrollIndicator",
      jsonType = JsonPrimitive("boolean"),
      notes =
        "Whether the generated screen gives `ScreenScaffold` a scroll indicator. The canvas draws " +
          "none either way: an indicator shows where a viewport sits in the content, and the " +
          "long-screenshot extent has no viewport. The real capture agrees — a `ScrollMode.LONG` " +
          "render sets `LocalScrollCaptureInProgress` and the emitted scaffold suppresses the " +
          "indicator while it is set, which is what keeps a stitched capture free of the dashes " +
          "an indicator drawn per frame leaves down the edge.",
    ),
    PropertyCapabilityV1(
      name = "background",
      jsonType = JsonPrimitive("string"),
      notes =
        "The screen's background. Wear is dark-first, so it defaults to the Wear Material 3 " +
          "`background` — pure black — rather than to the editor theme's surface.",
    ),
  )

/**
 * What a `ListHeader` lets an author set, which is its label and how the label reads.
 *
 * Not the full `m3/text` surface it borrows its shape from: a header's colour, alignment and size
 * are the component's, and offering them would invite a design that no longer measures 48dp — the
 * one thing this component exists to guarantee.
 */
private val WEAR_LIST_HEADER_PROPERTIES = setOf("text", "maxLines", "overflow")

/** `TransformingLazyColumn`'s authored parameters, minus the ones its state object carries. */
private fun wearTransformingLazyColumnProperties(): List<PropertyCapabilityV1> =
  listOf(
    PropertyCapabilityV1(
      name = "verticalSpacingDp",
      jsonType = JsonPrimitive("number"),
      notes = "`Arrangement.spacedBy` between items; 4dp is the Wear list default.",
    ),
    PropertyCapabilityV1(
      name = "transformation",
      jsonType = JsonPrimitive("string"),
      allowedValues = listOf(JsonPrimitive("spec"), JsonPrimitive("none")),
      notes =
        "Whether each item carries `SurfaceTransformation(spec)` and `transformedHeight`. The " +
          "canvas cannot draw either — see the wasm note — so this says what the generated " +
          "Kotlin emits, not what you are looking at.",
    ),
  )

/**
 * The Wear Material 3 components this catalog offers that have **no Material 3 counterpart at
 * all**.
 *
 * ## Why these can exist now, when `wear-m3/checkbox-button` was refused once
 *
 * `docs/design/UI_BUILDER_WEAR_SCREEN.md` rules out one specific thing, and it is worth quoting
 * rather than paraphrasing: *do not fabricate a component in the Wasm canvas to stand in for a
 * library the canvas cannot link*. That rule closed
 * [#395](https://github.com/yschimke/compose-preview-server/pull/395), which built
 * `CheckboxButton`, `SwitchButton` and `RadioButton` as hand-assembled Material 3 shapes at sizes
 * read off a screenshot — an impression of upstream with nothing in the build to check it against,
 * wrong silently in the one surface an author trusts. The same document says what would let them
 * in: *they arrive with the streaming preview or they do not arrive*.
 *
 * They arrive with the streaming preview. `ServeUiBuilderNativePreview` now compiles a Wear
 * design's own generated Kotlin against a bundle carrying `androidx.wear.compose:compose-material3`
 * and renders it on the Android/Robolectric daemon, and `wear-m3` declares that lane authoritative
 * and its own canvas approximate (`previewSurfaces`, read by `UiBuilderPreviewSurfaces`). So the
 * premise the rule rests on — that the canvas is the surface an author trusts — is no longer true
 * here, and the rule itself is kept rather than bent: **nothing below is drawn as a lookalike**.
 * The canvas gives each of these a named placeholder occupying its place in the layout and claiming
 * nothing about its size, colour or shape, and the picture comes from Android.
 *
 * What that buys is the whole point. A Wear screen can now hold the controls Wear actually
 * publishes — a labelled full-width `CheckboxButton`, a `Slider`, a `DatePicker` — instead of a
 * palette of three renamed borrows and a container to put them in.
 *
 * ## The three that stay lookalikes
 *
 * `wear-m3/text`, `wear-m3/card` and `wear-m3/button` keep the Material 3 drawing they have, for
 * the reason that made them acceptable in the first place: each is a *rename* of a borrow the
 * canvas was already drawing, not a shape assembled for the occasion. `WearCanvasStandInTest` pins
 * that map to exactly those three, and nothing here joins it.
 */
private fun wearNativeOnlyComponents(
  supportedWasm: WasmCapabilityV1,
  blockedSvg: SvgCapabilityV1?,
  iconKeys: List<JsonElement>,
): List<ComponentCapabilityV1> {
  /** The note every component in this group carries, with its own composable named. */
  fun note(composable: String, extra: String = "") =
    "Wear Material 3's `$composable`." +
      (if (extra.isEmpty()) "" else " $extra") +
      " The canvas draws a named placeholder where this node sits rather than the component: " +
      "`androidx.wear.compose:compose-material3` is an Android AAR a Wasm build cannot link, and a " +
      "hand-drawn lookalike would be an impression of upstream with nothing in this build to check " +
      "it against. Switch the render surface to Android for the real one — that lane compiles this " +
      "design's own generated Kotlin against real Wear Compose."

  fun component(
    componentId: String,
    displayName: String,
    composable: String,
    role: String,
    traits: List<String>,
    properties: List<PropertyCapabilityV1> = emptyList(),
    slots: List<SlotCapabilityV1> = emptyList(),
    extra: String = "",
  ) =
    ComponentCapabilityV1(
      componentId = componentId,
      displayName = displayName,
      role = role,
      traits = traits,
      slots = slots,
      properties = properties,
      // No modifier vocabulary, and that is a statement rather than an omission. A Wear control is
      // laid out by the list and the scaffold around it — a `CheckboxButton` is a full-width row
      // whose height upstream fixes — and `WearScreenCodeExporter` writes the whole screen, so
      // there is no per-node modifier chain for it to carry one into.
      modifierCapabilities = emptyList(),
      wasm = supportedWasm.copy(notes = note(composable, extra)),
      // The call site comes from `WearScreenCodeExporter`, which writes the whole screen, and never
      // from a per-component record: `wear-m3` has none, deliberately.
      code = null,
      svg =
        blockedSvg?.copy(
          notes =
            "A placeholder on the canvas must not claim structured SVG parity with $composable."
        ),
    )

  /** `label` and `secondaryLabel`, which is the shape every Wear selection control shares. */
  fun labelled(secondary: Boolean = true) = buildList {
    add(
      PropertyCapabilityV1(
        name = "label",
        jsonType = JsonPrimitive("string"),
        required = true,
        notes =
          "The row's primary label. Wear's selection controls are labelled rows, not bare boxes.",
      )
    )
    if (secondary) {
      add(
        PropertyCapabilityV1(
          name = "secondaryLabel",
          jsonType = JsonPrimitive("string"),
          notes = "The second line, where there is one. Empty emits no `secondaryLabel` argument.",
        )
      )
    }
  }

  /**
   * A checked/selected flag, drivable from a state variable exactly as `m3/checkbox.checked` is.
   */
  fun flag(name: String, notes: String) =
    PropertyCapabilityV1(
      name = name,
      // `object` beside `boolean` for the reason the five mobile flags carry it: a state binding
      // arrives as a wrapper object, and a declaration of `boolean` alone judges the binding by
      // whatever scalar happens to be inside it.
      jsonType = JsonArray(listOf(JsonPrimitive("boolean"), JsonPrimitive("object"))),
      notes = notes,
    )

  fun enum(name: String, values: List<String>, notes: String, required: Boolean = false) =
    PropertyCapabilityV1(
      name = name,
      jsonType = JsonPrimitive("string"),
      required = required,
      allowedValues = values.map(::JsonPrimitive),
      notes = notes,
    )

  fun number(name: String, notes: String) =
    PropertyCapabilityV1(
      name = name,
      jsonType = JsonArray(listOf(JsonPrimitive("number"), JsonPrimitive("object"))),
      notes = notes,
    )

  fun text(name: String, notes: String, required: Boolean = false) =
    PropertyCapabilityV1(
      name = name,
      jsonType = JsonPrimitive("string"),
      required = required,
      notes = notes,
    )

  /** One content slot holding a single child, for the components whose API takes one lambda. */
  fun singleSlot(name: String, traits: List<String>, min: Int = 0) =
    SlotCapabilityV1(
      name = name,
      cardinality = SlotCardinalityV1(min = min, max = 1),
      ordered = true,
      acceptedRoles = emptyList(),
      acceptedTraits = traits,
    )

  fun manySlot(name: String, traits: List<String>) =
    SlotCapabilityV1(
      name = name,
      cardinality = SlotCardinalityV1(min = 0, max = null),
      ordered = true,
      acceptedRoles = emptyList(),
      acceptedTraits = traits,
    )

  val listItem = listOf("ListItem", "WearListContent")

  return listOf(
    component(
      componentId = "wear-m3/icon",
      displayName = "Icon",
      composable = "Icon",
      role = "Leaf",
      traits = listOf("Adornment"),
      properties =
        listOf(
          PropertyCapabilityV1(
            name = "iconKey",
            jsonType = JsonPrimitive("string"),
            required = true,
            allowedValues = iconKeys,
            // The same keys `m3/icon` offers, and deliberately the same table. An icon is
            // `androidx.compose.material.icons`, which is not Material 3 and not Wear Material 3 —
            // it is the shared vector library both draw with — so this is one of the few places a
            // Wear component and a mobile one really do name the same symbol.
            notes =
              "A Material icon key, resolved to `Icons.…` by the same table `m3/icon` uses. The " +
                "vectors are `androidx.compose.material.icons`, which both platforms share, so " +
                "this key means the same thing on a watch as on a phone.",
          ),
          number("sizeDp", "The icon's box. Wear's own default is 24dp inside a button."),
        ),
    ),
    component(
      componentId = "wear-m3/icon-button",
      displayName = "Icon button",
      composable = "IconButton",
      role = "Container",
      traits = listOf("Action", "ListItem"),
      slots = listOf(singleSlot("content", listOf("Adornment"), min = 1)),
      properties =
        listOf(
          enum(
            "variant",
            listOf("filled", "filled-tonal", "filled-variant", "outlined", "standard"),
            "Which of the five `IconButton` overloads is written: `FilledIconButton`, " +
              "`FilledTonalIconButton`, `FilledVariantIconButton`, `OutlinedIconButton` or plain " +
              "`IconButton`. A variant selects the composable rather than tinting one, the way " +
              "`m3/button`'s style does.",
          )
        ),
    ),
    component(
      componentId = "wear-m3/text-button",
      displayName = "Text button",
      composable = "TextButton",
      role = "Container",
      traits = listOf("Action", "ListItem"),
      slots = listOf(singleSlot("content", listOf("AnyContent"), min = 1)),
      properties =
        listOf(
          enum(
            "variant",
            listOf("filled", "filled-tonal", "filled-variant", "outlined", "standard"),
            "As `wear-m3/icon-button`'s: the variant names the composable — `FilledTextButton` " +
              "and the rest — rather than recolouring one.",
          )
        ),
    ),
    component(
      componentId = "wear-m3/list-sub-header",
      displayName = "List sub-header",
      composable = "ListSubHeader",
      role = "Leaf",
      traits = listItem,
      properties = listOf(text("text", "The sub-header's label.", required = true)),
      extra =
        "The second level of list heading, under `wear-m3/list-header`: smaller, start-aligned, " +
          "and the one used to divide a long list into named runs.",
    ),
    component(
      componentId = "wear-m3/checkbox-button",
      displayName = "Checkbox button",
      composable = "CheckboxButton",
      role = "Leaf",
      traits = listItem + "Selection",
      properties =
        labelled() + flag("checked", "Whether the box is ticked. Bindable to a state variable."),
      extra =
        "A full-width labelled row with the checkbox at its end — not the mobile 20dp square, " +
          "which is the whole reason `m3/checkbox` could never have been borrowed for it.",
    ),
    component(
      componentId = "wear-m3/switch-button",
      displayName = "Switch button",
      composable = "SwitchButton",
      role = "Leaf",
      traits = listItem + "Selection",
      properties =
        labelled() + flag("checked", "Whether the switch is on. Bindable to a state variable."),
      extra = "The same labelled row as `wear-m3/checkbox-button`, with a switch as its control.",
    ),
    component(
      componentId = "wear-m3/radio-button",
      displayName = "Radio button",
      composable = "RadioButton",
      role = "Leaf",
      traits = listItem + "Selection",
      properties =
        labelled() + flag("selected", "Whether this row is the chosen one of its group."),
      extra = "The same labelled row again, with a radio control and single-choice semantics.",
    ),
    component(
      componentId = "wear-m3/slider",
      displayName = "Slider",
      composable = "Slider",
      role = "Leaf",
      traits = listItem,
      properties =
        listOf(
          number("value", "The current value, between `valueFrom` and `valueTo`."),
          number("valueFrom", "The low end of the range. 0 when absent."),
          number("valueTo", "The high end of the range. 1 when absent."),
          number("steps", "How many discrete stops sit between the ends. 0 is continuous."),
          enum(
            "segmented",
            listOf("segmented", "continuous"),
            "Whether the track is drawn as separated segments, which is what Wear's stepped " +
              "slider looks like.",
          ),
        ),
      extra =
        "Wear's slider is a row with a decrement and an increment button around the track, not a " +
          "bare thumb on a line.",
    ),
    component(
      componentId = "wear-m3/stepper",
      displayName = "Stepper",
      composable = "Stepper",
      role = "Container",
      traits = listOf("ScreenContent"),
      slots = listOf(singleSlot("content", listOf("AnyContent"), min = 1)),
      properties =
        listOf(
          number("value", "The current value."),
          number("valueFrom", "The low end of the range. 0 when absent."),
          number("valueTo", "The high end of the range. 1 when absent."),
          number("steps", "How many discrete stops sit between the ends. 0 is continuous."),
        ),
      extra =
        "A full-screen control: increment and decrement buttons at the top and bottom of the " +
          "round display with the current value between them. It is not a list row, which is why " +
          "it carries `ScreenContent` rather than `ListItem`.",
    ),
    component(
      componentId = "wear-m3/progress-indicator",
      displayName = "Progress indicator",
      composable = "CircularProgressIndicator",
      role = "Leaf",
      traits = listItem + "ScreenContent",
      properties =
        listOf(
          enum(
            "variant",
            listOf("circular", "segmented-circular", "linear", "arc"),
            "Which indicator is written: `CircularProgressIndicator`, " +
              "`SegmentedCircularProgressIndicator`, `LinearProgressIndicator` or " +
              "`ArcProgressIndicator`. The circular ones ring the whole display; the linear one is " +
              "a list row.",
          ),
          number("progress", "0..1. Absent is the indeterminate form, which takes no progress."),
          number("segments", "How many segments the segmented circular form is divided into."),
        ),
      extra = "Wear publishes four, and which one you get is the `variant`.",
    ),
    component(
      componentId = "wear-m3/edge-button",
      displayName = "Edge button",
      composable = "EdgeButton",
      role = "Container",
      traits = listOf("Action"),
      slots = listOf(singleSlot("content", listOf("AnyContent"), min = 1)),
      properties =
        listOf(
          enum(
            "size",
            listOf("extra-small", "small", "medium", "large"),
            "`EdgeButtonSize`. The button's shape comes from the screen's bottom curve, so its " +
              "size is chosen from upstream's four rather than set in dp.",
          )
        ),
      extra =
        "The button that hugs the bottom of a round screen. It belongs in the scaffold's " +
          "`edgeButton` slot, where `ScreenScaffold` reveals it from the scroll state; it is a " +
          "component of its own now rather than a `wear-m3/button` placed there, because " +
          "`EdgeButton` is a different composable with a different shape and a size enum.",
    ),
    component(
      componentId = "wear-m3/button-group",
      displayName = "Button group",
      composable = "ButtonGroup",
      role = "Container",
      traits = listItem,
      slots = listOf(manySlot("children", listOf("Action"))),
      extra =
        "A row of buttons that share the width and grow the one being pressed. Its children are " +
          "buttons; anything else has no `ButtonGroupScope` to be laid out in.",
    ),
    component(
      componentId = "wear-m3/alert-dialog",
      displayName = "Alert dialog",
      composable = "AlertDialog",
      role = "Container",
      traits = listOf("Overlay"),
      slots =
        listOf(
          manySlot("content", listOf("AnyContent")),
          singleSlot("confirmButton", listOf("Action")),
          singleSlot("dismissButton", listOf("Action")),
        ),
      properties =
        listOf(
          text("title", "The dialog's title.", required = true),
          text("text", "The supporting line under the title. Empty emits no `text` argument."),
          flag(
            "visible",
            "Whether the dialog is showing. A dialog is a screen state rather than a place in the " +
              "layout, so this is what the generated `AlertDialog(visible = …)` reads.",
          ),
        ),
      extra =
        "Wear's own, which is a full-screen scrolling dialog with its buttons on the bottom curve " +
          "— not a card floating over a scrim.",
    ),
    component(
      componentId = "wear-m3/confirmation-dialog",
      displayName = "Confirmation dialog",
      composable = "ConfirmationDialog",
      role = "Leaf",
      traits = listOf("Overlay"),
      properties =
        listOf(
          text("text", "The line shown under the icon.", required = true),
          enum(
            "variant",
            listOf("generic", "success", "failure"),
            "`ConfirmationDialog`, `SuccessConfirmationDialog` or `FailureConfirmationDialog`. " +
              "The two named ones bring their own icon and curved text; the generic one takes the " +
              "text alone.",
          ),
          flag("visible", "Whether it is showing, as for `wear-m3/alert-dialog`."),
        ),
      extra =
        "The brief full-screen acknowledgement Wear shows after an action and then dismisses.",
    ),
    component(
      componentId = "wear-m3/open-on-phone-dialog",
      displayName = "Open on phone dialog",
      composable = "OpenOnPhoneDialog",
      role = "Leaf",
      traits = listOf("Overlay"),
      properties =
        listOf(
          text("text", "The curved line under the animation. Empty takes upstream's own."),
          flag("visible", "Whether it is showing, as for `wear-m3/alert-dialog`."),
        ),
      extra =
        "The one Wear surface with no mobile analogue at all: it tells the wearer the rest of this " +
          "journey happens on their phone.",
    ),
    component(
      componentId = "wear-m3/date-picker",
      displayName = "Date picker",
      composable = "DatePicker",
      role = "Leaf",
      traits = listOf("ScreenContent"),
      properties =
        listOf(
          text("initialDate", "ISO-8601 `yyyy-MM-dd`. Empty picks upstream's own initial date."),
          enum(
            "type",
            listOf("year-month-day", "day-month-year", "month-day-year"),
            "`DatePickerType`, which is field order rather than formatting.",
          ),
        ),
      extra = "A full-screen three-column picker, driven by the rotary side button.",
    ),
    component(
      componentId = "wear-m3/time-picker",
      displayName = "Time picker",
      composable = "TimePicker",
      role = "Leaf",
      traits = listOf("ScreenContent"),
      properties =
        listOf(
          text("initialTime", "ISO-8601 `HH:mm[:ss]`. Empty picks upstream's own initial time."),
          enum(
            "type",
            listOf("hours-minutes-seconds", "hours-minutes-am-pm", "hours-minutes-24h"),
            "`TimePickerType`, which decides both the columns and the clock.",
          ),
        ),
      extra = "The time counterpart of `wear-m3/date-picker`, and the same full-screen shape.",
    ),
  )
}

/**
 * `wear-m3`: the Wear Compose Material 3 screen, as an authoring surface.
 *
 * ## Why this is a re-creation and not the library
 *
 * `androidx.wear.compose:compose-material3` is an Android AAR. The builder's canvas is Compose
 * Multiplatform for Wasm, which cannot link an AAR at all, so there is no version of this adapter
 * that draws with the real components — unlike `m3-catalog`, where the canvas draws the same
 * Material 3 the export names. Every capability note below says so rather than implying parity.
 *
 * ## The two components that are this catalog's whole point
 *
 * `wear-m3/screen-scaffold` and `wear-m3/transforming-lazy-column`. A Wear screen is a
 * `ScreenScaffold` wrapping a `TransformingLazyColumn` in something over ninety per cent of the
 * Wear Material 3 surface area, and neither has a Compose Multiplatform counterpart: the scaffold
 * owns the curved `TimeText` and the bezel scroll indicator, and the list scales and fades its rows
 * against the round display through `SurfaceTransformation`.
 *
 * The canvas draws the scaffold as a **stadium** — the screen's width, the content's height, round
 * caps — which is the Wear long-screenshot convention rather than a device. That is a deliberate
 * choice about what an author is building: the whole scrolling extent at once, not a 192dp keyhole
 * onto it. What it costs is stated in the wasm notes and again in
 * `docs/design/UI_BUILDER_WEAR_SCREEN.md`: straight sides overstate the width a row actually gets
 * near the curve, and the row transformation is not drawn.
 *
 * ## The rest is borrowed, and that is a limitation rather than a design
 *
 * The content components are `m3-catalog`'s. A Wear `Button` is not a Material 3 `Button` — it is a
 * pill 52dp tall with its own colour roles — and `TitleCard`, `ListHeader` and `EdgeButton` have no
 * mobile counterpart at all. They are borrowed here because the alternative was shipping a scaffold
 * with nothing to put in it, and every one of them is a wasm note saying "drawn as its mobile
 * counterpart". Real Wear content ids under `wear-m3` are the next change, not a missing detail of
 * this one.
 */
private fun wearM3Catalog(base: CatalogCapabilityV1): CatalogCapabilityV1 {
  val components = base.components.associateBy { it.componentId }
  val box = components.getValue("layout/box")
  val lazyColumn = components.getValue("layout/lazy-column")
  val supportedWasm = components.getValue("m3/text").wasm
  val blockedSvg = components.getValue("remote-compose/document").svg
  val boxSlot = box.slots.single()

  val contentSlot =
    boxSlot.copy(name = "content", cardinality = boxSlot.cardinality.copy(min = 0, max = 1))
  // `ScreenScaffold(edgeButton = …)` takes one composable, and upstream's own samples put an
  // `EdgeButton` in it and nothing else. Narrowed to `Action` so a Text cannot be dropped into a
  // slot whose whole job is to hug the bottom curve with a button in it.
  val edgeButtonSlot =
    contentSlot.copy(
      name = "edgeButton",
      acceptedRoles = emptyList(),
      acceptedTraits = listOf("Action"),
    )
  // Wear's dialogs are a screen *state*, not a place in the layout: each takes a `visible` flag and
  // draws over the whole display when it is set, and upstream's own samples put them beside the
  // scaffold in the same `AppScaffold`. A slot in `content` would have made them list rows, which
  // is a full-screen dialog inside a scrolling item. Unbounded, because a screen can have more than
  // one dialog it shows at different moments — only one is ever `visible`.
  val overlaySlot =
    contentSlot.copy(
      name = "overlays",
      cardinality = contentSlot.cardinality.copy(min = 0, max = null),
      acceptedRoles = emptyList(),
      acceptedTraits = listOf("Overlay"),
    )

  val scaffold =
    box.copy(
      componentId = "wear-m3/screen-scaffold",
      displayName = "Wear screen · ScreenScaffold",
      role = "Scaffold",
      traits = listOf("ScreenContent", "WearScreenHost"),
      slots = listOf(contentSlot, edgeButtonSlot, overlaySlot),
      properties = wearScreenScaffoldProperties(),
      modifierCapabilities = emptyList(),
      wasm =
        supportedWasm.copy(
          notes =
            "Drawn as a Wear long-screenshot stadium at the document frame's width, with the content padding the real `ScreenScaffold` computes for that screen size, the clock where `AppScaffold` puts it, and a bezel scroll indicator. It is not Wear Compose — `androidx.wear.compose:compose-material3` is an Android AAR the Wasm canvas cannot link — but it is measured against it: wear-m3-catalog's stitched `ScrollMode.LONG` capture of the same list matches this to within a dp."
        ),
      // No Compose export from the catalog's own record: `ScreenScaffold` is a scaffold with a
      // `contentPadding` lambda and a scroll-state argument that has to agree with the list inside
      // it, which is a shape `ScreenGenerator`'s call-site emitter cannot write from a record.
      // `WearScreenCodeExporter` writes the whole screen instead, the way
      // `WearWidgetCodeExporter` writes the whole widget.
      code = null,
      svg =
        blockedSvg?.copy(
          notes = "The stadium screen frame has not been through structured SVG parity."
        ),
    )

  // The first content component that is Wear's rather than borrowed, and it exists because the
  // round trip found it. `ListHeader` is a 48dp item at every screen size — measured — and the
  // template used to fake that with a padded `m3/text`. The canvas matched; the *generated screen*
  // did not, because a padded Text is not a ListHeader and the generator has no business emitting
  // one as the other. Fifteen dp of header is the difference between "the two pictures agree" and
  // "the two pictures agree except at the top", and there is no way to close it from the borrowed
  // side.
  val listHeader =
    components.getValue("m3/text").let { text ->
      text.copy(
        componentId = "wear-m3/list-header",
        displayName = "List header",
        traits = text.traits + "ListItem",
        properties = text.properties.filter { it.name in WEAR_LIST_HEADER_PROPERTIES },
        modifierCapabilities = emptyList(),
        wasm =
          text.wasm.copy(
            notes =
              "Wear Material 3's `ListHeader`: a 48dp item whose label sits low in it, drawn on the screen's own background rather than on a surface. The height is upstream's and is what makes a generated screen's first row land where the canvas puts it."
          ),
        code = null,
      )
    }

  val transformingLazyColumn =
    lazyColumn.copy(
      componentId = "wear-m3/transforming-lazy-column",
      displayName = "Transforming lazy column",
      traits = lazyColumn.traits + "WearListContent",
      slots =
        listOf(lazyColumn.slots.single().copy(cardinality = lazyColumn.slots.single().cardinality)),
      properties = wearTransformingLazyColumnProperties(),
      wasm =
        supportedWasm.copy(
          notes =
            "Drawn as a plain Column at the list's own spacing. That is what a stitched `ScrollMode.LONG` capture of the real one is: `LONG` turns the row transformation off in order to stitch, so every row on the reference is full content width at every position and the Column reproduces it exactly. What neither shows is a live frame, where `SurfaceTransformation` scales and fades a row by its distance from the bezel; the generated Kotlin emits that, and a single-frame render is what draws it."
        ),
      code = null,
      svg =
        blockedSvg?.copy(
          notes =
            "An untransformed stand-in must not claim structured SVG parity with the real list."
        ),
    )

  /**
   * A Wear component of this catalog's own, drawn on the canvas by its Material 3 lookalike.
   *
   * The rename is the point. `wear-m3` used to *borrow* `m3/text`, `m3/card`, `m3/button`,
   * `m3/icon` and `m3/surface` outright, and a borrowed Material id is a claim nobody should make
   * on a watch: **you do not use Material 3 and Wear Material 3 together.** They are different
   * libraries with different theme systems, sizes and colour roles, and a palette that offers
   * `m3/card` on a Wear screen says the design holds a `androidx.compose.material3.Card` when
   * `WearScreenCodeExporter` has always written it out as a `TitleCard`.
   *
   * So the id is Wear's and the drawing is borrowed, which is the same trade
   * [wear-m3/screen-scaffold][wearM3Catalog] and `wear-m3/list-header` already make: the Wasm
   * canvas cannot link `androidx.wear.compose:compose-material3` — it is an Android AAR — so it
   * draws the nearest Material 3 shape and says so in `wasm.notes`, while the generated Kotlin
   * names the real Wear composable.
   *
   * What is *actually* borrowed after this is foundation only: `layout/box`, `layout/column`,
   * `layout/row` and `asset/image` are `androidx.compose.foundation` and `androidx.compose.ui`,
   * which both platforms share, so borrowing them claims nothing about Material at all.
   */
  fun wearOwn(
    borrowedFrom: String,
    componentId: String,
    displayName: String,
    drawnAs: String,
    generatesAs: String,
  ) =
    components.getValue(borrowedFrom).let { source ->
      source.copy(
        componentId = componentId,
        displayName = displayName,
        wasm =
          source.wasm.copy(
            notes =
              "Wear Material 3's $generatesAs. Drawn on the canvas as $drawnAs, because the Wasm " +
                "canvas cannot link `androidx.wear.compose:compose-material3`; the generated " +
                "screen names the Wear composable."
          ),
        // The Compose call site comes from `WearScreenCodeExporter`, which writes the whole screen,
        // rather than from a per-component record: a Wear component's arguments are not the
        // Material
        // 3 component's, and a record naming the mobile callable is the mistake this rename undoes.
        code = null,
      )
    }

  val wearText = wearOwn("m3/text", "wear-m3/text", "Text", "a Material 3 Text", "`Text`")
  val wearCard =
    wearOwn("m3/card", "wear-m3/card", "Card", "a Material 3 Card", "`TitleCard`").let { card ->
      // A variant that **selects the composable**, the way `m3/card`'s does — not a recolouring of
      // one. Wear publishes four cards with different content lambdas: `TitleCard` (title,
      // subtitle, time), `AppCard` (an app name and icon above the title), `OutlinedCard` and the
      // plain `Card`. Rolling them into one id with a variant rather than four ids is the
      // `m3/card` precedent, and it keeps the palette the size of the vocabulary rather than the
      // size of the API.
      card.copy(
        properties =
          card.properties.filterNot { it.name == "variant" } +
            PropertyCapabilityV1(
              name = "variant",
              jsonType = JsonPrimitive("string"),
              allowedValues = listOf("title", "app", "outlined", "plain").map(::JsonPrimitive),
              notes =
                "Which card is written: `TitleCard`, `AppCard`, `OutlinedCard` or `Card`. " +
                  "`title` is the default and is the one a Wear list is mostly made of.",
            )
      )
    }
  val wearButton =
    wearOwn("m3/button", "wear-m3/button", "Button", "a Material 3 Button", "`Button`").let { button
      ->
      // The same treatment, and the same reason. Wear's four are `Button`, `FilledTonalButton`,
      // `OutlinedButton` and `ChildButton`; the mobile `style` list this borrowed carried `fab` and
      // `elevated`, which no watch publishes, so the property is replaced rather than filtered.
      button.copy(
        properties =
          button.properties.filterNot { it.name == "variant" || it.name == "style" } +
            PropertyCapabilityV1(
              name = "variant",
              jsonType = JsonPrimitive("string"),
              allowedValues =
                listOf("filled", "filled-tonal", "outlined", "child").map(::JsonPrimitive),
              notes =
                "Which button is written: `Button`, `FilledTonalButton`, `OutlinedButton` or " +
                  "`ChildButton`. There is no `fab` — a watch has no floating action button, " +
                  "which is one of the things a borrowed `m3/button` was quietly offering.",
            )
      )
    }
  val nativeOnly =
    wearNativeOnlyComponents(
      supportedWasm = supportedWasm,
      blockedSvg = blockedSvg,
      // The icon key table is `m3/icon`'s, taken from the catalog rather than restated: two lists
      // of icon names is two chances to disagree about which vector `genres` is.
      iconKeys =
        components.getValue("m3/icon").properties.single { it.name == "iconKey" }.allowedValues,
    )

  // Foundation only. Every one of these is `androidx.compose.foundation` or `androidx.compose.ui`,
  // shared by both platforms, so borrowing it claims nothing about which Material library a Wear
  // screen is built from — which is exactly what borrowing a Material component did claim.
  //
  // `m3/icon` left with the Material ones and has no Wear id yet: the icon key resolves to a vector
  // through a table this export module cannot reach, so `wear-m3/icon` would be a palette entry
  // that
  // refuses on export — which is what `m3/icon` already was here.
  val borrowedIds =
    listOf(
      "layout/box",
      "layout/column",
      "layout/row",
      "asset/image",
      // Every published `remote-m3` component, reachable from a Wear screen.
      //
      // Offering `remote-compose/document` is what lights up the builder's "Remote Compose
      // documents" palette, which lists every preview the *serving* catalog of that name publishes
      // an `ir/<id>.rc` for — on preview.coo.ee, the 28 Remote Compose components of
      // wear-m3-catalog's `:remote-catalog`, in all their published states. The bytes are fetched,
      // decoded and played in-process by the same `RcComposePlayer` the deployed player lanes use,
      // so a row dropped into a Wear list is drawn by the renderer a watch would use rather than by
      // a re-creation.
      //
      // This is the one component in the catalog that is not a stand-in for anything. It is also
      // the one the Compose generator refuses by name: a Remote Compose document has no Wear
      // Compose call site, so a screen holding one exports as a refusal that names the node rather
      // than as Kotlin that does not compile.
      "remote-compose/document",
    )
  val borrowed =
    borrowedIds.map(components::getValue).map { component ->
      // The note every borrowed component carries, and it now says the opposite of what it used to.
      // A borrowed Material component was a stand-in — "drawn as the Material 3 component of the
      // same name" — because Wear publishes its own and this drew the wrong one. A borrowed
      // foundation component is not a stand-in for anything: `Box`, `Column`, `Row` and `Image` are
      // the same declarations on both platforms, which is the whole reason these are the only ones
      // left.
      if (component.componentId == "remote-compose/document") component
      else
        component.copy(
          wasm =
            component.wasm.copy(
              notes =
                "Foundation, shared by Compose on both platforms — `androidx.compose.foundation` " +
                  "and `androidx.compose.ui` publish one of these, not two. It is the real " +
                  "component rather than a stand-in, which is why `wear-m3` borrows it and borrows " +
                  "no Material component at all."
            )
        )
    }

  return base.copy(
    // The one thing this catalog has to say about itself that is not a component.
    //
    // The Wasm canvas is where a `wear-m3` design is *authored* — you select a node on it, drag it,
    // watch the layout — and it is not where the design is *looked at*. It cannot be: every Wear
    // component on it is a Material 3 lookalike, because `androidx.wear.compose:compose-material3`
    // is an Android AAR and a Wasm build links no AAR, ever. Saying that here rather than leaving
    // each surface to work it out is what stops the editor offering a Preview mode whose claim is
    // false and the server picking a daemon by guessing from a catalog id.
    //
    // `statusSemantics` rather than a field of its own because `CatalogCapabilityV1` is published
    // from compose-preview-contracts and cannot grow one from here; this map is the catalog's own
    // open vocabulary and already carries `adapterStatus` and `svgStatus`. Read back by
    // `UiBuilderPreviewSurfaces.from`.
    statusSemantics =
      JsonObject(
        base.statusSemantics +
          ("previewSurfaces" to
            buildJsonObject {
              putJsonObject("wasm") {
                put("fidelity", JsonPrimitive("approximate"))
                put(
                  "reason",
                  JsonPrimitive(
                    "The canvas is Compose Multiplatform for Wasm and Wear Material 3 is an " +
                      "Android AAR, so every Wear component here is drawn by its nearest Material " +
                      "3 lookalike. Author on it; do not read a size, a colour or a shape off it. " +
                      "The Android preview compiles this design's own generated Kotlin against " +
                      "real Wear Compose."
                  ),
                )
              }
              putJsonObject("native") {
                put("fidelity", JsonPrimitive("authoritative"))
                put("backend", JsonPrimitive("android"))
              }
            })
      ),
    benchmark =
      base.benchmark.copy(
        id = "wear-m3-screen-scaffold",
        sourceRevision = "compose-ai-tools:samples/design-catalog-wear-m3",
        catalogSystemId = CurrentM3UiBuilderCatalogExecutor.WEAR_M3_CATALOG_SYSTEM_ID,
        catalogRevision = "wear-screen-scaffold-v1",
      ),
    components =
      listOf(scaffold, transformingLazyColumn, listHeader, wearText, wearCard, wearButton) +
        nativeOnly +
        borrowed,
  )
}

/** Immutable, renderer-neutral request for one exact saved document revision. */
public data class UiBuilderRenderRequest(
  val designId: String,
  val revision: Long,
  val documentHash: String,
  val widthPx: Int,
  val heightPx: Int,
  val density: Float,
  val localeTag: String,
  val fontScale: Float,
  val encodedDocument: String,
)

/** Narrow pixel/vector port implemented by the server beside its render-host dependency. */
public interface UiBuilderRenderPort : Closeable {
  public val supportsSvg: Boolean

  public fun renderPng(request: UiBuilderRenderRequest): ByteArray

  public fun renderSvg(request: UiBuilderRenderRequest): ByteArray
}

/** Combines the runtime-owned Compose projection with an injected renderer-neutral port. */
public class ProductionUiBuilderExportExecutor(
  private val renderer: UiBuilderRenderPort,
  // Required, not defaulted. It used to default to a projection this module owned, and that
  // default is what let three different things emit Compose for one document: the real generator
  // (`ScreenGenerator`, reached from `:server`, which is the only layer allowed to hold both the
  // component record and this port), the editor's own exporter, and the default here — which
  // shipped an `ALMOST_COMPILING_PROJECTION` warning on every export because it could not tell
  // whether its output compiled. `ServeRunner` has always passed the real one, so the default was
  // reachable only by wiring nobody does in production; a default nobody should take is worse than
  // an argument everybody must pass.
  private val compose: UiBuilderExportExecutor,
) : UiBuilderExportExecutor, Closeable {
  public val capabilities: ExportCapabilitiesV1 =
    ExportCapabilitiesV1(composeCode = true, svg = renderer.supportsSvg, png = true)

  override fun export(request: RevisionPinnedUiBuilderExport): ExportArtifactV1 =
    when (request.format) {
      ExportFormatV1.COMPOSE -> compose.export(request)
      ExportFormatV1.PNG -> request.binaryArtifact(renderer.renderPng(request.toRenderRequest()))
      ExportFormatV1.SVG -> request.svgArtifact(renderer.renderSvg(request.toRenderRequest()))
    }

  override fun close(): Unit = renderer.close()

  private fun RevisionPinnedUiBuilderExport.toRenderRequest(): UiBuilderRenderRequest =
    UiBuilderRenderRequest(
      designId = designId,
      revision = revision,
      documentHash = documentHash,
      widthPx = (document.environment.widthDp * document.environment.density).toInt(),
      heightPx = (document.environment.heightDp * document.environment.density).toInt(),
      density = document.environment.density.toFloat(),
      localeTag = document.environment.locale,
      fontScale = document.environment.fontScale.toFloat(),
      encodedDocument = projectRendererDocument(document),
    )

  private fun RevisionPinnedUiBuilderExport.binaryArtifact(bytes: ByteArray): ExportArtifactV1 =
    ExportArtifactV1(
      format = ExportFormatV1.PNG,
      mediaType = "image/png",
      encoding = ExportEncodingV1.BASE64,
      content = Base64.getEncoder().encodeToString(bytes),
      contentDigest = bytes.sha256(),
      diagnostics = provenanceDiagnostics(),
    )

  private fun RevisionPinnedUiBuilderExport.svgArtifact(bytes: ByteArray): ExportArtifactV1 =
    ExportArtifactV1(
      format = ExportFormatV1.SVG,
      mediaType = "image/svg+xml; charset=utf-8",
      encoding = ExportEncodingV1.UTF8,
      content = bytes.toString(Charsets.UTF_8),
      contentDigest = bytes.sha256(),
      diagnostics = provenanceDiagnostics(),
    )

  private fun RevisionPinnedUiBuilderExport.provenanceDiagnostics(): List<ExportDiagnosticV1> =
    listOf(
      ExportDiagnosticV1(
        severity = DiagnosticSeverityV1.INFO,
        code = "REVISION_PINNED_DAEMON_RENDER",
        message =
          "Rendered design $designId revision $revision ($documentHash) through the packaged Compose UI-builder preview.",
      )
    )
}

/** Runtime-owned opaque preview bundle; materialization and rendering stay outside this module. */
public object PackagedUiBuilderRenderBundle {
  public const val RESOURCE: String =
    "/ee/schimke/composeai/uibuilder/renderer/ui-builder-renderer.bundle.png"
  public const val PREVIEW_ID: String =
    "ee.schimke.composeai.uibuilder.ProductionUiBuilderPreviewKt.ProductionUiBuilderPreview"
  public const val DOCUMENT_OVERRIDE_KEY: String = "uiBuilder.document.v1"

  /**
   * The bundle's own statement of the Java feature version its classes need, beside the bundle.
   *
   * Written by `:ui-builder-render-bundle` from the same catalog entry `:ui-builder`'s toolchain
   * reads. A caller comparing it against the JVM it is about to render on turns a wrong-JVM launch
   * from an `UnsupportedClassVersionError` inside a spawned daemon into one sentence naming both
   * versions (yschimke/compose-preview-server#344).
   */
  public const val MANIFEST_RESOURCE: String =
    "/ee/schimke/composeai/uibuilder/renderer/ui-builder-renderer.bundle.properties"

  /** The Java feature version [copyTo]'s bundle needs, read from [MANIFEST_RESOURCE]. */
  public fun requiredJavaFeatureVersion(): Int {
    val properties =
      checkNotNull(javaClass.getResourceAsStream(MANIFEST_RESOURCE)) {
          "packaged UI-builder renderer bundle manifest is missing"
        }
        .use { Properties().apply { load(it) } }
    val javaMin = properties.getProperty("javaMin")
    return checkNotNull(javaMin?.trim()?.toIntOrNull()) {
      "packaged UI-builder renderer bundle manifest has no numeric javaMin: $javaMin"
    }
  }

  public fun copyTo(root: Path): Path {
    val bytes =
      checkNotNull(javaClass.getResourceAsStream(RESOURCE)) {
          "packaged UI-builder renderer bundle is missing"
        }
        .use { it.readBytes() }
    val generation = root.toAbsolutePath().normalize().resolve(bytes.sha256())
    Files.createDirectories(generation)
    val bundle = generation.resolve("ui-builder-renderer.bundle.png")
    if (!Files.exists(bundle) || !Files.readAllBytes(bundle).contentEquals(bytes)) {
      val partial = Files.createTempFile(generation, ".ui-builder-renderer.", ".tmp")
      try {
        Files.write(partial, bytes)
        Files.move(
          partial,
          bundle,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING,
        )
      } finally {
        Files.deleteIfExists(partial)
      }
    }
    return bundle
  }
}

/** Canonical, loss-checked protocol → renderer wire projection used by the named override. */
public fun projectRendererDocument(document: DesignDocumentV1): String {
  require(document.revision in 0..Int.MAX_VALUE.toLong()) {
    "renderer revision is outside the v1 Int range: ${document.revision}"
  }
  val source = json.encodeToJsonElement(document).jsonObject
  val projectedNodes =
    JsonObject(
      source
        .getValue("nodes")
        .jsonObject
        .entries
        .sortedBy { it.key }
        .associate { (id, value) ->
          val node = value.jsonObject
          id to
            JsonObject(
              linkedMapOf(
                "id" to node.getValue("id"),
                "componentId" to node.getValue("componentId"),
                "properties" to (node["properties"] ?: JsonObject(emptyMap())),
                "modifiers" to (node["modifiers"] ?: JsonArray(emptyList())),
                "slots" to (node["slots"] ?: JsonObject(emptyMap())),
                "eventBindings" to (node["eventBindings"] ?: JsonObject(emptyMap())),
              )
            )
        }
    )
  val projected =
    JsonObject(
      linkedMapOf(
        "schema" to source.getValue("schema"),
        "id" to source.getValue("id"),
        "title" to source.getValue("title"),
        "revision" to JsonPrimitive(document.revision.toInt()),
        "catalogPin" to source.getValue("catalogPin"),
        "environment" to source.getValue("environment"),
        "stateVariables" to (source["stateVariables"] ?: JsonObject(emptyMap())),
        "roots" to source.getValue("roots"),
        "nodes" to projectedNodes,
      )
    )
  return canonicalJson(projected)
}

private fun validateCatalog(catalog: CatalogCapabilityV1): CatalogCapabilityV1 {
  require(catalog.schema.isNotBlank()) { "catalog schema must not be blank" }
  require(catalog.benchmark.catalogSystemId == "m3-catalog") { "unexpected catalog system" }
  require(catalog.benchmark.catalogRevision == "candidate") { "unexpected catalog revision" }
  require(catalog.benchmark.nativeRuntimeId == "candidate") { "unexpected native runtime" }
  require(catalog.components.isNotEmpty()) { "catalog must contain components" }
  require(catalog.components.map { it.componentId }.distinct().size == catalog.components.size) {
    "catalog component ids must be unique"
  }
  catalog.components.forEach { component ->
    require(component.componentId.isNotBlank()) { "component id must not be blank" }
    require(component.slots.map { it.name }.distinct().size == component.slots.size) {
      "duplicate slot in ${component.componentId}"
    }
    require(component.properties.map { it.name }.distinct().size == component.properties.size) {
      "duplicate property in ${component.componentId}"
    }
    component.slots.forEach { slot ->
      require(slot.cardinality.min >= 0) { "negative slot minimum" }
      require(slot.cardinality.max == null || slot.cardinality.max!! >= slot.cardinality.min) {
        "invalid slot maximum"
      }
    }
  }
  return catalog
}

private fun issue(
  code: String,
  message: String,
  nodeId: String? = null,
  field: String? = null,
): UiBuilderCatalogIssue = UiBuilderCatalogIssue(code, message, nodeId, field)

private val json = Json {
  encodeDefaults = true
  explicitNulls = false
  ignoreUnknownKeys = false
}

private fun JsonObject.requiredString(name: String): String =
  requireNotNull(this[name]?.jsonPrimitive?.contentOrNull) { "$name must be text" }

private fun JsonObject.optionalString(name: String): String? =
  this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull

private fun JsonObject.objectOrEmpty(name: String): JsonObject =
  this[name] as? JsonObject ?: JsonObject(emptyMap())

private fun JsonObject.arrayOrEmpty(name: String): JsonArray =
  this[name] as? JsonArray ?: JsonArray(emptyList())

private fun JsonElement.unwrapTypedValue(): JsonElement {
  val objectValue = this as? JsonObject ?: return this
  return objectValue["value"] ?: objectValue
}

private fun JsonElement.accepts(value: JsonElement): Boolean {
  val names =
    if (this is JsonArray) map { it.jsonPrimitive.content } else listOf(jsonPrimitive.content)
  return names.any { name ->
    when (name) {
      "null" -> value is JsonNull
      "string" -> value is JsonPrimitive && value.isString
      "boolean" -> value is JsonPrimitive && value.booleanOrNull != null
      "number" -> value is JsonPrimitive && value.doubleOrNull != null
      "integer" -> value is JsonPrimitive && value.doubleOrNull?.rem(1.0) == 0.0
      "array" -> value is JsonArray
      "object" -> value is JsonObject
      else -> false
    }
  }
}

private fun JsonObject.number(name: String, default: Double = 0.0): String =
  (this[name] as? JsonPrimitive)?.doubleOrNull?.let { value ->
    if (value.rem(1.0) == 0.0) value.toLong().toString() else value.toString()
  } ?: default.toString()

private fun JsonElement?.kotlinLiteral(): String =
  when (this) {
    null,
    JsonNull -> "null"
    is JsonPrimitive -> if (isString) "\"${content.escapeKotlin()}\"" else content
    is JsonArray -> joinToString(prefix = "listOf(", postfix = ")") { it.kotlinLiteral() }
    is JsonObject ->
      entries
        .sortedBy { it.key }
        .joinToString(prefix = "mapOf(", postfix = ")") { (key, value) ->
          "\"${key.escapeKotlin()}\" to ${value.kotlinLiteral()}"
        }
  }

private fun canonicalJson(element: JsonElement): String =
  when (element) {
    is JsonObject ->
      element.entries
        .sortedBy { it.key }
        .joinToString(",", "{", "}") { (key, value) ->
          "${JsonPrimitive(key)}:${canonicalJson(value)}"
        }
    is JsonArray -> element.joinToString(",", "[", "]") { canonicalJson(it) }
    is JsonPrimitive -> element.toString()
  }

private fun String.identifier(): String {
  val words = split(Regex("[^A-Za-z0-9_]+")).filter(String::isNotEmpty)
  val candidate =
    words
      .mapIndexed { index, word ->
        if (index == 0) word.replaceFirstChar { it.lowercase() }
        else word.replaceFirstChar { it.uppercase() }
      }
      .joinToString("")
      .ifEmpty { "GeneratedDesign" }
  val safe = if (candidate.first().isDigit()) "_$candidate" else candidate
  return if (safe in KOTLIN_KEYWORDS) "`${safe}`" else safe
}

private fun String.escapeKotlin(): String =
  replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

private fun String.sha256(): String = toByteArray(Charsets.UTF_8).sha256()

private fun ByteArray.sha256(): String =
  MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

private val KOTLIN_KEYWORDS =
  setOf(
    "as",
    "break",
    "class",
    "continue",
    "do",
    "else",
    "false",
    "for",
    "fun",
    "if",
    "in",
    "interface",
    "is",
    "null",
    "object",
    "package",
    "return",
    "super",
    "this",
    "throw",
    "true",
    "try",
    "typealias",
    "typeof",
    "val",
    "var",
    "when",
    "while",
  )
