# Testing the editor as an agent

Three ways to see the editor work, in the order to try them. Each was verified end to end in a
cloud agent sandbox (Linux, no GPU, outbound traffic through an HTTPS proxy). Read
[Sandbox gotchas](#sandbox-gotchas) before debugging a failure: most of them are the sandbox, not
the editor.

| Option | Needs | Shows | Use it to |
| --- | --- | --- | --- |
| 1. Desktop JVM app under Xvfb | JDK 21, Xvfb, Gradle deps | The real editor, all three catalogs | Look at and click through Material 3, Wear M3 and Wear widget designs |
| 2. Web smoke test | Built `wasmDist`, Chromium | That the Wasm page boots (`data-ui-builder-ready`) | Prove a change did not break the browser build |
| 3. Deployed editor `https://preview.coo.ee/ui-builder/` | A browser that can fetch ~38 MB of Wasm | The shipped web build | Compare against the desktop app. Often **not** usable from a sandbox, see below |

The desktop app is the one to reach for when the question is "how does it look and feel". It is a
separate Compose Desktop target (`:ui-builder-desktop`), so the Wasm build and WebGL are not
involved at all.

## 1. Desktop JVM app under Xvfb

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64     # see gotcha 2 — do not skip
./gradlew --stop                                        # a daemon on another JDK will be reused
xvfb-run -a -s "-screen 0 1280x900x24" \
  build-brief ./gradlew --max-workers=1 :ui-builder-desktop:run --args='--catalog remote-m3' &
```

- `--catalog remote-m3` is the Wear widget catalog; omit it for Material 3. `--template <id>`
  starts from a template (`weather-widget`, …). Both are listed in
  [`UI_BUILDER_GETTING_STARTED.md`](UI_BUILDER_GETTING_STARTED.md).
- The first build downloads a lot. Expect several minutes and, in a sandbox, HTTP 429s (gotcha 1).
- The window opens at 800×600 in the **compact layout**: the canvas on top, and a bottom tab bar
  (Components, Layers, Properties, Code) whose panels are a bottom sheet. That is the closest thing
  here to a phone, but it is a landscape window, not a phone-shaped one.
- **File** (top left of the window) is the only way to pick a form factor: *New Material 3 design*,
  *New Wear M3 design*, *New Wear widgets design*, or *New from template*.

### Seeing and driving it

Xvfb has no screenshot tools installed, so use Java's `Robot`. It is committed at
[`scripts/ui-builder-desktop-drive/`](../scripts/ui-builder-desktop-drive/drive.sh):

```sh
D=scripts/ui-builder-desktop-drive/drive.sh
$D shot now.png                                           # screenshot the whole display
$D click 66 57 click 122 136 wait 400 shot templates.png  # File > New from template
```

Commands are `click X Y`, `wait MS`, `key KEYCODE`, `shot FILE`, chained in one call. Coordinates
are screen pixels of the 1280×900 display. Read the PNG afterwards to look at it.

Traps:

- A click that lands on an open bottom sheet is swallowed by the sheet. Close the sheet (tap its
  tab again) before clicking the canvas.
- A Swing menu stays open across separate `drive.sh` calls. Chain the whole menu path in one call
  or start from a `shot` to see what is open.
- Stop it with `pkill -f desktop.MainKt; pkill Xvfb`.

## 2. Web smoke test

`scripts/ui-builder-web-smoke/smoke.mjs` serves the built `wasmFrontendDist` and opens
`?mode=reference` and `?mode=interactive-editor` in Chromium, failing unless the page sets
`data-ui-builder-ready` with no page error. It proves the Wasm build boots; it does not prove the
editor looks right. It needs the Wasm build, which is the heaviest thing in the repo to compile, so
prefer CI for it.

## 3. The deployed editor

`https://preview.coo.ee/ui-builder/` needs WebGL (Skiko draws through it). When a browser has none,
the page says so in plain text rather than staying blank (`Main.kt`, `webGlAvailable`), so a
**stuck "Loading the editor" boot screen is not a WebGL problem**: it means the Wasm or `.mjs`
downloads did not finish. From an agent sandbox that has been the proxy dropping large or parallel
requests (`net::ERR_TOO_MANY_RETRIES`), and the page has no error or retry state for it. Playwright
needs `proxy: { server: process.env.HTTPS_PROXY }` and still failed for the 29 MB `uiBuilder.wasm`.
Do not spend long on this from a sandbox: use option 1.

## Sandbox gotchas

1. **HTTP 429 from Maven Central** during dependency resolution, on a different artifact each time.
   It is transient burst limiting by the proxy. Retry, with `--max-workers=1`; Gradle keeps what it
   downloaded, so each attempt gets further (one needed four tries). `curl` to the same URL works,
   so this is not a reachability problem.
2. **`UnsatisfiedLinkError: libfontmanager.so … GLIBC_ABI_DT_X86_64_PLT not found (required by
   /nix/store/…/libpthread.so.0)`** at `Skiko`/`setSystemLookAndFeel`. The sandbox shell sets
   `JAVA_HOME=/root/.cache/coo-ee/jdk-gl/17`, a Nix JDK. Gradle's daemon runs on it and the app
   process inherits its Nix library path, which mixes Nix glibc into the system JDK 21 that the
   toolchain picks. Fix: `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` and `./gradlew
   --stop` so the daemon restarts on it. A plain `java` AWT test passes, which is what makes this
   look like an app bug when it is not.
3. **No screenshot tools** (`xwd`, `import`, `scrot` are absent and there is no Pillow): use
   `drive.sh shot`.
4. **Fonts are DejaVu fallbacks** under Xvfb, so judge layout and behaviour, not typography.
5. **Mouse only.** Robot clicks are not touch events, so tap targets, gestures and drag-to-add
   are untested by this method.

## What a first pass found

A pass over the desktop app's compact layout (Material 3 Hello, Wear M3 screen, Weather widget,
Wear widget adaptive) found usability problems worth re-checking after related changes: the bottom
sheet covers the element being edited; tapping a button selects its inner text; the zoom control
overlaps the canvas corner; a phone design opens at 50% with unreadable text; the form factor is
reachable only from the File menu; and the Wear M3 template is nearly empty. Not covered: Layers,
drag-to-add, comments, export, touch.
