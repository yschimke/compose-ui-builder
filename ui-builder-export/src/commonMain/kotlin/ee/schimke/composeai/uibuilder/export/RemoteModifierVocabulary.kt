package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.uibuilder.EMBEDDED_REMOTE_MODIFIERS_JSON
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The `RemoteModifier` calls a `remoteCall` modifier may name, generated from the released
 * `remote-creation-compose` sources by `scripts/remote-vocabulary/generate_remote_modifiers.py`.
 *
 * The released API is authoritative: this is its value-authorable surface, not a hand-written list,
 * so a new alpha is a regeneration rather than a contracts release. Each overload keeps only the
 * parameters a document value can be — a number, a dp, a colour, a boolean, an int or a string —
 * and an overload that needs anything else (a lambda, a transition, scroll state) is not here at
 * all.
 */
public object RemoteModifierVocabulary {
  /** The modifier type a document writes for one of these calls. */
  public const val TYPE: String = "remoteCall"

  @Serializable
  public data class Parameter(
    val name: String,
    val type: String,
    val kind: String,
    val nullable: Boolean = false,
    val optional: Boolean = false,
  )

  @Serializable public data class Overload(val parameters: List<Parameter>)

  @Serializable
  public data class Modifier(val name: String, val `package`: String, val overloads: List<Overload>)

  @Serializable
  public data class Source(val group: String, val artifact: String, val version: String)

  @Serializable
  public data class File(val schema: String, val source: Source, val modifiers: List<Modifier>)

  private val file: File by lazy {
    Json { ignoreUnknownKeys = true }
      .decodeFromString(File.serializer(), EMBEDDED_REMOTE_MODIFIERS_JSON)
  }

  /** The release the vocabulary was generated from, e.g. `1.0.0-alpha20`. */
  public val version: String
    get() = file.source.version

  public val modifiers: Map<String, Modifier> by lazy { file.modifiers.associateBy { it.name } }

  /**
   * The overload a call naming [arguments] resolves to: every argument is one of its parameters and
   * every parameter it requires is given. Where several qualify (`width(RemoteDp)`,
   * `width(RemoteFloat)`, `width(Int)` all take `width`), the fewest parameters wins, then dp over
   * a bare number — a design's sizes are dp — then a `Remote*` type over a plain one, because only
   * a remote type can take a state read or a computed value.
   */
  public fun resolve(name: String, arguments: Set<String>): Overload? =
    modifiers[name]
      ?.overloads
      ?.filter { overload ->
        val names = overload.parameters.map { it.name }.toSet()
        names.containsAll(arguments) &&
          overload.parameters.filterNot { it.optional }.all { it.name in arguments }
      }
      ?.minWithOrNull(
        compareBy<Overload>(
          { it.parameters.size },
          { overload -> overload.parameters.count { it.kind == "float" || it.kind == "int" } },
          { overload -> overload.parameters.count { !it.type.startsWith("Remote") } },
        )
      )
}
