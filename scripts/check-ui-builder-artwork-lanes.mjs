import { readFile } from "node:fs/promises";
import { resolve } from "node:path";

const root = resolve(import.meta.dirname, "..");
const read = (path) => readFile(resolve(root, path), "utf8");
const manifest = JSON.parse(
    await read(
        "ui-builder-artwork/src/commonMain/composeResources/files/artwork/manifest-v1.json",
    ),
);
const fixture = JSON.parse(
    await read("docs/design/fixtures/ui-builder/jetcaster-discover-operations-v1.json"),
);
const provenance = JSON.parse(
    await read("ui-builder-reference-jetcaster/src/wasmJsMain/resources/provenance.json"),
);

if (manifest.source.networkRequired !== false || manifest.source.upstreamArtwork !== false) {
    throw new Error("project-owned artwork manifest must stay offline and must not claim upstream pixels");
}
if (manifest.source.pngEncoding !== "zlib-fixed-lz77-v1") {
    throw new Error("project-owned artwork must use the pinned cross-platform PNG encoder");
}
const generator = await read("scripts/generate-ui-builder-artwork.mjs");
if (/node:zlib|deflateSync/.test(generator)) {
    throw new Error("artwork generator must not depend on platform/version-specific native zlib output");
}
const serializedManifest = JSON.stringify(manifest).toLowerCase();
for (const mutableSource of ["http://", "https://", "rss", "feed.xml"]) {
    if (serializedManifest.includes(mutableSource)) {
        throw new Error(`artwork manifest contains mutable/network source '${mutableSource}'`);
    }
}

const manifestKeys = manifest.assets.map((asset) => asset.assetKey).sort();
const fixtureKeys = [
    ...new Set(
        fixture.operations
            .filter((operation) => operation.node?.componentId === "asset/image")
            .map((operation) => operation.node.properties.assetKey.value),
    ),
].sort();
if (JSON.stringify(manifestKeys) !== JSON.stringify(fixtureKeys)) {
    throw new Error(`manifest/fixture asset keys drifted: ${manifestKeys} != ${fixtureKeys}`);
}
const provenanceKeys = provenance.artwork.map((asset) => asset.assetKey).sort();
if (JSON.stringify(manifestKeys) !== JSON.stringify(provenanceKeys)) {
    throw new Error(`reference provenance asset keys drifted: ${provenanceKeys}`);
}

const lanes = [
    [
        "reference",
        "ui-builder-reference-jetcaster/src/wasmJsMain/kotlin/ee/schimke/composeai/uibuilderreference/jetcaster/Main.kt",
        "ProjectOwnedJetcasterArtwork",
    ],
    [
        "builder",
        "ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderRenderer.kt",
        "ProjectOwnedJetcasterArtwork",
    ],
    [
        "SVG",
        "ui-builder/src/jvmMain/kotlin/ee/schimke/composeai/uibuilder/JvmSkiaStructuredSvgRecorder.kt",
        "readProjectOwnedJetcasterArtwork",
    ],
    [
        "generated",
        "ui-builder-generated-jetcaster/src/wasmJsMain/kotlin/generated/uibuilder/JetcasterDiscoverExpanded.kt",
        "ProjectOwnedJetcasterArtwork",
    ],
];
for (const [name, path, binding] of lanes) {
    const source = await read(path);
    if (!source.includes(binding)) throw new Error(`${name} lane lost shared artwork binding`);
    if (/https?:\/\/|rss|feed\.xml/i.test(source)) {
        throw new Error(`${name} artwork lane contains a network or mutable-feed source`);
    }
}

const generated = await read(lanes.find(([name]) => name === "generated")[1]);
if (generated.includes("Color(0xFF0B57D0)") || generated.includes("jetcaster-benchmark-artwork")) {
    throw new Error("generated lane still contains the old painted benchmark placeholder");
}
