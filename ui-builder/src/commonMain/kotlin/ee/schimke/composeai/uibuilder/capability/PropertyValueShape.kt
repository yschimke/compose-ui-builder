package ee.schimke.composeai.uibuilder.capability

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * How one value of a typed adapter's list or object property is authored, as the catalog publishes
 * it inside the property capability's `editor` (compose-ai-tools' `AdapterValueCodec.shape`):
 * ```json
 * { "jsonType": "string|boolean|integer|number|array|object", "literalType": "<wrapper type>",
 *   "allowedValues"?: [ "…" ], "editor"?: { "control": "list" | "object", … } }
 * ```
 *
 * A scalar is stored as `{"type": literalType, "value": …}`, a list as `{"type": "list", "values":
 * [<wrapper>…]}` and an object as `{"type": "object", "fields": {"<name>": <wrapper>}}`, nesting to
 * any depth.
 */
data class PropertyValueShape(
  val jsonType: String,
  val literalType: String,
  val allowedValues: List<String> = emptyList(),
  /** Null for a scalar. */
  val composite: CompositeValueShape? = null,
)

/**
 * The recursive half of a [PropertyValueShape]: what a list holds or what an object's fields are.
 */
sealed interface CompositeValueShape {
  data class ListOf(val element: PropertyValueShape) : CompositeValueShape

  data class ObjectOf(val objectType: String, val fields: List<ObjectFieldShape>) :
    CompositeValueShape {
    fun field(name: String): ObjectFieldShape? = fields.firstOrNull { it.name == name }
  }
}

/**
 * One field of an object value: [required] when it has no [default] (the stored wrapper the codec
 * decodes when the field is left out).
 */
data class ObjectFieldShape(
  val name: String,
  val required: Boolean,
  val default: JsonObject?,
  val shape: PropertyValueShape,
)

/** One step into a list or object value: a field of an object, or a row of a list. */
sealed interface PropertyPathSegment {
  data class Field(val name: String) : PropertyPathSegment

  data class Index(val index: Int) : PropertyPathSegment
}

/** A value inside a list or object property, and the shape it has there. */
data class LocatedPropertyValue(val shape: PropertyValueShape, val wrapper: JsonObject)

/**
 * Reading, checking and rewriting list and object property values against their published
 * [PropertyValueShape]. Pure: every edit returns the whole new top-level wrapper, since the wire
 * carries the property as one value, the way a padding edge is merged back into its padding.
 */
object PropertyValueShapes {
  const val LIST: String = "list"
  const val OBJECT: String = "object"

  /** Deeper than any real value; a published shape nested past it is refused, not walked. */
  private const val MAXIMUM_DEPTH = 32

  private val SEGMENT = Regex("""^(?:\.([A-Za-z_][A-Za-z0-9_]*)|\[(\d+)])""")

  /**
   * The editor for a published list or object `editor` hint, or null when it is not one. A hint
   * that names a list or object but does not parse (malformed, or a newer shape) is held as that
   * control with no [PropertyEditorCapability.valueShape]: the property stays uneditable, and no
   * later policy or built-in override gives it an editor for some other value.
   */
  fun declaredCompositeEditor(editor: JsonObject): PropertyEditorCapability? =
    when (editor.string("control")) {
      LIST ->
        compositeEditor(editor) ?: PropertyEditorCapability(control = PropertyEditorControl.LIST)
      OBJECT ->
        compositeEditor(editor) ?: PropertyEditorCapability(control = PropertyEditorControl.OBJECT)
      else -> null
    }

  /**
   * The editor capability for a published list or object `editor` hint, or null when it is not one
   * or does not parse (a malformed shape is not editable, rather than editable wrongly).
   */
  fun compositeEditor(editor: JsonObject): PropertyEditorCapability? =
    when (val composite = parseEditor(editor, 0)) {
      is CompositeValueShape.ListOf ->
        PropertyEditorCapability(
          control = PropertyEditorControl.LIST,
          valueShape = PropertyValueShape("array", LIST, composite = composite),
        )
      is CompositeValueShape.ObjectOf ->
        PropertyEditorCapability(
          control = PropertyEditorControl.OBJECT,
          valueShape = PropertyValueShape("object", OBJECT, composite = composite),
        )
      null -> null
    }

  private fun parseEditor(editor: JsonObject, depth: Int): CompositeValueShape? {
    if (depth > MAXIMUM_DEPTH) return null
    return when (editor.string("control")) {
      LIST ->
        (editor["element"] as? JsonObject)
          ?.let { parseShape(it, depth + 1) }
          ?.let(CompositeValueShape::ListOf)
      OBJECT -> {
        val objectType = editor.string("objectType")?.takeIf(String::isNotBlank) ?: return null
        val declared = editor["fields"] as? JsonArray ?: return null
        val fields = declared.map { element ->
          val field = element as? JsonObject ?: return null
          val name = field.string("name")?.takeIf(String::isNotBlank) ?: return null
          val shape = parseShape(field, depth + 1) ?: return null
          val default = field["default"] as? JsonObject
          val required = (field["required"] as? JsonPrimitive)?.booleanOrNull ?: (default == null)
          ObjectFieldShape(name, required, default, shape)
        }
        if (fields.isEmpty() || fields.map { it.name }.distinct().size != fields.size) return null
        CompositeValueShape.ObjectOf(objectType, fields)
      }
      else -> null
    }
  }

  private fun parseShape(shape: JsonObject, depth: Int): PropertyValueShape? {
    val jsonType = shape.string("jsonType") ?: return null
    val literalType = shape.string("literalType") ?: return null
    val allowedValues =
      (shape["allowedValues"] as? JsonArray)
        ?.map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: return null }
        .orEmpty()
    val composite =
      when (val editor = shape["editor"]) {
        null,
        JsonNull -> null
        is JsonObject -> parseEditor(editor, depth) ?: return null
        else -> return null
      }
    // The wrapper spelling and the control must agree, or a value could pass one and not the other.
    val consistent =
      when (composite) {
        is CompositeValueShape.ListOf -> literalType == LIST
        is CompositeValueShape.ObjectOf -> literalType == OBJECT
        null -> literalType != LIST && literalType != OBJECT
      }
    return if (consistent) PropertyValueShape(jsonType, literalType, allowedValues, composite)
    else null
  }

  /**
   * A new value of [shape]: an empty list, an object of its fields' defaults (or their own seeds),
   * or a scalar's first allowed value, `false`, zero or empty text. What adding a row writes.
   */
  fun seed(shape: PropertyValueShape): JsonObject =
    when (val composite = shape.composite) {
      is CompositeValueShape.ListOf -> wrapper(LIST, "values", JsonArray(emptyList()))
      is CompositeValueShape.ObjectOf ->
        wrapper(
          OBJECT,
          "fields",
          JsonObject(composite.fields.associate { it.name to (it.default ?: seed(it.shape)) }),
        )
      null -> literal(shape, scalarSeed(shape))
    }

  private fun scalarSeed(shape: PropertyValueShape): JsonPrimitive =
    when {
      shape.allowedValues.isNotEmpty() -> JsonPrimitive(shape.allowedValues.first())
      shape.jsonType == "boolean" -> JsonPrimitive(false)
      shape.jsonType == "integer" -> JsonPrimitive(0)
      shape.jsonType == "number" -> JsonPrimitive(0.0)
      else -> JsonPrimitive("")
    }

  /** A scalar of [shape] holding [value]. */
  fun literal(shape: PropertyValueShape, value: JsonElement): JsonObject =
    wrapper(shape.literalType, "value", value)

  /**
   * Why [stored] is not a value of [shape], prefixed with where it sits (`contacts[1].name`), or
   * null when it is one. The rule the renderer's codec applies: exact wrappers, no unknown field, a
   * field without a default present, scalars of their JSON type and allowed values.
   */
  fun issue(shape: PropertyValueShape, stored: JsonElement?, where: String): String? {
    fun at(message: String) = if (where.isEmpty()) message else "$where: $message"
    val wrapper = stored as? JsonObject ?: return at("expected a `${shape.literalType}` wrapper")
    val type = wrapper.string("type")
    if (type != shape.literalType) {
      return at("expected a `${shape.literalType}` wrapper, found `$type`")
    }
    return when (val composite = shape.composite) {
      is CompositeValueShape.ListOf -> {
        val values = wrapper["values"] as? JsonArray
        if (wrapper.keys != setOf("type", "values") || values == null)
          return at("a `list` wrapper is exactly type and a values array")
        values.withIndex().firstNotNullOfOrNull { (index, value) ->
          issue(composite.element, value, "$where[$index]")
        }
      }
      is CompositeValueShape.ObjectOf -> {
        val fields = wrapper["fields"] as? JsonObject
        if (wrapper.keys != setOf("type", "fields") || fields == null)
          return at("an `object` wrapper is exactly type and a fields object")
        val unknown = fields.keys - composite.fields.map { it.name }.toSet()
        if (unknown.isNotEmpty()) {
          return at("${composite.objectType} has no field ${unknown.sorted().joinToString(", ")}")
        }
        composite.fields.firstNotNullOfOrNull { field ->
          val value = fields[field.name]
          if (value == null || value is JsonNull) {
            if (field.required) at("missing field `${field.name}`") else null
          } else issue(field.shape, value, "$where.${field.name}")
        }
      }
      null -> scalarIssue(shape, wrapper)?.let(::at)
    }
  }

  private fun scalarIssue(shape: PropertyValueShape, wrapper: JsonObject): String? {
    val value = wrapper["value"] as? JsonPrimitive ?: return "a `${shape.literalType}` has no value"
    if (value is JsonNull) return "a `${shape.literalType}` has no value"
    val matches =
      when (shape.jsonType) {
        "string" -> value.isString
        "boolean" -> !value.isString && value.booleanOrNull != null
        "integer" -> !value.isString && value.intOrNull != null
        "number" -> !value.isString && value.doubleOrNull?.isFinite() == true
        else -> true
      }
    if (!matches) return "expected a JSON ${shape.jsonType}"
    if (shape.allowedValues.isNotEmpty() && value.content !in shape.allowedValues) {
      return "`${value.content}` is not one of ${shape.allowedValues.joinToString(", ")}"
    }
    return null
  }

  /**
   * The value at [path] inside [root] (a property of [shape]), reading an absent root, or an absent
   * object field, as its default or [seed]. Null when [path] leaves the shape or names a row the
   * list does not have.
   */
  fun read(
    shape: PropertyValueShape,
    root: JsonObject?,
    path: List<PropertyPathSegment>,
  ): LocatedPropertyValue? {
    var current = LocatedPropertyValue(shape, root.orSeed(shape))
    for (segment in path) {
      current =
        when (val composite = current.shape.composite) {
          is CompositeValueShape.ListOf -> {
            val index = (segment as? PropertyPathSegment.Index)?.index ?: return null
            val row = current.wrapper.values().getOrNull(index) as? JsonObject ?: return null
            LocatedPropertyValue(composite.element, row)
          }
          is CompositeValueShape.ObjectOf -> {
            val name = (segment as? PropertyPathSegment.Field)?.name ?: return null
            val field = composite.field(name) ?: return null
            LocatedPropertyValue(field.shape, current.wrapper.fieldOrDefault(field))
          }
          null -> return null
        }
    }
    return current
  }

  /**
   * [root] with the value at [path] replaced by [value]: the whole new top-level wrapper, every
   * other row and field carried as stored. Null when [path] does not address a value of [shape].
   */
  fun write(
    shape: PropertyValueShape,
    root: JsonObject?,
    path: List<PropertyPathSegment>,
    value: JsonObject,
  ): JsonObject? {
    if (path.isEmpty()) return value
    val current = root.orSeed(shape)
    val rest = path.drop(1)
    return when (val composite = shape.composite) {
      is CompositeValueShape.ListOf -> {
        val index = (path.first() as? PropertyPathSegment.Index)?.index ?: return null
        val values = current.values().toMutableList()
        if (index !in values.indices) return null
        values[index] =
          write(composite.element, values[index] as? JsonObject, rest, value) ?: return null
        wrapper(LIST, "values", JsonArray(values))
      }
      is CompositeValueShape.ObjectOf -> {
        val name = (path.first() as? PropertyPathSegment.Field)?.name ?: return null
        val field = composite.field(name) ?: return null
        val fields = LinkedHashMap((current["fields"] as? JsonObject).orEmpty())
        fields[name] = write(field.shape, current.fieldOrDefault(field), rest, value) ?: return null
        wrapper(OBJECT, "fields", JsonObject(fields))
      }
      null -> null
    }
  }

  /** [root] with a [seed] row added to the list at [listPath], at [index] or at the end. */
  fun insertRow(
    shape: PropertyValueShape,
    root: JsonObject?,
    listPath: List<PropertyPathSegment>,
    index: Int? = null,
  ): JsonObject? =
    editRows(shape, root, listPath) { element, rows ->
      val at = index ?: rows.size
      if (at !in 0..rows.size) null else rows.apply { add(at, seed(element)) }
    }

  /** [root] without row [index] of the list at [listPath]. */
  fun removeRow(
    shape: PropertyValueShape,
    root: JsonObject?,
    listPath: List<PropertyPathSegment>,
    index: Int,
  ): JsonObject? =
    editRows(shape, root, listPath) { _, rows ->
      if (index !in rows.indices) null else rows.apply { removeAt(index) }
    }

  /** [root] with row [from] of the list at [listPath] moved to [to]. */
  fun moveRow(
    shape: PropertyValueShape,
    root: JsonObject?,
    listPath: List<PropertyPathSegment>,
    from: Int,
    to: Int,
  ): JsonObject? =
    editRows(shape, root, listPath) { _, rows ->
      if (from !in rows.indices || to !in rows.indices || from == to) null
      else rows.apply { add(to, removeAt(from)) }
    }

  private fun editRows(
    shape: PropertyValueShape,
    root: JsonObject?,
    listPath: List<PropertyPathSegment>,
    edit: (PropertyValueShape, MutableList<JsonElement>) -> MutableList<JsonElement>?,
  ): JsonObject? {
    val list = read(shape, root, listPath) ?: return null
    val element = (list.shape.composite as? CompositeValueShape.ListOf)?.element ?: return null
    val rows = edit(element, list.wrapper.values().toMutableList()) ?: return null
    return write(shape, root, listPath, wrapper(LIST, "values", JsonArray(rows)))
  }

  /** `contacts[0].name`: how a value inside [property] is named in fields, events and messages. */
  fun spell(property: String, path: List<PropertyPathSegment>): String =
    property +
      path.joinToString("") {
        when (it) {
          is PropertyPathSegment.Field -> ".${it.name}"
          is PropertyPathSegment.Index -> "[${it.index}]"
        }
      }

  /** [spell] read back: the property and the path, or null when [spelled] is not one. */
  fun parse(spelled: String): Pair<String, List<PropertyPathSegment>>? {
    val property = spelled.takeWhile { it != '.' && it != '[' }
    if (property.isEmpty()) return null
    var rest = spelled.substring(property.length)
    val path = mutableListOf<PropertyPathSegment>()
    while (rest.isNotEmpty()) {
      val match = SEGMENT.find(rest) ?: return null
      val (field, index) = match.destructured
      path +=
        if (field.isNotEmpty()) PropertyPathSegment.Field(field)
        else PropertyPathSegment.Index(index.toIntOrNull() ?: return null)
      rest = rest.substring(match.value.length)
    }
    return property to path
  }

  private fun JsonObject?.orSeed(shape: PropertyValueShape): JsonObject =
    this?.takeIf { it.string("type") == shape.literalType } ?: seed(shape)

  private fun JsonObject.values(): List<JsonElement> = (this["values"] as? JsonArray).orEmpty()

  private fun JsonObject.fieldOrDefault(field: ObjectFieldShape): JsonObject =
    ((this["fields"] as? JsonObject)?.get(field.name) as? JsonObject)
      ?: field.default
      ?: seed(field.shape)

  private fun wrapper(type: String, key: String, content: JsonElement): JsonObject =
    JsonObject(mapOf("type" to JsonPrimitive(type), key to content))

  private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
}
