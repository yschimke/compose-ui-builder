package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.uibuilder.EMBEDDED_REMOTE_LAYOUTS_JSON
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The released signatures of the Remote layout containers the builder's layout nodes export as
 * (`RemoteBox`, `RemoteColumn`, …), generated from the `remote-creation-compose` sources by
 * `scripts/remote-vocabulary/generate_remote_layouts.py`.
 *
 * `RemoteContentEmitter` reads them for one question a hand-kept list used to answer: may an empty
 * container be written without a content lambda? Only an overload that accepts the arguments being
 * written and takes no content, or defaults it, allows that.
 */
public object RemoteLayoutSignatures {
  @Serializable
  public data class Parameter(
    val name: String,
    val type: String,
    val optional: Boolean = false,
    val composableSlot: Boolean = false,
  )

  @Serializable public data class Overload(val parameters: List<Parameter>)

  @Serializable
  public data class Layout(val name: String, val `package`: String, val overloads: List<Overload>)

  @Serializable
  public data class Source(val group: String, val artifact: String, val version: String)

  @Serializable
  public data class File(val schema: String, val source: Source, val layouts: List<Layout>)

  private val file: File by lazy {
    Json { ignoreUnknownKeys = true }
      .decodeFromString(File.serializer(), EMBEDDED_REMOTE_LAYOUTS_JSON)
  }

  /** The release the signatures were generated from, e.g. `1.0.0-alpha21`. */
  public val version: String
    get() = file.source.version

  /** Every recorded layout, by its simple name. */
  public val layouts: Map<String, Layout> by lazy { file.layouts.associateBy { it.name } }

  /**
   * Whether `symbol(arguments)` with no trailing lambda resolves to a released overload: one that
   * names every argument, requires nothing else, and takes no content or defaults it.
   *
   * An unrecorded symbol answers `false`, so the emitter writes `{}`, which every container
   * accepts.
   */
  public fun contentLambdaOptional(symbol: String, arguments: Collection<String>): Boolean =
    layouts[symbol]?.overloads.orEmpty().any { overload ->
      val names = overload.parameters.map { it.name }.toSet()
      names.containsAll(arguments) &&
        overload.parameters.all { it.optional || it.name in arguments }
    }

  /**
   * Whether `symbol(arguments) { … }` resolves to a released overload: one that names every
   * argument, has a content slot, and requires nothing else.
   */
  public fun acceptsWithContent(symbol: String, arguments: Collection<String>): Boolean =
    layouts[symbol]?.overloads.orEmpty().any { overload ->
      val names = overload.parameters.map { it.name }.toSet()
      overload.parameters.any { it.composableSlot } &&
        names.containsAll(arguments) &&
        overload.parameters.all { it.optional || it.composableSlot || it.name in arguments }
    }
}
