# Wear panes

The opt-in **Wear panes** chip in the Frame inspector's "Also compare" row (off by default,
remembered per catalog in the browser as `ui-builder.wear-panes.<catalogSystemId>`) adds four
panes to the preview: the design on an approximate **round** and **square** (20% corner radius)
black watch screen, 240dp across, each in the **Small** (200×60dp content) and **Large**
(200×108dp content) Wear widget container. The container is the squircle host's: 26dp corners,
8dp padding, filled `Color(39, 36, 48)`, drawn by the same `wearHostFrame` the catalog's Wear
previews use.

| Capture | What it shows |
| --- | --- |
| [card.png](card.png) | A card (the weather widget's content without its container) on all four watches |
| [widget.png](widget.png) | A Large Wear widget: its own host panes, then the two Large watches, where it draws its own container |

Regenerate from the repository root:

```sh
WEAR_PANES_EVIDENCE="$PWD/docs/evidence/wear-panes" \
  ./gradlew :ui-builder:jvmTest --rerun --tests '*WearScreenPaneTest*'
```
