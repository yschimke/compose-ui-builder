package ee.schimke.composeai.uibuilder.editor

import ee.schimke.composeai.uibuilder.capability.CompositeValueShape
import ee.schimke.composeai.uibuilder.capability.PropertyCapability
import ee.schimke.composeai.uibuilder.capability.PropertyPathSegment
import ee.schimke.composeai.uibuilder.capability.PropertyValueShape
import ee.schimke.composeai.uibuilder.capability.PropertyValueShapes
import ee.schimke.composeai.uibuilder.capability.UnionCaseShape
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The structured editor for a typed adapter's list and object properties: the inspector rows one
 * value expands to, and the edits those rows make. Each edit returns the property's whole new
 * wrapper, which the reducer writes as one `SetProperty` — the way a padding edge is merged back
 * into its padding — so validation, collaboration and undo see one ordinary property write.
 */
internal object StructuredProperties {

  /**
   * The bounds a nested number is typed within: the published shape names no range, and these are
   * the bounds a slider's own numbers use, finite so the `Float` round trip holds.
   */
  private const val NESTED_NUMBER_LIMIT = 1_000_000.0

  /**
   * The choice a nullable value's picker offers for null. Not a case or object name, which are
   * identifiers, so it can never be mistaken for one.
   */
  const val NONE: String = "(none)"

  /** What one edit makes of the property: its whole new wrapper, or why there is none. */
  sealed interface Outcome {
    data class Written(val value: JsonObject) : Outcome

    data class Refused(val message: String) : Outcome
  }

  /**
   * The rows [property] (of [shape]) shows for [nodes], [anchor] being the node they edit: the
   * property's heading, then for an object one row per field and for a list one row per item, each
   * expanding the same way. A multi-selection that disagrees shows the heading alone, as mixed.
   */
  fun fields(
    nodes: List<UiBuilderNode>,
    anchor: UiBuilderNode,
    property: PropertyCapability,
    shape: PropertyValueShape,
    error: String?,
  ): List<EditorPropertyField> {
    val stored = anchor.properties[property.name] as? JsonObject
    val mixed = nodes.map { it.properties[property.name] }.distinct().size > 1
    // Written as a whole: the rows of a value nobody set are not values the export writes.
    val written = nodes.any { property.name in it.properties }
    val rows = mutableListOf<EditorPropertyField>()
    fun visit(
      path: List<PropertyPathSegment>,
      label: String,
      required: Boolean,
      row: EditorListRow?,
    ) {
      val located = PropertyValueShapes.read(shape, stored, path) ?: return
      val isNull = PropertyValueShapes.isNull(located.wrapper)
      val field =
        EditorPropertyField(
          // A structured edit writes the anchor alone, so its rows never claim the selection.
          nodeCount = 1,
          nodeId = anchor.id,
          name = PropertyValueShapes.spell(property.name, path),
          label = label,
          required = required,
          written = written,
          control = EditorPropertyControl.Unsupported,
          value = "",
          structure = EditorPropertyStructure(property.name, path, row),
        )
      // A null composite is its picker alone: "(none)", or the value to start it as.
      if (isNull && located.shape.composite != null) {
        rows += nullPicker(field, located.shape)
        return
      }
      when (val composite = located.shape.composite) {
        is CompositeValueShape.UnionOf -> {
          val case = composite.case(located.wrapper.string("case"))
          rows +=
            field.copy(
              control = EditorPropertyControl.Enum,
              value = case?.name.orEmpty(),
              choices =
                listOfNotNull(NONE.takeIf { located.shape.nullable }) +
                  composite.cases.map(UnionCaseShape::name),
            )
          if (mixed || case == null) return
          case.fields?.forEach { caseField ->
            visit(
              path + PropertyPathSegment.Field(caseField.name),
              caseField.name.humanLabel(),
              caseField.required,
              row = null,
            )
          }
        }
        is CompositeValueShape.ListOf -> {
          val count = (located.wrapper["values"] as? JsonArray)?.size ?: 0
          rows +=
            field.copy(
              control = EditorPropertyControl.List,
              value = "$count ${if (count == 1) "item" else "items"}",
              // A started nullable list keeps its way back to null: the heading offers [NONE].
              choices = listOfNotNull(NONE.takeIf { located.shape.nullable }),
            )
          if (mixed) return
          repeat(count) { index ->
            visit(
              path + PropertyPathSegment.Index(index),
              "Item ${index + 1}",
              required = false,
              row = EditorListRow(index, count),
            )
          }
        }
        is CompositeValueShape.ObjectOf -> {
          rows +=
            if (located.shape.nullable)
              nullPicker(field.copy(value = kindName(located.shape)), located.shape)
            else
              field.copy(
                control = EditorPropertyControl.Object,
                value = composite.objectType.substringAfterLast('.'),
              )
          if (mixed) return
          composite.fields.forEach { objectField ->
            visit(
              path + PropertyPathSegment.Field(objectField.name),
              objectField.name.humanLabel(),
              objectField.required,
              row = null,
            )
          }
        }
        null -> rows += scalarField(field, located.shape, located.wrapper)
      }
    }
    visit(emptyList(), property.name.humanLabel(), property.required, row = null)
    if (rows.isEmpty()) return emptyList()
    rows[0] =
      rows[0].copy(
        mixed = mixed,
        value = if (mixed) "" else rows[0].value,
        error = error,
        notes = property.notes,
      )
    return rows
  }

  /**
   * The picker of a nullable composite: [NONE], or the value's own kind (its object type, or
   * "List") to start one from its seed.
   */
  private fun nullPicker(
    field: EditorPropertyField,
    shape: PropertyValueShape,
  ): EditorPropertyField {
    val kind = kindName(shape)
    val choices =
      when (val composite = shape.composite) {
        is CompositeValueShape.UnionOf -> composite.cases.map(UnionCaseShape::name)
        else -> listOf(kind)
      }
    return field.copy(
      control = EditorPropertyControl.Enum,
      value = if (field.value.isEmpty()) NONE else field.value,
      choices = listOf(NONE) + choices,
    )
  }

  private fun kindName(shape: PropertyValueShape): String =
    when (val composite = shape.composite) {
      is CompositeValueShape.ObjectOf -> composite.objectType.substringAfterLast('.')
      is CompositeValueShape.ListOf -> "List"
      is CompositeValueShape.UnionOf -> composite.unionType.substringAfterLast('.')
      null -> shape.literalType
    }

  /**
   * A scalar row: an enum from its allowed values, a switch, a number (whole for an `integer`) or
   * text — a colour or token without a closed set is typed as its stored spelling. A nullable
   * scalar reads as empty when null, and an empty draft commits null (see [commit]); a nullable
   * enum or Boolean, whose control has no empty spelling, picks [NONE] instead — a switch would
   * show null as `false` and never return to it.
   */
  private fun scalarField(
    field: EditorPropertyField,
    shape: PropertyValueShape,
    wrapper: JsonObject,
  ): EditorPropertyField {
    val nullableBoolean = shape.nullable && shape.jsonType == "boolean"
    val control =
      when {
        shape.allowedValues.isNotEmpty() || nullableBoolean -> EditorPropertyControl.Enum
        shape.jsonType == "boolean" -> EditorPropertyControl.Boolean
        shape.jsonType == "integer" || shape.jsonType == "number" -> EditorPropertyControl.Number
        else -> EditorPropertyControl.Text
      }
    val stored = (wrapper["value"] as? JsonPrimitive)?.takeUnless { it is JsonNull }
    val pickable =
      shape.allowedValues.ifEmpty { listOf("true", "false").takeIf { nullableBoolean } }
    val nullPick = shape.nullable && control == EditorPropertyControl.Enum
    return field.copy(
      control = control,
      value = stored?.content ?: if (nullPick) NONE else "",
      choices = if (nullPick) listOf(NONE) + pickable.orEmpty() else shape.allowedValues,
      numberBounds =
        when (shape.jsonType) {
          "integer" ->
            EditorNumberBounds(-NESTED_NUMBER_LIMIT, NESTED_NUMBER_LIMIT, 1.0, integer = true)
          "number" ->
            EditorNumberBounds(-NESTED_NUMBER_LIMIT, NESTED_NUMBER_LIMIT, 0.1, integer = false)
          else -> null
        },
    )
  }

  /**
   * [stored] with the scalar at [path] set from [draft], parsed as that scalar's row parses it.
   * Refused for a path that names no scalar of [shape], or a draft its row would not accept.
   */
  fun commit(
    property: String,
    shape: PropertyValueShape,
    stored: JsonObject?,
    path: List<PropertyPathSegment>,
    draft: String,
  ): Outcome {
    val spelled = PropertyValueShapes.spell(property, path)
    val located =
      PropertyValueShapes.read(shape, stored, path)
        ?: return Outcome.Refused("$spelled is not a value of $property")
    fun written(value: JsonObject): Outcome =
      PropertyValueShapes.write(shape, stored, path, value)?.let(Outcome::Written)
        ?: Outcome.Refused("$spelled is not a value of $property")
    val nullable = located.shape.nullable
    if (nullable && draft == NONE) return written(PropertyValueShapes.NULL_WRAPPER)
    val isNull = PropertyValueShapes.isNull(located.wrapper)
    when (val composite = located.shape.composite) {
      // The picker: a case name starts that case from its seed; the stored case keeps its fields.
      is CompositeValueShape.UnionOf -> {
        val case =
          composite.case(draft)
            ?: return Outcome.Refused(
              "`$draft` is not a case of ${composite.unionType.substringAfterLast('.')}"
            )
        if (!isNull && located.wrapper.string("case") == case.name) return written(located.wrapper)
        return written(PropertyValueShapes.seedCase(case))
      }
      is CompositeValueShape.ListOf ->
        return if (nullable && isNull && draft == kindName(located.shape))
          written(PropertyValueShapes.seed(located.shape.copy(nullable = false)))
        else Outcome.Refused("$spelled is a list; add, remove or move its rows instead")
      is CompositeValueShape.ObjectOf ->
        return if (nullable && isNull && draft == kindName(located.shape))
          written(PropertyValueShapes.seed(located.shape.copy(nullable = false)))
        else Outcome.Refused("$spelled is an object; set its fields instead")
      null -> Unit
    }
    // A nullable scalar cleared to nothing is null; text has no other way to say it.
    if (nullable && draft.isEmpty()) return written(PropertyValueShapes.NULL_WRAPPER)
    val field =
      scalarField(
        EditorPropertyField(
          nodeId = "",
          name = spelled,
          label = spelled,
          required = false,
          control = EditorPropertyControl.Unsupported,
          value = "",
        ),
        located.shape,
        located.wrapper,
      )
    val value =
      when (val parsed = field.parseDraft(draft)) {
        is PropertyDraft.Invalid -> return Outcome.Refused(parsed.message)
        // A nullable Boolean is picked by name, and stored as the Boolean it names.
        is PropertyDraft.Valid ->
          if (located.shape.jsonType == "boolean") JsonPrimitive(draft.toBooleanStrict())
          else parsed.value
      }
    // Text with a syntax of its own is checked as the codec will read it.
    if (value is JsonPrimitive && value.isString) {
      PropertyValueShapes.formatIssue(located.shape.format, value.content)?.let {
        return Outcome.Refused("$spelled: $it")
      }
    }
    return written(PropertyValueShapes.literal(located.shape, value))
  }

  /** [stored] with [edit] made to the rows of the list at [path]. */
  fun editList(
    property: String,
    shape: PropertyValueShape,
    stored: JsonObject?,
    path: List<PropertyPathSegment>,
    edit: EditorListEdit,
  ): Outcome {
    val spelled = PropertyValueShapes.spell(property, path)
    val list = PropertyValueShapes.read(shape, stored, path)
    if (list?.shape?.composite !is CompositeValueShape.ListOf) {
      return Outcome.Refused("$spelled is not a list")
    }
    val count = (list.wrapper["values"] as? JsonArray)?.size ?: 0
    val edited =
      when (edit) {
        is EditorListEdit.Insert -> PropertyValueShapes.insertRow(shape, stored, path, edit.index)
        is EditorListEdit.Remove -> PropertyValueShapes.removeRow(shape, stored, path, edit.index)
        is EditorListEdit.Move ->
          PropertyValueShapes.moveRow(shape, stored, path, edit.from, edit.to)
      }
    return edited?.let(Outcome::Written)
      ?: Outcome.Refused(
        when (edit) {
          is EditorListEdit.Insert -> "$spelled has no row ${edit.index} to insert before"
          is EditorListEdit.Remove -> "$spelled has no row ${edit.index}; it holds $count"
          is EditorListEdit.Move ->
            "$spelled cannot move row ${edit.from} to ${edit.to}; it holds $count"
        }
      )
  }

  private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
}
