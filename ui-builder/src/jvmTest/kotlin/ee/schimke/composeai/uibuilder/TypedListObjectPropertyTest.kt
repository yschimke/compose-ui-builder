package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.capability.CapabilityIssueCode
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import ee.schimke.composeai.uibuilder.capability.CompositeValueShape
import ee.schimke.composeai.uibuilder.capability.PropertyEditorControl
import ee.schimke.composeai.uibuilder.capability.PropertyPathSegment.Field
import ee.schimke.composeai.uibuilder.capability.PropertyPathSegment.Index
import ee.schimke.composeai.uibuilder.capability.PropertyValueShapes
import ee.schimke.composeai.uibuilder.editor.EditorListEdit
import ee.schimke.composeai.uibuilder.editor.EditorListRow
import ee.schimke.composeai.uibuilder.editor.EditorPropertyControl
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorState
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * A typed adapter's list and object properties, as compose-ai-tools publishes them (`jsonType`
 * `"array"` / `"object"` and a recursive `editor` shape): validated against that shape, expanded by
 * the inspector into one row per field and item, and edited path by path and row by row through the
 * reducer, each edit one undoable write of the whole property.
 */
class TypedListObjectPropertyTest {
  private val catalog = CapabilityCatalogParser.parse(CATALOG)
  private val reducer = UiBuilderEditorReducer(catalog)

  private fun json(source: String): JsonElement = Json.parseToJsonElement(source)

  private fun document(vararg properties: Pair<String, String>) =
    UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "inbox",
      title = "Inbox",
      revision = 1,
      catalogPin = JsonObject(mapOf("catalogId" to JsonPrimitive("acme"))),
      environment = JsonObject(emptyMap()),
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("inbox"),
      nodes =
        mapOf(
          "inbox" to
            UiBuilderNode(
              id = "inbox",
              componentId = "acme/inbox",
              properties =
                JsonObject(properties.associate { (name, value) -> name to json(value) }),
            )
        ),
    )

  private fun state(vararg properties: Pair<String, String>): UiBuilderEditorState =
    reducer.initial(document(*properties), selectedNodeId = "inbox")

  private fun UiBuilderEditorState.contacts(): JsonObject =
    assertNotNull(document.nodes.getValue("inbox").properties["contacts"]).jsonObject

  private fun issues(vararg properties: Pair<String, String>) =
    CapabilityValidator(catalog).validate(document(*properties)).issues

  @Test
  fun `the published editor parses into a recursive shape`() {
    val contacts =
      catalog.componentsById.getValue("acme/inbox").propertiesByName.getValue("contacts")
    val editor = assertNotNull(contacts.editor)
    assertEquals(PropertyEditorControl.LIST, editor.control)
    val list = assertIs<CompositeValueShape.ListOf>(assertNotNull(editor.valueShape).composite)
    val contact = assertIs<CompositeValueShape.ObjectOf>(list.element.composite)
    assertEquals("com.example.Contact", contact.objectType)
    assertEquals(listOf("name", "unread", "role", "tags"), contact.fields.map { it.name })
    assertEquals(listOf(true, false, false, false), contact.fields.map { it.required })
    assertIs<CompositeValueShape.ListOf>(contact.field("tags")?.shape?.composite)

    val pinned = catalog.componentsById.getValue("acme/inbox").propertiesByName.getValue("pinned")
    assertEquals(PropertyEditorControl.OBJECT, pinned.editor?.control)
  }

  @Test
  fun `a path is spelled and read back`() {
    val path = listOf(Index(0), Field("tags"), Index(2))
    assertEquals("contacts[0].tags[2]", PropertyValueShapes.spell("contacts", path))
    assertEquals("contacts" to path, PropertyValueShapes.parse("contacts[0].tags[2]"))
    assertNull(PropertyValueShapes.parse("contacts[x]"))
    assertEquals(
      "contentPadding" to listOf(Field("topDp")),
      PropertyValueShapes.parse("contentPadding.topDp"),
    )
  }

  @Test
  fun `a well formed nested value validates`() {
    assertEquals(emptyList(), issues("contacts" to TWO_CONTACTS, "pinned" to ADA))
    assertEquals(emptyList(), issues("contacts" to """{"type": "list", "values": []}"""))
  }

  @Test
  fun `a malformed nested value is refused with where it is`() {
    val cases =
      mapOf(
        // An unknown field.
        """{"type": "list", "values": [{"type": "object", "fields": {
            "name": {"type": "string", "value": "Ada"}, "age": {"type": "int", "value": 3}}}]}""" to
          "contacts[0]: com.example.Contact has no field age",
        // A required field left out.
        """{"type": "list", "values": [{"type": "object", "fields": {}}]}""" to
          "contacts[0]: missing field `name`",
        // A nested list of the wrong element type.
        """{"type": "list", "values": [{"type": "object", "fields": {
            "name": {"type": "string", "value": "Ada"},
            "tags": {"type": "list", "values": [{"type": "int", "value": 1}]}}}]}""" to
          "contacts[0].tags[0]: expected a `string` wrapper, found `int`",
        // An enum field outside its values.
        """{"type": "list", "values": [{"type": "object", "fields": {
            "name": {"type": "string", "value": "Ada"},
            "role": {"type": "string", "value": "owner"}}}]}""" to
          "contacts[0].role:",
        // A whole number written as a fraction.
        """{"type": "list", "values": [{"type": "object", "fields": {
            "name": {"type": "string", "value": "Ada"},
            "unread": {"type": "int", "value": 1.5}}}]}""" to
          "contacts[0].unread: expected a JSON integer",
        // A list wrapper spelled as a literal.
        """{"type": "list", "value": []}""" to "contacts: a `list` wrapper",
        // A scalar where the list goes.
        """{"type": "string", "value": "Ada"}""" to "contacts: expected a `list` wrapper",
      )
    for ((value, expected) in cases) {
      val found = issues("contacts" to value)
      val issue = assertNotNull(found.singleOrNull(), "$value: $found")
      assertEquals("contacts", issue.field)
      assertTrue(issue.message.contains(expected), "${issue.message} should contain $expected")
    }
    assertEquals(
      CapabilityIssueCode.INVALID_PROPERTY_TYPE,
      issues("contacts" to """{"type": "string", "value": "Ada"}""").single().code,
    )
  }

  @Test
  fun `the wire accepts a list wrapper on set and checks what it nests`() {
    assertNull(propertyWrapperIssue("list", json(TWO_CONTACTS).jsonObject))
    assertEquals(
      "list wrapper must contain exactly type and a values array",
      propertyWrapperIssue("list", json("""{"type": "list", "value": []}""").jsonObject),
    )
    assertEquals(
      "list value 0 must be a typed value",
      propertyWrapperIssue("list", json("""{"type": "list", "values": ["Ada"]}""").jsonObject),
    )
  }

  @Test
  fun `the inspector expands a list of objects into one row per item and field`() {
    val fields = reducer.propertyFields(state("contacts" to TWO_CONTACTS))
    val contacts = fields.filter { it.structure?.property == "contacts" }
    assertEquals(
      listOf(
        "contacts",
        "contacts[0]",
        "contacts[0].name",
        "contacts[0].unread",
        "contacts[0].role",
        "contacts[0].tags",
        "contacts[0].tags[0]",
        "contacts[1]",
        "contacts[1].name",
        "contacts[1].unread",
        "contacts[1].role",
        "contacts[1].tags",
      ),
      contacts.map { it.name },
    )
    val byName = contacts.associateBy { it.name }
    assertEquals(EditorPropertyControl.List, byName.getValue("contacts").control)
    assertEquals("2 items", byName.getValue("contacts").value)
    assertEquals(EditorPropertyControl.Object, byName.getValue("contacts[0]").control)
    assertEquals(EditorListRow(0, 2), byName.getValue("contacts[0]").structure?.row)
    assertEquals("Item 1", byName.getValue("contacts[0]").label)
    assertEquals(EditorPropertyControl.Text, byName.getValue("contacts[0].name").control)
    assertEquals("Ada", byName.getValue("contacts[0].name").value)
    assertEquals(EditorPropertyControl.Number, byName.getValue("contacts[0].unread").control)
    assertEquals(true, byName.getValue("contacts[0].unread").numberBounds?.integer)
    // A field the stored object leaves out shows its default.
    assertEquals("0", byName.getValue("contacts[0].unread").value)
    assertEquals(EditorPropertyControl.Enum, byName.getValue("contacts[0].role").control)
    assertEquals(listOf("admin", "member"), byName.getValue("contacts[0].role").choices)
    assertEquals(EditorPropertyControl.List, byName.getValue("contacts[0].tags").control)
    assertEquals("vip", byName.getValue("contacts[0].tags[0]").value)
    assertEquals(EditorListRow(0, 1), byName.getValue("contacts[0].tags[0]").structure?.row)
    assertEquals(3, byName.getValue("contacts[0].tags[0]").structure?.depth)

    // An object property is a heading over its fields, seeded from the shape while unset.
    val pinned = fields.filter { it.structure?.property == "pinned" }
    assertEquals(
      listOf("pinned", "pinned.name", "pinned.unread", "pinned.role", "pinned.tags"),
      pinned.map { it.name },
    )
    assertEquals(false, pinned.first().written)
    assertEquals("member", pinned.first { it.name == "pinned.role" }.value)
  }

  @Test
  fun `editing a nested field writes the whole property and undoes as one edit`() {
    val initial = state("contacts" to TWO_CONTACTS)
    val edited =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.CommitPropertyPath(
          "inbox",
          "contacts",
          listOf(Index(1), Field("name")),
          "Grace Hopper",
        ),
      )
    assertIs<CommandOutcome.Accepted>(edited.lastOutcome)
    val expected =
      json(
          """{"type": "list", "values": [
            {"type": "object", "fields": {
              "name": {"type": "string", "value": "Ada"},
              "tags": {"type": "list", "values": [{"type": "string", "value": "vip"}]}}},
            {"type": "object", "fields": {
              "name": {"type": "string", "value": "Grace Hopper"},
              "unread": {"type": "int", "value": 2}}}]}"""
        )
        .jsonObject
    assertEquals(expected, edited.contacts())

    // The spelled name a field carries commits through the ordinary event, too.
    val unread =
      reducer.reduce(
        edited,
        UiBuilderEditorEvent.CommitProperty("inbox", "contacts[0].unread", "5"),
      )
    assertIs<CommandOutcome.Accepted>(unread.lastOutcome)
    assertEquals(
      json("""{"type": "int", "value": 5}"""),
      unread
        .contacts()
        .let { PropertyValueShapes.read(shape("contacts"), it, listOf(Index(0), Field("unread"))) }
        ?.wrapper,
    )

    val undone = reducer.reduce(unread, UiBuilderEditorEvent.Undo)
    assertEquals(expected, undone.contacts())
    val undoneAgain = reducer.reduce(undone, UiBuilderEditorEvent.Undo)
    assertEquals(json(TWO_CONTACTS), undoneAgain.contacts())
  }

  @Test
  fun `a draft the nested control would not accept is refused, keyed to the property`() {
    val refused =
      reducer.reduce(
        state("contacts" to TWO_CONTACTS),
        UiBuilderEditorEvent.CommitPropertyPath(
          "inbox",
          "contacts",
          listOf(Index(0), Field("unread")),
          "1.5",
        ),
      )
    val outcome = assertIs<CommandOutcome.Rejected>(refused.lastOutcome)
    assertTrue(outcome.message.contains("contacts[0].unread"), outcome.message)
    assertEquals(json(TWO_CONTACTS), refused.contacts())
    assertNotNull(reducer.propertyFields(refused).first { it.name == "contacts" }.error)

    val notAScalar =
      reducer.reduce(
        state("contacts" to TWO_CONTACTS),
        UiBuilderEditorEvent.CommitPropertyPath("inbox", "contacts", listOf(Index(0)), "Ada"),
      )
    assertIs<CommandOutcome.Rejected>(notAScalar.lastOutcome)
  }

  @Test
  fun `rows are added from the element shape, removed and moved, each undoable`() {
    val initial = state("contacts" to TWO_CONTACTS)
    fun edit(state: UiBuilderEditorState, change: EditorListEdit) =
      reducer.reduce(
        state,
        UiBuilderEditorEvent.EditPropertyList("inbox", "contacts", emptyList(), change),
      )
    fun names(state: UiBuilderEditorState) =
      reducer
        .propertyFields(state)
        .filter {
          it.structure?.property == "contacts" && it.structure?.path?.lastOrNull() == Field("name")
        }
        .map { it.value }

    val added = edit(initial, EditorListEdit.Insert())
    assertIs<CommandOutcome.Accepted>(added.lastOutcome)
    // Seeded from the shape: the required name empty, every other field its default.
    assertEquals(
      json(
        """{"type": "object", "fields": {
          "name": {"type": "string", "value": ""},
          "unread": {"type": "int", "value": 0},
          "role": {"type": "string", "value": "member"},
          "tags": {"type": "list", "values": []}}}"""
      ),
      PropertyValueShapes.read(shape("contacts"), added.contacts(), listOf(Index(2)))?.wrapper,
    )
    assertEquals(listOf("Ada", "Grace", ""), names(added))

    val moved = edit(added, EditorListEdit.Move(0, 1))
    assertIs<CommandOutcome.Accepted>(moved.lastOutcome)
    assertEquals(listOf("Grace", "Ada", ""), names(moved))

    val removed = edit(moved, EditorListEdit.Remove(2))
    assertIs<CommandOutcome.Accepted>(removed.lastOutcome)
    assertEquals(listOf("Grace", "Ada"), names(removed))

    assertIs<CommandOutcome.Rejected>(edit(removed, EditorListEdit.Remove(5)).lastOutcome)
    assertIs<CommandOutcome.Rejected>(edit(removed, EditorListEdit.Move(1, 2)).lastOutcome)

    val undone =
      reducer.reduce(reducer.reduce(removed, UiBuilderEditorEvent.Undo), UiBuilderEditorEvent.Undo)
    assertEquals(listOf("Ada", "Grace", ""), names(undone))
    assertEquals(json(TWO_CONTACTS), reducer.reduce(undone, UiBuilderEditorEvent.Undo).contacts())
  }

  @Test
  fun `a list inside an object inside a list is edited the same way`() {
    val tagged =
      reducer.reduce(
        state("contacts" to TWO_CONTACTS),
        UiBuilderEditorEvent.EditPropertyList(
          "inbox",
          "contacts",
          listOf(Index(1), Field("tags")),
          EditorListEdit.Insert(),
        ),
      )
    assertIs<CommandOutcome.Accepted>(tagged.lastOutcome)
    val named =
      reducer.reduce(
        tagged,
        UiBuilderEditorEvent.CommitPropertyPath(
          "inbox",
          "contacts",
          listOf(Index(1), Field("tags"), Index(0)),
          "friend",
        ),
      )
    assertIs<CommandOutcome.Accepted>(named.lastOutcome)
    assertEquals(
      json("""{"type": "list", "values": [{"type": "string", "value": "friend"}]}"""),
      PropertyValueShapes.read(shape("contacts"), named.contacts(), listOf(Index(1), Field("tags")))
        ?.wrapper,
    )
    // Validates as a whole, too.
    assertEquals(emptyList(), CapabilityValidator(catalog).validate(named.document).issues)
  }

  @Test
  fun `an unset object property is written whole by its first field edit`() {
    val edited =
      reducer.reduce(
        state(),
        UiBuilderEditorEvent.CommitProperty("inbox", "pinned.name", "Linus"),
      )
    assertIs<CommandOutcome.Accepted>(edited.lastOutcome)
    assertEquals(
      json(
        """{"type": "object", "fields": {
          "name": {"type": "string", "value": "Linus"},
          "unread": {"type": "int", "value": 0},
          "role": {"type": "string", "value": "member"},
          "tags": {"type": "list", "values": []}}}"""
      ),
      edited.document.nodes.getValue("inbox").properties["pinned"],
    )
  }

  private fun shape(property: String) =
    assertNotNull(
      catalog.componentsById
        .getValue("acme/inbox")
        .propertiesByName
        .getValue(property)
        .editor
        ?.valueShape
    )

  private companion object {
    const val ADA =
      """{"type": "object", "fields": {
        "name": {"type": "string", "value": "Ada"},
        "tags": {"type": "list", "values": [{"type": "string", "value": "vip"}]}}}"""

    const val TWO_CONTACTS =
      """{"type": "list", "values": [
        $ADA,
        {"type": "object", "fields": {
          "name": {"type": "string", "value": "Grace"},
          "unread": {"type": "int", "value": 2}}}]}"""

    /** `Contact`'s editor, as `AdapterObjectCodec.editor` publishes it. */
    const val CONTACT_EDITOR =
      """{"control": "object", "objectType": "com.example.Contact", "fields": [
          {"name": "name", "required": true, "jsonType": "string", "literalType": "string"},
          {"name": "unread", "required": false, "default": {"type": "int", "value": 0},
           "jsonType": "integer", "literalType": "int"},
          {"name": "role", "required": false, "default": {"type": "string", "value": "member"},
           "jsonType": "string", "literalType": "string", "allowedValues": ["admin", "member"]},
          {"name": "tags", "required": false, "default": {"type": "list", "values": []},
           "jsonType": "array", "literalType": "list",
           "editor": {"control": "list", "element": {"jsonType": "string", "literalType": "string"}}}]}"""

    /** One `Contact` as a list element, as `AdapterObjectCodec.shape()` publishes it. */
    const val CONTACT_SHAPE =
      """{"jsonType": "object", "literalType": "object", "editor": $CONTACT_EDITOR}"""

    const val CATALOG =
      """{"schema": "compose-ui-builder-capability/v1",
        "benchmark": {"id": "acme", "sourceRevision": "source", "catalogSystemId": "acme",
          "catalogRevision": "revision", "nativeRuntimeId": "runtime"},
        "components": [{"componentId": "acme/inbox", "displayName": "Inbox", "role": "Leaf",
          "properties": [
            {"name": "contacts", "jsonType": "array", "required": false,
             "editor": {"control": "list", "element": $CONTACT_SHAPE}},
            {"name": "pinned", "jsonType": "object", "required": false,
             "editor": $CONTACT_EDITOR}],
          "wasm": {"platformSupported": true, "adapterStatus": "supported"}}]}"""
  }
}
