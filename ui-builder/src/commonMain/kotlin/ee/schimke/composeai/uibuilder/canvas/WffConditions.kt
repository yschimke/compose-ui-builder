package ee.schimke.composeai.uibuilder.canvas

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlExporter
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlImporter

/**
 * The branch of [condition] the watch would draw at [preview]: the first `<Compare>` whose
 * expression (the `<Expression>` its `expression` names) is true, else the `<Default>`, else
 * nothing. Where a compare reads a source the preview cannot evaluate, and nothing before it was
 * true, the first branch is drawn, as the preview did before conditions were evaluated, rather than
 * a branch a real watch might not take.
 */
internal fun wffConditionBranch(
  document: UiBuilderDocument,
  condition: UiBuilderNode,
  preview: WffPreviewContext,
): String? {
  val all = condition.slots["branches"].orEmpty()
  // `<Compare expression="name">` names an `<Expression>` of the condition's `<Expressions>`,
  // which is held raw; a name with no definition is read as the expression itself.
  val named =
    all
      .mapNotNull { document.nodes[it] }
      .filter { it.componentId == WatchFaceXmlExporter.RAW }
      .flatMap { WatchFaceXmlImporter.conditionExpressions(it.string("xml")).entries }
      .associate { it.key to it.value }
  val branches = all.filter { document.nodes[it]?.componentId != WatchFaceXmlExporter.RAW }
  for (id in branches) {
    val branch = document.nodes[id] ?: continue
    when (branch.componentId) {
      WatchFaceXmlExporter.COMPARE -> {
        val reference = branch.string("expression")
        val value =
          evaluateWffExpression(named[reference] ?: reference, preview) ?: return branches.first()
        if (value.truthy()) return id
      }
      WatchFaceXmlExporter.DEFAULT -> return id
    }
  }
  return null
}

/**
 * Each user setting's id to the value it holds in [this]: its `defaultValue`, which a preview's
 * configuration picker has already replaced with the chosen option on the copy being drawn. What
 * `[CONFIGURATION.<id>]` reads.
 */
internal fun UiBuilderDocument.wffConfigurationValues(): Map<String, String> =
  wffConfigurations().associate { it.id to it.defaultValue }
