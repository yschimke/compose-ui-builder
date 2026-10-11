package ee.schimke.composeai.buildlogic

/**
 * Which version each coordinate carries on a release where only some modules publish. Pure, so
 * `ComposeAiMavenPublishingPlugin` (POM project dependency versions) and `:bom` (constraints)
 * cannot disagree.
 */
object PublishedVersions {
  /**
   * The version [artifactId] carries. With a null [publishSet] everything takes [tagVersion]. A
   * module outside the set takes its last published version; a missing record is an error, never a
   * fallback, so no POM names an unuploaded coordinate.
   */
  fun resolve(
    artifactId: String,
    tagVersion: String,
    publishSet: Collection<String>?,
    manifestText: String,
  ): String {
    if (publishSet == null || artifactId in publishSet) return tagVersion
    return recordedVersion(artifactId, manifestText)
      ?: error(
        "'$artifactId' is not in the publish set and has no entry in publishing-manifest.json, " +
          "so there is no version it can safely carry. That file is not committed: the release " +
          "plan writes it from Maven Central (maven-publish-plan.sh --write-manifest). An empty " +
          "name here means a project path that resolves to no artifact id."
      )
  }

  /**
   * The version the manifest records for [artifactId], or null. A regex because build-logic has no
   * JSON dependency; `PublishedVersionsTest` pins the shape.
   */
  fun recordedVersion(artifactId: String, manifestText: String): String? =
    Regex("\"${Regex.escape(artifactId)}\"\\s*:\\s*\"([^\"]+)\"")
      .find(manifestText)
      ?.groupValues
      ?.get(1)

  /**
   * Parses the `-Pcomposeai.publishSet` property. Absent and empty must not be collapsed:
   *
   *  * absent (`null`): no plan ran, so publish everything (the `workflow_dispatch` recovery path).
   *  * present but empty (`""`): the plan found nothing to publish; publish nothing, BOM included.
   */
  fun parsePublishSet(property: String?): Set<String>? =
    property?.split(",")?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
}
