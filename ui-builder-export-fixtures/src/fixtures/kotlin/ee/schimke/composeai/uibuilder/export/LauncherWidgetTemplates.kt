package ee.schimke.composeai.uibuilder.export

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Sample `remote-widgets` designs for tests: the catalog's own root at a grid size, with
 * `remote-creation-compose` text and literal colours (no Remote Material 3 theme). They seeded the
 * New design chooser until the catalog published its own templates.
 */
object LauncherWidgetTemplates {

  /** The catalog these templates are for. */
  const val CATALOG_SYSTEM_ID: String = "remote-widgets"

  /** "Hello, World!" centred on the accent: the smallest widget that already shows something. */
  const val HELLO_TEMPLATE: String = "hello-widget"

  /** Every template [document] builds. */
  val ids: Set<String> = setOf(HELLO_TEMPLATE)

  /**
   * 3x1, 203×102dp: the smallest grid size "Hello, World!" fits at a readable 24sp. A 2x1 is 130dp
   * wide, and the line would wrap or clip there.
   */
  val HELLO_SIZE: LauncherWidgetGrid.Size = LauncherWidgetGrid.Size(3, 1)

  /** The baseline Material 3 light accent pair, as literals — see the object comment. */
  private const val ACCENT = "#FF6750A4"
  private const val ON_ACCENT = "#FFFFFFFF"

  /**
   * A phone's density rather than the fixture's: launcher grid dp are measured on a Pixel 4, and
   * the catalog's stickers render at its 440dpi (2.75×).
   */
  private const val LAUNCHER_DENSITY = 2.75

  fun document(
    templateId: String,
    designId: String,
    catalogPin: JsonObject,
    environment: JsonObject,
  ): UiBuilderDocument {
    require(templateId in ids) { "`$templateId` is not a $CATALOG_SYSTEM_ID template" }
    return helloLauncherWidgetDocument(designId, catalogPin, environment)
  }

  /**
   * The hello widget: `RemoteText` centred (via its `align` modifier) in a `RemoteBox` that fills
   * the cell, on the root's background, which the launcher clips to its radius.
   */
  private fun helloLauncherWidgetDocument(
    designId: String,
    catalogPin: JsonObject,
    environment: JsonObject,
  ): UiBuilderDocument =
    UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = designId,
      title = "Hello widget · ${HELLO_SIZE.label} (${HELLO_SIZE.widthDp}×${HELLO_SIZE.heightDp}dp)",
      revision = 0,
      catalogPin = catalogPin,
      environment = launcherWidgetEnvironment(environment, HELLO_SIZE),
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("launcher-widget"),
      nodes =
        listOf(
            UiBuilderNode(
              id = "launcher-widget",
              componentId = LauncherWidgetCodeExporter.ROOT,
              properties = JsonObject(mapOf("background" to string(ACCENT))),
              modifiers = JsonArray(emptyList()),
              slots = mapOf("content" to listOf("hello-content")),
            ),
            UiBuilderNode(
              id = "hello-content",
              componentId = "layout/box",
              properties = JsonObject(emptyMap()),
              modifiers =
                JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("fillMaxSize"))))),
              slots = mapOf("children" to listOf("hello-text")),
            ),
            UiBuilderNode(
              id = "hello-text",
              componentId = LAUNCHER_WIDGET_TEXT_COMPONENT_ID,
              properties =
                JsonObject(
                  mapOf(
                    "text" to string("Hello, World!"),
                    "color" to string(ON_ACCENT),
                    "fontSize" to
                      JsonObject(
                        mapOf("type" to JsonPrimitive("float"), "value" to JsonPrimitive(24))
                      ),
                  )
                ),
              modifiers =
                JsonArray(
                  listOf(
                    JsonObject(
                      mapOf(
                        "type" to JsonPrimitive("align"),
                        "alignment" to JsonPrimitive("center"),
                      )
                    )
                  )
                ),
              slots = emptyMap(),
            ),
          )
          .associateBy(UiBuilderNode::id),
    )

  /**
   * [environment] framed at [size] on the launcher grid, at a phone's density, with no export
   * devices: a launcher widget previews at its grid size, never in a device frame.
   */
  fun launcherWidgetEnvironment(
    environment: JsonObject,
    size: LauncherWidgetGrid.Size,
  ): JsonObject =
    JsonObject(
      environment.toMutableMap().also {
        it["widthDp"] = JsonPrimitive(size.widthDp)
        it["heightDp"] = JsonPrimitive(size.heightDp)
        it["density"] = JsonPrimitive(LAUNCHER_DENSITY)
        it["theme"] = JsonPrimitive("light")
        it["exportDevices"] = JsonArray(emptyList())
      }
    )

  private fun string(value: String): JsonObject =
    JsonObject(mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive(value)))
}
