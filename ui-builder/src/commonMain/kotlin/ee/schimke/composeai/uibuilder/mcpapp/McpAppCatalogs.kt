package ee.schimke.composeai.uibuilder.mcpapp

/**
 * Which capability catalog a design opened in an MCP App host is edited against.
 *
 * The IntelliJ project session's rule (`OfflineCatalog.forSystem(document.catalogPin.systemId)`):
 * the design's `catalogPin.systemId` picks one of the catalogs **packaged with the editor**, and a
 * design pinned to any other is refused with its name rather than opened against the wrong
 * components. The web archive carries the same three capability files at its root —
 * `<systemId>-capabilities-v1.json` — and the MCP App reads them from the origin it loaded the
 * editor from, so resolution needs nothing but the editor's own assets: it works offline whenever
 * the editor itself loaded.
 *
 * A host may also name a [hostedBaseUrl] (`composeUiBuilderMcpApp.catalogBase` in the shell), a
 * place serving `<systemId>-capabilities-v1.json` for catalogs the archive does not package. It is
 * tried only for those, and only when set; the MCP App shell leaves it unset today, so an unknown
 * catalog is an error naming it.
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
