# Catalog fonts (vendored)

Every family is a **variable** font except Lobster Two, which has no variable release. A variable
file is listed in [`fonts.json`](fonts.json) once per weight it covers (`100`…`900`, clipped to
its `wght` range), every row naming the same file: the loaders read each file once and build each
weight as an instance of it (`FontVariation.weight`), so `FontWeight.Medium` is the 500 instance
rather than the nearest static, and a text's own variation settings reach the same bytes.

The variable files are subset to the code points the static files they replaced covered (all
axes and all layout features kept). Two have gaps their variable sources do not fill, so those
characters fall back to the platform's face:

- Noto Serif (112 code points, from Robolectric's static): Coptic letters U+03E2–03EF, arrows
  (`←↑→↓↔↕↨`), math operators (`∂∆∏∑∕∙√∞∟∩∫≈≠≡≤≥`), box drawing and blocks U+2500–25FF, and a few
  miscellaneous symbols. Serif text rarely carries them; set a `monospace` or default face for
  diagrams.
- Noto Sans Mono (8, from Droid Sans Mono): the soft hyphen, `∏∑∫` and the `ﬁ ﬂ ﬃ ﬄ`
  presentation forms (the plain letters still shape).

- `RobotoFlex.ttf` — the default face (`role: "default"`; applied to the whole M3 `Typography`)
  and Wear's `roboto-flex`. SIL OFL-1.1 — see [RobotoFlex-OFL.txt](RobotoFlex-OFL.txt).
- `NotoSerif-Variable.ttf` + `NotoSansMono-Variable.ttf` — the generic `serif` / `monospace`
  families (`role: "generic"`; consumed by `genericFontFamily(...)` lookups in catalog components —
  CMP's `FontFamily.Resolver` is sealed, so resolver-level interception isn't available to apps).
  Noto Serif is what Android's `fonts.xml` maps `serif` to; Noto Sans Mono replaces Droid Sans
  Mono, which has no variable release. Both from google/fonts, SIL OFL-1.1 — see
  [NotoSerif-OFL.txt](NotoSerif-OFL.txt) and [NotoSansMono-OFL.txt](NotoSansMono-OFL.txt).
  These were Robolectric's static files; the variable ones draw slightly differently from the
  Android snapshot bakes.
- `orbitron-variable.ttf`, `space-grotesk-variable.ttf`, `jetbrains-mono-variable.ttf`,
  `inter-variable.ttf`, `google-sans-flex-variable.ttf` — downloadable **GoogleFont**s
  (`role: "named"`), the faces catalog themes and specimens name as `google:<Family>`: Orbitron for
  the `text-branded` specimen, Google Sans Flex for the `remote-m3` catalog's typeface theme, Inter
  for its four conference themes (AndroidMakers, ConfettiDefault, Droidcon, KotlinConf). The
  `remote-m3` lane is *manifest-only* — it never fetches — so a named face missing here fails
  `RcComposeSupport.fontFamilyIssue`'s availability check instead of rendering. Each is the
  google/fonts variable file, SIL OFL-1.1 — see the matching `*-OFL.txt`; Google Sans Flex's is
  [GoogleSansFlex-OFL.txt](GoogleSansFlex-OFL.txt), from `ofl/googlesansflex/OFL.txt`.
- `LobsterTwo-Regular.ttf` + `LobsterTwo-Bold.ttf` — static, the one family with no variable
  release. SIL OFL-1.1 — see [LobsterTwo-OFL.txt](LobsterTwo-OFL.txt).

The committed [`fonts.json`](fonts.json) is the **dev-time default**; the design-catalog export
regenerates it from the per-preview `fonts/used` records (`previews/<id>.fonts.json` in the packed
bundle → `scripts/design-artifacts/render-fonts-manifest.mjs`), so the published manifest tracks
what the catalog's previews actually resolve. The regeneration also **preserves this file's
`role: "default"` and `role: "named"` families** (whose files are still vendored): they are the
catalog's declared **theme-override** typefaces (a Roboto Flex default, a Lobster Two named face),
applied to *clean* previews only via the theme wrapper, so the recorder never sees them and would
otherwise drop them — leaving the published viewer's font-override picks falling back.

Loading is driven by [`fonts.json`](fonts.json): each `role: "default"` family's files are
fetched **by URL** and become the app's whole M3 type scale (`Main.kt` → `loadCatalogFonts()`,
default base `./fonts/`, overridable via `?fontsBase=`). Self-hosted beside the app so the bundle stays offline-clean behind an
egress proxy; on the public server the serve process is the cache — it fetches these files once
from the trusted `design-artifacts` branch and serves them locally. A fetch failure or timeout
degrades to the CMP bundled font.

`index.html` starts the manifest + font fetches at document load, in parallel with the Wasm boot,
and the app consumes those in-flight promises — so fonts add no latency to the first frame. The
prefetch must live in the iframe itself: the sandbox's opaque origin has its own HTTP-cache
partition, so the embedding viewer page cannot warm fonts for it.

The manifest is additive: future roles (named families, generic-family mappings like `serif`)
can be declared per family without breaking older apps, which only consume `role: "default"`.

License: every face here is SIL OFL-1.1; each family's `*-OFL.txt` sits beside it.
