# Jetcaster renderer fidelity evidence

This change aligns catalog-driven Wasm rendering and capability-generated Compose with Material's
native content-color, floating-toolbar, and compact-layout behavior. The independently compiled
reference remains unchanged; the authored 108-node Jetcaster document now records the reference's
search elevation, podcast-card padding and icon color, summary color, and episode metadata padding.

The screenshots are deterministic 412×800 Chromium captures. The before image is the checked-in
compact builder baseline; the after image is rendered from the aligned operations fixture.

| Before | After |
| --- | --- |
| ![Compact builder before authored alignment](../../preview-harness/snapshots/jetcaster-discover-compact-builder.png) | ![Compact builder after authored alignment](../../preview-harness/snapshots/jetcaster-compact-fidelity-after.png) |

The same-browser `pixelmatch` measurements at threshold `0.1` changed as follows:

| Render | Before | After |
| --- | ---: | ---: |
| Builder, expanded 1280×800 | 5,200 px (0.508%) | 2,167 px (0.212%) |
| Builder, compact 412×800 | 3,823 px (1.160%) | 635 px (0.193%) |
| Generated Compose, expanded 1280×800 | 5,075 px (0.496%) | 2,042 px (0.199%) |
| Generated Compose, compact 412×800 | 3,624 px (1.100%) | 436 px (0.132%) |

These are convergence measurements, not a pixel-perfect release claim. Both compact renderers now
pass the same 1% independent-oracle threshold as expanded mode. The matching movement in the Wasm
builder and independently generated Compose application demonstrates that the previous compact
delta was authored structure and styling, rather than platform font raster noise. The remaining
mismatch is concentrated around native chip/button outlines and text baselines.
