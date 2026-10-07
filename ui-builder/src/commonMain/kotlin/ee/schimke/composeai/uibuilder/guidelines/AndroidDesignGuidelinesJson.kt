package ee.schimke.composeai.uibuilder.guidelines

/**
 * `docs/guidelines/android-design-guidelines.json`, embedded so the Wasm editor needs no resource
 * loading. `AndroidDesignGuidelinesFileTest` holds the two equal; edit the file and paste it here.
 * compose-preview-server ships the same file as a resource for its server-side check.
 */
internal const val ANDROID_DESIGN_GUIDELINES_JSON: String =
  """
{
  "schema": "compose-ui-builder/design-guidelines/v1",
  "version": 3,
  "about": "Design rules a model checks a UI-builder design against. Each rule quotes the guidance it comes from and links its source on developer.android.com (or the Android Knowledge Base for agent skills). `check` is a yes/no question where YES means the design follows the rule. `kind` is `structure` when the design tree is enough evidence, `visual` when it needs a picture of the design. Candidates are extracted with scripts/guidelines/extract-guidance.mjs and reviewed by a person before they are added here. Visual rules say which picture to judge: the device picture (the first frame on the watch) or the unrolled picture (the whole scrolling content, scrolled to the end so a revealed edge button shows).",
  "rules": [
    {
      "id": "wear.layout.time-text-clear",
      "platforms": [
        "wear"
      ],
      "kind": "visual",
      "severity": "warning",
      "guidance": "Accommodate Time Text if used, but don't overlap the top section of the page.",
      "check": "Judge this on the device picture (the first frame, as the wearer first sees it). Is the time text at the top of the screen clear of any overlapping content?",
      "source": "https://developer.android.com/design/ui/wear/guides/surfaces/apps/layouts/non-scrolling"
    },
    {
      "id": "wear.layout.time-text-shown",
      "platforms": [
        "wear"
      ],
      "kind": "structure",
      "severity": "info",
      "guidance": "To reinforce that the device is a watch, we recommend showing the time text even when in an app journey (optional, but recommended).",
      "check": "Does a design built on a wear-m3/screen-scaffold set its timeText? Answer not_applicable for widgets, dialogs, confirmations and pickers.",
      "source": "https://developer.android.com/design/ui/wear/guides/surfaces/apps/layouts"
    },
    {
      "id": "wear.layout.no-clipping",
      "platforms": [
        "wear"
      ],
      "kind": "visual",
      "severity": "warning",
      "guidance": "Margins should be defined in percentages to avoid clipping and provide proportional scaling of elements.",
      "check": "Judge round-edge clipping on the device picture (the first frame, as the wearer first sees it). Judge clipping inside components on the unrolled picture (the whole scrolling content) when one is attached. Is every piece of text, icon and control fully visible, with nothing cut off by the round edge or by its own container? Content that continues below the bottom of the device picture in a scrolling list is not clipped.",
      "source": "https://developer.android.com/design/ui/wear/guides/foundations/adaptive-design"
    },
    {
      "id": "wear.layout.responsive-width",
      "platforms": [
        "wear"
      ],
      "kind": "structure",
      "severity": "warning",
      "guidance": "Don't use components with a fixed width that don't fill the screen responsively or adjust the behavior of content to fill the available space. We recommend using percentage margins so the size of the margins adapts to the growing curve of the display.",
      "check": "Do buttons and cards in the screen's content slot fill the available width, with no fixed width or size modifier? Answer not_applicable for widgets and for icon buttons, which are meant to size to their content.",
      "source": "https://developer.android.com/design/ui/wear/guides/surfaces/apps/best-practices"
    },
    {
      "id": "wear.layout.column-never-scrolls",
      "platforms": [
        "wear"
      ],
      "kind": "structure",
      "severity": "warning",
      "guidance": "Use a Column as a direct child of ScreenScaffold only if the screen will never scroll, even with the largest system font.",
      "check": "If the screen-scaffold's content is a plain layout/column rather than a transforming-lazy-column, is it short enough (a few short items) that it would still fit at the largest font size? Answer not_applicable when the content is a scrolling list.",
      "source": "kb://android/agents/skills/wear/wear-compose-m3/skill"
    },
    {
      "id": "wear.dialog.dedicated-task",
      "platforms": [
        "wear"
      ],
      "kind": "structure",
      "severity": "warning",
      "guidance": "Dialogs should be direct in communicating information and dedicated to completing a task.",
      "check": "If this design is a dialog or confirmation, does it communicate one thing and offer only the actions needed to complete that one task? Answer not_applicable when it is not a dialog.",
      "source": "https://developer.android.com/design/ui/wear/guides/surfaces/apps/layouts/non-scrolling"
    },
    {
      "id": "wear.touch-target-48dp",
      "platforms": [
        "wear"
      ],
      "kind": "structure",
      "severity": "warning",
      "guidance": "Reserves at least 48.dp in size to disambiguate touch interactions if the element would measure smaller. Smaller visual sizes, such as compact and extra-small buttons, are padded out to a 48dp touch target.",
      "check": "Is the tap area of every tappable control at least 48dp in each direction? Count the padding Wear adds to compact and extra-small buttons and icon buttons (their tap area is 48dp even when they draw smaller); flag only controls given a fixed size or height that leaves the tap area under 48dp.",
      "source": "https://developer.android.com/reference/kotlin/androidx/wear/compose/material3/package-summary"
    },
    {
      "id": "wear.color.role-pairs",
      "platforms": [
        "wear"
      ],
      "kind": "structure",
      "severity": "warning",
      "guidance": "Color roles come in pairs (container and on-container) that provide a minimum of 3:1 color contrast. Custom colors should keep that contrast.",
      "check": "Where the design overrides colors, does it pair each container color with its matching content (on-) role, or with colors that are clearly high contrast?",
      "source": "https://developer.android.com/design/ui/wear/guides/styles/color/roles-tokens"
    },
    {
      "id": "wear.empty-state-has-action",
      "platforms": [
        "wear"
      ],
      "kind": "structure",
      "severity": "info",
      "guidance": "All empty states should have a clear call-to-action so the user knows what to do to fix a potential issue, or find out more information.",
      "check": "If this design shows an empty, error or signed-out state, does it offer a clear action? Answer not_applicable when it does not show such a state.",
      "source": "https://developer.android.com/design/ui/wear/guides/surfaces/tiles/states"
    },
    {
      "id": "wear.gestures.no-hidden-actions",
      "platforms": [
        "wear"
      ],
      "kind": "structure",
      "severity": "warning",
      "guidance": "Never hide a gesture-only action. A visible element that performs the same task must be present.",
      "check": "Does every action bound to a gesture (long press, swipe, double tap) also have a visible control that performs it?",
      "source": "https://developer.android.com/design/ui/wear/guides/patterns/gestures"
    },
    {
      "id": "wear.widgets.background-on-container",
      "platforms": [
        "wear"
      ],
      "kind": "structure",
      "severity": "warning",
      "guidance": "Always supply your widget's background brush directly to WearWidgetDocument so the host system can paint and mask the background to the native OEM container shape. Setting a transparent or black canvas and drawing a custom rounded container inside the layout can cause floating box and letterboxing defects on some devices.",
      "check": "If this design is a widget (its root is a widget-container), is its background set on the widget container's background property rather than drawn by a coloured or rounded box inside the content? Answer not_applicable when it is not a widget.",
      "source": "https://developer.android.com/training/wearables/widgets/get_started"
    },
    {
      "id": "wear.widgets.content-fills-container",
      "platforms": [
        "wear"
      ],
      "kind": "structure",
      "severity": "warning",
      "guidance": "Ensure your root container composable uses RemoteModifier.fillMaxSize(). Because container DP dimensions and safe insets vary across watch models, hardcoding fixed dimensions or outer padding can cause clipping or misaligned layouts.",
      "check": "If this design is a widget, does the content directly inside the widget container fill it (fillMaxSize), with no fixed width, height or outer padding? Answer not_applicable when it is not a widget.",
      "source": "https://developer.android.com/training/wearables/widgets/get_started"
    },
    {
      "id": "wear.widgets.focused",
      "platforms": [
        "wear"
      ],
      "kind": "structure",
      "severity": "info",
      "guidance": "Tiles should feel focused, concise, and purpose-driven. Avoid providing a superfluous amount of functionality, and refrain from using more than one interactive component to trigger a specific action. (Tiles guidance; widgets replace Tiles.)",
      "check": "If this design is a widget, does it focus on one task, with no two controls doing the same thing? Answer not_applicable when it is not a widget.",
      "source": "https://developer.android.com/design/ui/wear/guides/surfaces/tiles/bestpractices"
    },
    {
      "id": "wear.widgets.containers-have-actions",
      "platforms": [
        "wear"
      ],
      "kind": "structure",
      "severity": "info",
      "guidance": "We don't recommend using containers for decorative or structural purposes, to avoid taps that don't do anything.",
      "check": "If this design is a widget, does every container that looks like a button (filled, rounded and button-shaped) have an action or click binding? Treat an empty or placeholder click handler as an action. Answer not_applicable for app screens, where non-interactive cards are fine.",
      "source": "https://developer.android.com/design/ui/wear/guides/surfaces/tiles/bestpractices"
    },
    {
      "id": "glasses.cards.single-action",
      "platforms": [
        "glasses"
      ],
      "kind": "structure",
      "severity": "warning",
      "guidance": "If a card has an action, it should only contain a single action. Don't have multiple buttons within a card.",
      "check": "Does every card contain at most one button or action?",
      "source": "https://developer.android.com/design/ui/ai-glasses/guides/components/cards"
    },
    {
      "id": "glasses.lists.one-per-view",
      "platforms": [
        "glasses"
      ],
      "kind": "structure",
      "severity": "warning",
      "guidance": "Don't have more than one list per view; this is overwhelming visually and focus wise.",
      "check": "Does the screen contain at most one list?",
      "source": "https://developer.android.com/design/ui/ai-glasses/guides/components/lists"
    },
    {
      "id": "glasses.title-chip.not-an-action",
      "platforms": [
        "glasses"
      ],
      "kind": "structure",
      "severity": "warning",
      "guidance": "Don't use title chips as a tapable action prompt, since they do not have a focus state; use a button instead.",
      "check": "Are title chips used only as labels, never with a click or tap action?",
      "source": "https://developer.android.com/design/ui/ai-glasses/guides/components/title-chip"
    },
    {
      "id": "glasses.button-group.limits",
      "platforms": [
        "glasses"
      ],
      "kind": "structure",
      "severity": "warning",
      "guidance": "Don't grow the button group past 10 buttons. Don't stack multiple button groups. Don't group buttons of different heights together.",
      "check": "Does each button group have 10 or fewer buttons of one height, with no button group stacked on another?",
      "source": "https://developer.android.com/design/ui/ai-glasses/guides/components/button-group"
    },
    {
      "id": "glasses.buttons.purposeful",
      "platforms": [
        "glasses"
      ],
      "kind": "structure",
      "severity": "warning",
      "guidance": "Don't use a button as a static decorative element. Don't use a toggle button for non binary actions. Don't overwhelm the user's view with too many buttons.",
      "check": "Does every button have an action, and is every toggle a true on/off choice?",
      "source": "https://developer.android.com/design/ui/ai-glasses/guides/components/buttons"
    },
    {
      "id": "glasses.surfaces.dark",
      "platforms": [
        "glasses"
      ],
      "kind": "visual",
      "severity": "warning",
      "guidance": "Surfaces must be black for highest contrast. Don't use bright or filled surfaces. Don't fill the screen with all white, as this can cause thermal mitigation.",
      "check": "Are the surfaces black or transparent, with no large bright or white areas?",
      "source": "https://developer.android.com/design/ui/ai-glasses/guides/styles/color"
    },
    {
      "id": "glasses.color.sparing",
      "platforms": [
        "glasses"
      ],
      "kind": "visual",
      "severity": "info",
      "guidance": "Don't use color text for all content. Don't use overly saturated colors.",
      "check": "Is colored text kept for emphasis only, with body text in the default foreground color and no overly saturated colors?",
      "source": "https://developer.android.com/design/ui/ai-glasses/guides/styles/color"
    },
    {
      "id": "glasses.icons.unfilled",
      "platforms": [
        "glasses"
      ],
      "kind": "visual",
      "severity": "info",
      "guidance": "Use unfilled icons to avoid halation, or light bleed. Use a heavier stroke weight and rounded icon variants; avoid thin strokes.",
      "check": "Are icons outlined (unfilled) and rounded, with no thin-stroke icons?",
      "source": "https://developer.android.com/design/ui/ai-glasses/guides/styles/icons"
    },
    {
      "id": "glasses.layout.bottom-anchored",
      "platforms": [
        "glasses"
      ],
      "kind": "visual",
      "severity": "info",
      "guidance": "Anchor elements to the bottom of the canvas, to build upwards. Content should avoid being centered vertically.",
      "check": "Is the content anchored to the bottom of the frame rather than floating at the top or center?",
      "source": "https://developer.android.com/design/ui/ai-glasses/guides/surfaces/app"
    },
    {
      "id": "wear.edge-button.in-slot",
      "platforms": [
        "wear"
      ],
      "kind": "structure",
      "severity": "warning",
      "guidance": "This button represents the most important action on the screen, and must take the whole width of the screen as well as being anchored to the screen bottom.",
      "check": "Is there at most one wear-m3/edge-button, placed in the screen-scaffold's edgeButton slot rather than inside a list or layout? Answer not_applicable when there is no edge-button.",
      "source": "https://developer.android.com/reference/kotlin/androidx/wear/compose/material3/package-summary"
    },
    {
      "id": "wear.edge-button.content-fits-size",
      "platforms": [
        "wear"
      ],
      "kind": "structure",
      "severity": "info",
      "guidance": "EdgeButton has 4 standard sizes, taking 1 line of text for the extra small, 2 for small and medium, and 3 for the large. Optionally, a single icon can be used instead of the text.",
      "check": "Does the edge-button's content fit its size: one short line of text (or a single icon) for size=extra-small, at most two lines for small and medium, at most three for large? Answer not_applicable when there is no edge-button.",
      "source": "https://developer.android.com/reference/kotlin/androidx/wear/compose/material3/package-summary"
    },
    {
      "id": "wear.button.emphasis",
      "platforms": [
        "wear"
      ],
      "kind": "structure",
      "severity": "info",
      "guidance": "This is a high-emphasis button for the primary, most important or most common action on a screen.",
      "check": "When the screen has two or more wear-m3/button actions, is variant=filled kept for the one primary action, with the others filled-tonal, outlined or child? Answer not_applicable with fewer than two buttons.",
      "source": "https://developer.android.com/reference/kotlin/androidx/wear/compose/material3/package-summary"
    },
    {
      "id": "wear.button.child-not-primary",
      "platforms": [
        "wear"
      ],
      "kind": "structure",
      "severity": "info",
      "guidance": "ChildButton is a low-emphasis button for optional or supplementary actions with the least amount of prominence.",
      "check": "Is a button with variant=child never the only action or the primary action on the screen? Answer not_applicable when no child buttons are used.",
      "source": "https://developer.android.com/reference/kotlin/androidx/wear/compose/material3/package-summary"
    },
    {
      "id": "wear.type.titles-not-in-controls",
      "platforms": [
        "wear"
      ],
      "kind": "structure",
      "severity": "info",
      "guidance": "Title styles are not recommended for interactive components, rather page headings or sub headings. Label styles are applied to interactive components.",
      "check": "Does text inside buttons and cards use label or body styles, with title styles kept for headings outside controls? Answer not_applicable when no text style is set inside a control.",
      "source": "https://developer.android.com/design/ui/wear/guides/styles/typography/apply"
    },
    {
      "id": "wear.type.numerals-digits-only",
      "platforms": [
        "wear"
      ],
      "kind": "structure",
      "severity": "info",
      "guidance": "Numeral text styles are used for numerical digits, usually limited to a few characters, where no localization is required.",
      "check": "Is every text with a numeral style purely digits, a time or a short number (no words)? Answer not_applicable when no numeral styles are used.",
      "source": "https://developer.android.com/design/ui/wear/guides/styles/typography/apply"
    },
    {
      "id": "wear.type.theme-styles",
      "platforms": [
        "wear"
      ],
      "kind": "structure",
      "severity": "warning",
      "guidance": "Do NOT hard-code text sizes, use typography from MaterialTheme. Do NOT hard-code colors, use colorScheme.",
      "check": "Do text nodes set a style rather than fontSizeSp, lineHeightSp or letterSpacingSp, and do colours come from the theme's colour roles rather than hex values on individual components? Theme-level overrides on the screen-scaffold (theme*Color, theme*Typeface) are fine.",
      "source": "kb://android/agents/skills/wear/wear-compose-m3/skill"
    },
    {
      "id": "wear.scroll-indicator-matches",
      "platforms": [
        "wear"
      ],
      "kind": "structure",
      "severity": "warning",
      "guidance": "Only use the scroll-indicator on scrolling screens. Remember to add the scroll-indicator on scrolling screens.",
      "check": "Is the screen-scaffold's scrollIndicator on exactly when its content is a scrolling list (transforming-lazy-column)? Answer not_applicable for widgets and designs without a screen-scaffold.",
      "source": "https://developer.android.com/design/ui/wear/guides/surfaces/apps/best-practices"
    },
    {
      "id": "wear.time-text.not-on-transient",
      "platforms": [
        "wear"
      ],
      "kind": "structure",
      "severity": "info",
      "guidance": "Don't display the time on a temporary dialog, confirmation overlay or a picker.",
      "check": "When the design is an alert dialog, confirmation or picker (date-picker, time-picker), is timeText left unset? Answer not_applicable for other designs.",
      "source": "https://developer.android.com/design/ui/wear/guides/surfaces/apps/best-practices"
    },
    {
      "id": "wear.actions-labelled",
      "platforms": [
        "wear"
      ],
      "kind": "structure",
      "severity": "info",
      "guidance": "Use both icons and labels when possible. Don't rely solely on icons to prompt the user to take action.",
      "check": "Are icon-only controls (icon-button, icon-only edge-button) kept to universally recognisable actions (add, close, play, settings), with other actions labelled? Answer not_applicable when there are no icon-only controls.",
      "source": "https://developer.android.com/design/ui/wear/guides/surfaces/apps/best-practices"
    },
    {
      "id": "wear.color.container-not-for-content",
      "platforms": [
        "wear"
      ],
      "kind": "structure",
      "severity": "warning",
      "guidance": "Container roles are used as a fill color for foreground elements like buttons. They shouldn't be used for text or icons.",
      "check": "Is no *Container colour role (primaryContainer, secondaryContainer, tertiaryContainer, errorContainer) used as a text colour, icon tint or contentColor? Answer not_applicable when the design names no colour roles.",
      "source": "https://developer.android.com/design/ui/wear/guides/styles/color/roles-tokens"
    },
    {
      "id": "wear.card.text-budget",
      "platforms": [
        "wear"
      ],
      "kind": "structure",
      "severity": "info",
      "guidance": "When subtitle is used content shouldn't exceed 2 lines height. Overall the title, content and subtitle text should be no more than 5 rows of text combined.",
      "check": "Does each wear-m3/card with variant=title hold at most 5 rows of text in total? Answer not_applicable when there are no title cards.",
      "source": "https://developer.android.com/reference/kotlin/androidx/wear/compose/material3/package-summary"
    },
    {
      "id": "wear.dialog.alert-is-a-decision",
      "platforms": [
        "wear"
      ],
      "kind": "structure",
      "severity": "warning",
      "guidance": "It should be used when the user will be presented with a binary decision. Where user input is not required, such as displaying a transient success or failure message, use ConfirmationDialog. The title should not exceed 3 lines.",
      "check": "If this design is an alert dialog (alert-dialog-edge-button), does it pose a yes/no decision with a title of 3 lines or fewer, rather than a status message that needs no input? Answer not_applicable for other designs.",
      "source": "https://developer.android.com/reference/kotlin/androidx/wear/compose/material3/package-summary"
    },
    {
      "id": "wear.slider.segment-limit",
      "platforms": [
        "wear"
      ],
      "kind": "structure",
      "severity": "info",
      "guidance": "Recommendation is while using this flag do not have more than MaxSegmentSteps steps.",
      "check": "Does a segmented wear-m3/slider (segmented=segmented) have 8 steps or fewer? Answer not_applicable when there is no segmented slider.",
      "source": "https://developer.android.com/reference/kotlin/androidx/wear/compose/material3/package-summary"
    },
    {
      "id": "wear.list-header.short",
      "platforms": [
        "wear"
      ],
      "kind": "structure",
      "severity": "info",
      "guidance": "ListHeaders are typically expected to be a few words of text on a single line.",
      "check": "Is each wear-m3/list-header a few words on one line? Answer not_applicable when there are no list headers.",
      "source": "https://developer.android.com/reference/kotlin/androidx/wear/compose/material3/package-summary"
    },
    {
      "id": "wear.widgets.no-edge-button",
      "platforms": [
        "wear"
      ],
      "kind": "structure",
      "severity": "warning",
      "guidance": "Because partial-height widgets integrate directly into a vertically scrolling surface, this fixed bottomSlot paradigm no longer exists, and migrating requires a deliberate UI redesign rather than a direct component swap.",
      "check": "If this design is a widget, does it avoid remote-m3/remote-edge-button, using an inline button or a whole-container tap instead? Answer not_applicable when it is not a widget.",
      "source": "https://developer.android.com/training/wearables/widgets/migration"
    },
    {
      "id": "wear.widgets.streamlined",
      "platforms": [
        "wear"
      ],
      "kind": "visual",
      "severity": "info",
      "guidance": "Rather than porting a dense, full-screen Tile layout one-to-one, streamline your design to emphasize primary information within the main content area.",
      "check": "If this design is a widget, does it lead with one primary piece of information rather than a dense grid of equal items? Answer not_applicable when it is not a widget.",
      "source": "https://developer.android.com/training/wearables/widgets/migration"
    },
    {
      "id": "wear.widgets.predictable-actions",
      "platforms": [
        "wear"
      ],
      "kind": "structure",
      "severity": "warning",
      "guidance": "The actions in this Tile are unclear: where does the container with the album art take the user, and is that different from the Play button?",
      "check": "If this design is a widget, is no clickable card or container wrapping a button that does something different? Answer not_applicable when it is not a widget.",
      "source": "https://developer.android.com/design/ui/wear/guides/surfaces/tiles/bestpractices"
    },
    {
      "id": "wear.widgets.small-single-row",
      "platforms": [
        "wear"
      ],
      "kind": "structure",
      "severity": "warning",
      "guidance": "If you do not hide the app title on small screen, and have two rows, the height of the components will not adhere to our accessibility standards.",
      "check": "If this design is a small widget (remote-m3/widget-container-small, 76dp tall), does it avoid stacking a title above a row of tap targets, or two rows of tap targets? Answer not_applicable for other designs.",
      "source": "https://developer.android.com/design/ui/wear/guides/surfaces/tiles/bestpractices"
    },
    {
      "id": "glasses.one-thing-at-a-time",
      "platforms": [
        "glasses"
      ],
      "kind": "structure",
      "severity": "warning",
      "guidance": "Show only one primary piece of information at a time (for example, using a Stack). Avoid multiple simultaneous cards.",
      "check": "Is at most one card shown outside a Stack or List?",
      "source": "kb://android/agents/skills/xr/display-glasses-with-jetpack-compose-glimmer/skill"
    },
    {
      "id": "glasses.stack-vs-list",
      "platforms": [
        "glasses"
      ],
      "kind": "structure",
      "severity": "info",
      "guidance": "If the items are of different types use a stack. If the items are of the same type, use a list. Don't use a title chip with a stack.",
      "check": "Do Lists hold items of one type and Stacks mixed types, with no TitleChip on a Stack? Answer not_applicable when there is neither.",
      "source": "kb://android/agents/skills/xr/display-glasses-with-jetpack-compose-glimmer/skill"
    },
    {
      "id": "glasses.list.sole-scroller",
      "platforms": [
        "glasses"
      ],
      "kind": "structure",
      "severity": "warning",
      "guidance": "Scrolling containers like lists should be the only major component on a screen. Avoid placing a scrollable list directly above or below other interactive elements, such as buttons.",
      "check": "Is a List free of sibling buttons directly above or below it? Answer not_applicable when there is no list.",
      "source": "https://developer.android.com/develop/xr/jetpack-xr-sdk/jetpack-compose-glimmer/focus"
    },
    {
      "id": "glasses.card.not-in-list-item",
      "platforms": [
        "glasses"
      ],
      "kind": "structure",
      "severity": "warning",
      "guidance": "Never embed a card within a List Item.",
      "check": "Does no ListItem contain a Card? Answer not_applicable when there are no list items.",
      "source": "kb://android/agents/skills/xr/display-glasses-with-jetpack-compose-glimmer/skill"
    },
    {
      "id": "glasses.card.click-xor-nested",
      "platforms": [
        "glasses"
      ],
      "kind": "structure",
      "severity": "warning",
      "guidance": "Leave onClick as null if the Card contains multiple internal interactive elements, to avoid focus contention.",
      "check": "Does every clickable Card contain no buttons of its own? Answer not_applicable when no card is clickable.",
      "source": "kb://android/agents/skills/xr/display-glasses-with-jetpack-compose-glimmer/skill"
    },
    {
      "id": "glasses.title-chip.short",
      "platforms": [
        "glasses"
      ],
      "kind": "structure",
      "severity": "warning",
      "guidance": "Always center text in a title chip. Never let the title chip go to two lines. Keep the label to three words or less.",
      "check": "Is every TitleChip label three words or fewer, on one line? Answer not_applicable when there are no title chips.",
      "source": "kb://android/agents/skills/xr/display-glasses-with-jetpack-compose-glimmer/skill"
    },
    {
      "id": "glasses.icon.trigger-is-icon-button",
      "platforms": [
        "glasses"
      ],
      "kind": "structure",
      "severity": "warning",
      "guidance": "If an icon serves as a trigger, then you must use an IconButton.",
      "check": "Does every tappable icon use an IconButton rather than a clickable Icon? Answer not_applicable when no icon is tappable.",
      "source": "kb://android/agents/skills/xr/display-glasses-with-jetpack-compose-glimmer/skill"
    },
    {
      "id": "glasses.icon.no-black-tint",
      "platforms": [
        "glasses"
      ],
      "kind": "structure",
      "severity": "warning",
      "guidance": "NEVER use pure black (#000000) for icon tints.",
      "check": "Is no icon tinted #000000?",
      "source": "kb://android/agents/skills/xr/display-glasses-with-jetpack-compose-glimmer/skill"
    },
    {
      "id": "glasses.progress.honest",
      "platforms": [
        "glasses"
      ],
      "kind": "structure",
      "severity": "info",
      "guidance": "Don't use a determinate indicator when the processing time is unknown. Keep to one progress indicator at a time.",
      "check": "Is there at most one progress indicator, indeterminate when it shows loading of unknown length? Answer not_applicable when there are none.",
      "source": "https://developer.android.com/design/ui/ai-glasses/guides/components/progress"
    },
    {
      "id": "glasses.color.stock-neutrals",
      "platforms": [
        "glasses"
      ],
      "kind": "structure",
      "severity": "warning",
      "guidance": "While background, surface, outline, and outlineVariant are marked as customizable, we strongly recommend that you don't customize these values.",
      "check": "Are the background, surface, outline and outlineVariant colour roles left at their defaults? Answer not_applicable when the design overrides no colour roles.",
      "source": "https://developer.android.com/develop/xr/jetpack-xr-sdk/jetpack-compose-glimmer/colors"
    },
    {
      "id": "glasses.list-item.uniform",
      "platforms": [
        "glasses"
      ],
      "kind": "structure",
      "severity": "info",
      "guidance": "Always use a consistent background color and corner radius for every item. Don't vary these unless you are visually grouping different types of content.",
      "check": "Do the items of each List share one background and shape? Answer not_applicable when there is no list.",
      "source": "kb://android/agents/skills/xr/display-glasses-with-jetpack-compose-glimmer/skill"
    }
  ]
}
"""
