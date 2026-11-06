# Bitmap-font `.rc` for widget hosts

`widget-hosts.png` plays one widget document with the CMP player three ways:

- with Orbitron loaded, as the editor's canvas draws it;
- with no Orbitron, as a widget host draws the plain `.rc`: the heading and the bound reading
  fall back to the system face;
- the document after **Download .rc for a Wear widget**, again with no Orbitron: the heading
  (fixed) and the reading (bound to `USER:temperature`) are drawn from embedded glyphs, and the
  `sans-serif` label stays text.

`menu-before.png` and `menu-after.png` are the Export menu's rows (`ExportMenuRows`) for a catalog
with PNG, JSON and `.rc`, before and after this change.

All three were captured with `ImageComposeScene` at density 2 from a throwaway JVM test. The
conversion is `WidgetBitmapFonts.convert`, as `BrowserExportHost` calls it, over
`assets/rc-fonts/orbitron-variable.ttf`: 287 bytes in, about 31 KB out for one face at two sizes.
`WidgetBitmapFontsTest` covers the same document.
