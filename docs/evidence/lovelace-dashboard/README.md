# Local dashboard browser evidence

Captured 2026-10-16 by `scripts/ui-builder-web-smoke/lovelace-dashboard.mjs` with the actual
packaged Wasm editor, harc host resources, generated harc catalog composed by
`PublishedUiBuilderCatalog`, and fresh Robolectric Remote Compose converter captures.
The final browser run used the current-main package and includes precise integer/decimal
import, preview and export, actual card drag/undo, delayed/failed/overlapping dashboard loads,
and rejection of stale editor replies. The native matrix covers tile on/off, Braun dark tile, gauge, climate tile and custom fallback.

`report.json` records the successful checks and render count. `dashboard.png` shows the dashboard
after section/view template placement and capture/import/reuse. `cards-classic.png` and
`cards-braun-dark.png` show the rendered canvas before and after the host changes tile styling.
The browser asserts that actual canvas pixels change; the native test separately checks repeated
inputs are stable and state/style changes alter converter bytes. Unknown/custom JSON is preserved
regardless of rendering support.

All Home Assistant traffic was intercepted at `dashboard-fixture.invalid`; writes went only to the
in-memory fixture transport. This is local integration evidence, not proof of deployment. The
coordinated PRs record release/deployment status. Reproduction commands are in
[the implementation note](../../design/LOVELACE_DASHBOARD_IMPLEMENTATION.md).

The full current-main editor JVM suite passed (1,957 cases, 14 opt-in skips), as did the exporter
suite (553 cases, 37 opt-in skips), runtime suite (411 cases, one opt-in skip) and runtime ABI check. The matching required JVM and web-archive
CI lanes also passed on the implementation PR.
