package ee.schimke.composeai.uibuilder.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class WearWidgetScaffoldSize(val componentId: String, val label: String) {
  Small("remote-m3/widget-container-small", "Small (216×76dp)"),
  Large("remote-m3/widget-container-large", "Large (216×124dp)"),
}

/**
 * One state variable a new design starts with. State can also be added later through the Screen
 * inspector; both produce the same declarations.
 */
data class NewDesignState(
  val name: String,
  val type: NewDesignStateType,
  val initialValue: JsonElement,
) {
  init {
    require(name.isNotBlank()) { "state variable name must not be blank" }
  }
}

/** The declaration shapes `StateVariableV1` admits, narrowed to what a blank screen can use. */
enum class NewDesignStateType(val wireType: String, val valueType: String, val label: String) {
  Flag("value", "bool", "Flag"),
  Text("text", "string", "Text"),
  Number("value", "int", "Number");

  /** What an empty initial value box is offering to mean. */
  val placeholder: String
    get() =
      when (this) {
        Flag -> "false"
        Text -> "Hello"
        Number -> "0"
      }

  /**
   * The typed initial value for what someone typed. Always succeeds: a flag reads anything but
   * `true` as off, and a non-number starts at zero.
   */
  fun parse(raw: String): JsonPrimitive =
    when (this) {
      Flag -> JsonPrimitive(raw.trim().toBooleanStrictOrNull() ?: false)
      Text -> JsonPrimitive(raw)
      Number -> JsonPrimitive(raw.trim().toLongOrNull() ?: 0L)
    }
}

/**
 * The name a state variable may take: a Kotlin identifier, since the Compose exporter declares it
 * as one. Checked here so the dialog can say so while typing.
 */
val NEW_DESIGN_STATE_NAME: Regex = Regex("[A-Za-z_][A-Za-z0-9_]*")

/**
 * The id a new design may take: what the create route validates and a `/ui-builder/<designId>`
 * segment can hold. Shared by the browser form, the server's create/copy routes and the Designs
 * page's `pattern`.
 */
val NEW_DESIGN_ID: Regex = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

/**
 * State declarations as one string (JSON, since initial text values can contain any separator), for
 * carrying across a navigation as a single query parameter.
 */
fun encodeNewDesignStates(state: List<NewDesignState>): String =
  Json.encodeToString(
    JsonArray.serializer(),
    JsonArray(
      state.map {
        JsonObject(
          mapOf(
            "name" to JsonPrimitive(it.name),
            "kind" to JsonPrimitive(it.type.name),
            "initial" to it.initialValue,
          )
        )
      }
    ),
  )

/**
 * The inverse, defensively: the input comes from the address bar, so anything unparsable, with a
 * non-identifier name or an unknown kind is dropped rather than failing the session.
 */
fun decodeNewDesignStates(encoded: String): List<NewDesignState> {
  val parsed =
    runCatching { Json.parseToJsonElement(encoded) as? JsonArray }.getOrNull() ?: return emptyList()
  return parsed
    .mapNotNull { element ->
      val entry = element as? JsonObject ?: return@mapNotNull null
      val name = (entry["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
      if (!NEW_DESIGN_STATE_NAME.matches(name)) return@mapNotNull null
      val kind =
        NewDesignStateType.entries.firstOrNull {
          it.name == (entry["kind"] as? JsonPrimitive)?.contentOrNull
        } ?: return@mapNotNull null
      val initial = entry["initial"] as? JsonPrimitive ?: return@mapNotNull null
      NewDesignState(name, kind, initial)
    }
    .distinctBy(NewDesignState::name)
}

/**
 * Refuses [state] a new design could never export: duplicate names, or names that collide or become
 * a Kotlin keyword once written as identifiers. Shared by every seed that takes declarations.
 */
internal fun requireExportableState(state: List<NewDesignState>) {
  require(state.map(NewDesignState::name).distinct().size == state.size) {
    "state variable names must be unique"
  }
  // Unique as the exporter writes them: `identifier()` folds `foo-bar` and `foo_bar` together and
  // does not escape keywords. Creation is the only point to refuse, since the wire cannot rename a
  // variable later.
  state.forEach { declared ->
    val identifier = exportedStateIdentifier(declared.name)
    require(identifier !in KOTLIN_HARD_KEYWORDS) {
      "state variable `${declared.name}` becomes the Kotlin keyword `$identifier` when exported"
    }
  }
  require(state.map { exportedStateIdentifier(it.name) }.distinct().size == state.size) {
    "state variable names must stay distinct once exported as Kotlin identifiers"
  }
}

/** A valid minimal screen for an honest from-scratch browser session. */
fun blankUiBuilderDocument(
  designId: String,
  catalogPin: JsonObject,
  environment: JsonObject,
  state: List<NewDesignState> = emptyList(),
): UiBuilderDocument {
  require(designId.isNotBlank()) { "blank design id must not be blank" }
  requireExportableState(state)
  val scaffoldId = "screen-scaffold"
  val contentId = "screen-content"
  return UiBuilderDocument(
    schema = "compose-ui-builder-document/v1-candidate",
    id = designId,
    title = "Untitled Compose screen",
    revision = 0,
    catalogPin = catalogPin,
    environment = environment,
    stateVariables = JsonObject(state.associate { it.name to it.declaration() }),
    roots = listOf(scaffoldId),
    nodes =
      mapOf(
        scaffoldId to
          UiBuilderNode(
            id = scaffoldId,
            componentId = "layout/scaffold",
            properties = JsonObject(emptyMap()),
            modifiers = JsonArray(emptyList()),
            slots =
              mapOf(
                "topBar" to emptyList(),
                "snackbarHost" to emptyList(),
                "content" to listOf(contentId),
              ),
          ),
        contentId to
          UiBuilderNode(
            id = contentId,
            componentId = "layout/box",
            properties = JsonObject(emptyMap()),
            modifiers = JsonArray(emptyList()),
            slots = mapOf("children" to emptyList()),
          ),
      ),
  )
}

/**
 * The wire declaration for one variable. `persistence` is `preview`, the only value a
 * browser-authored design can honestly claim.
 */
internal fun NewDesignState.declaration(): JsonObject =
  JsonObject(
    mapOf(
      "type" to JsonPrimitive(type.wireType),
      "valueType" to JsonPrimitive(type.valueType),
      "nullable" to JsonPrimitive(false),
      "initialValue" to initialValue,
      "persistence" to JsonPrimitive("preview"),
    )
  )

/**
 * [environment] on the frame a new mobile screen opens at: `pixel_6`, whose geometry four catalog
 * entries share. The fixture's own template keeps the fixture's frame.
 */
fun mobileScreenEnvironment(environment: JsonObject): JsonObject =
  JsonObject(
    environment.toMutableMap().also {
      it["widthDp"] = JsonPrimitive(PIXEL_6_WIDTH_DP)
      it["heightDp"] = JsonPrimitive(PIXEL_6_HEIGHT_DP)
      it["density"] = JsonPrimitive(PIXEL_6_DENSITY)
      it["exportDevices"] = JsonArray(MOBILE_EXPORT_DEVICES.map(::JsonPrimitive))
    }
  )

/**
 * The five frames a new mobile screen is checked on, one per width the layout has to survive:
 *
 * |                    |            | why this one                                                |
 * |--------------------|------------|-------------------------------------------------------------|
 * | `pixel_4a`         | 393 × 851  | the narrowest real Pixel in the render catalog              |
 * | `pixel_9`          | 411 × 923  | the modal phone; four catalog entries share this geometry   |
 * | `pixel_9_pro_xl`   | 438 × 997  | the widest phone; its 3.0 density catches dp-vs-px mistakes |
 * | `pixel_9_pro_fold` | 791 × 819  | unfolded: the width where a pane scaffold is still deciding |
 * | `pixel_tablet`     | 1280 × 800 | expanded, where two panes are the answer                    |
 *
 * Not covered by any catalog frame: a foldable's outer display, and landscape.
 */
private val MOBILE_EXPORT_DEVICES =
  listOf(
    "id:pixel_4a",
    "id:pixel_9",
    "id:pixel_9_pro_xl",
    "id:pixel_9_pro_fold",
    "id:pixel_tablet",
  )

/** `pixel_6` from `DeviceDimensions`, which is what the frame menu matches this against. */
private const val PIXEL_6_WIDTH_DP = 411

private const val PIXEL_6_HEIGHT_DP = 914

private const val PIXEL_6_DENSITY = 2.625
