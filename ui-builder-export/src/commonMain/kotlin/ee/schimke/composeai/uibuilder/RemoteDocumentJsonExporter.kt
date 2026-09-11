package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import kotlin.math.roundToInt
import kotlinx.serialization.json.*

/** Design semantics lowered to authoring JSON, shared by the editor and service export lanes. */
object RemoteDocumentJsonExporter {
  const val INTEGER_PROFILE = "compose-preview-integer-expressions-v1"
  const val STATE_PROFILE = "compose-preview-state-v1"

  sealed interface Result {
    /** Boolean state uses named integers (0/1); the host bridge must use [stateKinds]. */
    data class Emitted(val source: String, val stateKinds: Map<String, String>) : Result

    data class Refused(val reasons: List<String>) : Result
  }

  fun export(document: DesignDocumentV1): Result {
    val unsupported = buildList {
      if (document.tokenBindings.isNotEmpty())
        add("tokenBindings: resolve catalog tokens before JSON export")
      document.nodes.forEach { (id, node) ->
        if (node.predicate != null) add("nodes.$id.predicate: predicate lowering is not available")
        if (node.accessibility != null)
          add("nodes.$id.accessibility: semantics lowering is not available")
        if (node.assetBindings.isNotEmpty())
          add("nodes.$id.assetBindings: asset lowering is not available")
        if (node.tokenBindings.isNotEmpty())
          add("nodes.$id.tokenBindings: resolve catalog tokens before JSON export")
      }
    }
    if (unsupported.isNotEmpty()) return Result.Refused(unsupported)
    return export(document.toUiBuilderDocument())
  }

  fun export(document: UiBuilderDocument): Result =
    try {
      Emitter(document).export()
    } catch (e: IllegalArgumentException) {
      Result.Refused(listOf("document: malformed Remote JSON input: ${e.message}"))
    }

  private class Emitter(val document: UiBuilderDocument) {
    val errors = mutableListOf<String>()
    val stateKinds = linkedMapOf<String, String>()
    val integers = linkedMapOf<String, JsonElement>()
    val floats = linkedMapOf<String, JsonElement>()
    val strings = linkedMapOf<String, JsonElement>()
    val textLiterals = mutableListOf<JsonObject>()
    val active = mutableSetOf<String>()
    var emittedNodes = 0
    var expressionIndex = 0
    var usesIntegerProfile = false
    var usesStateProfile = false
    var density = 1.0

    fun export(): Result {
      density = number(document.environment["density"], "environment.density", 1.0)
      val width = number(document.environment["widthDp"], "environment.widthDp", 400.0)
      val height = number(document.environment["heightDp"], "environment.heightDp", 800.0)
      if (
        density <= 0 ||
          width <= 0 ||
          height <= 0 ||
          width * density > Int.MAX_VALUE ||
          height * density > Int.MAX_VALUE
      )
        errors += "environment: dimensions and density must produce positive Int pixel dimensions"
      val widthPx = (width * density).roundToInt()
      val heightPx = (height * density).roundToInt()
      if (widthPx <= 0 || heightPx <= 0)
        errors += "environment: rounded pixel dimensions must be positive"
      if (document.environment["layoutDirection"]?.jsonPrimitive?.contentOrNull == "rtl")
        errors += "environment.layoutDirection: RTL lowering is not available"
      if (document.environment["background"]?.takeUnless { it is JsonNull } != null)
        errors += "environment.background: background lowering is not available"
      document.stateVariables.entries
        .sortedBy { it.key }
        .forEach { (name, value) -> declare(name, value) }
      validateReferences()
      if (errors.isNotEmpty()) return Result.Refused(errors.distinct())
      val roots = document.roots.mapNotNull { node(it, JsonObject(emptyMap())) }
      val source = buildJsonObject {
        if (strings.isNotEmpty() || usesStateProfile) put("compilerProfile", STATE_PROFILE)
        else if (usesIntegerProfile) put("compilerProfile", INTEGER_PROFILE)
        putJsonObject("header") {
          put("width", widthPx)
          put("height", heightPx)
          put("apiLevel", 7)
          put("profiles", 513)
        }
        putJsonArray("root") {
          add(
            buildJsonObject {
              put("type", "resources")
              put("integers", JsonObject(integers))
              put("variables", JsonObject(floats))
            }
          )
          textLiterals.forEach(::add)
          strings.forEach { (name, value) ->
            add(
              buildJsonObject {
                put("type", "mutableString")
                put("name", name)
                put("value", value)
              }
            )
          }
          roots.forEach(::add)
        }
      }
      return if (errors.isEmpty()) Result.Emitted(source.toString(), stateKinds.toMap())
      else Result.Refused(errors.distinct())
    }

    fun declare(name: String, value: JsonElement) {
      val path = "stateVariables.$name"
      val declaration = value as? JsonObject
      val initial = declaration?.get("initialValue") as? JsonPrimitive
      val kind =
        declaration?.get("valueType")?.jsonPrimitive?.contentOrNull
          ?: initial
            ?.takeUnless { it is JsonNull }
            ?.let { selectionLiteral(it)["type"]!!.jsonPrimitive.content }
      if (!Regex("[A-Za-z_][A-Za-z0-9_]*").matches(name)) {
        errors += "$path: state names must be identifiers"
        return
      }
      if (declaration?.get("nullable") == JsonPrimitive(true) || !matches(initial, kind)) {
        errors += "$path: JSON export requires a non-null $kind initial value"
        return
      }
      if (kind == "string") {
        stateKinds[name] = kind
        strings[name] = initial!!
        return
      }
      stateKinds[name] = kind!!
      val encoded =
        if (kind == "bool") JsonPrimitive(if (initial!!.boolean) 1 else 0) else initial!!
      val resource = buildJsonObject {
        put("value", encoded)
        put("export", true)
      }
      when (kind) {
        "int",
        "bool" -> integers[name] = resource
        "float" -> floats[name] = resource
      }
    }

    /** Validate references even under an empty loop, where no body is emitted. */
    fun validateReferences() {
      val visiting = mutableSetOf<String>()
      val visited = mutableSetOf<String>()
      fun visit(id: String) {
        if (id in visited) return
        require(visiting.size < 128) { "nodes.$id: nesting exceeds 128 levels" }
        if (!visiting.add(id)) {
          errors += "nodes.$id: cyclic child or component reference"
          return
        }
        val authored = document.nodes[id]
        if (authored == null) errors += "nodes.$id: missing node"
        else {
          authored.slots.values.flatten().forEach(::visit)
          authored.component?.let { placement ->
            componentRoot(placement, "nodes.$id.component")?.let(::visit)
          }
        }
        visiting.remove(id)
        visited.add(id)
      }
      document.roots.forEach(::visit)
    }

    fun componentRoot(placement: JsonObject, path: String): String? {
      val key = (placement["componentKey"] as? JsonPrimitive)?.takeIf { it.isString }?.content
      val definition = key?.let { document.components[it] as? JsonObject }
      val root = (definition?.get("root") as? JsonPrimitive)?.takeIf { it.isString }?.content
      if (root == null) errors += "$path: missing component definition or root for $key"
      definition
        ?.keys
        ?.filter { it !in setOf("name", "root", "description", "source") }
        ?.forEach { errors += "components.$key.$it: component field lowering is not available" }
      return root
    }

    // Match the canvas's lexical argument scopes: direct property reads and forwarded arguments.
    // Nested modifier/action bindings are not substituted until those surfaces define their types.
    fun resolve(value: JsonElement, arguments: JsonObject, path: String): JsonElement {
      val binding =
        (value as? JsonObject)?.takeIf { it["type"] == JsonPrimitive("binding") } ?: return value
      val key = (binding["value"] as? JsonPrimitive)?.takeIf { it.isString }?.content
      val supplied = key?.let { arguments[it] }
      if (
        binding.keys != setOf("type", "value") ||
          supplied == null ||
          (supplied as? JsonObject)?.get("type") == JsonPrimitive("binding")
      ) {
        errors += "$path: missing or invalid argument ${key ?: "binding key"}"
        return value
      }
      return supplied
    }

    fun node(id: String, arguments: JsonObject): JsonObject? {
      require(++emittedNodes <= 10_000) { "nodes.$id: expanded document exceeds 10000 nodes" }
      require(active.size < 128) { "nodes.$id: nesting exceeds 128 levels" }
      val raw = document.nodes[id] ?: return null // Already checked by validateReferences.
      if (!active.add(id)) {
        errors += "nodes.$id: cyclic child or component reference"
        return null
      }
      try {
        val path = "nodes.$id"
        val node =
          raw.copy(
            properties =
              JsonObject(
                raw.properties.mapValues { (name, value) ->
                  resolve(value, arguments, "$path.properties.$name")
                }
              )
          )
        val placement = node.component
        if (placement != null) {
          if (
            node.componentId != "design/component-instance" ||
              node.properties.isNotEmpty() ||
              node.slots.isNotEmpty()
          )
            errors +=
              "$path.component: a placement requires design/component-instance with no properties or slots"
          placement.keys
            .filter { it !in setOf("componentKey", "arguments") }
            .forEach { errors += "$path.component.$it: placement field lowering is not available" }
          val declared = placement["arguments"]
          if (declared != null && declared !is JsonObject)
            errors += "$path.component.arguments: expected an argument dictionary"
          val supplied =
            JsonObject(
              (declared as? JsonObject).orEmpty().mapValues { (name, value) ->
                resolve(value, arguments, "$path.component.arguments.$name")
              }
            )
          val root = componentRoot(placement, "$path.component") ?: return null
          val body = node(root, supplied)
          return layout(
            node.copy(
              componentId = "layout/box",
              component = null,
              properties = JsonObject(emptyMap()),
              slots = emptyMap(),
            ),
            arguments,
            listOfNotNull(body),
          )
        }
        if (node.componentId == "layout/for-each") {
          node.properties.keys
            .filter { it !in setOf("data", "verticalSpacingDp") }
            .forEach { errors += "$path.properties.$it: loop property lowering is not available" }
          val template = node.slots["template"]?.singleOrNull()
          if (template == null || node.slots.keys != setOf("template")) {
            errors += "$path.slots: a loop requires exactly one template child"
            return null
          }
          val data = node.properties["data"] as? JsonObject
          val rows = data?.get("values") as? JsonArray
          if (
            data?.get("type") != JsonPrimitive("list") ||
              data.keys != setOf("type", "values") ||
              rows == null
          ) {
            errors += "$path.properties.data: expected an authored list of row objects"
            return null
          }
          require(rows.size <= 10_000) {
            "$path.properties.data: expanded document exceeds 10000 rows"
          }
          val children = rows.mapIndexedNotNull { index, row ->
            val record = row as? JsonObject
            val fields = record?.get("fields") as? JsonObject
            if (
              record?.get("type") != JsonPrimitive("object") ||
                record.keys != setOf("type", "fields") ||
                fields == null
            ) {
              errors += "$path.properties.data.values[$index]: expected a row object with fields"
              null
            } else node(template, fields)
          }
          return layout(
            node.copy(
              componentId = "layout/column",
              properties = JsonObject(node.properties - "data"),
              slots = emptyMap(),
            ),
            arguments,
            children,
          )
        }
        return layout(node, arguments)
      } finally {
        active.remove(id)
      }
    }

    fun layout(
      node: UiBuilderNode,
      arguments: JsonObject,
      expandedContents: List<JsonObject>? = null,
    ): JsonObject? {
      val path = "nodes.${node.id}"
      val type =
        when (node.componentId) {
          "layout/box" -> "box"
          "layout/row" -> "row"
          "layout/column" -> "column"
          else -> {
            errors +=
              "$path.componentId: ${node.componentId} needs a declared Remote JSON lowering recipe"
            return null
          }
        }
      val allowedProperties =
        setOf(SHOW_BY_STATE) +
          when (type) {
            "row" -> setOf("verticalAlignment", "horizontalSpacingDp")
            "column" -> setOf("horizontalAlignment", "verticalSpacingDp")
            else -> emptySet()
          }
      node.properties.keys
        .filter { it !in allowedProperties }
        .forEach { errors += "$path.properties.$it: property lowering is not available" }
      node.slots.keys
        .filter { it != "children" }
        .forEach { errors += "$path.slots.$it: unsupported slot" }
      stateSelectionIssue(node, document.stateVariables)?.let {
        errors += "$path.$SHOW_BY_STATE: $it"
        return null
      }
      val authoredChildren = node.slots["children"].orEmpty()
      if (authoredChildren.size != authoredChildren.toSet().size)
        errors += "$path.slots.children: duplicate child references"
      val children =
        if (expandedContents != null) emptyMap()
        else
          authoredChildren
            .mapNotNull { child -> node(child, arguments)?.let { child to it } }
            .toMap()
      val selection = node.stateSelection()
      val contents =
        expandedContents
          ?: if (selection == null) children.values.toList()
          else selection(node, selection, children)
      val modifiers =
        node.modifiers
          .flatMapIndexed { index, value ->
            modifier(value as? JsonObject, "$path.modifiers[$index]")
          }
          .toMutableList()
      val spacing = if (type == "row") "horizontalSpacingDp" else "verticalSpacingDp"
      node.properties[spacing]?.let { value ->
        val literal = value as? JsonObject
        if (
          literal?.get("type") !in listOf(JsonPrimitive("int"), JsonPrimitive("float")) ||
            literal?.get("value") == null
        )
          errors += "$path.properties.$spacing: expected a numeric literal"
        modifiers += buildJsonObject {
          put(
            "spacedBy",
            number((value as? JsonObject)?.get("value"), "$path.properties.$spacing", 0.0) *
              density,
          )
        }
      }
      node.eventBindings.forEach { (event, value) ->
        if (event != "click")
          errors += "$path.eventBindings.$event: only click actions have a JSON mapping"
        else {
          val actions = value as? JsonArray
          if (actions == null)
            errors += "$path.eventBindings.$event: expected an ordered action list"
          else
            modifiers += buildJsonObject {
              put(
                "onClick",
                JsonArray(
                  actions.mapIndexedNotNull { index, action ->
                    action(action as? JsonObject, "$path.eventBindings.$event[$index]")
                  }
                ),
              )
            }
        }
      }
      return buildJsonObject {
        put("type", type)
        put(
          "horizontalAlignment",
          alignment(node, "horizontalAlignment", "start", setOf("start", "center", "end")),
        )
        put(
          "verticalAlignment",
          alignment(
            node,
            "verticalAlignment",
            if (type == "row") "center" else "top",
            setOf("top", "center", "bottom"),
          ),
        )
        put("modifiers", JsonArray(modifiers))
        if (contents.isNotEmpty()) put("children", JsonArray(contents))
      }
    }

    fun alignment(node: UiBuilderNode, key: String, default: String, allowed: Set<String>): String {
      val property = node.properties[key] ?: return default
      val value = (property as? JsonObject)?.get("value") as? JsonPrimitive
      if (value?.isString != true || value.content !in allowed) {
        errors += "nodes.${node.id}.properties.$key: expected ${allowed.joinToString()}"
        return default
      }
      return value.content
    }

    fun selection(
      node: UiBuilderNode,
      selection: StateSelection,
      children: Map<String, JsonObject>,
    ): List<JsonObject> {
      val selector = selection.selector
      val variable = selector["variable"]?.jsonPrimitive?.contentOrNull
      val kind =
        if (selector["type"] == JsonPrimitive("state")) stateKinds[variable]
        else selector["type"]?.jsonPrimitive?.contentOrNull
      val path = "nodes.${node.id}.$SHOW_BY_STATE"
      if (kind !in setOf("int", "bool", "float")) {
        errors += "$path: exact $kind selection needs a supported JSON expression mapping"
        return emptyList()
      }
      fun integer(value: JsonPrimitive): Int =
        if (kind == "bool") {
          if (value.boolean) 1 else 0
        } else value.int
      val reference =
        if (variable != null) "@$variable"
        else if (kind == "float") selector["value"]!!.jsonPrimitive.float.toString()
        else integer(selector["value"]!!.jsonPrimitive).toString()
      val result = mutableListOf<JsonObject>()
      fun expression(value: String): String {
        usesIntegerProfile = true
        var name: String
        do {
          name = "__rc_${expressionIndex++}"
        } while (name in stateKinds)
        result += buildJsonObject {
          put("type", "integerExpression")
          put("name", name)
          put("value", value)
        }
        return "@$name"
      }
      // Quotient/remainder halves preserve all Int bits and avoid abs(Int.MIN_VALUE) overflow.
      val low = if (kind != "float") expression("$reference % 65536") else ""
      val high = if (kind != "float") expression("$reference / 65536") else ""
      val ordered = node.slots["children"].orEmpty().filter { it in selection.cases }
      var ordinal = ordered.size.toString()
      ordered.withIndex().reversed().forEach { (index, child) ->
        val match =
          if (kind == "float") {
            usesStateProfile = true
            var name: String
            do {
              name = "__rc_${expressionIndex++}"
            } while (name in stateKinds)
            result += buildJsonObject {
              put("type", "floatEquals")
              put("name", name)
              put(
                "left",
                if (variable != null) JsonPrimitive(reference)
                else JsonPrimitive(reference.toFloat()),
              )
              put("right", selection.cases.getValue(child).float)
            }
            "@$name"
          } else {
            val value = integer(selection.cases.getValue(child))
            val lowMatch = expression("1 - min(1, abs($low - (${value % 65536})))")
            val highMatch = expression("1 - min(1, abs($high - (${value / 65536})))")
            expression("$lowMatch * $highMatch")
          }
        ordinal = expression("$match * $index + (1 - $match) * $ordinal")
      }
      result += buildJsonObject {
        put("type", "stateLayout")
        put("indexId", ordinal)
        putJsonArray("children") {
          ordered.forEach { child -> children[child]?.let { add(it) } }
          add(
            selection.fallback?.let(children::get)
              ?: buildJsonObject {
                put("type", "box")
                put("horizontalAlignment", "start")
                put("verticalAlignment", "top")
              }
          )
        }
      }
      return result
    }

    fun action(action: JsonObject?, path: String): JsonObject? {
      val variable = action?.get("variable")?.jsonPrimitive?.contentOrNull
      val kind = stateKinds[variable]
      if (variable == null || kind == null) {
        errors += "$path.variable: state is not declared"
        return null
      }
      val type = action["type"]?.jsonPrimitive?.contentOrNull
      val value = action["value"] as? JsonPrimitive
      if (type == "toggle" && kind == "bool")
        return buildJsonObject {
          put("type", "valueIntegerExpressionChange")
          put("target", "@$variable")
          put("expression", "1 - @$variable")
        }
      if (type !in setOf("set", "select") || !matches(value, kind)) {
        errors += "$path: $type needs a non-null $kind literal"
        return null
      }
      if (kind == "string") {
        // Stock string actions interpret @/$ prefixes as references. Intern a separate immutable
        // literal, then reference its ID, so every authored String remains a literal assignment.
        var name: String
        do {
          name = "__rc_text_${expressionIndex++}"
        } while (name in stateKinds)
        textLiterals += buildJsonObject {
          put("type", "variable")
          put("vtype", "string")
          put("name", name)
          put("value", value!!)
        }
        return buildJsonObject {
          put("type", "valueStringChange")
          put("target", "@$variable")
          put("value", "@$name")
        }
      }
      return buildJsonObject {
        put(
          "type",
          when (kind) {
            "int",
            "bool" -> "valueIntegerChange"
            else -> "valueFloatChange"
          },
        )
        put("target", "@$variable")
        put(
          "value",
          when (kind) {
            "bool" -> JsonPrimitive(if (value!!.boolean) 1 else 0)
            else -> value!!
          },
        )
      }
    }

    fun modifier(modifier: JsonObject?, path: String): List<JsonElement> {
      val type = modifier?.get("type")?.jsonPrimitive?.contentOrNull
      val fields =
        when (type) {
          "fillMaxSize",
          "fillMaxWidth",
          "fillMaxHeight" -> setOf("fraction")
          "width",
          "height" -> setOf("${type}Dp")
          "size" -> setOf("widthDp", "heightDp")
          "padding" -> setOf("startDp", "topDp", "endDp", "bottomDp")
          "background" -> setOf("color")
          "alpha" -> setOf("alpha")
          else -> emptySet()
        }
      modifier
        ?.entries
        ?.filter { it.key != "type" && it.key !in fields && it.value !is JsonNull }
        ?.forEach { errors += "$path.${it.key}: modifier field lowering is not available" }
      fun pixels(key: String, default: Double = 0.0): Double {
        if (type != "padding" && modifier?.get(key) == null)
          errors += "$path.$key: dimension is required"
        val value = number(modifier?.get(key), "$path.$key", default) * density
        if (value < 0) errors += "$path.$key: dimension must be non-negative"
        return value
      }
      fun single(key: String, value: JsonElement) = listOf(buildJsonObject { put(key, value) })
      return when (type) {
        "fillMaxSize",
        "fillMaxWidth",
        "fillMaxHeight" -> {
          val fraction = number(modifier?.get("fraction"), "$path.fraction", 1.0)
          if (fraction !in 0.0..1.0) errors += "$path.fraction: expected a value in 0..1"
          single(type, JsonPrimitive(fraction))
        }
        "width",
        "height" -> single(type, JsonPrimitive(pixels("${type}Dp")))
        "size" ->
          listOf(
            buildJsonObject { put("width", pixels("widthDp")) },
            buildJsonObject { put("height", pixels("heightDp")) },
          )
        "padding" ->
          single(
            "padding",
            buildJsonObject {
              listOf("start", "top", "end", "bottom").forEach { put(it, pixels("${it}Dp")) }
            },
          )
        "background" -> {
          val authored = modifier?.get("color")
          if (authored is JsonObject && authored["type"] != JsonPrimitive("color")) {
            errors += "$path.color: catalog tokens must be resolved before JSON export"
          }
          val color =
            (authored as? JsonObject)?.get("value") as? JsonPrimitive ?: authored as? JsonPrimitive
          if (
            color?.isString == true &&
              Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?").matches(color.content)
          )
            single(type, color)
          else {
            errors += "$path.color: resolve the catalog color to #RRGGBB or #AARRGGBB"
            emptyList()
          }
        }
        "alpha" ->
          single(
            "graphicsLayer",
            buildJsonObject { put("alpha", number(modifier?.get("alpha"), "$path.alpha", 1.0)) },
          )
        else -> {
          errors += "$path: modifier $type has no JSON mapping"
          emptyList()
        }
      }
    }

    fun number(value: JsonElement?, path: String, default: Double): Double {
      if (value == null) return default
      val number = (value as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
      if (number == null || !number.isFinite()) {
        errors += "$path: expected a finite number"
        return default
      }
      return number
    }

    fun matches(value: JsonPrimitive?, kind: String?): Boolean =
      value != null &&
        value !is JsonNull &&
        when (kind) {
          "int" -> !value.isString && value.intOrNull != null
          "float" -> !value.isString && value.floatOrNull?.isFinite() == true
          "bool" -> !value.isString && value.booleanOrNull != null
          "string" -> value.isString
          else -> false
        }
  }
}
