package ee.schimke.composeai.uibuilder.capability

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * How one value of a typed adapter's list or object property is authored, as the catalog publishes
 * it inside the property capability's `editor` (compose-ai-tools' `AdapterValueCodec.shape`):
 * ```json
 * { "jsonType": "string|boolean|integer|number|array|object", "literalType": "<wrapper type>",
 *   "allowedValues"?: [ "…" ], "format"?: "date-time|hex|…", "nullable"?: true,
 *   "editor"?: { "control": "list" | "object" | "union", … } }
 * ```
 *
 * A scalar is stored as `{"type": literalType, "value": …}`, a list as `{"type": "list", "values":
 * [<wrapper>…]}`, an object as `{"type": "object", "fields": {"<name>": <wrapper>}}` and one case
 * of a sealed type as `{"type": "union", "case": "<name>", "fields"?: {…}}`, nesting to any depth.
 * A [nullable] value may instead be `{"type": "null"}`.
 */
data class PropertyValueShape(
  val jsonType: String,
  val literalType: String,
  val allowedValues: List<String> = emptyList(),
  /** Null for a scalar. */
  val composite: CompositeValueShape? = null,
  /** The syntax a `string` value has (`date-time`, `hex`), when it has one of its own. */
  val format: String? = null,
  /** Whether `{"type": "null"}` is a value of this shape too. */
  val nullable: Boolean = false,
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

  /** One case of the sealed type [unionType]: [cases] in the order the catalog lists them. */
  data class UnionOf(val unionType: String, val cases: List<UnionCaseShape>) : CompositeValueShape {
    fun case(name: String?): UnionCaseShape? = cases.firstOrNull { it.name == name }
  }
}

/**
 * One case of a [CompositeValueShape.UnionOf]: a class case with its [fields], or a `data object`
 * case (null [fields]), which stores none.
 */
data class UnionCaseShape(
  val name: String,
  val objectType: String,
  val fields: List<ObjectFieldShape>?,
) {
  fun field(name: String): ObjectFieldShape? = fields?.firstOrNull { it.name == name }
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
  const val UNION: String = "union"
  const val NULL: String = "null"

  /** Deeper than any real value; a published shape nested past it is refused, not walked. */
  private const val MAXIMUM_DEPTH = 32

  private val SEGMENT = Regex("""^(?:\.([A-Za-z_][A-Za-z0-9_]*)|\[(\d+)])""")

  /**
   * The editor for a published list or object `editor` hint, or null when it is not one. A hint
   * that names a list or object but does not parse (malformed, or a newer shape) is held as that
   * control with no [PropertyEditorCapability.valueShape]: the property stays uneditable, and no
   * later policy or built-in override gives it an editor for some other value.
   */
  fun declaredCompositeEditor(
    editor: JsonObject,
    nullable: Boolean = false,
  ): PropertyEditorCapability? =
    when (editor.string("control")) {
      LIST ->
        compositeEditor(editor, nullable)
          ?: PropertyEditorCapability(control = PropertyEditorControl.LIST)
      OBJECT ->
        compositeEditor(editor, nullable)
          ?: PropertyEditorCapability(control = PropertyEditorControl.OBJECT)
      UNION ->
        compositeEditor(editor, nullable)
          ?: PropertyEditorCapability(control = PropertyEditorControl.UNION)
      else -> null
    }

  /**
   * The editor capability for a published list or object `editor` hint, or null when it is not one
   * or does not parse (a malformed shape is not editable, rather than editable wrongly).
   */
  fun compositeEditor(editor: JsonObject, nullable: Boolean = false): PropertyEditorCapability? =
    when (val composite = parseEditor(editor, 0)) {
      is CompositeValueShape.ListOf ->
        PropertyEditorCapability(
          control = PropertyEditorControl.LIST,
          valueShape =
            PropertyValueShape("array", LIST, composite = composite, nullable = nullable),
        )
      is CompositeValueShape.ObjectOf ->
        PropertyEditorCapability(
          control = PropertyEditorControl.OBJECT,
          valueShape =
            PropertyValueShape("object", OBJECT, composite = composite, nullable = nullable),
        )
      is CompositeValueShape.UnionOf ->
        PropertyEditorCapability(
          control = PropertyEditorControl.UNION,
          valueShape =
            PropertyValueShape("object", UNION, composite = composite, nullable = nullable),
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
        val fields = parseFields(editor["fields"], depth) ?: return null
        if (fields.isEmpty()) return null
        CompositeValueShape.ObjectOf(objectType, fields)
      }
      UNION -> {
        val unionType = editor.string("unionType")?.takeIf(String::isNotBlank) ?: return null
        val declared = editor["cases"] as? JsonArray ?: return null
        val cases = declared.map { element ->
          val case = element as? JsonObject ?: return null
          val name = case.string("name")?.takeIf(String::isNotBlank) ?: return null
          val objectType = case.string("objectType")?.takeIf(String::isNotBlank) ?: return null
          val fields =
            if ("fields" in case) parseFields(case["fields"], depth) ?: return null else null
          UnionCaseShape(name, objectType, fields)
        }
        if (cases.isEmpty() || cases.map { it.name }.distinct().size != cases.size) return null
        CompositeValueShape.UnionOf(unionType, cases)
      }
      else -> null
    }
  }

  /** An object's (or a union case's) published fields: named, distinct, each with its shape. */
  private fun parseFields(declared: JsonElement?, depth: Int): List<ObjectFieldShape>? {
    val array = declared as? JsonArray ?: return null
    val fields = array.map { element ->
      val field = element as? JsonObject ?: return null
      val name = field.string("name")?.takeIf(String::isNotBlank) ?: return null
      val shape = parseShape(field, depth + 1) ?: return null
      val default = field["default"] as? JsonObject
      val required = (field["required"] as? JsonPrimitive)?.booleanOrNull ?: (default == null)
      ObjectFieldShape(name, required, default, shape)
    }
    return fields.takeIf { it.map(ObjectFieldShape::name).distinct().size == it.size }
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
    val format = shape.string("format")?.takeIf(String::isNotBlank)
    val nullable = (shape["nullable"] as? JsonPrimitive)?.booleanOrNull ?: false
    // The wrapper spelling and the control must agree, or a value could pass one and not the other.
    val consistent =
      when (composite) {
        is CompositeValueShape.ListOf -> literalType == LIST
        is CompositeValueShape.ObjectOf -> literalType == OBJECT
        is CompositeValueShape.UnionOf -> literalType == UNION
        null -> literalType !in COMPOSITE_TYPES
      }
    return if (consistent)
      PropertyValueShape(jsonType, literalType, allowedValues, composite, format, nullable)
    else null
  }

  private val COMPOSITE_TYPES = setOf(LIST, OBJECT, UNION, NULL)

  /**
   * A new value of [shape]: an empty list, an object of its fields' defaults (or their own seeds),
   * or a scalar's first allowed value, `false`, zero or empty text. What adding a row writes.
   */
  fun seed(shape: PropertyValueShape): JsonObject =
    if (shape.nullable) NULL_WRAPPER
    else
      when (val composite = shape.composite) {
        is CompositeValueShape.ListOf -> wrapper(LIST, "values", JsonArray(emptyList()))
        is CompositeValueShape.ObjectOf -> wrapper(OBJECT, "fields", seedFields(composite.fields))
        is CompositeValueShape.UnionOf -> seedCase(composite.cases.first())
        null -> literal(shape, scalarSeed(shape))
      }

  /** A new value of [case]: its name and, for a class case, its fields' defaults or seeds. */
  fun seedCase(case: UnionCaseShape): JsonObject =
    JsonObject(
      buildMap {
        put("type", JsonPrimitive(UNION))
        put("case", JsonPrimitive(case.name))
        case.fields?.let { put("fields", seedFields(it)) }
      }
    )

  /** `{"type": "null"}`, a [PropertyValueShape.nullable] value's null. */
  val NULL_WRAPPER: JsonObject = JsonObject(mapOf("type" to JsonPrimitive(NULL)))

  /** Whether [stored] is exactly `{"type": "null"}`. */
  fun isNull(stored: JsonElement?): Boolean =
    stored is JsonObject && stored.keys == setOf("type") && stored.string("type") == NULL

  private fun seedFields(fields: List<ObjectFieldShape>): JsonObject =
    JsonObject(fields.associate { it.name to (it.default ?: seed(it.shape)) })

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
    if (shape.nullable && isNull(stored)) return null
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
        fieldsIssue(composite.objectType, composite.fields, fields, where)
      }
      is CompositeValueShape.UnionOf -> {
        val name = wrapper.string("case")
        val case =
          composite.case(name)
            ?: return at(
              "`$name` is not a case of ${composite.unionType}: " +
                composite.cases.joinToString(", ") { it.name }
            )
        val declared = case.fields
        if (declared == null) {
          if (wrapper.keys != setOf("type", "case"))
            return at("the `${case.name}` case is exactly type and case")
          null
        } else {
          val fields = wrapper["fields"] as? JsonObject
          if (wrapper.keys != setOf("type", "case", "fields") || fields == null)
            return at("the `${case.name}` case is exactly type, case and a fields object")
          fieldsIssue(case.objectType, declared, fields, where)
        }
      }
      null -> scalarIssue(shape, wrapper)?.let(::at)
    }
  }

  private fun fieldsIssue(
    objectType: String,
    declared: List<ObjectFieldShape>,
    fields: JsonObject,
    where: String,
  ): String? {
    fun at(message: String) = if (where.isEmpty()) message else "$where: $message"
    val unknown = fields.keys - declared.map { it.name }.toSet()
    if (unknown.isNotEmpty()) {
      return at("$objectType has no field ${unknown.sorted().joinToString(", ")}")
    }
    return declared.firstNotNullOfOrNull { field ->
      val value = fields[field.name]
      if (value == null || value is JsonNull) {
        if (field.required) at("missing field `${field.name}`") else null
      } else issue(field.shape, value, "$where.${field.name}")
    }
  }

  private fun scalarIssue(shape: PropertyValueShape, wrapper: JsonObject): String? {
    val value = wrapper["value"] as? JsonPrimitive ?: return "a `${shape.literalType}` has no value"
    if (value is JsonNull) return "a `${shape.literalType}` has no value"
    val matches =
      when (shape.jsonType) {
        "string" -> value.isString
        "boolean" -> !value.isString && value.booleanOrNull != null
        // A Long's full range: an `int` wrapper also stores a typed adapter's `Long`.
        "integer" -> !value.isString && value.longOrNull != null
        "number" -> !value.isString && value.doubleOrNull?.isFinite() == true
        else -> true
      }
    if (!matches) return "expected a JSON ${shape.jsonType}"
    if (shape.allowedValues.isNotEmpty() && value.content !in shape.allowedValues) {
      return "`${value.content}` is not one of ${shape.allowedValues.joinToString(", ")}"
    }
    return formatIssue(shape.format, value.content)
  }

  /**
   * Why [text] is not spelled as [format] says, or null. Only the formats the typed codecs publish
   * are checked here, a `date-time` by the same `Instant.parse` its codec decodes with; the
   * renderer's codec is the authority, and an unknown format is the codec's alone.
   */
  @OptIn(ExperimentalTime::class)
  fun formatIssue(format: String?, text: String): String? =
    when (format) {
      DATE_TIME ->
        if (ISO_INSTANT.matches(text) && Instant.parseOrNull(text) != null) null
        else "`$text` is not an ISO-8601 instant, like 2026-10-12T08:00:00Z"
      HEX ->
        if (text.length % 2 == 0 && text.all { it in HEX_DIGITS }) null
        else "`$text` is not hex: pairs of 0-9 and a-f"
      else -> null
    }

  const val DATE_TIME: String = "date-time"
  const val HEX: String = "hex"
  private const val HEX_DIGITS = "0123456789abcdefABCDEF"
  private val ISO_INSTANT =
    Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d{1,9})?)?(Z|[+-]\d{2}:\d{2})$""")

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
          // A field of whichever case is stored; a null union has none.
          is CompositeValueShape.UnionOf -> {
            val name = (segment as? PropertyPathSegment.Field)?.name ?: return null
            val field = composite.case(current.wrapper.string("case"))?.field(name) ?: return null
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
      is CompositeValueShape.UnionOf -> {
        val name = (path.first() as? PropertyPathSegment.Field)?.name ?: return null
        val case = composite.case(current.string("case")) ?: return null
        val field = case.field(name) ?: return null
        val fields = LinkedHashMap((current["fields"] as? JsonObject).orEmpty())
        fields[name] = write(field.shape, current.fieldOrDefault(field), rest, value) ?: return null
        JsonObject(
          mapOf(
            "type" to JsonPrimitive(UNION),
            "case" to JsonPrimitive(case.name),
            "fields" to JsonObject(fields),
          )
        )
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

  // A stored null is read as itself, so its rows show that nothing is set.
  private fun JsonObject?.orSeed(shape: PropertyValueShape): JsonObject =
    this?.takeIf { it.string("type") == shape.literalType || (shape.nullable && isNull(it)) }
      ?: seed(shape)

  private fun JsonObject.values(): List<JsonElement> = (this["values"] as? JsonArray).orEmpty()

  /** [field] of this object (or union case) as stored, or its default or [seed]. */
  private fun JsonObject.fieldOrDefault(field: ObjectFieldShape): JsonObject =
    ((this["fields"] as? JsonObject)?.get(field.name) as? JsonObject)
      ?: field.default
      ?: seed(field.shape)

  private fun wrapper(type: String, key: String, content: JsonElement): JsonObject =
    JsonObject(mapOf("type" to JsonPrimitive(type), key to content))

  private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
}
