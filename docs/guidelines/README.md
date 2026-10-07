# Design guidelines check

The editor's **Issues** panel can check the design on screen against the Android design guides for
its platform: Wear OS for the `wear-m3` and `remote-m3` catalogs, AI glasses for `glimmer`. A model
reached through [OpenRouter](https://openrouter.ai) reads the design and answers one yes/no
question per rule. Each rule it judges broken is listed with the guidance it comes from and a link
to that guide. Findings are advice: they never block an export.

## Running it in the editor (your own key)

The check runs on **your** OpenRouter account. Anyone can use it, and nothing goes through the
design host:

1. Open a design and choose **Issues** on the inspector rail.
2. Under **Design guidelines**, either:
   - press **Connect OpenRouter**. You sign in at openrouter.ai and approve the editor, and
     OpenRouter returns you with a key. This is OpenRouter's
     [OAuth PKCE](https://openrouter.ai/docs/use-cases/oauth-pkce) flow, so there is no client
     secret and no server involved; or
   - press **Get a key**, which opens [openrouter.ai/settings/keys](https://openrouter.ai/settings/keys).
     Create a key (a small credit limit is sensible), paste it into **OpenRouter key**, and press
     **Save key**.
3. Press **Check guidelines**.

The key is kept in this browser's `localStorage` and is sent only to `openrouter.ai`. **Model &
key** changes the model (default `typesafe/jev-router`, TypeSafe's Jev decision model; any
OpenRouter model id works) or forgets the key. A key OpenRouter rejects is forgotten automatically.

When the editor is served by compose-preview-server, two more things go with the design tree: the
Jetpack Compose source the design exports to (from `export.compose`), so rules about code are
judged on the real calls, and the design's thumbnail, so the `visual` rules are judged too. Anywhere else, only the `structure` rules run and the panel says how
many visual rules were left out.

## Running it on the server (shared key)

compose-preview-server's `ui_builder_check_design` MCP tool takes `checks: ["guidelines"]`. That
check runs on the operator's key, only for the GitHub users and organizations the operator names.
See `deploy/image/README.md` → *UI-builder guidelines check* in compose-preview-server. It sends
the same request built from the same rules.

## The rules

[`android-design-guidelines.json`](android-design-guidelines.json) is the rule set. Each rule has:

| Field | Meaning |
| --- | --- |
| `id` | Stable name; it is the finding's `code`. |
| `platforms` | `wear`, `glasses`. |
| `kind` | `structure`: the design tree is enough evidence. `visual`: the model needs a picture, and the `check` says which one. |

| `severity` | `warning` or `info`. |
| `guidance` | The guidance as written at `source`. |
| `check` | A yes/no question; YES means the design follows the rule. |
| `source` | The page on developer.android.com, or a `kb://` Android Knowledge Base article. |

### Two pictures for scrolling screens

A Wear screen whose content scrolls is shown to the model twice:

- the **device picture**: the first frame on the round watch, scrolled to the top. Wear hides the edge button here; `ScreenScaffold` reveals it only when the list reaches its end.
- the **unrolled picture**: the same design on a tall canvas, so the whole list is visible and the edge button is revealed.

A visual rule's `check` names the picture it is judged on. Content running off the bottom of the device picture continues on scroll, so it is not counted as clipping.

The file is copied in two places:

- `ui-builder/.../guidelines/AndroidDesignGuidelinesJson.kt` in this repository. `DesignGuidelinesTest`
  fails when this copy differs from the file.
- `server/src/main/resources/.../guidelines/android-design-guidelines.json` in
  compose-preview-server. This copy is updated by hand, in a pull request there.

## Adding rules

The guides come from the Android Knowledge Base: the offline copy of developer.android.com that the
`android` CLI downloads for `android docs search`.

```sh
android docs search "wear"     # downloads ~/.android/cli/docs/kbzip/dac.zip once
node scripts/guidelines/extract-guidance.mjs --out /tmp/candidates.json
```

The script lists every normative sentence ("must", "should", "don't", "avoid", "at least", …) in
the Wear OS, AI glasses and mobile design guides, and the Wear Compose agent skill, with the page
each came from. Today that is about 440 sentences from about 160 pages.

Candidates are raw material, not rules. Pick the ones a model can judge from one screen, then for
each:

- write the `check` as a question about this design, saying when to answer `not_applicable`
  (for example "If this design is a Tile, …");
- choose `visual` only when the tree cannot answer it;
- leave out anything a measurement answers better. Touch target size, contrast and clipping at
  200% font scale are measured by compose-preview-server's `a11y` check, which is more reliable
  than a model's estimate.

The Material 3 component guidelines on m3.material.io are not in the Knowledge Base, and neither
are the Figma kits' usage annotations.
