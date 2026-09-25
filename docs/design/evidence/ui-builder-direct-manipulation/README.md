# Direct manipulation: an unframed palette, and resize handles

Desktop Compose captures of the real editor (`UiBuilderEditor` with the Material chrome, dark
scheme, `m3-catalog`), taken from a `runDesktopComposeUiTest` harness. They are not mock-ups.

## The palette

`palette-before.png` is the editor as it was: every component sat in a filled, rounded card with its
id under the name and an **Add** text button under that. Each tile read as a small picture *of* a
component.

`palette-after.png` and `palette-after-buttons.png` are the same panel now. The card, its padding and
the id line are gone; the component is drawn straight onto the panel, clipped to its own box (plus
4dp for a shadow) and never magnified past its own size, so a Button looks like a Button sitting on
the shelf. The name sits under it with a small **+** beside it. A tile only gets a faint ground while
the pointer is over it; expanded variants keep a trace of one so a family reads as one component's
alternatives. Empty containers still draw their arrangement sketch.

The whole tile is now the grip, not only the picture. A mouse drags in any direction. A finger that
moves up or down scrolls the shelf; a finger that moves sideways, or is held for a long press, picks
the component up. On the compact layout the palette sheet fades out while a component is carried, so
the canvas under it can be dropped on.

## Resize handles

`resize-dragging.png`: the selected text with its end handle being dragged in. The new box is drawn
dashed with its size, `W 143dp`, beside it. Nothing is written until release.

`resize-landed.png`: after release the node carries `{"type":"width","widthDp":143}`, the text wraps
to the new width, the handles follow the new box, and the hover editor shows the width as a selected
`143dp` chip beside **Hug** and **Fill**.

Dragging an edge to within 12dp of the parent's edge snaps to **Fill** (`fillMaxWidth`/`fillMaxHeight`,
or `weight(1f)` on a row's or column's main axis). Double-clicking a handle flips that axis between
Fill and Hug. The corner handle is left off nodes too small to separate it from the edge handles.

Automated coverage: `NodeSizingTest` (the chain rewrite, snapping and toggling),
`DirectManipulationTest` (handles, chips and the palette's touch rules through the real pointer on
the real editor) and `CanvasMoveDragTest` (mouse and touch moves of the selection).
