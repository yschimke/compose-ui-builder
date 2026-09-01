# Jetcaster renderer fidelity evidence

This change aligns catalog-driven Wasm rendering and capability-generated Compose with Material's
native content-color and floating-toolbar behavior. The authored 108-node Jetcaster document and
the independently compiled reference are unchanged.

The screenshots are deterministic 1280×800 Chromium captures. The before image is the generated
Compose application on `origin/main`; the after image is regenerated from the same operations
fixture after the exporter fixes.

| Before | After |
| --- | --- |
| ![Generated Jetcaster before detail authoring](../../preview-harness/snapshots/jetcaster-generated-fidelity-after.png) | ![Generated Jetcaster after detail authoring](../../preview-harness/snapshots/jetcaster-detail-fidelity-after.png) |

The same-browser `pixelmatch` measurements at threshold `0.1` changed as follows:

| Render | Before | After |
| --- | ---: | ---: |
| Builder, expanded 1280×800 | 15,028 px (1.468%) | 5,200 px (0.508%) |
| Builder, compact 412×800 | 4,088 px (1.240%) | 3,823 px (1.160%) |
| Generated Compose, expanded 1280×800 | 14,806 px (1.446%) | 5,075 px (0.496%) |
| Generated Compose, compact 412×800 | 9,271 px (2.813%) | 3,624 px (1.100%) |

These are convergence measurements, not a pixel-perfect release claim. The authored detail pane
now includes the reference's podcast label, queue and more actions, nested copy spacing, semibold
header title, and checked Following action. The remaining expanded mismatch is concentrated around
native chip/button outlines and text baselines; compact is intentionally unchanged because the
supporting detail pane is not present in that window class.
