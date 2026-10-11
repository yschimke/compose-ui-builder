package ee.schimke.composeai.uibuilder.mcpapp

/**
 * Which capability catalog a design opened in an MCP App host is edited against. As in the IntelliJ
 * project session (`OfflineCatalog.forSystem(document.catalogPin.systemId)`), the design's
 * `catalogPin.systemId` picks one of the catalogs **packaged with the editor**
 * (`<systemId>-capabilities-v1.json` at the archive root, loaded from the editor's origin, so it
 * works offline); any other pin is refused by name.
 *
 * A host may name a [hostedBaseUrl] (`composeUiBuilderMcpApp.catalogBase`) serving catalogs the
 * archive doesn't package, tried only for those.
 */
class McpAppCatalogs(
  private val fetchAsset: suspend (String) -> String,
  private val hostedBaseUrl: String? = null,
) {
  /** Where a catalog's capability JSON came from. */
  enum class Source {
    Bundled,
    Hosted,
  }

  data class Resolved(val systemId: String, val capabilitiesJson: String, val source: Source)

  private val cache = mutableMapOf<String, Resolved>()

  suspend fun resolve(systemId: String?): Resolved {
    require(!systemId.isNullOrBlank()) { "the design names no catalog (catalogPin.systemId)" }
    cache[systemId]?.let {
      return it
    }
    require(SAFE_ID.matches(systemId)) { "the design names an invalid catalog id '$systemId'" }
    val resolved =
      when {
        systemId in BUNDLED ->
          Resolved(systemId, fetchAsset(capabilitiesFile(systemId)), Source.Bundled)
        !hostedBaseUrl.isNullOrBlank() ->
          Resolved(
            systemId,
            fetchAsset(hostedBaseUrl.trimEnd('/') + "/" + capabilitiesFile(systemId)),
            Source.Hosted,
          )
        else ->
          throw IllegalArgumentException(
            "the design is pinned to catalog '$systemId', which this editor does not package " +
              "(it packages ${BUNDLED.sorted().joinToString()}) and no hosted catalog is configured"
          )
      }
    cache[systemId] = resolved
    return resolved
  }

  companion object {
    /** The catalogs the web archive packages, as `OfflineCatalog` lists them. */
    val BUNDLED: Set<String> = setOf("m3-catalog", "wear-m3", "remote-m3")

    fun capabilitiesFile(systemId: String): String = "$systemId-capabilities-v1.json"

    private val SAFE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
  }
}
