package ee.schimke.composeai.uibuilder.mcpapp

/**
 * Which capability catalog a design opened in an MCP App host is edited against. As in the IntelliJ
 * project session (`OfflineCatalog.forSystem(document.catalogPin.systemId)`), the design's
 * `catalogPin.systemId` selects the catalog. When a host supplies [hostedBaseUrl], its current
 * published capability is preferred. Packaged catalogs remain an offline fallback.
 *
 * A host may name a [hostedBaseUrl] (`composeUiBuilderMcpApp.catalogBase`) for catalogs the archive
 * does not package too; those require the host to be available.
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
    val hosted = hostedBaseUrl?.takeIf { it.isNotBlank() }
    val resolved =
      if (hosted != null) {
        try {
          Resolved(
            systemId,
            fetchAsset(hosted.trimEnd('/') + "/" + capabilitiesFile(systemId)),
            Source.Hosted,
          )
        } catch (error: Exception) {
          if (error is kotlin.coroutines.cancellation.CancellationException) throw error
          if (systemId !in BUNDLED) throw error
          Resolved(systemId, fetchAsset(capabilitiesFile(systemId)), Source.Bundled)
        }
      } else if (systemId in BUNDLED) {
        Resolved(systemId, fetchAsset(capabilitiesFile(systemId)), Source.Bundled)
      } else {
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
