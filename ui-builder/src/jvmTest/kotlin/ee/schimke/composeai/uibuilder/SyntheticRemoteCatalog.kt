package ee.schimke.composeai.uibuilder

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A Remote-Compose-shaped catalog: the three widget containers (root-only), the Remote theme node
 * and text, the `layout/…` floor with its repetition, and enough of `draw/…` for a loop. The shapes
 * are remote-m3-catalog's.
 */
internal val SYNTHETIC_REMOTE_JSON: String by lazy {
  syntheticCatalogJson(
    systemId = "remote-m3",
    catalogRevision = "wear-widget-scaffolds-v1",
    statusSemantics = buildJsonObject { put("platform", "remote-compose") },
    components =
      listOf(
        syntheticComponent(
          "remote-m3/widget-container-small",
          "Wear widget · Small (216×76dp)",
          "Scaffold",
          traits = "ScreenContent WearWidgetHost RemoteContentHost",
          slots =
            listOf(
              syntheticSlot(
                "background",
                max = null,
                roles = "Leaf",
                traits = "DrawLayer ImageContent",
              ),
              syntheticSlot(
                "content",
                max = 1,
                roles = "Scaffold Container Leaf",
                traits = "RemoteAuthorable",
              ),
            ),
        ) {
          p("background", "string")
          p("themeDisplayTypeface", "string")
          p("themeTitleTypeface", "string")
          p("themeBodyTypeface", "string")
          p("themeLabelTypeface", "string")
          p(
            "themeTextStyle",
            "string",
            allowed =
              "displayLarge displayMedium displaySmall titleLarge titleMedium titleSmall labelLarge labelMedium labelSmall bodyLarge bodyMedium bodySmall bodyExtraSmall numeralExtraLarge numeralLarge numeralMedium numeralSmall numeralExtraSmall",
          )
        },
        syntheticComponent(
          "remote-m3/widget-container-large",
          "Wear widget · Large (216×124dp)",
          "Scaffold",
          traits = "ScreenContent WearWidgetHost RemoteContentHost",
          slots =
            listOf(
              syntheticSlot(
                "background",
                max = null,
                roles = "Leaf",
                traits = "DrawLayer ImageContent",
              ),
              syntheticSlot(
                "content",
                max = 1,
                roles = "Scaffold Container Leaf",
                traits = "RemoteAuthorable",
              ),
            ),
        ) {
          p("background", "string")
          p("themeDisplayTypeface", "string")
          p("themeTitleTypeface", "string")
          p("themeBodyTypeface", "string")
          p("themeLabelTypeface", "string")
          p(
            "themeTextStyle",
            "string",
            allowed =
              "displayLarge displayMedium displaySmall titleLarge titleMedium titleSmall labelLarge labelMedium labelSmall bodyLarge bodyMedium bodySmall bodyExtraSmall numeralExtraLarge numeralLarge numeralMedium numeralSmall numeralExtraSmall",
          )
        },
        syntheticComponent(
          "remote-m3/widget-container-adaptive",
          "Wear widget · Adaptive (experimental)",
          "Scaffold",
          traits = "ScreenContent WearWidgetHost RemoteContentHost",
          slots =
            listOf(
              syntheticSlot(
                "background",
                max = null,
                roles = "Leaf",
                traits = "DrawLayer ImageContent",
              ),
              syntheticSlot(
                "headline",
                max = null,
                roles = "Scaffold Container Leaf",
                traits = "RemoteAuthorable",
              ),
              syntheticSlot(
                "supporting",
                max = null,
                roles = "Scaffold Container Leaf",
                traits = "RemoteAuthorable",
              ),
              syntheticSlot(
                "action",
                max = null,
                roles = "Scaffold Container Leaf",
                traits = "RemoteAuthorable",
              ),
            ),
        ) {
          p("background", "string")
          p("themeDisplayTypeface", "string")
          p("themeTitleTypeface", "string")
          p("themeBodyTypeface", "string")
          p("themeLabelTypeface", "string")
          p(
            "themeTextStyle",
            "string",
            allowed =
              "displayLarge displayMedium displaySmall titleLarge titleMedium titleSmall labelLarge labelMedium labelSmall bodyLarge bodyMedium bodySmall bodyExtraSmall numeralExtraLarge numeralLarge numeralMedium numeralSmall numeralExtraSmall",
          )
        },
        syntheticComponent(
          "remote-m3/remote-material-theme",
          "Theme",
          "Container",
          traits = "RemoteAuthorable",
          slots =
            listOf(
              syntheticSlot(
                "children",
                max = 1,
                roles = "Scaffold Container Leaf",
                traits = "AnyContent",
              )
            ),
        ) {
          p("themePrimaryColor", "string")
          p("themeOnPrimaryColor", "string")
          p("themePrimaryContainerColor", "string")
          p("themeOnPrimaryContainerColor", "string")
          p("themeSecondaryColor", "string")
          p("themeOnSecondaryColor", "string")
          p("themeSecondaryContainerColor", "string")
          p("themeOnSecondaryContainerColor", "string")
          p("themeTertiaryColor", "string")
          p("themeOnTertiaryColor", "string")
          p("themeTertiaryContainerColor", "string")
          p("themeOnTertiaryContainerColor", "string")
          p("themeSurfaceContainerLowColor", "string")
          p("themeSurfaceContainerColor", "string")
          p("themeSurfaceContainerHighColor", "string")
          p("themeOnSurfaceColor", "string")
          p("themeOnSurfaceVariantColor", "string")
          p("themeOutlineColor", "string")
          p("themeOutlineVariantColor", "string")
          p("themeBackgroundColor", "string")
          p("themeOnBackgroundColor", "string")
          p("themeErrorColor", "string")
          p("themeOnErrorColor", "string")
        },
        syntheticComponent(
          "remote-m3/remote-text",
          "Text",
          "Leaf",
          traits = "RemoteContent RemoteAuthorable",
          modifiers =
            "size fillMaxSize fillMaxWidth padding clip alpha offset rotate scale zIndex width height widthIn heightIn fillMaxHeight background border wrapContentSize verticalScroll horizontalScroll align alignHorizontal alignVertical weight animateEnterExit collapsiblePriority remoteCall sharedElement",
        ) {
          p("text", "string", "object", required = true)
          p("color", "string")
          p("fontSize", "number")
          p("maxLines", "integer")
          p(
            "style",
            "string",
            allowed =
              "displayLarge displayMedium displaySmall titleLarge titleMedium titleSmall labelLarge labelMedium labelSmall bodyLarge bodyMedium bodySmall bodyExtraSmall numeralExtraLarge numeralLarge numeralMedium numeralSmall numeralExtraSmall",
          )
          p("textAlign", "string", allowed = "start center end justify")
          p("fontVariationSettings", "string")
          p("fontFeatureSettings", "string")
        },
        syntheticComponent(
          "m3/text",
          "Text",
          "Leaf",
          traits = "TextContent RemoteAuthorable",
          modifiers =
            "size fillMaxWidth padding alpha offset rotate scale zIndex width height widthIn heightIn align alignHorizontal alignVertical weight animateEnterExit collapsiblePriority remoteCall sharedElement",
        ) {
          p("text", "string", required = true)
          p(
            "style",
            "string",
            allowed =
              "displayLarge displayMedium displaySmall headlineLarge headlineMedium headlineSmall titleLarge titleMedium titleSmall bodyLarge bodyMedium bodySmall labelLarge labelMedium labelSmall",
          )
          p("color", "string")
          p("fontSizeSp", "number")
        },
        syntheticComponent(
          "layout/box",
          "Box",
          "Container",
          traits = "OrderedContent OverlayContent LayoutPrimitive RemoteAuthorable",
          modifiers =
            "size fillMaxSize fillMaxWidth padding clip alpha offset rotate scale zIndex width height widthIn heightIn fillMaxHeight background border wrapContentSize verticalScroll horizontalScroll align alignHorizontal alignVertical weight animateEnterExit collapsiblePriority remoteCall sharedElement",
          slots =
            listOf(
              syntheticSlot(
                "children",
                max = null,
                roles = "Scaffold Container Leaf",
                traits = "AnyContent",
              )
            ),
        ) {
          p("contentAlignment", "string")
        },
        syntheticComponent(
          "layout/column",
          "Column",
          "Container",
          traits = "OrderedContent VerticalContent LayoutPrimitive RemoteAuthorable",
          modifiers =
            "size fillMaxSize fillMaxWidth padding alpha offset rotate scale zIndex width height widthIn heightIn fillMaxHeight background border wrapContentSize verticalScroll align alignHorizontal alignVertical weight animateEnterExit collapsiblePriority remoteCall sharedElement",
          slots =
            listOf(
              syntheticSlot(
                "children",
                max = null,
                roles = "Scaffold Container Leaf",
                traits = "AnyContent",
              )
            ),
        ) {
          p(
            "verticalArrangement",
            "string",
            allowed = "top center bottom spaceBetween spaceAround spaceEvenly",
          )
          p("verticalSpacingDp", "number")
        },
        syntheticComponent(
          "layout/row",
          "Row",
          "Container",
          traits = "OrderedContent HorizontalContent LayoutPrimitive RemoteAuthorable",
          modifiers =
            "size fillMaxSize fillMaxWidth padding alpha offset rotate scale zIndex width height widthIn heightIn fillMaxHeight background border wrapContentSize horizontalScroll align alignHorizontal alignVertical weight animateEnterExit collapsiblePriority remoteCall sharedElement",
          slots =
            listOf(
              syntheticSlot(
                "children",
                max = null,
                roles = "Scaffold Container Leaf",
                traits = "AnyContent",
              )
            ),
        ) {
          p(
            "horizontalArrangement",
            "string",
            allowed = "start center end spaceBetween spaceAround spaceEvenly",
          )
          p("horizontalSpacingDp", "number")
        },
        syntheticComponent(
          "layout/for-each",
          "For each",
          "Container",
          traits = "OrderedContent RepeatedContent RemoteAuthorable",
          modifiers =
            "size fillMaxWidth padding alpha offset rotate scale zIndex width height widthIn heightIn align alignHorizontal alignVertical weight animateEnterExit collapsiblePriority remoteCall sharedElement",
          slots =
            listOf(
              syntheticSlot(
                "template",
                min = 1,
                max = 1,
                roles = "Container Leaf",
                traits = "AnyContent",
              )
            ),
        ) {
          p("data", "object", required = true)
          p("verticalSpacingDp", "number")
        },
        syntheticComponent(
          "draw/canvas",
          "Canvas",
          "Container",
          traits = "RemoteAuthorable",
          modifiers =
            "size fillMaxSize fillMaxWidth padding clip alpha offset rotate scale zIndex width height widthIn heightIn fillMaxHeight background border wrapContentSize verticalScroll horizontalScroll align alignHorizontal alignVertical weight animateEnterExit collapsiblePriority remoteCall sharedElement",
          slots =
            listOf(
              syntheticSlot(
                "ops",
                max = null,
                roles = "Scaffold Container Leaf",
                traits = "DrawOperation",
              )
            ),
        ),
        syntheticComponent("draw/rect", "Rectangle", "Leaf", traits = "DrawOperation") {
          p("xDp", "number", "object")
          p("yDp", "number", "object")
          p("widthDp", "number", "object")
          p("heightDp", "number", "object")
          p("color", "string")
        },
        syntheticComponent(
          "draw/repeat",
          "Repeat",
          "Container",
          traits = "DrawOperation",
          slots =
            listOf(
              syntheticSlot(
                "ops",
                max = null,
                roles = "Scaffold Container Leaf",
                traits = "DrawOperation",
              )
            ),
        ) {
          p("from", "number", "object")
          p("until", "number", "object")
          p("step", "number", "object")
          p("index", "string")
        },
      ),
  )
}
