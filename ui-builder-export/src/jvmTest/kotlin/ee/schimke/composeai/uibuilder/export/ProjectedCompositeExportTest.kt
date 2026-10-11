package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.discovery.ComponentCode
import ee.schimke.composeai.discovery.ComponentOrigin
import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.discovery.ComponentSymbol
import ee.schimke.composeai.discovery.ExportProjection
import ee.schimke.composeai.discovery.ExportProjectionJson
import ee.schimke.composeai.discovery.TargetParameter
import ee.schimke.composeai.uibuilder.protocol.*
import java.io.File
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * A typed adapter's list and object properties export through the projection its catalog publishes:
 * a list of objects is `listOf(Contact(name = …))`, the class admitted to the allowlist as the one
 * callable it is, because it sits beside the catalog's own components.
 */
class ProjectedCompositeExportTest {
  private val inbox =
    ComponentRecord.Builder(
        canonicalId = "app/com.example.InboxKt.Inbox",
        symbol =
          ComponentSymbol.Builder(
              jvmOwner = "com.example.InboxKt",
              callable = "com.example.Inbox",
              name = "Inbox",
              origin = ComponentOrigin.PROJECT,
            )
            .build(),
      )
      .also { b ->
        b.componentIds = listOf("acme/inbox")
        b.signatureKnown = true
        b.parameters =
          listOf(
            TargetParameter.Builder("contacts", "List<Contact>")
              .also {
                it.typeFqn = "kotlin.collections.List"
                it.hasDefault = true
              }
              .build(),
            TargetParameter.Builder("pinned", "Contact")
              .also {
                it.typeFqn = "com.example.Contact"
                it.hasDefault = true
              }
              .build(),
            TargetParameter.Builder("modifier", "Modifier")
              .also {
                it.typeFqn = "androidx.compose.ui.Modifier"
                it.hasDefault = true
              }
              .build(),
          )
        b.code =
          ComponentCode.Builder()
            .also { c ->
              c.call = "Inbox()"
              c.imports = listOf("com.example.Inbox")
            }
            .build()
      }
      .build()

  private val records = ComponentRecordFile.Builder("app", "release", listOf(inbox)).build()

  /** As `TypedComponentAdapter.exportAs(target(…, mapOf(… to from(contacts))))` publishes it. */
  private val projection: ExportProjection =
    ExportProjectionJson.decode(
        Json.parseToJsonElement(
          """
          { "targets": [ { "record": "app/com.example.InboxKt.Inbox", "parameters": {
              "contacts": { "property": "contacts", "codec": { "list": { "element": $CONTACT } } },
              "pinned": { "property": "pinned", "codec": $CONTACT } } } ] }
          """
        )
      )
      .getOrThrow()

  private val projections = mapOf("acme/inbox" to projection)

  private val root =
    generateSequence(File(".").absoluteFile) { it.parentFile }
      .first { File(it, "docs/design/evidence/ui-builder-repetition-export").isDirectory }

  private fun document(vararg properties: Pair<String, UiValueV1>): DesignDocumentV1 {
    val base =
      Json.decodeFromString<DesignDocumentV1>(
        File(root, "docs/design/evidence/ui-builder-repetition-export/repetition-initial.uid")
          .readText()
      )
    val node = DesignNodeV1("inbox", "acme/inbox", properties = properties.toMap())
    return base.copy(
      title = "Inbox",
      stateVariables = emptyMap(),
      components = emptyMap(),
      roots = listOf(node.id),
      nodes = mapOf(node.id to node),
    )
  }

  private fun contact(name: String, unread: Long? = null) =
    ObjectValueV1(
      buildMap {
        put("name", StringValueV1(name))
        unread?.let { put("unread", IntegerValueV1(it)) }
      }
    )

  @Test
  fun `a list of objects exports as listOf of named constructor calls`() {
    val outcome =
      ScreenExportGate.export(
        document(
          "contacts" to ListValueV1(listOf(contact("Ada", 2), contact("Grace"))),
          "pinned" to contact("Linus", 7),
        ),
        records,
        projections,
      )
    val source = assertIs<ScreenExportGate.Outcome.Emitted>(outcome, outcome.toString()).source
    assertContains(source, "listOf(")
    assertContains(source, "Contact(name = \"Ada\", unread = 2)")
    // A field left out is left to the class's own default.
    assertContains(source, "Contact(name = \"Grace\")")
    assertContains(source, "Contact(name = \"Linus\", unread = 7)")
  }

  @Test
  fun `an empty list is written, and an unset one is not`() {
    val empty =
      ScreenExportGate.export(
        document("contacts" to ListValueV1(emptyList())),
        records,
        projections,
      )
    assertContains(
      assertIs<ScreenExportGate.Outcome.Emitted>(empty, empty.toString()).source,
      "listOf()",
    )

    val unset = ScreenExportGate.export(document(), records, projections)
    val source = assertIs<ScreenExportGate.Outcome.Emitted>(unset, unset.toString()).source
    assertFalse("contacts =" in source, source)
  }

  @Test
  fun `a malformed nested value is refused with its path`() {
    val outcome =
      ScreenExportGate.export(
        document(
          "contacts" to
            ListValueV1(
              listOf(
                ObjectValueV1(mapOf("name" to StringValueV1("Ada"), "age" to IntegerValueV1(3)))
              )
            )
        ),
        records,
        projections,
      )
    val reasons = assertIs<ScreenExportGate.Outcome.Refused>(outcome).reasons
    assertTrue(reasons.any { "node `inbox`" in it && "age" in it }, reasons.toString())
  }

  @Test
  fun `the object class is admitted as itself, never its whole package's parent`() {
    assertEquals(
      setOf("com.example.Contact"),
      ScreenExportGate.projectedOwners(projections, records),
    )
  }

  private companion object {
    const val CONTACT =
      """{ "object": { "type": "com.example.Contact", "fields": { "name": "text", "unread": "whole" } } }"""
  }
}
