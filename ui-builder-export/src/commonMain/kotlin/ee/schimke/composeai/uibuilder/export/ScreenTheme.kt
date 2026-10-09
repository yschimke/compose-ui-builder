package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.protocol.ColorValueV1
import ee.schimke.composeai.uibuilder.protocol.DecimalValueV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.EnumValueV1
import ee.schimke.composeai.uibuilder.protocol.IntegerValueV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.UiValueV1
import kotlinx.serialization.json.Json

/**
 * The theme an `m3/surface` theme host sets, as the canvas reads it, for the server's Compose
 * export.
 *
 * The canvas draws everything under the first top-level surface in that surface's theme: its four
 * colours over the light or dark baseline scheme, its type scale on every type role, its corner
 * radius on the three shape roles, and its [ThemeTypefaces] on their role groups (see
 * `UiBuilderRenderer`). Until this the export passed those properties to `Surface` as arguments,
 * which it does not declare, so every themed design refused.
 *
 * The export writes them as a `MaterialTheme` around the surface. [COMPONENT_ID] is the node that
 * call is, and [RECORD] the component record it resolves through: no catalog publishes one for
 * `MaterialTheme`, because it is not a design component, so the projection supplies its own and
 * every caller of `ScreenGenerator` resolves through `Outcome.Projected.resolvable`.
 */
internal class ScreenTheme
private constructor(
  /** Whether the baseline scheme is `darkColorScheme()` rather than `lightColorScheme()`. */
  val dark: Boolean,
  /** `ColorScheme` parameter name to `#RRGGBB` / `#AARRGGBB` literal. */
  val colors: Map<String, String>,
  /** The factor every type role's size and line height is multiplied by; 1 leaves them. */
  val typeScale: Float,
  /** The `large` shape role's corner radius in dp, or null to keep the baseline shapes. */
  val cornerRadiusDp: Float?,
  /** The family each [ThemeTypefaces] group names, by group. */
  val families: Map<ThemeTypefaces.Group, String>,
  /** The Material 3 role text with no `style` is set in, or null for the theme's `bodyLarge`. */
  val textRole: String?,
) {
  val scalesType: Boolean
    get() = typeScale != 1f

  companion object {
    const val COMPONENT_ID: String = "builder/material-theme"

    /** `ProvideTextStyle`, which sets the [textRole] inside the theme. */
    const val TEXT_STYLE_COMPONENT_ID: String = "builder/provide-text-style"

    const val PRIMARY: String = "themePrimaryColor"
    const val BACKGROUND: String = "themeBackgroundColor"
    const val SURFACE: String = "themeSurfaceColor"
    const val CONTENT: String = "themeContentColor"
    const val TYPE_SCALE: String = "themeTypeScale"
    const val CORNER_RADIUS: String = "themeCornerRadiusDp"

    /**
     * Every property a surface carries for its theme, spent on the theme rather than passed to
     * `Surface`. A surface that is not the host carries them too and the canvas ignores them there,
     * so the export does the same.
     */
    val PROPERTIES: Set<String> =
      setOf(PRIMARY, BACKGROUND, SURFACE, CONTENT, TYPE_SCALE, CORNER_RADIUS) +
        ThemeTypefaces.PROPERTIES +
        ThemeTextStyle.PROPERTY

    /**
     * The `ColorScheme` roles each colour property sets — the same table the canvas copies onto its
     * baseline, so the generated screen paints what the design did.
     */
    private val ROLES: List<Pair<String, List<String>>> =
      listOf(
        PRIMARY to listOf("primary"),
        BACKGROUND to listOf("background"),
        CONTENT to listOf("onBackground", "onSurface", "onSurfaceVariant"),
        SURFACE to
          listOf(
            "surface",
            "surfaceContainer",
            "surfaceContainerLow",
            "surfaceContainerHigh",
            "surfaceContainerHighest",
          ),
      )

    /**
     * [host]'s theme, or null when it sets none of [PROPERTIES]. [dark] is the design's environment
     * asking for the dark theme, which is what picks the canvas's baseline scheme.
     */
    fun of(host: DesignNodeV1, dark: Boolean): ScreenTheme? {
      if (host.properties.keys.none { it in PROPERTIES }) return null
      val colors = buildMap {
        ROLES.forEach { (property, roles) ->
          // A literal only, as `UiBuilderRenderer.themeColor` reads it; anything else — a token, a
          // malformed literal — leaves the baseline role, there and here.
          host
            .text(property)
            ?.takeIf { HEX.matches(it) }
            ?.let { value -> roles.forEach { put(it, value) } }
        }
      }
      return ScreenTheme(
        dark = dark,
        colors = colors,
        typeScale = host.number(TYPE_SCALE)?.toFloat()?.coerceIn(0.75f, 1.5f) ?: 1f,
        cornerRadiusDp = host.number(CORNER_RADIUS)?.toFloat()?.coerceIn(0f, 48f),
        families = ThemeTypefaces.families { host.text(it) },
        textRole = ThemeTextStyle.role { host.text(it) }?.let(ThemeTextStyle::m3Role),
      )
    }

    private val HEX = Regex("#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})")

    private fun DesignNodeV1.text(property: String): String? =
      when (val value: UiValueV1? = properties[property]) {
        is StringValueV1 -> value.value
        is ColorValueV1 -> value.value
        is EnumValueV1 -> value.value
        else -> null
      }

    private fun DesignNodeV1.number(property: String): Double? =
      when (val value = properties[property]) {
        is IntegerValueV1 -> value.value.toDouble()
        is DecimalValueV1 -> value.value
        else -> null
      }

    /**
     * `androidx.compose.material3.MaterialTheme`, as a record component the generator resolves
     * [COMPONENT_ID] through. The three theme arguments and the content slot, which is the whole of
     * what the export writes.
     */
    private val RECORD: ComponentRecordFile by lazy {
      Json { ignoreUnknownKeys = true }
        .decodeFromString(
          """
          {
            "schemaVersion": 2,
            "module": "ui-builder",
            "variant": "theme",
            "components": [
              {
                "canonicalId": "ui-builder/androidx.compose.material3.MaterialThemeKt.MaterialTheme",
                "componentIds": ["$COMPONENT_ID"],
                "symbol": {
                  "jvmOwner": "androidx.compose.material3.MaterialThemeKt",
                  "callable": "androidx.compose.material3.MaterialTheme",
                  "name": "MaterialTheme",
                  "origin": "LIBRARY"
                },
                "parameters": [
                  {"name": "colorScheme", "type": "ColorScheme", "hasDefault": true, "typeFqn": "androidx.compose.material3.ColorScheme"},
                  {"name": "shapes", "type": "Shapes", "hasDefault": true, "typeFqn": "androidx.compose.material3.Shapes"},
                  {"name": "typography", "type": "Typography", "hasDefault": true, "typeFqn": "androidx.compose.material3.Typography"},
                  {"name": "content", "type": "@Composable () -> Unit", "hasDefault": false, "composableSlot": true}
                ],
                "slots": [{"name": "content", "required": true}],
                "code": {"call": "MaterialTheme()", "imports": ["androidx.compose.material3.MaterialTheme"]},
                "signatureKnown": true
              },
              {
                "canonicalId": "ui-builder/androidx.compose.material3.TextKt.ProvideTextStyle",
                "componentIds": ["$TEXT_STYLE_COMPONENT_ID"],
                "symbol": {
                  "jvmOwner": "androidx.compose.material3.TextKt",
                  "callable": "androidx.compose.material3.ProvideTextStyle",
                  "name": "ProvideTextStyle",
                  "origin": "LIBRARY"
                },
                "parameters": [
                  {"name": "value", "type": "TextStyle", "hasDefault": false, "typeFqn": "androidx.compose.ui.text.TextStyle"},
                  {"name": "content", "type": "@Composable () -> Unit", "hasDefault": false, "composableSlot": true}
                ],
                "slots": [{"name": "content", "required": true}],
                "code": {"call": "ProvideTextStyle()", "imports": ["androidx.compose.material3.ProvideTextStyle"]},
                "signatureKnown": true
              }
            ]
          }
          """
            .trimIndent()
        )
    }

    /** [record] with each of [RECORD]'s components it does not already resolve added. */
    fun withThemeRecord(record: ComponentRecordFile): ComponentRecordFile {
      val claimed = record.components.flatMapTo(mutableSetOf()) { it.componentIds }
      val missing = RECORD.components.filter { it.componentIds.none(claimed::contains) }
      return if (missing.isEmpty()) record
      else record.newBuilder().apply { components = record.components + missing }.build()
    }
  }
}
