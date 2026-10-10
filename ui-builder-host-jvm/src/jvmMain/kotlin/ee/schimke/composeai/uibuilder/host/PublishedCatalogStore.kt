package ee.schimke.composeai.uibuilder.host

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.export.CatalogOwnership
import ee.schimke.composeai.uibuilder.export.CatalogSeedTemplates
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import ee.schimke.composeai.uibuilder.service.PublishedUiBuilderCatalog
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Where a catalog publishes: its delivery repository and the `design-artifacts/<system>` branch.
 *
 * Coordinates only. The offline hosts carry no catalog definitions — each catalog owns and
 * publishes its own `ui-builder.json`, record and template documents, and the hosts read what it
 * published (UI_BUILDER_CATALOG_CUTOVER.md, "No copies"). The delivery system differs from the
 * catalog id for Wear: `wear-m3` is published as `wear-m3-catalog`.
 */
public data class PublishedCatalogLocation(
  val systemId: String,
  val repository: String,
  val deliverySystem: String,
) {
  /** The branch's raw-file root. */
  val baseUrl: String
    get() = "https://raw.githubusercontent.com/$repository/design-artifacts/$deliverySystem"

  public companion object {
    /** The catalogs [OfflineCatalog] offers, by id. */
    public val DEFAULTS: Map<String, PublishedCatalogLocation> =
      listOf(
          PublishedCatalogLocation("m3-catalog", "yschimke/m3-catalog-out", "m3-catalog"),
          PublishedCatalogLocation("wear-m3", "yschimke/wear-m3-catalog-out", "wear-m3-catalog"),
          PublishedCatalogLocation("remote-m3", "yschimke/remote-m3-catalog-out", "remote-m3"),
          PublishedCatalogLocation(
            "remote-widgets",
            "yschimke/remote-m3-catalog-out",
            "remote-widgets",
          ),
          PublishedCatalogLocation(
            "glimmer-catalog",
            "yschimke/glimmer-catalog-out",
            "glimmer-catalog",
          ),
          PublishedCatalogLocation("a2ui-catalog", "yschimke/a2ui-catalog-out", "a2ui-catalog"),
        )
        .associateBy { it.systemId }
  }
}

/**
 * Reads one published file. Answers null when the file does not exist there (404) and throws
 * [IOException] when the network did not answer, so a missing file and an offline machine stay
 * distinguishable.
 */
public fun interface CatalogFileFetcher {
  @Throws(IOException::class) public fun fetch(url: String): String?

  public companion object {
    /**
     * Reads catalogs from a local directory laid out as `<systemId>/ui-builder.json`,
     * `<systemId>/components.json` and `<systemId>/designs/<template>.json` (each JSON file may
     * also be gzipped, `.json.gz`), instead of from where they publish — for a catalog being
     * developed locally, and for tests, which must not reach the network. [locations] maps a
     * published URL back to the catalog it belongs to.
     */
    public fun directory(
      root: Path,
      locations: Map<String, PublishedCatalogLocation> = PublishedCatalogLocation.DEFAULTS,
    ): CatalogFileFetcher = CatalogFileFetcher { url ->
      val location =
        locations.values.firstOrNull { url.startsWith(it.baseUrl + "/") }
          ?: return@CatalogFileFetcher null
      val path = url.removePrefix(location.baseUrl + "/")
      val local =
        if (path.startsWith("ui-builder/designs/")) "designs/" + path.substringAfterLast('/')
        else path
      val dir = root.resolve(location.systemId)
      val plain = dir.resolve(local)
      val gzipped = dir.resolve("$local.gz")
      when {
        Files.isRegularFile(plain) -> Files.readString(plain)
        Files.isRegularFile(gzipped) ->
          java.util.zip.GZIPInputStream(Files.newInputStream(gzipped)).use {
            it.readBytes().decodeToString()
          }
        else -> null
      }
    }

    /** Plain HTTP GET with short timeouts; nothing is fetched until a catalog is opened. */
    public val HTTP: CatalogFileFetcher = CatalogFileFetcher { url ->
      val connection = URI(url).toURL().openConnection() as HttpURLConnection
      connection.connectTimeout = 10_000
      connection.readTimeout = 30_000
      try {
        when (val code = connection.responseCode) {
          HttpURLConnection.HTTP_OK ->
            connection.inputStream.use { it.readBytes().decodeToString() }
          HttpURLConnection.HTTP_NOT_FOUND -> null
          else -> throw IOException("$url answered HTTP $code")
        }
      } finally {
        connection.disconnect()
      }
    }
  }
}

/**
 * The catalogs an offline host authors against, fetched from where each catalog publishes and kept
 * in [cacheDir] so a host that has opened a catalog once can open it again without a network.
 *
 * A catalog is composed in this process from its own `ui-builder.json` and record, through the same
 * [PublishedUiBuilderCatalog] a preview server uses, so an offline design is authored against
 * exactly what a server owning that catalog would serve. A catalog that has never been fetched and
 * cannot be reached is [Load.Unavailable] with a reason the host shows — never a stale copy
 * packaged into this build.
 */
public class PublishedCatalogStore(
  private val cacheDir: Path,
  private val fetcher: CatalogFileFetcher = CatalogFileFetcher.HTTP,
  private val locations: Map<String, PublishedCatalogLocation> = PublishedCatalogLocation.DEFAULTS,
) {

  /** What opening a catalog produced. */
  public sealed interface Load {
    public val systemId: String

    public data class Loaded(
      override val systemId: String,
      /** The composed [CatalogCapabilityV1], as JSON — what the editor parses. */
      val capabilityJson: String,
      val templates: CatalogSeedTemplates,
      /** True when the network could not be reached and the cached copy was used. */
      val fromCache: Boolean,
    ) : Load

    public data class Unavailable(override val systemId: String, val reason: String) : Load
  }

  private val loaded = mutableMapOf<String, Load>()

  /** [systemId], fetched once per store and composed; later calls answer the same result. */
  @Synchronized
  public fun load(systemId: String): Load = loaded.getOrPut(systemId) { fetchAndCompose(systemId) }

  /** [systemId] if this store has already opened it, without fetching anything. */
  @Synchronized public fun peek(systemId: String): Load? = loaded[systemId]

  private fun fetchAndCompose(systemId: String): Load {
    val location =
      locations[systemId]
        ?: return Load.Unavailable(systemId, "no published location is known for '$systemId'")
    val dir = cacheDir.resolve(systemId)
    val files =
      try {
        fetchAll(location)?.also { write(dir, it) }
      } catch (offline: IOException) {
        read(dir)?.let {
          return compose(systemId, it, fromCache = true)
        }
        return Load.Unavailable(
          systemId,
          "catalog '$systemId' has not been fetched yet and ${location.baseUrl} could not be " +
            "reached (${offline.message ?: offline::class.simpleName}). Open it once with a " +
            "network connection and it is kept for offline use.",
        )
      }
        ?: return Load.Unavailable(
          systemId,
          "${location.baseUrl} publishes no ui-builder.json for '$systemId'",
        )
    return compose(systemId, files, fromCache = false)
  }

  /** The branch's files, or null when it publishes no `ui-builder.json`. */
  private fun fetchAll(location: PublishedCatalogLocation): Map<String, String>? {
    val published = fetcher.fetch("${location.baseUrl}/$UI_BUILDER_JSON") ?: return null
    val files = linkedMapOf(UI_BUILDER_JSON to published)
    fetcher.fetch("${location.baseUrl}/$COMPONENTS_JSON")?.let { files[COMPONENTS_JSON] = it }
    for (path in templatePaths(published)) {
      fetcher.fetch("${location.baseUrl}/$path")?.let { files[path] = it }
    }
    return files
  }

  private fun compose(systemId: String, files: Map<String, String>, fromCache: Boolean): Load {
    val published = files.getValue(UI_BUILDER_JSON)
    val record =
      files[COMPONENTS_JSON]?.let {
        runCatching { json.decodeFromString(ComponentRecordFile.serializer(), it) }
          .getOrElse { failure ->
            return Load.Unavailable(systemId, "$COMPONENTS_JSON did not parse: ${failure.message}")
          }
      }
    val composed =
      when (val result = PublishedUiBuilderCatalog.compose(published, record, EXPORTS)) {
        is PublishedUiBuilderCatalog.Result.Composed -> result.catalog
        is PublishedUiBuilderCatalog.Result.Unusable ->
          return Load.Unavailable(systemId, "published catalog does not compose: ${result.reason}")
      }
    // Served exactly as a preview server owning the catalog serves it: the executor adds the
    // builder's own vocabulary (`layout/`, `shape/`, `asset/`) a catalog does not declare.
    val catalog = runCatching {
      CurrentM3UiBuilderCatalogExecutor.Builder()
        .also {
          it.catalogSystemIds = setOf(systemId)
          it.published = mapOf(systemId to composed)
          it.catalogOwnership = CatalogOwnership.of(setOf(systemId))
        }
        .build()
        .listCatalogs()
        .single { it.benchmark.catalogSystemId == systemId }
    }
      .getOrElse {
        return Load.Unavailable(systemId, "published catalog cannot be served: ${it.message}")
      }
    val templates =
      when (
        val read = CatalogSeedTemplates.read(systemId, templatePaths(published)) { files[it] }
      ) {
        is CatalogSeedTemplates.Result.Read -> read.templates
        is CatalogSeedTemplates.Result.Unusable ->
          return Load.Unavailable(systemId, "published templates do not read: ${read.reason}")
      }
    return Load.Loaded(
      systemId = systemId,
      capabilityJson = json.encodeToString(CatalogCapabilityV1.serializer(), catalog),
      templates = templates,
      fromCache = fromCache,
    )
  }

  private fun write(dir: Path, files: Map<String, String>) {
    // A cache that cannot be written still serves this run; it only costs the next offline open.
    runCatching {
      for ((path, text) in files) {
        val target = dir.resolve(path)
        Files.createDirectories(target.parent)
        Files.writeString(target, text)
      }
    }
  }

  private fun read(dir: Path): Map<String, String>? {
    val published = dir.resolve(UI_BUILDER_JSON).takeIf(Files::isRegularFile) ?: return null
    val text = Files.readString(published)
    val files = linkedMapOf(UI_BUILDER_JSON to text)
    for (path in listOf(COMPONENTS_JSON) + templatePaths(text)) {
      dir.resolve(path).takeIf(Files::isRegularFile)?.let { files[path] = Files.readString(it) }
    }
    return files
  }

  public companion object {
    private const val UI_BUILDER_JSON = "ui-builder.json"
    private const val COMPONENTS_JSON = "components.json"
    private val json = Json { ignoreUnknownKeys = true }

    // What an offline host offers, all in process: Compose source, and SVG and PNG drawn through
    // Skia (DesktopExportHost).
    private val EXPORTS =
      ExportCapabilitiesV1.Builder()
        .also {
          it.composeCode = true
          it.svg = true
          it.png = true
        }
        .build()

    /** The paths a published `ui-builder.json` names under `statusSemantics.templates`. */
    internal fun templatePaths(published: String): List<String> = runCatching {
      ((json.parseToJsonElement(published) as? JsonObject)?.get("statusSemantics") as? JsonObject)
        ?.get("templates")
        .let { it as? JsonArray }
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .orEmpty()
    }
      .getOrDefault(emptyList())

    /** `$XDG_CACHE_HOME/compose-ui-builder/catalogs`, or `~/.cache/…` without it. */
    public fun defaultCacheDir(): Path =
      (System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of)
          ?: Path.of(System.getProperty("user.home"), ".cache"))
        .resolve("compose-ui-builder")
        .resolve("catalogs")

    /**
     * The system property naming a local catalog directory ([CatalogFileFetcher.directory]) to read
     * instead of the network. Set for the hosts' own tests by their Gradle builds.
     */
    public const val CATALOG_DIR_PROPERTY: String = "compose.uiBuilder.catalogDir"

    /**
     * The store the offline hosts share: from where each catalog publishes, cached under
     * [defaultCacheDir]; or, when [CATALOG_DIR_PROPERTY] names a directory, from that directory
     * with a throwaway cache.
     */
    @Volatile
    public var shared: PublishedCatalogStore =
      System.getProperty(CATALOG_DIR_PROPERTY)
        ?.takeIf { it.isNotBlank() }
        ?.let { dir ->
          PublishedCatalogStore(
            Files.createTempDirectory("ui-builder-catalogs"),
            CatalogFileFetcher.directory(Path.of(dir)),
          )
        } ?: PublishedCatalogStore(defaultCacheDir())
  }
}
