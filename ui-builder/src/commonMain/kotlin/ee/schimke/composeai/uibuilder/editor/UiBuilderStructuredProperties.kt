package ee.schimke.composeai.uibuilder.editor

import ee.schimke.composeai.uibuilder.capability.CompositeValueShape
import ee.schimke.composeai.uibuilder.capability.PropertyCapability
import ee.schimke.composeai.uibuilder.capability.PropertyPathSegment
import ee.schimke.composeai.uibuilder.capability.PropertyValueShape
import ee.schimke.composeai.uibuilder.capability.PropertyValueShapes
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
      when (val composite = located.shape.composite) {
        is CompositeValueShape.ListOf -> {
          val count = (located.wrapper["values"] as? JsonArray)?.size ?: 0
          rows +=
            field.copy(
              control = EditorPropertyControl.List,
              value = "$count ${if (count == 1) "item" else "items"}",
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
   * A scalar row: an enum from its allowed values, a switch, a number (whole for an `integer`) or
   * text — a colour or token without a closed set is typed as its stored spelling.
   */
  private fun scalarField(
    field: EditorPropertyField,
    shape: PropertyValueShape,
    wrapper: JsonObject,
  ): EditorPropertyField {
    val control =
      when {
        shape.allowedValues.isNotEmpty() -> EditorPropertyControl.Enum
        shape.jsonType == "boolean" -> EditorPropertyControl.Boolean
        shape.jsonType == "integer" || shape.jsonType == "number" -> EditorPropertyControl.Number
        else -> EditorPropertyControl.Text
      }
    val stored = (wrapper["value"] as? JsonPrimitive)?.takeUnless { it is JsonNull }
    return field.copy(
      control = control,
      value = stored?.content.orEmpty(),
      choices = shape.allowedValues,
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
    when (located.shape.composite) {
      is CompositeValueShape.ListOf ->
        return Outcome.Refused("$spelled is a list; add, remove or move its rows instead")
      is CompositeValueShape.ObjectOf ->
        return Outcome.Refused("$spelled is an object; set its fields instead")
      null -> Unit
    }
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
        is PropertyDraft.Valid -> parsed.value
      }
    return PropertyValueShapes.write(
        shape,
        stored,
        path,
        PropertyValueShapes.literal(located.shape, value),
      )
      ?.let(Outcome::Written) ?: Outcome.Refused("$spelled is not a value of $property")
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
}
