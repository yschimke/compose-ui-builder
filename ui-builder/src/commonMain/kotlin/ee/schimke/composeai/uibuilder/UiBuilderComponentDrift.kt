package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.editor.EditorProblem
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorState
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What a later read of a project's component library found, as the editor sees it. Names match the
 * server's `component-drift` route.
 */
enum class ComponentDriftState {
  UNCHANGED,
  DRIFTED,
  WITHDRAWN,
  UNUSABLE,
}

/** One imported component's verdict, as read from `GET …/designs/{id}/component-drift`. */
data class ComponentDriftFinding(
  val componentKey: String,
  val system: String,
  val componentId: String,
  /** How the palette names the same symbol, taken from the wire; the server owns that rule. */
  val paletteId: String,
  val state: ComponentDriftState,
  val importedDigest: String = "",
  val currentDigest: String? = null,
)

/**
 * Drift, as problems the Issues panel can show: **supplied**, never fetched here.
 * [UiBuilderEditorState.problems] is a pure function of the document (and cached on it), while
 * drift needs a network read; the host fetches and merges this result.
 * [ComponentDriftState.UNCHANGED] produces nothing.
 */
fun componentDriftProblems(findings: List<ComponentDriftFinding>): List<EditorProblem> =
  findings
    .sortedBy { it.componentKey }
    .mapNotNull { finding ->
      val message =
        when (finding.state) {
          ComponentDriftState.UNCHANGED -> return@mapNotNull null
          // Never "your design is out of date". The design is not wrong — it draws what it has
          // always drawn, and taking the new version is a decision its owner makes.
          ComponentDriftState.DRIFTED ->
            "`${finding.componentId}` has changed in ${finding.system} since this design imported " +
              "it; this design still draws the version it imported"
          ComponentDriftState.WITHDRAWN ->
            "${finding.system} no longer publishes `${finding.componentId}`, which this design " +
              "imported; the copy it holds still draws"
          // Distinct from withdrawn: an unreachable branch is not a removal. Says nothing about the
          // project's contents, since the server returns this when the host has no coordinate for
          // the system at all and so read nothing.
          ComponentDriftState.UNUSABLE ->
            "`${finding.componentId}` cannot be checked against ${finding.system} right now, so " +
              "whether this design has drifted from it is unknown"
        }
      EditorProblem(
        code = "COMPONENT_${finding.state.name}",
        message = message,
        componentId = finding.paletteId,
        // None of these blocks export: the design keeps the bodies it imported.
        blocking = false,
      )
    }

/**
 * The findings that still describe [document], for carrying across a rebuilt editor state. A
 * finding survives only while its component is still declared with the same recorded source
 * (project, symbol id and digest); a re-import or removal drops it until the host's next fetch.
 */
fun List<ComponentDriftFinding>.stillDescribing(
  document: UiBuilderDocument
): List<ComponentDriftFinding> = filter { finding ->
  document.components.importedSourceOf(finding.componentKey) ==
    ImportedSource(finding.system, finding.componentId, finding.importedDigest)
}

/** The whole of a component's recorded `source`, since any part of it moving invalidates a row. */
private data class ImportedSource(
  val system: String,
  val componentId: String,
  val digest: String,
)

/**
 * The digest alone is not enough: the same content can be re-imported from another project or
 * symbol.
 */
private fun JsonObject.importedSourceOf(componentKey: String): ImportedSource? {
  val source =
    runCatching { get(componentKey)?.jsonObject?.get("source")?.jsonObject }.getOrNull()
      ?: return null
  return ImportedSource(
    system = source.text("system") ?: return null,
    componentId = source.text("componentId") ?: return null,
    digest = source.text("digest") ?: return null,
  )
}

private fun JsonObject.text(key: String): String? = runCatching {
  this[key]?.jsonPrimitive?.content
}
  .getOrNull()
