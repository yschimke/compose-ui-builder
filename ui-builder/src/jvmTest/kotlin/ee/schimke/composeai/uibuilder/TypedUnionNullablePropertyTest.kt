package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import ee.schimke.composeai.uibuilder.capability.CompositeValueShape
import ee.schimke.composeai.uibuilder.capability.PropertyEditorControl
import ee.schimke.composeai.uibuilder.capability.PropertyPathSegment.Field
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
 * A typed adapter's sealed-type (`union`), nullable and formatted values, as compose-ai-tools
 * publishes them: a case picked, then that case's fields; `(none)` for a null; a `date-time` or
 * `hex` string checked as the codec will read it; and a `Long`'s full range in an `int` wrapper.
 */
class TypedUnionNullablePropertyTest {
  private val catalog = CapabilityCatalogParser.parse(CATALOG)
  private val reducer = UiBuilderEditorReducer(catalog)

  private fun json(source: String): JsonElement = Json.parseToJsonElement(source)

  private fun document(vararg properties: Pair<String, String>) =
    UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "device",
      title = "Device",
      revision = 1,
      catalogPin = JsonObject(mapOf("catalogId" to JsonPrimitive("acme"))),
      environment = JsonObject(emptyMap()),
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("device"),
      nodes =
        mapOf(
          "device" to
            UiBuilderNode(
              id = "device",
              componentId = "acme/device",
              properties =
                JsonObject(properties.associate { (name, value) -> name to json(value) }),
            )
        ),
    )

  private fun state(vararg properties: Pair<String, String>): UiBuilderEditorState =
    reducer.initial(document(*properties), selectedNodeId = "device")

  private fun issues(vararg properties: Pair<String, String>) =
    CapabilityValidator(catalog).validate(document(*properties)).issues

  private fun UiBuilderEditorState.status(): JsonObject =
    assertNotNull(document.nodes.getValue("device").properties["status"]).jsonObject

  private fun commit(state: UiBuilderEditorState, path: List<Field>, draft: String) =
    reducer.reduce(
      state,
      UiBuilderEditorEvent.CommitPropertyPath("device", "status", path, draft),
    )

  @Test
  fun `a union editor parses its cases, a field's format and nullability`() {
    val status = catalog.componentsById.getValue("acme/device").propertiesByName.getValue("status")
    val editor = assertNotNull(status.editor)
    assertEquals(PropertyEditorControl.UNION, editor.control)
    val union = assertIs<CompositeValueShape.UnionOf>(assertNotNull(editor.valueShape).composite)
    assertEquals(listOf("idle", "failed"), union.cases.map { it.name })
    assertNull(union.case("idle")?.fields)
    val seen = assertNotNull(union.case("failed")?.field("seen")).shape
    assertEquals("date-time", seen.format)
    assertTrue(seen.nullable)
  }

  @Test
  fun `cases, nulls, formats and long integers validate as the codec reads them`() {
    assertEquals(emptyList(), issues("status" to IDLE))
    assertEquals(emptyList(), issues("status" to FAILED))
    assertEquals(
      emptyList(),
      issues(
        "status" to
          """{"type": "union", "case": "failed", "fields": {
            "message": {"type": "string", "value": "timeout"}, "seen": {"type": "null"},
            "storedKb": {"type": "int", "value": 9007199254740993}}}"""
      ),
    )
    val cases =
      mapOf(
        """{"type": "union", "case": "lost"}""" to "`lost` is not a case",
        """{"type": "union", "case": "idle", "fields": {}}""" to "exactly type and case",
        """{"type": "union", "case": "failed"}""" to "exactly type, case and a fields object",
        """{"type": "union", "case": "failed", "fields": {
            "message": {"type": "string", "value": "x"},
            "seen": {"type": "string", "value": "yesterday"}}}""" to
          "status.seen: `yesterday` is not an ISO-8601 instant",
        """{"type": "union", "case": "failed", "fields": {
            "message": {"type": "null"}}}""" to
          "status.message: expected a `string` wrapper",
      )
    for ((stored, expected) in cases) {
      val messages = issues("status" to stored).joinToString("\n") { it.message }
      assertTrue(expected in messages, "$stored: expected `$expected` in\n$messages")
    }
  }

  @Test
  fun `the inspector shows a case picker and the stored case's fields`() {
    val idle =
      reducer
        .propertyFields(state("status" to IDLE))
        .filter { it.name.startsWith("status") }
        .associateBy { it.name }
    val picker = idle.getValue("status")
    assertEquals(EditorPropertyControl.Enum, picker.control)
    assertEquals("idle", picker.value)
    assertEquals(listOf("idle", "failed"), picker.choices)
    assertEquals(setOf("status"), idle.keys)

    val failed =
      reducer
        .propertyFields(state("status" to FAILED))
        .filter { it.name.startsWith("status") }
        .associateBy { it.name }
    assertEquals(setOf("status", "status.message", "status.seen", "status.storedKb"), failed.keys)
    assertEquals("timeout", failed.getValue("status.message").value)
    assertEquals("2026-10-12T08:00:00Z", failed.getValue("status.seen").value)
  }

  @Test
  fun `picking a case seeds it, editing a field keeps it, and an empty nullable draft is null`() {
    val failed = commit(state("status" to IDLE), emptyList(), "failed")
    assertEquals(
      json(
        """{"type": "union", "case": "failed", "fields": {
          "message": {"type": "string", "value": ""}, "seen": {"type": "null"},
          "storedKb": {"type": "int", "value": 0}}}"""
      ),
      failed.status(),
    )
    val named = commit(failed, listOf(Field("message")), "timeout")
    assertEquals(
      JsonPrimitive("timeout"),
      named.status().getValue("fields").jsonObject.getValue("message").jsonObject["value"],
    )
    // Picking the stored case again changes nothing.
    assertEquals(named.status(), commit(named, emptyList(), "failed").status())

    val seen = commit(named, listOf(Field("seen")), "2026-10-12T08:00:00Z")
    assertEquals(
      json("""{"type": "string", "value": "2026-10-12T08:00:00Z"}"""),
      seen.status().getValue("fields").jsonObject.getValue("seen"),
    )
    val cleared = commit(seen, listOf(Field("seen")), "")
    assertEquals(
      json("""{"type": "null"}"""),
      cleared.status().getValue("fields").jsonObject.getValue("seen"),
    )

    // Malformed text is refused before it is written, keyed to the property.
    val refused = commit(seen, listOf(Field("seen")), "yesterday")
    assertEquals(seen.status(), refused.status())
    assertNotNull(reducer.propertyFields(refused).first { it.name == "status" }.error)

    val idle = commit(cleared, emptyList(), "idle")
    assertEquals(json(IDLE), idle.status())
  }

  @Test
  fun `a nullable object is picked between none and its type`() {
    val unset = reducer.propertyFields(state("owner" to """{"type": "null"}"""))
    val picker = unset.first { it.name == "owner" }
    assertEquals(EditorPropertyControl.Enum, picker.control)
    assertEquals("(none)", picker.value)
    assertEquals(listOf("(none)", "Owner"), picker.choices)
    assertTrue(unset.none { it.name.startsWith("owner.") })

    val started =
      reducer.reduce(
        state("owner" to """{"type": "null"}"""),
        UiBuilderEditorEvent.CommitPropertyPath("device", "owner", emptyList(), "Owner"),
      )
    assertEquals(
      json("""{"type": "object", "fields": {"name": {"type": "string", "value": ""}}}"""),
      started.document.nodes.getValue("device").properties["owner"],
    )
    val shown = reducer.propertyFields(started).associateBy { it.name }
    assertEquals("Owner", shown.getValue("owner").value)
    assertTrue("owner.name" in shown)

    val none =
      reducer.reduce(
        started,
        UiBuilderEditorEvent.CommitPropertyPath("device", "owner", emptyList(), "(none)"),
      )
    // On the node itself, null is the property unset: it reads as the adapter's null default.
    val owner = none.document.nodes.getValue("device").properties["owner"]
    assertTrue(owner == null || owner == json("""{"type": "null"}"""), "owner is $owner")
    assertEquals("(none)", reducer.propertyFields(none).first { it.name == "owner" }.value)
  }

  private companion object {
    const val IDLE = """{"type": "union", "case": "idle"}"""
    const val FAILED =
      """{"type": "union", "case": "failed", "fields": {
        "message": {"type": "string", "value": "timeout"},
        "seen": {"type": "string", "value": "2026-10-12T08:00:00Z"}}}"""

    const val STATUS_EDITOR =
      """{"control": "union", "unionType": "com.example.Status", "cases": [
        {"name": "idle", "objectType": "com.example.Status.Idle"},
        {"name": "failed", "objectType": "com.example.Status.Failed", "fields": [
          {"name": "message", "required": true, "jsonType": "string", "literalType": "string"},
          {"name": "seen", "required": false, "default": {"type": "null"}, "jsonType": "string",
           "literalType": "string", "format": "date-time", "nullable": true},
          {"name": "storedKb", "required": false, "default": {"type": "int", "value": 0},
           "jsonType": "integer", "literalType": "int"}]}]}"""

    const val OWNER_EDITOR =
      """{"control": "object", "objectType": "com.example.Owner", "fields": [
        {"name": "name", "required": true, "jsonType": "string", "literalType": "string"}]}"""

    const val CATALOG =
      """{"schema": "compose-ui-builder-capability/v1",
        "benchmark": {"id": "acme", "sourceRevision": "source", "catalogSystemId": "acme",
          "catalogRevision": "revision", "nativeRuntimeId": "runtime"},
        "components": [{"componentId": "acme/device", "displayName": "Device", "role": "Leaf",
          "properties": [
            {"name": "status", "jsonType": "object", "required": false,
             "editor": $STATUS_EDITOR},
            {"name": "owner", "jsonType": "object", "required": false, "nullable": true,
             "editor": {"control": "object", "objectType": "com.example.Owner", "fields": [
               {"name": "name", "required": true, "jsonType": "string",
                "literalType": "string"}]}}],
          "wasm": {"platformSupported": true, "adapterStatus": "supported"}}]}"""
  }
}
