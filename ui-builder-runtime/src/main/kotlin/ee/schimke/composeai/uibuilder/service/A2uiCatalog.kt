package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ComponentCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.PropertyCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.SlotCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.SlotCardinalityV1
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `a2ui-catalog`: the A2UI basic catalog v0.9.1 as a builder palette, packaged here.
 *
 * ## What an A2UI design is
 *
 * Not a screen of any one toolkit. A design built from this palette is the component tree an agent
 * sends in an A2UI `updateComponents` message, and whichever client holds the catalog draws it — on
 * Android that is `material3-a2ui`. So the palette is its own platform (`a2ui`): nothing from a
 * Material 3 phone screen, a Wear screen or a widget body can be sent to an A2UI client, and no
 * pack of theirs is offered here. It exports two ways, both through `:ui-builder-export`'s one
 * lowering: the A2UI JSON an agent streams, and the Kotlin an Android app writes to send the same
 * payload to its own processor.
 *
 * ## Where the vocabulary comes from
 *
 * The catalog's own JSON Schema (`a2ui-basic-catalog.schema.json`, v0.9.1), transcribed rather than
 * invented, the same projection yschimke/a2ui-catalog's `ui-builder.policy.json` publishes:
 * - **Properties** are the schema's, with its enums as allowed values, its `required` list, and its
 *   descriptions as notes. A composed type (`oneOf`, `allOf`) takes its first typed branch.
 * - A **dynamic** value (`DynamicString`, `DynamicNumber`, `DynamicBoolean`, `DynamicStringList`)
 *   accepts its literal type or an object, so a property can be bound to a state variable; the
 *   export writes that binding as the data-model path `{"path": "/<variable>"}`.
 * - An **array** accepts an object too, because the builder writes a list as a `list` wrapper,
 *   which is an object on the wire.
 * - A `ComponentId` property (`child`, `trigger`, `content`) is a single-child **slot**, required
 *   where the schema requires it; a `ChildList` (`children`) is a slot of any size. Tabs' `tabs`
 *   stays a list property: each entry is a title and a component id, which is a record, not a slot.
 *
 * Every component declares no modifiers. A2UI lays out through its own components — `Row`,
 * `Column`, `List`, and `weight` as a property — and the export refuses a modifier by name.
 *
 * ## The canvas
 *
 * `material3-a2ui` is published only as an Android AAR, which a Wasm build cannot link, so every
 * component draws on the canvas as a named placeholder holding its children: the tree an agent
 * would send, not a picture of it. The picture comes from the native lane, which renders the
 * exported payload with the library itself. [a2uiCatalog]'s `previewSurfaces` says exactly that, so
 * the editor does not offer a Preview mode whose claim is false.
 */
internal fun a2uiCatalog(base: CatalogCapabilityV1): CatalogCapabilityV1 {
  val components = base.components.associateBy { it.componentId }
  // Borrowed for what they say about a LANE rather than about a component, as `remote-m3` borrows
  // them: the canvas draws a declared id (here, as a placeholder), and the SVG lane has no
  // structured answer for a placeholder.
  val canvas = components.getValue("m3/text").wasm
  val blockedSvg = components.getValue("remote-compose/document").svg
  return base
    .newBuilder()
    .also {
      it.statusSemantics =
        JsonObject(
          base.statusSemantics +
            (CurrentM3UiBuilderCatalogExecutor.PLATFORM_KEY to JsonPrimitive(A2UI_PLATFORM)) +
            ("componentMenu" to a2uiComponentMenu()) +
            ("previewSurfaces" to
              buildJsonObject {
                putJsonObject("wasm") {
                  put("fidelity", JsonPrimitive("approximate"))
                  put(
                    "reason",
                    JsonPrimitive(
                      "The builder's Wasm canvas cannot link `material3-a2ui`, which is " +
                        "published only as an Android AAR, so every component draws as a named " +
                        "placeholder that shows the tree an agent would send."
                    ),
                  )
                }
                putJsonObject("native") {
                  put("fidelity", JsonPrimitive("authoritative"))
                  put("backend", JsonPrimitive("android"))
                  put(
                    "reason",
                    JsonPrimitive(
                      "The exported A2UI payload drawn by `material3-a2ui` through Robolectric."
                    ),
                  )
                }
              })
        )
      it.benchmark =
        base.benchmark
          .newBuilder()
          .also { benchmark ->
            benchmark.id = A2UI_CATALOG_REVISION
            benchmark.sourceRevision = "a2ui-basic-catalog.schema.json@v0.9.1"
            benchmark.catalogSystemId = CurrentM3UiBuilderCatalogExecutor.A2UI_CATALOG_SYSTEM_ID
            benchmark.catalogRevision = A2UI_CATALOG_REVISION
          }
          .build()
      it.components = A2UI_BASIC_CATALOG.map { component ->
        ComponentCapabilityV1.Builder(
            "$A2UI_COMPONENT_PREFIX${component.name}",
            component.name,
            component.role,
            canvas
              .newBuilder()
              .also { wasm ->
                wasm.canvas = null
                wasm.notes =
                  "A2UI `${component.name}`: ${component.summary} Drawn here as a named " +
                    "placeholder; the native preview draws it with `material3-a2ui`."
              }
              .build(),
          )
          .also { capability ->
            capability.traits = listOf(A2UI_TRAIT)
            capability.slots = component.slots.map(A2uiSlot::capability)
            capability.properties = component.properties
            capability.modifierCapabilities = emptyList()
            capability.code = null
            capability.svg =
              blockedSvg
                ?.newBuilder()
                ?.also { svg ->
                  svg.notes = "A placeholder on the canvas has no structured SVG answer."
                }
                ?.build()
          }
          .build()
      }
    }
    .build()
}

/** The platform word an A2UI catalog declares. */
internal const val A2UI_PLATFORM: String = "a2ui"

private const val A2UI_COMPONENT_PREFIX = "a2ui/"
private const val A2UI_CATALOG_REVISION = "a2ui-basic-catalog-v0.9.1"

/** The trait every A2UI component carries and every A2UI slot accepts — and nothing else does. */
private const val A2UI_TRAIT = "A2uiComponent"

/** The shelves, in the order the published policy lists them. */
private val A2UI_GROUP_ORDER =
  listOf("Layout", "Containers", "Content", "Media", "Actions", "Fields")

private fun a2uiComponentMenu(): JsonObject = buildJsonObject {
  putJsonArray("groupOrder") { A2UI_GROUP_ORDER.forEach { add(JsonPrimitive(it)) } }
  putJsonObject("components") {
    A2UI_BASIC_CATALOG.sortedBy { A2UI_GROUP_ORDER.indexOf(it.group) }
      .forEach { component ->
        putJsonObject("$A2UI_COMPONENT_PREFIX${component.name}") {
          put("group", JsonPrimitive(component.group))
          if (component.properties.any { it.name == "variant" }) {
            put("variantProperty", JsonPrimitive("variant"))
          }
        }
      }
  }
}

private class A2uiComponent(
  val name: String,
  val group: String,
  val role: String,
  val summary: String,
  val properties: List<PropertyCapabilityV1>,
  val slots: List<A2uiSlot> = emptyList(),
)

/**
 * A component-reference property as a slot. [single] is a `ComponentId` — one child, and required
 * where the schema requires it. Otherwise a `ChildList`, which the schema requires to be PRESENT
 * but which may be empty, so its minimum is zero.
 */
private class A2uiSlot(val name: String, val single: Boolean, val required: Boolean) {
  fun capability(): SlotCapabilityV1 =
    SlotCapabilityV1.Builder(
        name,
        SlotCardinalityV1.Builder()
          .also {
            it.min = if (single && required) 1 else 0
            it.max = if (single) 1 else null
          }
          .build(),
        true,
      )
      .also {
        it.acceptedRoles = listOf("Container", "Leaf")
        it.acceptedTraits = listOf(A2UI_TRAIT)
      }
      .build()
}

private fun types(vararg names: String): JsonElement = JsonArray(names.map(::JsonPrimitive))

private val STRING: JsonElement = JsonPrimitive("string")
private val NUMBER: JsonElement = JsonPrimitive("number")
private val BOOLEAN: JsonElement = JsonPrimitive("boolean")
private val OBJECT: JsonElement = JsonPrimitive("object")
private val LIST: JsonElement = types("array", "object")
private val DYNAMIC_STRING: JsonElement = types("string", "object")
private val DYNAMIC_NUMBER: JsonElement = types("number", "object")
private val DYNAMIC_BOOLEAN: JsonElement = types("boolean", "object")
private val DYNAMIC_STRING_LIST: JsonElement = types("array", "object")

private fun a2uiProperty(
  name: String,
  type: JsonElement,
  required: Boolean = false,
  allowed: List<String> = emptyList(),
  notes: String,
): PropertyCapabilityV1 =
  PropertyCapabilityV1.Builder(name, type)
    .also {
      it.required = required
      it.allowedValues = allowed.map(::JsonPrimitive)
      it.notes = notes.ifEmpty { null }
    }
    .build()

/**
 * The 18 components of the A2UI basic catalog v0.9.1, in the schema's order, each property in the
 * order the schema declares it. Transcribed from `a2ui-basic-catalog.schema.json`; the shelf and
 * the role are the published policy's.
 */
private val A2UI_BASIC_CATALOG: List<A2uiComponent> =
  listOf(
    A2uiComponent(
      name = "Text",
      group = "Content",
      role = "Leaf",
      summary = "Displays dynamic text.",
      properties =
        listOf(
          a2uiProperty(
            "variant",
            STRING,
            allowed = listOf("h1", "h2", "h3", "h4", "h5", "caption", "body"),
            notes = "A hint for the base text style.",
          ),
          a2uiProperty(
            "weight",
            NUMBER,
            notes =
              "The relative weight of the component within a Row or Column. Note: this may ONLY be set when the component is a direct descendant of a Row or Column.",
          ),
          a2uiProperty(
            "text",
            DYNAMIC_STRING,
            required = true,
            notes =
              "The text content to display. While simple Markdown formatting is supported (i.e. without HTML, images, or links), utilizing dedicated UI components is generally preferred for a richer and more structured presentation. A2UI type: DynamicString. A literal, or a state variable the export binds as a data-model path.",
          ),
          a2uiProperty("accessibility", OBJECT, notes = "A2UI type: AccessibilityAttributes."),
        ),
    ),
    A2uiComponent(
      name = "Image",
      group = "Media",
      role = "Leaf",
      summary = "Displays an image from a URL.",
      properties =
        listOf(
          a2uiProperty(
            "fit",
            STRING,
            allowed = listOf("contain", "cover", "fill", "none", "scaleDown"),
            notes =
              "Specifies how the image should be resized to fit its container. This corresponds to the CSS 'object-fit' property.",
          ),
          a2uiProperty(
            "variant",
            STRING,
            allowed =
              listOf("icon", "avatar", "smallFeature", "mediumFeature", "largeFeature", "header"),
            notes = "A hint for the image size and style.",
          ),
          a2uiProperty(
            "weight",
            NUMBER,
            notes =
              "The relative weight of the component within a Row or Column. Note: this may ONLY be set when the component is a direct descendant of a Row or Column.",
          ),
          a2uiProperty(
            "description",
            DYNAMIC_STRING,
            notes =
              "Accessibility text for the image. A2UI type: DynamicString. A literal, or a state variable the export binds as a data-model path.",
          ),
          a2uiProperty("accessibility", OBJECT, notes = "A2UI type: AccessibilityAttributes."),
          a2uiProperty(
            "url",
            DYNAMIC_STRING,
            required = true,
            notes =
              "The URL of the image to display. A2UI type: DynamicString. A literal, or a state variable the export binds as a data-model path.",
          ),
        ),
    ),
    A2uiComponent(
      name = "Icon",
      group = "Content",
      role = "Leaf",
      summary = "Displays an icon from a predefined set of icons or an SVG path.",
      properties =
        listOf(
          a2uiProperty(
            "name",
            STRING,
            required = true,
            allowed =
              listOf(
                "accountCircle",
                "add",
                "arrowBack",
                "arrowForward",
                "attachFile",
                "calendarToday",
                "call",
                "camera",
                "check",
                "close",
                "delete",
                "download",
                "edit",
                "error",
                "event",
                "fastForward",
                "favorite",
                "favoriteOff",
                "folder",
                "help",
                "home",
                "info",
                "locationOn",
                "lock",
                "lockOpen",
                "mail",
                "menu",
                "moreHoriz",
                "moreVert",
                "notifications",
                "notificationsOff",
                "pause",
                "payment",
                "person",
                "phone",
                "photo",
                "play",
                "print",
                "refresh",
                "rewind",
                "search",
                "send",
                "settings",
                "share",
                "shoppingCart",
                "skipNext",
                "skipPrevious",
                "star",
                "starHalf",
                "starOff",
                "stop",
                "upload",
                "visibility",
                "visibilityOff",
                "volumeDown",
                "volumeMute",
                "volumeOff",
                "volumeUp",
                "warning",
              ),
            notes = "The name of the icon to display.",
          ),
          a2uiProperty("accessibility", OBJECT, notes = "A2UI type: AccessibilityAttributes."),
          a2uiProperty(
            "weight",
            NUMBER,
            notes =
              "The relative weight of the component within a Row or Column. Note: this may ONLY be set when the component is a direct descendant of a Row or Column.",
          ),
        ),
    ),
    A2uiComponent(
      name = "Video",
      group = "Media",
      role = "Leaf",
      summary = "Displays a video from a URL.",
      properties =
        listOf(
          a2uiProperty("accessibility", OBJECT, notes = "A2UI type: AccessibilityAttributes."),
          a2uiProperty(
            "weight",
            NUMBER,
            notes =
              "The relative weight of the component within a Row or Column. Note: this may ONLY be set when the component is a direct descendant of a Row or Column.",
          ),
          a2uiProperty(
            "url",
            DYNAMIC_STRING,
            required = true,
            notes =
              "The URL of the video to display. A2UI type: DynamicString. A literal, or a state variable the export binds as a data-model path.",
          ),
        ),
    ),
    A2uiComponent(
      name = "AudioPlayer",
      group = "Media",
      role = "Leaf",
      summary = "A player for audio content from a URL.",
      properties =
        listOf(
          a2uiProperty(
            "weight",
            NUMBER,
            notes =
              "The relative weight of the component within a Row or Column. Note: this may ONLY be set when the component is a direct descendant of a Row or Column.",
          ),
          a2uiProperty(
            "description",
            DYNAMIC_STRING,
            notes =
              "A description of the audio, such as a title or summary. A2UI type: DynamicString. A literal, or a state variable the export binds as a data-model path.",
          ),
          a2uiProperty("accessibility", OBJECT, notes = "A2UI type: AccessibilityAttributes."),
          a2uiProperty(
            "url",
            DYNAMIC_STRING,
            required = true,
            notes =
              "The URL of the audio to be played. A2UI type: DynamicString. A literal, or a state variable the export binds as a data-model path.",
          ),
        ),
    ),
    A2uiComponent(
      name = "Row",
      group = "Layout",
      role = "Container",
      summary =
        "A layout component that arranges its children horizontally. To create a grid layout, nest Columns within this Row.",
      properties =
        listOf(
          a2uiProperty(
            "weight",
            NUMBER,
            notes =
              "The relative weight of the component within a Row or Column. Note: this may ONLY be set when the component is a direct descendant of a Row or Column.",
          ),
          a2uiProperty(
            "align",
            STRING,
            allowed = listOf("start", "center", "end", "stretch"),
            notes =
              "Defines the alignment of children along the cross axis (vertically). This is similar to the CSS 'align-items' property, but uses camelCase values (e.g., 'start').",
          ),
          a2uiProperty("accessibility", OBJECT, notes = "A2UI type: AccessibilityAttributes."),
          a2uiProperty(
            "justify",
            STRING,
            allowed =
              listOf(
                "center",
                "end",
                "spaceAround",
                "spaceBetween",
                "spaceEvenly",
                "start",
                "stretch",
              ),
            notes =
              "Defines the arrangement of children along the main axis (horizontally). Use 'spaceBetween' to push items to the edges, or 'start'/'end'/'center' to pack them together.",
          ),
        ),
      slots = listOf(A2uiSlot("children", single = false, required = true)),
    ),
    A2uiComponent(
      name = "Column",
      group = "Layout",
      role = "Container",
      summary =
        "A layout component that arranges its children vertically. To create a grid layout, nest Rows within this Column.",
      properties =
        listOf(
          a2uiProperty(
            "weight",
            NUMBER,
            notes =
              "The relative weight of the component within a Row or Column. Note: this may ONLY be set when the component is a direct descendant of a Row or Column.",
          ),
          a2uiProperty(
            "align",
            STRING,
            allowed = listOf("center", "end", "start", "stretch"),
            notes =
              "Defines the alignment of children along the cross axis (horizontally). This is similar to the CSS 'align-items' property.",
          ),
          a2uiProperty("accessibility", OBJECT, notes = "A2UI type: AccessibilityAttributes."),
          a2uiProperty(
            "justify",
            STRING,
            allowed =
              listOf(
                "start",
                "center",
                "end",
                "spaceBetween",
                "spaceAround",
                "spaceEvenly",
                "stretch",
              ),
            notes =
              "Defines the arrangement of children along the main axis (vertically). Use 'spaceBetween' to push items to the edges (e.g. header at top, footer at bottom), or 'start'/'end'/'center' to pack them together.",
          ),
        ),
      slots = listOf(A2uiSlot("children", single = false, required = true)),
    ),
    A2uiComponent(
      name = "List",
      group = "Layout",
      role = "Container",
      summary = "A scrollable list of components laid out vertically or horizontally.",
      properties =
        listOf(
          a2uiProperty(
            "weight",
            NUMBER,
            notes =
              "The relative weight of the component within a Row or Column. Note: this may ONLY be set when the component is a direct descendant of a Row or Column.",
          ),
          a2uiProperty(
            "align",
            STRING,
            allowed = listOf("start", "center", "end", "stretch"),
            notes = "Defines the alignment of children along the cross axis.",
          ),
          a2uiProperty("accessibility", OBJECT, notes = "A2UI type: AccessibilityAttributes."),
          a2uiProperty(
            "direction",
            STRING,
            allowed = listOf("vertical", "horizontal"),
            notes = "The direction in which the list items are laid out.",
          ),
        ),
      slots = listOf(A2uiSlot("children", single = false, required = true)),
    ),
    A2uiComponent(
      name = "Card",
      group = "Containers",
      role = "Container",
      summary = "A layout component that wraps its child content in a styled card container.",
      properties =
        listOf(
          a2uiProperty("accessibility", OBJECT, notes = "A2UI type: AccessibilityAttributes."),
          a2uiProperty(
            "weight",
            NUMBER,
            notes =
              "The relative weight of the component within a Row or Column. Note: this may ONLY be set when the component is a direct descendant of a Row or Column.",
          ),
        ),
      slots = listOf(A2uiSlot("child", single = true, required = true)),
    ),
    A2uiComponent(
      name = "Tabs",
      group = "Containers",
      role = "Container",
      summary = "A set of tabs, each with a title and a corresponding child component.",
      properties =
        listOf(
          a2uiProperty(
            "tabs",
            LIST,
            required = true,
            notes =
              "An array of objects, where each object defines a tab with a title and a child component.",
          ),
          a2uiProperty("accessibility", OBJECT, notes = "A2UI type: AccessibilityAttributes."),
          a2uiProperty(
            "weight",
            NUMBER,
            notes =
              "The relative weight of the component within a Row or Column. Note: this may ONLY be set when the component is a direct descendant of a Row or Column.",
          ),
        ),
    ),
    A2uiComponent(
      name = "Modal",
      group = "Containers",
      role = "Container",
      summary = "A dialog window.",
      properties =
        listOf(
          a2uiProperty(
            "weight",
            NUMBER,
            notes =
              "The relative weight of the component within a Row or Column. Note: this may ONLY be set when the component is a direct descendant of a Row or Column.",
          ),
          a2uiProperty("accessibility", OBJECT, notes = "A2UI type: AccessibilityAttributes."),
        ),
      slots =
        listOf(
          A2uiSlot("trigger", single = true, required = true),
          A2uiSlot("content", single = true, required = true),
        ),
    ),
    A2uiComponent(
      name = "Divider",
      group = "Content",
      role = "Leaf",
      summary = "A horizontal or vertical dividing line.",
      properties =
        listOf(
          a2uiProperty(
            "axis",
            STRING,
            allowed = listOf("horizontal", "vertical"),
            notes = "The orientation of the divider.",
          ),
          a2uiProperty("accessibility", OBJECT, notes = "A2UI type: AccessibilityAttributes."),
          a2uiProperty(
            "weight",
            NUMBER,
            notes =
              "The relative weight of the component within a Row or Column. Note: this may ONLY be set when the component is a direct descendant of a Row or Column.",
          ),
        ),
    ),
    A2uiComponent(
      name = "Button",
      group = "Actions",
      role = "Container",
      summary = "A clickable button that dispatches an action.",
      properties =
        listOf(
          a2uiProperty(
            "variant",
            STRING,
            allowed = listOf("default", "primary", "borderless"),
            notes =
              "A hint for the button style. If omitted, a default button style is used. 'primary' indicates this is the main call-to-action button. 'borderless' means the button has no visual border or background, making its child content appear like a clickable link.",
          ),
          a2uiProperty(
            "weight",
            NUMBER,
            notes =
              "The relative weight of the component within a Row or Column. Note: this may ONLY be set when the component is a direct descendant of a Row or Column.",
          ),
          a2uiProperty("action", OBJECT, required = true, notes = "A2UI type: Action."),
          a2uiProperty(
            "checks",
            LIST,
            notes =
              "A list of checks to perform. These are function calls that must return a boolean indicating validity.",
          ),
          a2uiProperty("accessibility", OBJECT, notes = "A2UI type: AccessibilityAttributes."),
        ),
      slots = listOf(A2uiSlot("child", single = true, required = true)),
    ),
    A2uiComponent(
      name = "TextField",
      group = "Fields",
      role = "Leaf",
      summary = "A field for user text input.",
      properties =
        listOf(
          a2uiProperty(
            "validationRegexp",
            STRING,
            notes = "A regular expression used for client-side validation of the input.",
          ),
          a2uiProperty(
            "checks",
            LIST,
            notes =
              "A list of checks to perform. These are function calls that must return a boolean indicating validity.",
          ),
          a2uiProperty("accessibility", OBJECT, notes = "A2UI type: AccessibilityAttributes."),
          a2uiProperty(
            "variant",
            STRING,
            allowed = listOf("longText", "number", "shortText", "obscured"),
            notes = "The type of input field to display.",
          ),
          a2uiProperty(
            "weight",
            NUMBER,
            notes =
              "The relative weight of the component within a Row or Column. Note: this may ONLY be set when the component is a direct descendant of a Row or Column.",
          ),
          a2uiProperty(
            "label",
            DYNAMIC_STRING,
            required = true,
            notes =
              "The text label for the input field. A2UI type: DynamicString. A literal, or a state variable the export binds as a data-model path.",
          ),
          a2uiProperty(
            "value",
            DYNAMIC_STRING,
            notes =
              "The value of the text field. A2UI type: DynamicString. A literal, or a state variable the export binds as a data-model path.",
          ),
        ),
    ),
    A2uiComponent(
      name = "CheckBox",
      group = "Fields",
      role = "Leaf",
      summary = "A checkbox with a label and a boolean value.",
      properties =
        listOf(
          a2uiProperty(
            "weight",
            NUMBER,
            notes =
              "The relative weight of the component within a Row or Column. Note: this may ONLY be set when the component is a direct descendant of a Row or Column.",
          ),
          a2uiProperty(
            "checks",
            LIST,
            notes =
              "A list of checks to perform. These are function calls that must return a boolean indicating validity.",
          ),
          a2uiProperty(
            "label",
            DYNAMIC_STRING,
            required = true,
            notes =
              "The text to display next to the checkbox. A2UI type: DynamicString. A literal, or a state variable the export binds as a data-model path.",
          ),
          a2uiProperty("accessibility", OBJECT, notes = "A2UI type: AccessibilityAttributes."),
          a2uiProperty(
            "value",
            DYNAMIC_BOOLEAN,
            required = true,
            notes =
              "The current state of the checkbox (true for checked, false for unchecked). A2UI type: DynamicBoolean. A literal, or a state variable the export binds as a data-model path.",
          ),
        ),
    ),
    A2uiComponent(
      name = "ChoicePicker",
      group = "Fields",
      role = "Leaf",
      summary = "A component that allows selecting one or more options from a list.",
      properties =
        listOf(
          a2uiProperty(
            "displayStyle",
            STRING,
            allowed = listOf("checkbox", "chips"),
            notes = "The display style of the component.",
          ),
          a2uiProperty(
            "filterable",
            BOOLEAN,
            notes = "If true, displays a search input to filter the options.",
          ),
          a2uiProperty(
            "checks",
            LIST,
            notes =
              "A list of checks to perform. These are function calls that must return a boolean indicating validity.",
          ),
          a2uiProperty("accessibility", OBJECT, notes = "A2UI type: AccessibilityAttributes."),
          a2uiProperty(
            "variant",
            STRING,
            allowed = listOf("mutuallyExclusive", "multipleSelection"),
            notes = "A hint for how the choice picker should be displayed and behave.",
          ),
          a2uiProperty(
            "options",
            LIST,
            required = true,
            notes = "The list of available options to choose from.",
          ),
          a2uiProperty(
            "weight",
            NUMBER,
            notes =
              "The relative weight of the component within a Row or Column. Note: this may ONLY be set when the component is a direct descendant of a Row or Column.",
          ),
          a2uiProperty(
            "label",
            DYNAMIC_STRING,
            notes =
              "The label for the group of options. A2UI type: DynamicString. A literal, or a state variable the export binds as a data-model path.",
          ),
          a2uiProperty(
            "value",
            DYNAMIC_STRING_LIST,
            required = true,
            notes =
              "The list of currently selected values. This should be bound to a string array in the data model. A2UI type: DynamicStringList. A literal, or a state variable the export binds as a data-model path.",
          ),
        ),
    ),
    A2uiComponent(
      name = "Slider",
      group = "Fields",
      role = "Leaf",
      summary = "A slider for selecting a numeric value within a range.",
      properties =
        listOf(
          a2uiProperty(
            "checks",
            LIST,
            notes =
              "A list of checks to perform. These are function calls that must return a boolean indicating validity.",
          ),
          a2uiProperty("min", NUMBER, notes = "The minimum value of the slider."),
          a2uiProperty("accessibility", OBJECT, notes = "A2UI type: AccessibilityAttributes."),
          a2uiProperty("max", NUMBER, required = true, notes = "The maximum value of the slider."),
          a2uiProperty(
            "weight",
            NUMBER,
            notes =
              "The relative weight of the component within a Row or Column. Note: this may ONLY be set when the component is a direct descendant of a Row or Column.",
          ),
          a2uiProperty(
            "label",
            DYNAMIC_STRING,
            notes =
              "The label for the slider. A2UI type: DynamicString. A literal, or a state variable the export binds as a data-model path.",
          ),
          a2uiProperty(
            "value",
            DYNAMIC_NUMBER,
            required = true,
            notes =
              "The current value of the slider. A2UI type: DynamicNumber. A literal, or a state variable the export binds as a data-model path.",
          ),
        ),
    ),
    A2uiComponent(
      name = "DateTimeInput",
      group = "Fields",
      role = "Leaf",
      summary = "Allows the user to select a date and/or time.",
      properties =
        listOf(
          a2uiProperty("enableDate", BOOLEAN, notes = "If true, allows the user to select a date."),
          a2uiProperty(
            "checks",
            LIST,
            notes =
              "A list of checks to perform. These are function calls that must return a boolean indicating validity.",
          ),
          a2uiProperty(
            "min",
            DYNAMIC_STRING,
            notes =
              "The minimum allowed date/time in ISO 8601 format. A2UI type: DynamicString. A literal, or a state variable the export binds as a data-model path.",
          ),
          a2uiProperty("accessibility", OBJECT, notes = "A2UI type: AccessibilityAttributes."),
          a2uiProperty(
            "max",
            DYNAMIC_STRING,
            notes =
              "The maximum allowed date/time in ISO 8601 format. A2UI type: DynamicString. A literal, or a state variable the export binds as a data-model path.",
          ),
          a2uiProperty("enableTime", BOOLEAN, notes = "If true, allows the user to select a time."),
          a2uiProperty(
            "weight",
            NUMBER,
            notes =
              "The relative weight of the component within a Row or Column. Note: this may ONLY be set when the component is a direct descendant of a Row or Column.",
          ),
          a2uiProperty(
            "label",
            DYNAMIC_STRING,
            notes =
              "The text label for the component. A2UI type: DynamicString. A literal, or a state variable the export binds as a data-model path.",
          ),
          a2uiProperty(
            "value",
            DYNAMIC_STRING,
            required = true,
            notes =
              "The selected date and/or time value in ISO 8601 format. If not yet set, initialize with an empty string. A2UI type: DynamicString. A literal, or a state variable the export binds as a data-model path.",
          ),
        ),
    ),
  )
