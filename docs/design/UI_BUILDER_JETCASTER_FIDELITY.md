# Jetcaster renderer fidelity evidence

This change aligns catalog-driven Wasm rendering and capability-generated Compose with Material's
native content-color and floating-toolbar behavior. The authored 99-node Jetcaster document and
the independently compiled reference are unchanged.

The screenshots are deterministic 1280×800 Chromium captures. The before image is the generated
Compose application on `origin/main`; the after image is regenerated from the same operations
fixture after the exporter fixes.

| Before | After |
| --- | --- |
| ![Generated Jetcaster before fidelity fixes](../../preview-harness/snapshots/jetcaster-generated-fidelity-before.png) | ![Generated Jetcaster after fidelity fixes](../../preview-harness/snapshots/jetcaster-generated-fidelity-after.png) |

The same-browser `pixelmatch` measurements at threshold `0.1` changed as follows:

| Render | Before | After |
| --- | ---: | ---: |
| Builder, expanded 1280×800 | 15,293 px (1.493%) | 15,028 px (1.468%) |
| Builder, compact 412×800 | 4,088 px (1.240%) | 3,823 px (1.160%) |
| Generated Compose, expanded 1280×800 | 20,498 px (2.002%) | 14,806 px (1.446%) |
| Generated Compose, compact 412×800 | 9,271 px (2.813%) | 3,624 px (1.100%) |

These are convergence measurements, not a pixel-perfect release claim. The largest remaining
expanded mismatch is the independently authored reference's richer detail-episode copy and
actions: those elements do not exist in the frozen design document, so the renderer and exporter
must not synthesize them. Smaller differences remain around native FilterChip outlines, text
baselines, and the reference's authored podcast-header typography/action content.
