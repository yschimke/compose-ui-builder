package ee.schimke.composeai.uibuilder

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What a later read of a project's component library found, as the editor sees it.
 *
 * The names match the server's `component-drift` route so the two cannot describe the same fact
 * differently; this is the client's own view of those rows, parsed from the wire.
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
  /**
   * How the palette names the same symbol, taken from the wire rather than rebuilt here.
   *
   * The server owns that rule and already publishes it on every row of the component library; a
   * second copy of `"project/" + id` on this side would be a rule in two places that can disagree,
   * and the disagreement would show up as an Issues row selecting nothing.
   */
  val paletteId: String,
  val state: ComponentDriftState,
  val importedDigest: String = "",
  val currentDigest: String? = null,
)

/**
 * Drift, as problems the Issues panel can show — **supplied**, never fetched here.
 *
 * [UiBuilderEditorState.problems] is deliberately a pure function of the document: it reads nothing
 * else, which is what lets a caller cache it against the document alone. Drift is not a fact about
 * the document at all — it is a fact about a library on some other host, and answering it means a
 * network read. Folding that into `problems` would make a cached pure function do I/O and quietly
 * cost the panel its one useful property.
 *
 * So the shape is: the host fetches, parses into [ComponentDriftFinding]s, maps them here, and
 * merges the result. This function stays pure and testable, the wording lives in one place rather
 * than in whichever platform layer happened to do the fetch, and the panel keeps its cache.
 *
 * [ComponentDriftState.UNCHANGED] produces nothing. A panel that listed every component that is
 * *fine* would bury the ones that are not.
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
          // Separated from withdrawn on purpose: a branch that is unreachable today is not a
          // removal, and telling somebody their component was deleted when it was not is worse
          // than telling them it could not be read.
          ComponentDriftState.UNUSABLE ->
            "${finding.system} publishes `${finding.componentId}` but it cannot be read right now, " +
              "so whether this design has drifted from it is unknown"
        }
      EditorProblem(
        code = "COMPONENT_${finding.state.name}",
        message = message,
        componentId = finding.paletteId,
      )
    }

/**
 * The findings that still describe [document], for carrying across a rebuilt editor state.
 *
 * Every accepted edit — local or remote — rebuilds the editor from a new document, so findings
 * either survive that or the panel loses them on the next keystroke anyone in the session makes.
 * Surviving wholesale would be wrong in the other direction: the edit that arrived can be the very
 * re-import that resolves the drift, and a row saying a design has drifted from a symbol it now
 * matches is worse than no row.
 *
 * So a finding survives exactly while the thing it was computed about is unchanged: the component
 * is still declared and still records the digest the finding was taken against. A re-import writes
 * a new digest and drops the row; a removal drops it too. Both are then re-answered by the host's
 * next fetch, which is the only thing that can say what the library holds *now*.
 */
fun List<ComponentDriftFinding>.stillDescribing(
  document: UiBuilderDocument
): List<ComponentDriftFinding> = filter { finding ->
  document.components.importedDigestOf(finding.componentKey) == finding.importedDigest
}

private fun JsonObject.importedDigestOf(componentKey: String): String? = runCatching {
  get(componentKey)?.jsonObject?.get("source")?.jsonObject?.get("digest")
}
  .getOrNull()
  ?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
