package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * A design holds the bodies of its imported components, so nothing in it can tell the library
 * moved; the Issues panel reports host-fetched findings instead.
 */
class ComponentDriftProblemsTest {
  private fun finding(
    key: String,
    state: ComponentDriftState,
    componentId: String = key,
    system: String = "m3-catalog",
  ) =
    ComponentDriftFinding(
      componentKey = key,
      system = system,
      componentId = componentId,
      paletteId = "project/$componentId",
      state = state,
      importedDigest = "sha256:imported",
      currentDigest = if (state == ComponentDriftState.DRIFTED) "sha256:current" else null,
    )

  @Test
  fun `a component that has not moved is not a row`() {
    // A panel that listed every component that is *fine* would bury the ones that are not, and the
    // library answers for every imported component precisely so the client need not ask per symbol.
    assertEquals(
      emptyList(),
      componentDriftProblems(
        listOf(
          finding("contribution-cell", ComponentDriftState.UNCHANGED),
          finding("streak-badge", ComponentDriftState.UNCHANGED),
        )
      ),
    )
  }

  @Test
  fun `nothing fetched is nothing said`() {
    assertEquals(emptyList(), componentDriftProblems(emptyList()))
  }

  @Test
  fun `a drifted component says the design still draws what it imported`() {
    val problem =
      componentDriftProblems(listOf(finding("contribution-cell", ComponentDriftState.DRIFTED)))
        .single()
    assertEquals("COMPONENT_DRIFTED", problem.code)
    assertEquals("project/contribution-cell", problem.componentId)
    // Never "your design is out of date": the design is not wrong, and taking the new version is a
    // decision its owner makes. The row has to survive somebody who does not want the new version.
    assertTrue(problem.message.contains("still draws the version it imported"), problem.message)
    assertTrue(problem.message.contains("m3-catalog"), problem.message)
  }

  @Test
  fun `withdrawn and unusable do not read the same`() {
    // An unreachable branch is not a deletion; claiming removal would send people looking for a
    // symbol that still exists.
    val withdrawn =
      componentDriftProblems(listOf(finding("streak-badge", ComponentDriftState.WITHDRAWN)))
        .single()
    val unusable =
      componentDriftProblems(listOf(finding("streak-badge", ComponentDriftState.UNUSABLE))).single()
    assertEquals("COMPONENT_WITHDRAWN", withdrawn.code)
    assertEquals("COMPONENT_UNUSABLE", unusable.code)
    assertTrue(withdrawn.message.contains("no longer publishes"), withdrawn.message)
    assertTrue(unusable.message.contains("cannot be checked against"), unusable.message)
    // Says nothing about the project's contents: the server returns this when the host has no
    // coordinate for the system and read nothing.
    assertTrue(!unusable.message.contains("publishes"), unusable.message)
    assertTrue(unusable.message.contains("unknown"), unusable.message)
  }

  @Test
  fun `every row names a node-less problem against the symbol on the palette`() {
    // No `nodeId`: drift is a fact about the component, not about any one placement of it, and a
    // row that selected the first placement would suggest that placement is the broken thing.
    val problems =
      componentDriftProblems(
        listOf(
          finding("streak-badge", ComponentDriftState.WITHDRAWN),
          finding("contribution-cell", ComponentDriftState.DRIFTED),
        )
      )
    assertTrue(problems.all { it.nodeId == null }, problems.toString())
    assertEquals(
      listOf("project/contribution-cell", "project/streak-badge"),
      problems.map { it.componentId },
    )
  }

  @Test
  fun `rows come out in key order whatever order the wire used`() {
    // The panel is a list somebody reads twice — once now and once after a re-fetch — and a stable
    // order is what makes the second reading a comparison rather than a re-read.
    val ordered =
      componentDriftProblems(
        listOf(
          finding("streak-badge", ComponentDriftState.DRIFTED),
          finding("activity-row", ComponentDriftState.WITHDRAWN),
          finding("contribution-cell", ComponentDriftState.UNCHANGED),
          finding("badge-shelf", ComponentDriftState.UNUSABLE),
        )
      )
    assertEquals(
      listOf("project/activity-row", "project/badge-shelf", "project/streak-badge"),
      ordered.map { it.componentId },
    )
  }

  @Test
  fun `the palette id comes from the wire rather than being rebuilt here`() {
    // The finding's own server-published `paletteId` lands on the problem, so the row selects the
    // right component.
    val problem =
      componentDriftProblems(
          listOf(
            finding("contribution-cell", ComponentDriftState.DRIFTED)
              .copy(paletteId = "project-library/contribution-cell")
          )
        )
        .single()
    assertEquals("project-library/contribution-cell", problem.componentId)
  }

  private fun documentDeclaring(components: JsonObject) =
    UiBuilderDocument(
      schema = "compose-ui-builder-design/v1",
      id = "d1",
      title = "Home",
      revision = 1,
      catalogPin = JsonObject(emptyMap()),
      environment = JsonObject(emptyMap()),
      stateVariables = JsonObject(emptyMap()),
      roots = emptyList(),
      nodes = emptyMap(),
      components = components,
    )

  private fun declared(
    digest: String?,
    system: String = "m3-catalog",
    componentId: String = "contribution-cell",
  ): JsonObject =
    Json.parseToJsonElement(
        """
        {
          "contribution-cell": {
            "name": "ContributionCell",
            "root": "n1"
            ${if (digest == null) "" else """,
            "source": {
              "system": "$system",
              "componentId": "$componentId",
              "digest": "$digest"
            }"""}
          }
        }
        """
      )
      .jsonObject

  @Test
  fun `a finding survives an edit that left its component alone`() {
    // Every accepted edit rebuilds the editor from a new document. Dropping findings there would
    // lose the panel's drift rows on the next keystroke anybody in the session made.
    val findings = listOf(finding("contribution-cell", ComponentDriftState.DRIFTED))
    assertEquals(
      findings,
      findings.stillDescribing(documentDeclaring(declared("sha256:imported"))),
    )
  }

  @Test
  fun `a re-import drops the row it answered`() {
    // The arriving edit may be the re-import that resolves the drift; only the host's next fetch
    // can say what the library holds now.
    assertEquals(
      emptyList(),
      listOf(finding("contribution-cell", ComponentDriftState.DRIFTED))
        .stillDescribing(documentDeclaring(declared("sha256:reimported"))),
    )
  }

  @Test
  fun `drift rows are never presented as export blockers`() {
    // Drift rows aren't export refusals: the design exports the bodies it holds.
    val problems =
      componentDriftProblems(
        listOf(
          finding("streak-badge", ComponentDriftState.DRIFTED),
          finding("activity-row", ComponentDriftState.WITHDRAWN),
          finding("badge-shelf", ComponentDriftState.UNUSABLE),
        )
      )
    assertTrue(problems.none { it.blocking }, problems.toString())
  }

  @Test
  fun `a key re-imported from elsewhere drops its row even when the content is identical`() {
    // The digest alone doesn't identify a finding: two projects can publish identical content, so a
    // re-pointed key must drop the row.
    val drifted = listOf(finding("contribution-cell", ComponentDriftState.DRIFTED))
    assertEquals(
      emptyList(),
      drifted.stillDescribing(documentDeclaring(declared("sha256:imported", system = "wear-m3"))),
    )
    assertEquals(
      emptyList(),
      drifted.stillDescribing(
        documentDeclaring(declared("sha256:imported", componentId = "contribution-tile"))
      ),
    )
  }

  @Test
  fun `a component that is gone, or was never imported, keeps no verdict`() {
    val drifted = listOf(finding("contribution-cell", ComponentDriftState.DRIFTED))
    assertEquals(emptyList(), drifted.stillDescribing(documentDeclaring(JsonObject(emptyMap()))))
    // Declared but authored here rather than imported: there is no source to have drifted from.
    assertEquals(emptyList(), drifted.stillDescribing(documentDeclaring(declared(null))))
  }
}
