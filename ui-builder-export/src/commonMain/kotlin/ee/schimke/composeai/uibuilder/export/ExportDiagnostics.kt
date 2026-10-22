package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.uibuilder.protocol.DiagnosticSeverityV1
import ee.schimke.composeai.uibuilder.protocol.ExportDiagnosticV1

/**
 * The codes of the diagnostics an emitted export carries beside its source
 * ([RecordFreeExport.Generated.Emitted.diagnostics]). A refusal stops the export; these do not, so
 * they say what the source alone cannot: a dependency it needs, a setting it leaves out, a layout
 * that throws when captured (compose-preview-server #1666).
 */
object ExportDiagnosticCodes {
  /**
   * A warning: the icon key names a vector that ships only in
   * [MaterialIconArtifacts.EXTENDED_ARTIFACT]. See [MaterialIconArtifacts].
   */
  const val MATERIAL_ICONS_EXTENDED_REQUIRED: String = "MATERIAL_ICONS_EXTENDED_REQUIRED"

  /**
   * An error: a layout outside the Glance Wear widget profile inside a widget. The file compiles,
   * and capturing the widget on Android throws.
   */
  const val OUTSIDE_WIDGET_PROFILE: String = "OUTSIDE_GLANCE_WEAR_WIDGET_PROFILE"

  /** A warning: a setting the design states that the generated call is not written with. */
  const val SETTING_NOT_WRITTEN: String = "SETTING_NOT_WRITTEN"

  /**
   * A warning: a widget whose container leaves its host frame to the device
   * ([WearWidgetHostFrame.Device]) imports its document from [WearWidgetHostFrame.DEVICE_ARTIFACT],
   * which the generated source compiles only beside.
   */
  const val WEAR_PREVIEW_RUNTIME_REQUIRED: String = "WEAR_PREVIEW_RUNTIME_REQUIRED"
}

/**
 * Which Material Icons artifact an exported icon key needs.
 *
 * [ScreenDocumentProjection.ICON_MEMBERS] spans `material-icons-core` and
 * `material-icons-extended`, because the canvas draws from both. A generated file naming an
 * extended-only vector (`Icons.Filled.PauseCircle`) compiles only where the consumer depends on
 * [EXTENDED_ARTIFACT]; a core-only classpath fails with `Unresolved reference`. Which artifact each
 * key ships in is recorded by `generateMaterialIconInventory` from the two jars themselves.
 */
object MaterialIconArtifacts {
  const val EXTENDED_ARTIFACT: String = "androidx.compose.material:material-icons-extended"

  /** Whether [key]'s vector ships only in [EXTENDED_ARTIFACT]; false for a key no table names. */
  fun requiresExtended(key: String): Boolean =
    key in ScreenDocumentProjection.ICON_MEMBERS && key !in GeneratedMaterialIconCoreKeys

  /**
   * The warning for [nodeId]'s [property] naming icon [key], or null when the key's vector is in
   * `material-icons-core` (or is not an icon key at all, which the emitters refuse by name).
   */
  fun diagnostic(
    nodeId: String,
    componentId: String,
    property: String,
    key: String,
  ): ExportDiagnosticV1? {
    if (!requiresExtended(key)) return null
    val member = ScreenDocumentProjection.ICON_MEMBERS.getValue(key)
    return ExportDiagnosticV1(
      severity = DiagnosticSeverityV1.WARNING,
      code = ExportDiagnosticCodes.MATERIAL_ICONS_EXTENDED_REQUIRED,
      message =
        "`$componentId` `$nodeId` sets `$property` to the icon key `$key`, written as " +
          "`Icons.$member`, which ships only in `$EXTENDED_ARTIFACT`: the generated source " +
          "compiles only where that dependency is declared, so add it, or pick an icon from " +
          "`material-icons-core`",
      nodeId = nodeId,
    )
  }
}
