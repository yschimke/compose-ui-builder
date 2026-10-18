# Root-host offline creation evidence

Captured in Chromium on 2026-10-18. Before: the released 3.121.0 boot script on a local root host registers no worker; a fresh offline tab fails with ERR_INTERNET_DISCONNECTED. After: the updated editor on the root HTTP-contract harness reuses /push-sw.js, caches the published-only catalog and template, and creates a new browser-local design in a fresh tab after networking and fixture routes are disabled. The persisted document retains the complete catalog pin and published node IDs, with a new ID and revision zero.

The published-only fixture has one empty column, intentionally proving the document came from the publication rather than a built-in factory. The screenshot shows the browser-local offline status. No native rendering or production deployment is claimed.

Run the published lane of scripts/ui-builder-web-smoke/smoke.mjs against the built distribution to exercise both prefixed and root hosts. The root helper models the server contract; compose-preview-server's ServeUiBuilderHostRootTest checks the actual wrapper, capability gate, route exclusions and ETag. Production cold-offline verification remains a release/deployment gate before compatibility retirement.
