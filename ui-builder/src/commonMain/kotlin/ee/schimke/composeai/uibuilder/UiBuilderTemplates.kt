package ee.schimke.composeai.uibuilder

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class WearWidgetScaffoldSize(val componentId: String, val label: String) {
  Small("remote-m3/widget-container-small", "Small (216×76dp)"),
  Large("remote-m3/widget-container-large", "Large (216×124dp)"),
}

/** A slot-ready Wear widget host frame whose dimensions match the upstream preview contract. */
fun wearWidgetUiBuilderDocument(
  designId: String,
  catalogPin: JsonObject,
  environment: JsonObject,
  size: WearWidgetScaffoldSize,
): UiBuilderDocument {
  require(designId.isNotBlank()) { "wear widget design id must not be blank" }
  val scaffoldId = "wear-widget-${size.name.lowercase()}"
  return UiBuilderDocument(
    schema = "compose-ui-builder-document/v1-candidate",
    id = designId,
    title = "Wear widget · ${size.label}",
    revision = 0,
    catalogPin = catalogPin,
    environment = environment,
    stateVariables = JsonObject(emptyMap()),
    roots = listOf(scaffoldId),
    nodes =
      mapOf(
        scaffoldId to
          UiBuilderNode(
            id = scaffoldId,
            componentId = size.componentId,
            properties = JsonObject(emptyMap()),
            modifiers = JsonArray(emptyList()),
            slots = mapOf("content" to emptyList()),
          )
      ),
  )
}

/**
 * One state variable a new design starts with.
 *
 * Declared at creation because that is the only moment a client can put state into a design: the
 * wire's mutation set reaches nodes, properties and the environment, and never `stateVariables`. A
 * design that starts without state can never gain any, so a screen that reacts to anything has to
 * say so up front.
 *
 * That is a real limitation rather than a design preference, and the fix is a protocol addition
 * (`setStateVariable`), not a workaround here.
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
  Number("value", "int", "Number"),
}

/** A valid minimal screen for an honest from-scratch browser session. */
fun blankUiBuilderDocument(
  designId: String,
  catalogPin: JsonObject,
  environment: JsonObject,
  state: List<NewDesignState> = emptyList(),
): UiBuilderDocument {
  require(designId.isNotBlank()) { "blank design id must not be blank" }
  require(state.map(NewDesignState::name).distinct().size == state.size) {
    "state variable names must be unique"
  }
  // Unique *as the exporter will write them*, and legal there. `identifier()` drops separators, so
  // `foo-bar` and `foo_bar` both become `fooBar` and declare the same variable twice; and it does
  // not escape keywords, so `when` becomes `var when: Boolean`. Neither compiles, and neither is
  // visible until somebody exports. Refusing at creation is the only moment this design can be
  // stopped from holding a name it can never generate — the wire cannot rename a variable later.
  state.forEach { declared ->
    val identifier = exportedStateIdentifier(declared.name)
    require(identifier !in KOTLIN_HARD_KEYWORDS) {
      "state variable `${declared.name}` becomes the Kotlin keyword `$identifier` when exported"
    }
  }
  require(state.map { exportedStateIdentifier(it.name) }.distinct().size == state.size) {
    "state variable names must stay distinct once exported as Kotlin identifiers"
  }
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
 * The wire declaration for one variable.
 *
 * `persistence` is `preview`, the only value a design authored in a browser can honestly claim:
 * anything durable is a promise about a host this document knows nothing about.
 */
private fun NewDesignState.declaration(): JsonObject =
  JsonObject(
    mapOf(
      "type" to JsonPrimitive(type.wireType),
      "valueType" to JsonPrimitive(type.valueType),
      "nullable" to JsonPrimitive(false),
      "initialValue" to initialValue,
      "persistence" to JsonPrimitive("preview"),
    )
  )
