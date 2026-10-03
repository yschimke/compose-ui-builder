# Landing page

The GitHub Pages site for Compose UI Builder: plain HTML/CSS/JS, no build step.
`.github/workflows/pages.yml` publishes this directory.

| File | |
| --- | --- |
| `index.html` | the page: the three-step hero, the showcase, Get started, features |
| `gallery.html`, `gallery.json` | the gallery; one manifest entry per design |
| `live.html`, `js/mcp-host.js` | the live editor, for any gallery design |
| `designs/*.uid` | the designs the page opens (see below) |
| `styles.css` | light and dark themes |
| `site.js` | the agent tabs, copy buttons, and `VIDEO_URL` |
| `img/*.png` | screenshots, generated (see below) |

## The live editor

`live.html?design=<gallery id>` runs the real editor with no server. The Pages workflow unpacks the
latest released `compose-preview-ui-builder-web-<v>.zip` at `editor/`, and `js/mcp-host.js` opens
its MCP App shell (`editor/mcp-app/ui-builder-mcp-app.html`) in a frame, playing the chat client's
side of that protocol with the `.uid` file held in the page. Edits are kept in this browser
(`localStorage`); **Reset** returns to the committed design, **Download .uid** saves it.

To try it locally, unpack a release zip at `site/editor/` (git-ignored) and serve the directory:
`python3 -m http.server -d site`.

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

The Pages workflow re-captures them on every deploy from the **latest release**, and runs weekly
and on each published release, so the page follows the editor without commits. The copies committed
here are the fallback if a capture fails and what a local preview shows. To refresh them:

```sh
cd scripts/site-screenshots
npm ci && npx playwright install chromium
# against a local build (./gradlew :ui-builder:wasmFrontendDist) ...
npm run capture
# ... or an unpacked release: compose-preview-ui-builder-web-<v>.zip
node capture.mjs /path/to/unpacked-zip ../../site/img
```

`SHOTS=hero,gallery/weather-widget` captures only some. If the editor's chrome moves, the click
points in `capture.mjs` (the Screen dock and its compare chips, the code toggle, the comment menu)
may need adjusting. Each can be overridden from the environment while tuning, `DEBUG_SHOTS=1` saves
the open comment menu, and `SHOTS=explore EXPLORE='x,y;x,y'` clicks through the editor saving a
picture after each step.

## Adding a gallery design

Add an entry to `gallery.json` (`id`, `title`, `kind`, `description`, `file`, `source`, and
`fixture` when it comes from a committed design), run the capture, and commit the design and its
`img/gallery/<id>.png`.

## The demo video

Set `VIDEO_URL` at the top of `site.js` (a YouTube embed URL or an `.mp4`). Until then the video
section and its button stay hidden.
