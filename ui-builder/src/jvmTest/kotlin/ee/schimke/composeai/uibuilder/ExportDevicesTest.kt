package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.canvas.withScreenFields
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.client.toProtocolSubmission
import ee.schimke.composeai.uibuilder.editor.EditorLayoutDirection
import ee.schimke.composeai.uibuilder.editor.EditorScreenTheme
import ee.schimke.composeai.uibuilder.editor.EditorSubmission
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.editor.screenEnvironmentSettings
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import ee.schimke.composeai.uibuilder.protocol.DesignCommandV1
import ee.schimke.composeai.uibuilder.protocol.ResetExportDevicesEnvironmentChangeV1
import ee.schimke.composeai.uibuilder.protocol.SetExportDevicesEnvironmentChangeV1
import ee.schimke.composeai.uibuilder.protocol.UpdateEnvironmentMutationV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The set of devices a design says it also exports as, beside the single approved frame, so the
 * document answers which devices it claims.
 */
class ExportDevicesTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  @Test
  fun `a design written before the field exports at its own frame alone`() {
    assertEquals(emptyList(), document.screenEnvironmentSettings().exportDevices)
  }

  @Test
  fun `the chosen set survives the round trip through the document`() {
    val initial = reducer.initial(document, selectedNodeId = null)
    val settings = initial.document.screenEnvironmentSettings()

    val next =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.UpdateEnvironment(
          settings.copy(exportDevices = listOf("id:pixel_6", "id:pixel_fold"))
        ),
      )

    assertEquals(
      listOf("id:pixel_6", "id:pixel_fold"),
      next.document.screenEnvironmentSettings().exportDevices,
    )
  }

  /**
   * An absent key and an empty array mean the same but differ as JSON; unrelated environment edits
   * must not add an empty `exportDevices` (or the undo step carries a second field).
   */
  @Test
  fun `an unrelated environment edit does not write the field at all`() {
    val initial = reducer.initial(document, selectedNodeId = null)
    val settings = initial.document.screenEnvironmentSettings()

    val next =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.UpdateEnvironment(settings.copy(fontScale = 1.3)),
      )
    val submission = assertIs<EditorSubmission.Batch>(reducer.acceptedSubmission(initial, next))

    assertEquals(
      listOf("fontScale"),
      submission.command.operations.map { assertIs<DesignOperation.SetEnvironment>(it).field },
    )
  }

  /**
   * Apply must not overwrite fields it doesn't own (see #903): asserted on the `withScreenFields`
   * helper the dock goes through, since the reducer faithfully wrote what it was handed.
   */
  @Test
  fun `applying the screen fields keeps the devices the picker chose`() {
    val chosen =
      document
        .screenEnvironmentSettings()
        .copy(exportDevices = listOf("id:pixel_3_xl", "id:pixel_9_pro"))

    val applied =
      chosen.withScreenFields(
        widthDp = 1280,
        heightDp = 800,
        density = 2.0,
        fontScale = 1.0,
        locale = "en-US",
        theme = EditorScreenTheme.Light,
        layoutDirection = EditorLayoutDirection.Ltr,
      )

    assertEquals(listOf("id:pixel_3_xl", "id:pixel_9_pro"), applied.exportDevices)
    assertEquals(2.0, applied.density)
  }

  /** And the reducer therefore writes only the field that moved, leaving the devices untouched. */
  @Test
  fun `an apply that changes only the density leaves the chosen devices in the document`() {
    val initial = reducer.initial(document, selectedNodeId = null)
    val withDevices =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.UpdateEnvironment(
          initial.document.screenEnvironmentSettings().copy(exportDevices = listOf("id:pixel_6"))
        ),
      )
    val current = withDevices.document.screenEnvironmentSettings()

    val applied =
      reducer.reduce(
        withDevices,
        UiBuilderEditorEvent.UpdateEnvironment(
          current.withScreenFields(
            widthDp = current.widthDp,
            heightDp = current.heightDp,
            density = 2.0,
            fontScale = current.fontScale,
            locale = current.locale,
            theme = current.theme,
            layoutDirection = current.layoutDirection,
          )
        ),
      )

    assertEquals(
      listOf("id:pixel_6"),
      applied.document.screenEnvironmentSettings().exportDevices,
    )
    val submission =
      assertIs<EditorSubmission.Batch>(reducer.acceptedSubmission(withDevices, applied))
    assertEquals(
      listOf("density"),
      submission.command.operations.map { assertIs<DesignOperation.SetEnvironment>(it).field },
    )
  }

  @Test
  fun `a chosen set reaches the wire as one Set change`() {
    val changes = environmentChanges(listOf("id:pixel_6", "id:pixel_tablet"))

    assertEquals(1, changes.size)
    assertEquals(
      listOf("id:pixel_6", "id:pixel_tablet"),
      assertIs<SetExportDevicesEnvironmentChangeV1>(changes.single()).value,
    )
  }

  /**
   * Clearing the picker resets the field (the protocol distinguishes reset from empty), so the
   * design reads like one that never set it.
   */
  @Test
  fun `clearing the set reaches the wire as a Reset`() {
    val initial = reducer.initial(document, selectedNodeId = null)
    val chosen =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.UpdateEnvironment(
          initial.document.screenEnvironmentSettings().copy(exportDevices = listOf("id:pixel_6"))
        ),
      )
    val cleared =
      reducer.reduce(
        chosen,
        UiBuilderEditorEvent.UpdateEnvironment(
          chosen.document.screenEnvironmentSettings().copy(exportDevices = emptyList())
        ),
      )

    val submission = assertIs<EditorSubmission.Batch>(reducer.acceptedSubmission(chosen, cleared))
    val mutation =
      assertIs<UpdateEnvironmentMutationV1>(
        assertIs<DesignCommandV1>(submission.toWire()).operations.single()
      )
    assertTrue(
      mutation.changes.single() is ResetExportDevicesEnvironmentChangeV1,
      "expected a Reset, got ${mutation.changes.single()}",
    )
  }

  private fun EditorSubmission.toWire() = toProtocolSubmission("actor", "client", document.revision)

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  private fun environmentChanges(devices: List<String>) = run {
    val initial = reducer.initial(document, selectedNodeId = null)
    val next =
      reducer.reduce(
        initial,
        UiBuilderEditorEvent.UpdateEnvironment(
          initial.document.screenEnvironmentSettings().copy(exportDevices = devices)
        ),
      )
    val submission = assertIs<EditorSubmission.Batch>(reducer.acceptedSubmission(initial, next))
    val command = assertIs<DesignCommandV1>(submission.toWire())
    assertIs<UpdateEnvironmentMutationV1>(command.operations.single()).changes
  }
}
