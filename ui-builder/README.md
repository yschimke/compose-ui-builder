# UI builder

This module is the extraction boundary for the Compose UI builder. It owns the candidate document
reducer, native Compose renderer and code exporter, but has no dependency on `:server`,
`:render-host` or `:wasm-ui`.

The public operation fixture remains under `docs/design/fixtures/ui-builder`. JVM tests consume that
same file directly; the Wasm application and eventual MCP adapter must call the same reducer API.
