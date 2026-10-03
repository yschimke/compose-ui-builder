# Landing page

The GitHub Pages site for Compose UI Builder: plain HTML/CSS/JS, no build step.
`.github/workflows/pages.yml` publishes this directory.

| File | |
| --- | --- |
| `index.html` | the page: the three-step hero, the showcase, Get started, features |
| `gallery.html`, `gallery.json` | the gallery; one manifest entry per design |
| `live.html`, `js/mcp-host.js` | the live editor, for any gallery design |
| `designs/*.uid` | the designs the page opens (see below) |
| `device-presets.json` | a copy of preview.coo.ee's `/api/ui-builder/v1/device-presets`, refreshed on every deploy |
| `styles.css` | light and dark themes |
| `site.js` | the agent tabs, copy buttons, the screenshot viewer, and `VIDEO_URL` |
| `img/*.png` | screenshots, generated (see below) |

## The live editor

`live.html?design=<gallery id>` runs the real editor with no server. The Pages workflow unpacks the
latest released `compose-preview-ui-builder-web-<v>.zip` at `editor/`, and `js/mcp-host.js` opens
its MCP App shell (`editor/mcp-app/ui-builder-mcp-app.html`) in a frame, playing the chat client's
side of that protocol with the `.uid` file held in the page. It also names
`editor/device-presets.json` through the shell's `__COMPOSE_UI_BUILDER_DEVICE_PRESETS__`
placeholder, so the Screen dock offers real device frames and the preview strip draws a design's
export devices. The copy has to sit under `editor/`: an MCP App fetches only below its asset base,
and editors up to 3.79 fail to start when the presets URL is outside it. Edits are kept in this browser
(`localStorage`); **Reset** returns to the committed design, **Download .uid** saves it.

To try it locally, unpack a release zip at `site/editor/` (git-ignored), copy
`device-presets.json` into it, and serve the directory: `python3 -m http.server -d site`.

## Designs

- `wear-list.uid`, `weather-widget.uid` are the templates exactly as **New from template** seeds
  them. `SiteTemplateDesignsTest` (`:ui-builder-export`) fails when a template change has not
  reached them; regenerate with
  `UPDATE_SITE_DESIGNS=1 ./gradlew :ui-builder-export:jvmTest --tests '*SiteTemplateDesignsTest*'`.
- Gallery entries with a `fixture` are replayed from
  `docs/design/fixtures/ui-builder/designs/<fixture>.json` by the capture script, which writes them
  here.

## Screenshots

Every PNG in `img/` is the real editor, captured by
[`scripts/site-screenshots/capture.mjs`](../scripts/site-screenshots/capture.mjs). The step and
showcase pictures open the editor's MCP App shell beside a small chat column that stages the
prompt; the comment in step 2 is the real `ui/message` the editor sends. The gallery pictures are
taken through `live.html` itself, so a capture also proves the live page boots.

The step and hero designs claim the small, large and XL round watches as export devices, so the
preview strip shows all three and the Compose export writes them as `@Preview(device = …)`.

**They update themselves.** `.github/workflows/site-screenshots.yml` runs on every push to `main`
that touches the editor, the site or the designs (and weekly): it builds that commit's editor,
captures, and when a picture changed opens or updates the `site-screenshots/refresh` pull request.
Merge it to publish them; the Pages workflow deploys the committed images as they are. Captures are
deterministic, so that pull request only appears when something visible moved. On a pull request,
CI's `ui-builder-web-smoke` job captures the same pictures from that pull request's editor and
uploads them as the `site-screenshots` artifact, without committing them.

To capture by hand:

```sh
cd scripts/site-screenshots
npm ci && npx playwright install chromium
# against a local build (./gradlew :ui-builder:wasmFrontendDist) ...
npm run capture
# ... or an unpacked release: compose-preview-ui-builder-web-<v>.zip
node capture.mjs /path/to/unpacked-zip ../../site/img
```

`SHOTS=hero,gallery/weather-widget` captures only some. If the editor's chrome moves, the click
points in `capture.mjs` (the Screen dock and its compare chips, the view menu, the code toggle, the comment and Quick edit menus)
may need adjusting. Each can be overridden from the environment while tuning, `DEBUG_SHOTS=1` saves
the open comment menu, and `SHOTS=explore EXPLORE='x,y;x,y'` clicks through the editor saving a
picture after each step (`EXPLORE_SCENE=chat` for step 2's focused canvas).

## Adding a gallery design

Add an entry to `gallery.json` (`id`, `title`, `kind`, `description`, `file`, `source`, and
`fixture` when it comes from a committed design), run the capture, and commit the design and its
`img/gallery/<id>.png`.

## The demo video

Set `VIDEO_URL` at the top of `site.js` (a YouTube embed URL or an `.mp4`). Until then the video
section and its button stay hidden.
