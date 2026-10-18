# Adaptive selection menus

Representative captures of the shared Material menu used by the canvas, layers tree and selection
overflow. `ModifierMenuUiTest` renders real selection-menu entries with the modifier registry
filtered for a column parent; the backdrop is a test fixture, not a saved design. The checked
"Fill the width" action and disabled Paste illustrate retained state and availability.

| Available modifier actions | Presentation | Capture |
| --- | --- | --- |
| 0–4 | Inline, in registry order | [Compact](compact.png) (3 actions) |
| 5–12 | One Modifiers submenu | [Medium](medium.png) (5 actions, submenu open) |
| More than 12 | Nonempty purpose groups in fixed order | [Large](large.png) |
| More than 12 | Layout & sizing submenu open | [Large with layout](large-layout.png) |

Groups use registry keys (including Remote call names), never display labels. Registry order is
preserved within each group. Unclassified future keys go to Behavior. A category exceeding twelve
items splits into numbered ranges, each containing at most twelve actions. Grouping runs after
catalog and parent-scope filtering, so it never introduces unavailable actions.

Menus use Material's viewport-aware popup positioning and scrolling. Root menus also cap at 720dp,
submenus at 600dp. The UI test reaches the twelfth action in a 360px-high viewport by scrolling.
Keyboard coverage includes Right to enter, Up/Down navigation, Left to return to the parent, Escape
to dismiss a submenu, and Enter to activate. Left and Right reverse in RTL layouts.

Regenerate the captures from the repository root:

```sh
MODIFIER_MENU_EVIDENCE="$PWD/docs/evidence/modifier-context-menu" \
  ./gradlew :ui-builder:jvmTest --rerun --tests '*ModifierMenuUiTest'
```

These are representative after screenshots for the oversized flat menu in the reported example.
