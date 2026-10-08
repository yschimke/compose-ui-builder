package ee.schimke.composeai.uibuilder.editor

import ee.schimke.composeai.uibuilder.capability.DesignToken
import ee.schimke.composeai.uibuilder.capability.DesignTokenKind
import ee.schimke.composeai.uibuilder.export.PropertyValueKinds
import kotlin.math.round
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Design tokens in and out of the builder, in the formats other tools speak.
 *
 * - **DTCG**, the W3C Design Tokens Community Group format: what Style Dictionary, Tokens Studio,
 *   Penpot and Figma's variable export converge on, and the one this writes. Groups are nested
 *   objects, so a token's dotted id is its path; a token is an object with `$value`, typed by
 *   `$type` on it or on a group above it; and what the format has no field for — a token's
 *   bindings, its range, its nullable default — rides in `$extensions` under [DTCG_EXTENSION],
 *   which other tools carry through untouched.
 * - **Material Theme Builder**'s JSON export, read only: a Material 3 colour scheme whose role
 *   names are the `color.*` token ids a Material catalog declares.
 *
 * See [`UI_BUILDER_DESIGN_TOKENS.md`](../../../../../../docs/design/UI_BUILDER_DESIGN_TOKENS.md).
 */
const val DTCG_EXTENSION: String = "ee.schimke.compose-ui-builder"

/** What an export wrote, and what it could not. */
data class DesignTokenExport(
  /** The DTCG document. */
  val json: JsonObject,
  /** Tokens set in this design, so written with a `$value`. */
  val written: List<String>,
  /**
   * Tokens left out: unset ones, because DTCG has no spelling for "the design system decides" — a
   * token without `$value` is not a token — and mixed ones, which hold no single value to write.
   */
  val skipped: List<String>,
) {
  fun text(): String = PRETTY.encodeToString(JsonObject.serializer(), json) + "\n"
}

/** What a file said, read against this catalog's tokens, before anything is applied. */
data class DesignTokenImport(
  /** Which format it was read as: `DTCG` or `Material Theme Builder (<scheme>)`. */
  val format: String,
  /** Token id to the value to apply, in the spelling [DesignToken.parse] reads. */
  val values: Map<String, String>,
  /** Token paths in the file this catalog declares no token for; left alone. */
  val unknown: List<String>,
  /** Tokens the catalog declares whose value it cannot take, with why. */
  val invalid: List<Pair<String, String>>,
)

/**
 * The design's tokens as a DTCG document. Only set tokens are written; see
 * [DesignTokenExport.skipped].
 */
fun exportDesignTokens(rows: List<EditorDesignTokenRow>): DesignTokenExport {
  val root = linkedMapOf<String, Any>()
  val written = mutableListOf<String>()
  val skipped = mutableListOf<String>()
  rows.forEach { row ->
    val set = row.value as? DesignTokenValue.Set
    if (set == null) {
      skipped += row.token.id
      return@forEach
    }
    var group = root
    val path = row.token.id.split('.')
    path.dropLast(1).forEach { segment ->
      @Suppress("UNCHECKED_CAST")
      group = group.getOrPut(segment) { linkedMapOf<String, Any>() } as LinkedHashMap<String, Any>
    }
    group[path.last()] = row.token.dtcg(set.value)
    written += row.token.id
  }
  return DesignTokenExport(root.toJson() as JsonObject, written, skipped)
}

private fun DesignToken.dtcg(value: JsonPrimitive): JsonObject {
  val dimension = kind == DesignTokenKind.Number && bindings.any { it.property.endsWith("Dp") }
  val encoded: JsonElement =
    when (kind) {
      DesignTokenKind.Color -> {
        val content = value.contentOrNull.orEmpty()
        // A theme role is a reference to another colour of the scheme, which is what a DTCG alias
        // is: `{color.primary}`. A reader that has the scheme resolves it; this one reads it back
        // as the role.
        if (content.startsWith("#")) dtcgColour(content) ?: JsonPrimitive(content)
        else JsonPrimitive("{color.$content}")
      }
      DesignTokenKind.Number -> {
        val number = value.doubleOrNull ?: 0.0
        // DTCG's units are px and rem. A dp is the density-independent pixel the px unit stands
        // for in Android tooling's DTCG exports, and the extension says so.
        if (dimension)
          JsonObject(mapOf("value" to tidyNumber(number), "unit" to JsonPrimitive("px")))
        else tidyNumber(number)
      }
    }
  val extension =
    buildMap<String, JsonElement> {
      put("default", default ?: JsonNull)
      minimum?.let { put("minimum", tidyNumber(it)) }
      maximum?.let { put("maximum", tidyNumber(it)) }
      if (integer) put("integer", JsonPrimitive(true))
      if (dimension) put("unit", JsonPrimitive("dp"))
      val (theme, components) = bindings.partition { it.theme }
      listOf("theme" to theme, "components" to components).forEach { (key, list) ->
        if (list.isNotEmpty())
          put(
            key,
            JsonArray(
              list.map {
                JsonObject(
                  mapOf(
                    "component" to JsonPrimitive(it.componentId),
                    "property" to JsonPrimitive(it.property),
                  )
                )
              }
            ),
          )
      }
    }
  return JsonObject(
    buildMap {
      put("\$type", JsonPrimitive(if (dimension) "dimension" else kind.wire))
      put("\$value", encoded)
      put("\$description", JsonPrimitive(label))
      put("\$extensions", JsonObject(mapOf(DTCG_EXTENSION to JsonObject(extension))))
    }
  )
}

/**
 * Read [text] — a DTCG document or a Material Theme Builder export — against [tokens]. Nothing is
 * applied: the result says what would be, and the caller hands [DesignTokenImport.values] to
 * [UiBuilderEditorEvent.ImportDesignTokens]. A Theme Builder file carries a light and a dark
 * scheme; [scheme] picks one, falling back to whichever it has.
 */
fun importDesignTokens(
  text: String,
  tokens: List<DesignToken>,
  scheme: String = "light",
): Result<DesignTokenImport> = runCatching {
  val root =
    runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
      ?: error("This is not a JSON object")
  val byId = tokens.associateBy(DesignToken::id)
  val schemes = root["schemes"] as? JsonObject
  if (schemes != null) themeBuilder(schemes, byId, scheme) else dtcg(root, byId)
}

/** One colour scheme out of a Material Theme Builder export. */
data class ThemeBuilderScheme(
  /** The scheme's name in the file: `light`, `dark`, `light-medium-contrast`, … */
  val name: String,
  /** Role to `#RRGGBB`, for every role whose value is a colour literal. */
  val roles: Map<String, String>,
  /** The scheme names the file carries, for a light/dark choice. */
  val available: List<String>,
)

/**
 * The [preferred] scheme of a Material Theme Builder export, falling back to the first the file
 * has. Fails when [text] is not one; see [isThemeBuilderExport].
 */
fun readThemeBuilderScheme(text: String, preferred: String = "light"): Result<ThemeBuilderScheme> =
  runCatching {
    val root =
      runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
        ?: error("This is not a JSON object")
    val schemes =
      root["schemes"] as? JsonObject
        ?: error("This is not a Material Theme Builder export: it has no \"schemes\"")
    val (name, roles) = themeBuilderScheme(schemes, preferred)
    ThemeBuilderScheme(
      name,
      roles.entries
        .mapNotNull { (role, value) ->
          (value as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isArgbColor() }
            ?.let { role to it.uppercase() }
        }
        .toMap(),
      schemes.entries.filter { it.value is JsonObject }.map { it.key },
    )
  }

private fun themeBuilderScheme(schemes: JsonObject, preferred: String): Pair<String, JsonObject> =
  (schemes[preferred] as? JsonObject)?.let { preferred to it }
    ?: schemes.entries.firstNotNullOfOrNull { (key, value) ->
      (value as? JsonObject)?.let { key to it }
    }
    ?: error("The Theme Builder file has no colour scheme")

private fun themeBuilder(
  schemes: JsonObject,
  byId: Map<String, DesignToken>,
  preferred: String,
): DesignTokenImport {
  val (name, roles) = themeBuilderScheme(schemes, preferred)
  val values = linkedMapOf<String, String>()
  val unknown = mutableListOf<String>()
  val invalid = mutableListOf<Pair<String, String>>()
  roles.forEach { (role, element) ->
    val id = "color.$role"
    val token = byId[id]
    val hex = (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    when {
      token == null || token.kind != DesignTokenKind.Color -> unknown += id
      hex == null -> invalid += id to "is not a colour"
      else ->
        token
          .parse(hex)
          .fold({ values[id] = token.spelling(it) }, { invalid += id to it.message.orEmpty() })
    }
  }
  return DesignTokenImport("Material Theme Builder ($name)", values, unknown, invalid)
}

private fun dtcg(root: JsonObject, byId: Map<String, DesignToken>): DesignTokenImport {
  // Every token in the file, by path, with the `$type` it inherits from its groups.
  val entries = linkedMapOf<String, Pair<String?, JsonElement>>()
  fun walk(group: JsonObject, path: List<String>, inherited: String?) {
    val type = (group["\$type"] as? JsonPrimitive)?.contentOrNull ?: inherited
    if ("\$value" in group) {
      entries[path.joinToString(".")] = type to group.getValue("\$value")
      return
    }
    group.forEach { (key, child) ->
      if (!key.startsWith("$") && child is JsonObject) walk(child, path + key, type)
    }
  }
  walk(root, emptyList(), null)
  if (entries.isEmpty()) error("This file holds no design tokens")

  fun resolve(element: JsonElement, depth: Int = 0): JsonElement {
    val alias = (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    if (alias == null || !alias.startsWith("{") || !alias.endsWith("}") || depth > 8) return element
    val target = alias.removeSurrounding("{", "}")
    // `{color.primary}` names a role of the theme when the role is one the canvas draws — which
    // is how this writer spells a role, and how a scheme-aware file spells "use the primary".
    val role = target.removePrefix("color.").takeIf { target.startsWith("color.") }
    if (role != null && role in PropertyValueKinds.CANVAS_COLOR_TOKENS) return JsonPrimitive(role)
    return entries[target]?.second?.let { resolve(it, depth + 1) } ?: element
  }

  val values = linkedMapOf<String, String>()
  val unknown = mutableListOf<String>()
  val invalid = mutableListOf<Pair<String, String>>()
  entries.forEach { (id, entry) ->
    val token = byId[id] ?: return@forEach run { unknown += id }
    val literal =
      dtcgLiteral(token, resolve(entry.second))
        ?: return@forEach run { invalid += id to "has a value this builder cannot read" }
    token
      .parse(literal)
      .fold({ values[id] = token.spelling(it) }, { invalid += id to it.message.orEmpty() })
  }
  return DesignTokenImport("DTCG", values, unknown, invalid)
}

/** A DTCG `$value` in the spelling [DesignToken.parse] reads, or null when it is not one. */
private fun dtcgLiteral(token: DesignToken, value: JsonElement): String? =
  when (token.kind) {
    DesignTokenKind.Color ->
      when (value) {
        is JsonPrimitive -> value.takeIf { it.isString }?.contentOrNull
        is JsonObject -> colourFromObject(value)
        else -> null
      }
    DesignTokenKind.Number ->
      when (value) {
        is JsonPrimitive ->
          if (value.isString) value.content.removeSuffix("px").removeSuffix("dp").trim()
          else value.doubleOrNull?.let(::formatTunableValue)
        // `rem` is relative to a root font size a watch or phone screen does not have.
        is JsonObject ->
          (value["unit"] as? JsonPrimitive)
            ?.contentOrNull
            ?.takeIf { it == "px" || it == "dp" }
            ?.let { (value["value"] as? JsonPrimitive)?.doubleOrNull?.let(::formatTunableValue) }
        else -> null
      }
  }

/** A DTCG 2025 colour object: its `hex` when it has one, else its sRGB `components`. */
private fun colourFromObject(value: JsonObject): String? {
  val alpha = (value["alpha"] as? JsonPrimitive)?.doubleOrNull ?: 1.0
  val hex = (value["hex"] as? JsonPrimitive)?.contentOrNull?.uppercase()
  if (hex != null && hex.length == 7) {
    return if (alpha >= 1.0) hex else "#" + channel(alpha) + hex.removePrefix("#")
  }
  if ((value["colorSpace"] as? JsonPrimitive)?.contentOrNull != "srgb") return null
  val components = (value["components"] as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull }
  if (components?.size != 3 || components.any { it == null }) return null
  val rgb = components.joinToString("") { channel(it!!) }
  return "#" + (if (alpha >= 1.0) "" else channel(alpha)) + rgb
}

private fun channel(unit: Double): String =
  round(unit.coerceIn(0.0, 1.0) * 255).toInt().toString(16).uppercase().padStart(2, '0')

/** `#RRGGBB` or `#AARRGGBB` as a DTCG 2025 sRGB colour object, or null when it is neither. */
private fun dtcgColour(literal: String): JsonObject? {
  val hex = literal.removePrefix("#")
  val (alpha, rgb) =
    when (hex.length) {
      6 -> null to hex
      8 -> hex.substring(0, 2) to hex.substring(2)
      else -> return null
    }
  val channels = rgb.chunked(2).map { it.toIntOrNull(16) ?: return null }
  return JsonObject(
    buildMap {
      put("colorSpace", JsonPrimitive("srgb"))
      put(
        "components",
        JsonArray(channels.map { tidyNumber(round(it / 255.0 * 10000) / 10000) }),
      )
      alpha?.let { a ->
        val value = a.toIntOrNull(16) ?: return null
        put("alpha", tidyNumber(round(value / 255.0 * 10000) / 10000))
      }
      put("hex", JsonPrimitive("#" + rgb.lowercase()))
    }
  )
}

/** A parsed value as the Theme panel would type it: `8`, not `8.0`. */
private fun DesignToken.spelling(value: JsonPrimitive): String =
  if (kind == DesignTokenKind.Number) value.doubleOrNull?.let(::formatTunableValue) ?: value.content
  else value.content

private fun tidyNumber(value: Double): JsonPrimitive =
  if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e15) JsonPrimitive(value.toLong())
  else JsonPrimitive(value)

private fun Any.toJson(): JsonElement =
  when (this) {
    is JsonElement -> this
    is Map<*, *> ->
      JsonObject(entries.associate { (key, value) -> key as String to value!!.toJson() })
    else -> error("unexpected $this")
  }

private val PRETTY = Json { prettyPrint = true }

/** Whether [text] looks like a Material Theme Builder export, which carries named schemes. */
fun isThemeBuilderExport(text: String): Boolean =
  (runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject)?.get("schemes") is
    JsonObject
