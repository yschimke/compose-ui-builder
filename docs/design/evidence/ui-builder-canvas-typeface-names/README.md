# Named faces on the Remote Compose canvas

`canvas-typefaces.png` plays one document through `RcComposePlayer` with the editor's faces loaded,
Orbitron under `google:Orbitron` and Lobster Two under `Lobster Two`, as the font registry keys
them, the document's own spelling. Left is the canvas loader as it was: the player asks for a
family by its normalised name (`orbitron`, `lobster two`), the lookup was verbatim, and both fell
back to the default face. Right is the loader matching by `canonicalFamilyName`. The `sans-serif`
line is the player's own and is the same in both.

Captured with `ImageComposeScene` at density 2 from a throwaway JVM test that renders the player
with each loader. It is the player and loader the canvas uses (`RemoteComposeCanvas.hostTypefaces`)
rather than a capture of the whole editor. `CanvasTypefaceNamesTest` pins the same behaviour and
fails against the old lookup.
