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
  "version": 1,
  "about": "Design rules a model checks a UI-builder design against. Each rule quotes the guidance it comes from and links its source on developer.android.com (or the Android Knowledge Base for agent skills). `check` is a yes/no question where YES means the design follows the rule. `kind` is `structure` when the design tree is enough evidence, `visual` when it needs a picture of the design. Candidates are extracted with scripts/guidelines/extract-guidance.mjs and reviewed by a person before they are added here.",
  "rules": [
    {
      "id": "wear.layout.time-text-clear",
      "platforms": ["wear"],
      "kind": "visual",
      "severity": "warning",
      "guidance": "Accommodate Time Text if used, but don't overlap the top section of the page.",
      "check": "Is the time text at the top of the screen clear of any overlapping content?",
      "source": "https://developer.android.com/design/ui/wear/guides/surfaces/apps/layouts/non-scrolling"
    },
    {
      "id": "wear.layout.time-text-shown",
      "platforms": ["wear"],
      "kind": "structure",
      "severity": "info",
      "guidance": "To reinforce that the device is a watch, we recommend showing the time text even when in an app journey (optional, but recommended).",
      "check": "Does a full-screen app screen show time text (for example a screen scaffold with timeText set)? Answer not_applicable for tiles, widgets and dialogs.",
      "source": "https://developer.android.com/design/ui/wear/guides/surfaces/apps/layouts"
    },
    {
      "id": "wear.layout.no-clipping",
      "platforms": ["wear"],
      "kind": "visual",
      "severity": "warning",
      "guidance": "Margins should be defined in percentages to avoid clipping and provide proportional scaling of elements.",
      "check": "Is every piece of text, icon and control fully inside the round display, with nothing cut off by the screen edge?",
      "source": "https://developer.android.com/design/ui/wear/guides/foundations/adaptive-design"
    },
    {
      "id": "wear.layout.responsive-width",
      "platforms": ["wear"],
      "kind": "structure",
      "severity": "warning",
      "guidance": "Don't use components with a fixed width that don't fill the screen responsively or adjust the behavior of content to fill the available space. We recommend using percentage margins so the size of the margins adapts to the growing curve of the display.",
      "check": "Do the main controls and containers fill the available width (fillMaxWidth or the scaffold's padding) rather than use fixed dp widths or fixed dp side padding?",
      "source": "https://developer.android.com/design/ui/wear/guides/surfaces/apps/best-practices"
    },
    {
      "id": "wear.layout.column-never-scrolls",
      "platforms": ["wear"],
      "kind": "structure",
      "severity": "warning",
      "guidance": "Use a Column as a direct child of ScreenScaffold only if the screen will never scroll, even with the largest system font.",
      "check": "If the screen content is a plain non-scrolling column, is it short enough (a few short items) that it would still fit at the largest font size? Answer not_applicable when the content is a scrolling list.",
      "source": "kb://android/agents/skills/wear/wear-compose-m3/skill"
    },
    {
      "id": "wear.layout.more-value-when-larger",
      "platforms": ["wear"],
      "kind": "structure",
      "severity": "info",
      "guidance": "A larger display size should never display less information than ones that are smaller than it. Don't just scale up the design.",
      "check": "Does nothing in the design hide or remove content specifically on larger screens?",
      "source": "https://developer.android.com/design/ui/wear/guides/foundations/quality-tiers"
    },
    {
      "id": "wear.dialog.dedicated-task",
      "platforms": ["wear"],
      "kind": "structure",
      "severity": "warning",
      "guidance": "Dialogs should be direct in communicating information and dedicated to completing a task.",
      "check": "If this design is a dialog or confirmation, does it communicate one thing and offer only the actions needed to complete that one task? Answer not_applicable when it is not a dialog.",
      "source": "https://developer.android.com/design/ui/wear/guides/surfaces/apps/layouts/non-scrolling"
    },
    {
      "id": "wear.touch-target-48dp",
      "platforms": ["wear"],
      "kind": "structure",
      "severity": "warning",
      "guidance": "Ensure rows and controls are tall enough (at least 48dp). This is our minimum tap target size for accessibility.",
      "check": "Is every tappable control at least 48dp in both directions, judging from its component and any explicit size or height modifiers?",
      "source": "https://developer.android.com/design/ui/wear/guides/surfaces/tiles/bestpractices"
    },
    {
      "id": "wear.color.role-pairs",
      "platforms": ["wear"],
      "kind": "structure",
      "severity": "warning",
      "guidance": "Color roles come in pairs (container and on-container) that provide a minimum of 3:1 color contrast. Custom colors should keep that contrast.",
      "check": "Where the design overrides colors, does it pair each container color with its matching content (on-) role, or with colors that are clearly high contrast?",
      "source": "https://developer.android.com/design/ui/wear/guides/styles/color/roles-tokens"
    },
    {
      "id": "wear.empty-state-has-action",
      "platforms": ["wear"],
      "kind": "structure",
      "severity": "info",
      "guidance": "All empty states should have a clear call-to-action so the user knows what to do to fix a potential issue, or find out more information.",
      "check": "If this design shows an empty, error or signed-out state, does it offer a clear action? Answer not_applicable when it does not show such a state.",
      "source": "https://developer.android.com/design/ui/wear/guides/surfaces/tiles/states"
    },
    {
      "id": "wear.gestures.no-hidden-actions",
      "platforms": ["wear"],
      "kind": "structure",
      "severity": "warning",
      "guidance": "Never hide a gesture-only action. A visible element that performs the same task must be present.",
      "check": "Does every action bound to a gesture (long press, swipe, double tap) also have a visible control that performs it?",
      "source": "https://developer.android.com/design/ui/wear/guides/patterns/gestures"
    },
    {
      "id": "wear.tiles.black-background",
      "platforms": ["wear"],
      "kind": "visual",
      "severity": "warning",
      "guidance": "Always set the background color to black. Don't set the background as a full bleed image or block color.",
      "check": "If this design is a Tile, is its background black (no full-bleed image or block color)? Answer not_applicable when it is not a Tile.",
      "source": "https://developer.android.com/design/ui/wear/guides/surfaces/tiles/bestpractices"
    },
    {
      "id": "wear.tiles.no-app-icon",
      "platforms": ["wear"],
      "kind": "structure",
      "severity": "warning",
      "guidance": "Do not add the app icon in the Tile design as it may appear twice or overlapped if also displayed at the system level.",
      "check": "If this design is a Tile, is it free of the app's own launcher icon? Answer not_applicable when it is not a Tile.",
      "source": "https://developer.android.com/design/ui/wear/guides/surfaces/tiles/bestpractices"
    },
    {
      "id": "wear.tiles.focused",
      "platforms": ["wear"],
      "kind": "structure",
      "severity": "info",
      "guidance": "Tiles should feel focused, concise, and purpose-driven. Avoid providing a superfluous amount of functionality, and refrain from using more than one interactive component to trigger a specific action.",
      "check": "If this design is a Tile, does it focus on one task, with no two controls doing the same thing? Answer not_applicable when it is not a Tile.",
      "source": "https://developer.android.com/design/ui/wear/guides/surfaces/tiles/bestpractices"
    },
    {
      "id": "wear.tiles.no-decorative-containers",
      "platforms": ["wear"],
      "kind": "structure",
      "severity": "info",
      "guidance": "We don't recommend using containers for decorative or structural purposes, to avoid taps that don't do anything.",
      "check": "Does every container that looks tappable (card, chip, button-shaped surface) have an action?",
      "source": "https://developer.android.com/design/ui/wear/guides/surfaces/tiles/bestpractices"
    },
    {
      "id": "glasses.cards.single-action",
      "platforms": ["glasses"],
      "kind": "structure",
      "severity": "warning",
      "guidance": "If a card has an action, it should only contain a single action. Don't have multiple buttons within a card.",
      "check": "Does every card contain at most one button or action?",
      "source": "https://developer.android.com/design/ui/ai-glasses/guides/components/cards"
    },
    {
      "id": "glasses.lists.one-per-view",
      "platforms": ["glasses"],
      "kind": "structure",
      "severity": "warning",
      "guidance": "Don't have more than one list per view; this is overwhelming visually and focus wise.",
      "check": "Does the screen contain at most one list?",
      "source": "https://developer.android.com/design/ui/ai-glasses/guides/components/lists"
    },
    {
      "id": "glasses.title-chip.not-an-action",
      "platforms": ["glasses"],
      "kind": "structure",
      "severity": "warning",
      "guidance": "Don't use title chips as a tapable action prompt, since they do not have a focus state; use a button instead.",
      "check": "Are title chips used only as labels, never with a click or tap action?",
      "source": "https://developer.android.com/design/ui/ai-glasses/guides/components/title-chip"
    },
    {
      "id": "glasses.button-group.limits",
      "platforms": ["glasses"],
      "kind": "structure",
      "severity": "warning",
      "guidance": "Don't grow the button group past 10 buttons. Don't stack multiple button groups. Don't group buttons of different heights together.",
      "check": "Does each button group have 10 or fewer buttons of one height, with no button group stacked on another?",
      "source": "https://developer.android.com/design/ui/ai-glasses/guides/components/button-group"
    },
    {
      "id": "glasses.buttons.purposeful",
      "platforms": ["glasses"],
      "kind": "structure",
      "severity": "warning",
      "guidance": "Don't use a button as a static decorative element. Don't use a toggle button for non binary actions. Don't overwhelm the user's view with too many buttons.",
      "check": "Does every button have an action, is every toggle a true on/off choice, and are there only a few buttons on screen?",
      "source": "https://developer.android.com/design/ui/ai-glasses/guides/components/buttons"
    },
    {
      "id": "glasses.surfaces.dark",
      "platforms": ["glasses"],
      "kind": "visual",
      "severity": "warning",
      "guidance": "Surfaces must be black for highest contrast. Don't use bright or filled surfaces. Don't fill the screen with all white, as this can cause thermal mitigation.",
      "check": "Are the surfaces black or transparent, with no large bright or white areas?",
      "source": "https://developer.android.com/design/ui/ai-glasses/guides/styles/color"
    },
    {
      "id": "glasses.color.sparing",
      "platforms": ["glasses"],
      "kind": "visual",
      "severity": "info",
      "guidance": "Don't use color text for all content. Don't use overly saturated colors.",
      "check": "Is colored text kept for emphasis only, with body text in the default foreground color and no overly saturated colors?",
      "source": "https://developer.android.com/design/ui/ai-glasses/guides/styles/color"
    },
    {
      "id": "glasses.icons.unfilled",
      "platforms": ["glasses"],
      "kind": "visual",
      "severity": "info",
      "guidance": "Use unfilled icons to avoid halation, or light bleed. Use a heavier stroke weight and rounded icon variants; avoid thin strokes.",
      "check": "Are icons outlined (unfilled) and rounded, with no thin-stroke icons?",
      "source": "https://developer.android.com/design/ui/ai-glasses/guides/styles/icons"
    },
    {
      "id": "glasses.layout.bottom-anchored",
      "platforms": ["glasses"],
      "kind": "visual",
      "severity": "info",
      "guidance": "Design elements should be anchored to the bottom of the frame.",
      "check": "Is the content anchored to the bottom of the frame rather than floating at the top or center?",
      "source": "https://developer.android.com/design/ui/ai-glasses/guides/components/cards"
    }
  ]
}
"""
