# Design guidelines check

The editor's **Issues** panel can check the design on screen against the Android design guides for
its platform: Wear OS for the `wear-m3` and `remote-m3` catalogs, AI glasses for `glimmer`, and
phones and tablets (adaptive layout) for `m3`. A model
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

## Seeing the prompt, and sharing the result

**Show the prompt** in the Issues panel builds the exact request without sending it, so it needs no
key. It shows the system prompt, the user message, the pictures attached and one sentence on where
each part comes from, and **Copy prompt** puts the request on the clipboard as JSON without the
picture bytes. On compose-preview-server the request comes from the server
(`GET /api/ui-builder/v1/designs/{id}/guidelines/prompt`), with the native device and unrolled
pictures. It is the same request an agent gets from `ui_builder_guidelines_prompt`.

Each design keeps one shared result: its latest run, whoever ran it. A run on your own key is posted
back to it. So is a `ui_builder_check_design` run on the server key, and so is an agent's verdict
list recorded with `ui_builder_record_guidelines` after judging the prompt with its own model. The
panel shows that result with its model and who ran it, and says when it was checked against an
earlier revision. The shape is `compose-ui-builder/guidelines-result/v1` (`DesignGuidelineRecord`).

## The rules

[`android-design-guidelines.json`](android-design-guidelines.json) is the rule set. Each rule has:

| Field | Meaning |
| --- | --- |
| `id` | Stable name; it is the finding's `code`. |
| `platforms` | `wear`, `glasses`, `mobile`. |
| `surfaces` | Optional: `screen` or `widget`. A Wear widget (its root is a widget container) is asked only rules for widgets, and a screen only rules for screens; a rule without `surfaces` applies to both. Leave a rule out of a surface it can only ever answer `not_applicable` for. |
| `kind` | `structure`: the design tree is enough evidence. `visual`: the model needs a picture, and the `check` says which one. |

| `severity` | `warning` or `info`. |
| `guidance` | The guidance as written at `source`. |
| `check` | A yes/no question; YES means the design follows the rule. |
| `source` | The page on developer.android.com, or a `kb://` Android Knowledge Base article. |

### The pictures each design is shown in

`DesignGuidelineFrames.plan` (in `:ui-builder-export`, so the editor and compose-preview-server
share it) names the frames a design is drawn in. A visual rule's `check` names the picture it is
judged on.

| Design | Pictures |
| --- | --- |
| Wear screen | the **device picture**, its first frame on the watch, scrolled to the top. When a component its catalog marks `ScrollableContent` is on it, also the **unrolled picture**: the same design on a canvas four times as tall, so the whole list shows and `ScreenScaffold` reveals the edge button it hides on the first frame. Content running off the bottom of the device picture continues on scroll, so it is not clipping. |
| Wear widget | the **Samsung** picture (the stadium-shaped launcher container, fully rounded ends) and the **Pixel Watch** picture (rounded rectangle), at the widget's size from `WearWidgetScaffoldSize.hostSpec`. An adaptive widget is drawn at Large. |
| Phone or tablet (`m3`) | the **phone picture** at 412×915dp (compact) and the **tablet picture** at 1280×800dp (expanded), whatever size the design was authored at, so the adaptive rules can compare the two. |
| Anything else | the device picture. |

The file is the only copy. `:ui-builder-export:embedDesignGuidelines` embeds it at build time as
`DesignGuidelineRuleSet.Bundled`. The editor reads it from there, and so does compose-preview-server
through its `ui-builder-export` dependency, so a rule change reaches the server when it next bumps
`composeai-ui-builder`.

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
