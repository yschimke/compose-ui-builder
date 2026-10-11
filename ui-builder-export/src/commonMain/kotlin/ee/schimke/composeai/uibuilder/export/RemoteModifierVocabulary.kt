package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.uibuilder.EMBEDDED_REMOTE_MODIFIERS_JSON
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The `RemoteModifier` calls a `remoteCall` modifier may name, generated from the released
 * `remote-creation-compose` sources by `scripts/remote-vocabulary/generate_remote_modifiers.py`.
 * Only overloads whose parameters are document values (number, dp, colour, boolean, int, string).
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

  /** `semantics { … }`: accessibility, its receiver lambda's settable values declared by hand. */
  public const val SEMANTICS: String = "semantics"

  /** The `androidx.compose.ui.semantics.Role`s the player maps to an accessible role. */
  public val ROLES: List<String> =
    listOf("Button", "Checkbox", "Switch", "RadioButton", "Tab", "Image", "DropdownList")

  /**
   * Calls whose released signature takes a receiver lambda, declared by hand; the emitter writes
   * the lambda. `semantics` is accessibility, not interaction; `clear` writes
   * `clearAndSetSemantics`.
   */
  private val handDeclared: List<Modifier> =
    listOf(
      Modifier(
        SEMANTICS,
        "androidx.compose.remote.creation.compose.modifier",
        listOf(
          Overload(
            listOf(
              Parameter("contentDescription", "RemoteString", "string", optional = true),
              Parameter("stateDescription", "RemoteString", "string", optional = true),
              Parameter("role", "Role", "string", optional = true),
              Parameter("enabled", "Boolean", "bool", optional = true),
              Parameter("mergeDescendants", "Boolean", "bool", optional = true),
              Parameter("clear", "Boolean", "bool", optional = true),
            )
          )
        ),
      )
    )

  public val modifiers: Map<String, Modifier> by lazy {
    (file.modifiers + handDeclared).associateBy { it.name }
  }

  /**
   * The overload a call naming [arguments] resolves to: all arguments are parameters and all
   * required parameters are given. Ties prefer fewest parameters, then dp over a bare number, then
   * a
   * `Remote*` type (which can take state or a computed value).
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
