# Landing page

The GitHub Pages site for Compose UI Builder: plain HTML/CSS/JS, no build step.
`.github/workflows/pages.yml` publishes this directory.

| File | |
| --- | --- |
| `index.html` | the page: the three-step hero, the showcase, Get started, features |
| `styles.css` | light and dark themes |
| `site.js` | the agent tabs, copy buttons, and `VIDEO_URL` |
| `img/*.png` | screenshots, generated (see below) |

## Screenshots

Every PNG in `img/` is the real editor, captured by
[`scripts/site-screenshots/capture.mjs`](../scripts/site-screenshots/capture.mjs). It opens the
editor's MCP App shell in Chromium, beside a small chat column that stages the prompt, on designs
committed under `docs/design/fixtures/ui-builder/designs/`. The comment in step 3 is the real
`ui/message` the editor sends.

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

`SHOTS=hero,step-3-iterate` captures only some. If the editor's chrome moves, the click points in
`capture.mjs` (the code toggle, the comment menu) may need adjusting; `DEBUG_SHOTS=1` saves the open
menu in step 3, and each point can be overridden from the environment while tuning.

## The demo video

Set `VIDEO_URL` at the top of `site.js` (a YouTube embed URL or an `.mp4`). Until then the video
section and its button stay hidden.

## Previewing

Open `index.html` directly, or `python3 -m http.server -d site`.
