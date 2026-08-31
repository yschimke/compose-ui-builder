import { createHash } from "node:crypto";
import { readFile, writeFile, mkdir } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import process from "node:process";

const ROOT = resolve(import.meta.dirname, "..");
const SIZE = 512;
const FILE_ROOT = resolve(
    ROOT,
    "ui-builder-artwork/src/commonMain/composeResources",
);
const variants = [
    {
        assetKey: "jetcaster.cover.android-developers-backstage",
        id: "project-owned-android-audio-512-v1",
        fileStem: "jetcaster-owned-android-512",
        palette: [0x1259c3, 0x05a58d, 0x111b32],
        accent: [230, 246, 255],
    },
    {
        assetKey: "jetcaster.cover.google-developers-podcast",
        id: "project-owned-google-audio-512-v1",
        fileStem: "jetcaster-owned-google-512",
        palette: [0xd94b41, 0xf0ae2c, 0x264f9b],
        accent: [255, 246, 224],
    },
];
const expectedHashes = new Map([
    [
        "jetcaster.cover.android-developers-backstage",
        {
            encoded: "c043b2552d5bcec590ded52bdccc820c319d31260307eeb43393d24fb112e4b3",
            decoded: "f339470758577b581d590e44bd07f421a5aa270048713bfb25e69bbc0717d5c6",
        },
    ],
    [
        "jetcaster.cover.google-developers-podcast",
        {
            encoded: "eb95e6a98ca0b2b67a47ce463b3e11dd1f93e99a1e51932f9fcc00d823d95f42",
            decoded: "4bf3d4e0c8251f00e18b5eb1b73ca2f4a20d613f9ac275ff894d552a23cd932e",
        },
    ],
]);

function sha256(bytes) {
    return createHash("sha256").update(bytes).digest("hex");
}

function color(value) {
    return [(value >> 16) & 255, (value >> 8) & 255, value & 255];
}

function blendChannel(bottom, top, alpha) {
    return Math.round((bottom * (255 - alpha) + top * alpha) / 255);
}

function renderArtwork(spec) {
    const rgba = Buffer.alloc(SIZE * SIZE * 4);
    const palette = spec.palette.map(color);
    for (let y = 0; y < SIZE; y++) {
        for (let x = 0; x < SIZE; x++) {
            const diagonal = x + y;
            const half = SIZE - 1;
            const segment = diagonal <= half ? 0 : 1;
            const local = segment === 0 ? diagonal : diagonal - half;
            const amount = Math.min(255, Math.round((local * 255) / half));
            const from = palette[segment];
            const to = palette[segment + 1];
            const pixel = from.map((channel, index) =>
                blendChannel(channel, to[index], amount),
            );

            const lightDx = x - 390;
            const lightDy = y - 118;
            if (lightDx * lightDx + lightDy * lightDy <= 170 * 170) {
                for (let channel = 0; channel < 3; channel++)
                    pixel[channel] = blendChannel(pixel[channel], 255, 34);
            }
            const darkDx = x - 112;
            const darkDy = y - 366;
            if (darkDx * darkDx + darkDy * darkDy <= 112 * 112) {
                for (let channel = 0; channel < 3; channel++)
                    pixel[channel] = blendChannel(pixel[channel], 0, 42);
            }

            // Project-owned audio mark: three equalizer bars within a ring.
            const centerDx = x - 256;
            const centerDy = y - 256;
            const radiusSquared = centerDx * centerDx + centerDy * centerDy;
            const inRing = radiusSquared >= 132 * 132 && radiusSquared <= 148 * 148;
            const bar =
                (x >= 154 && x < 194 && y >= 214 && y < 338) ||
                (x >= 236 && x < 276 && y >= 166 && y < 338) ||
                (x >= 318 && x < 358 && y >= 234 && y < 338);
            if (inRing || bar) {
                for (let channel = 0; channel < 3; channel++)
                    pixel[channel] = blendChannel(pixel[channel], spec.accent[channel], 205);
            }

            const offset = (y * SIZE + x) * 4;
            rgba[offset] = pixel[0];
            rgba[offset + 1] = pixel[1];
            rgba[offset + 2] = pixel[2];
            rgba[offset + 3] = 255;
        }
    }
    return rgba;
}

const crcTable = Array.from({ length: 256 }, (_, value) => {
    let crc = value;
    for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ (crc & 1 ? 0xedb88320 : 0);
    return crc >>> 0;
});

function crc32(bytes) {
    let crc = 0xffffffff;
    for (const byte of bytes) crc = (crc >>> 8) ^ crcTable[(crc ^ byte) & 255];
    return (crc ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
    const name = Buffer.from(type, "ascii");
    const body = Buffer.concat([name, data]);
    const checksum = Buffer.alloc(4);
    checksum.writeUInt32BE(crc32(body));
    const length = Buffer.alloc(4);
    length.writeUInt32BE(data.length);
    return Buffer.concat([length, body, checksum]);
}

function encodePng(rgba) {
    const ihdr = Buffer.alloc(13);
    ihdr.writeUInt32BE(SIZE, 0);
    ihdr.writeUInt32BE(SIZE, 4);
    ihdr[8] = 8;
    ihdr[9] = 6;
    const raw = Buffer.alloc((SIZE * 4 + 1) * SIZE);
    for (let y = 0; y < SIZE; y++) {
        const row = y * (SIZE * 4 + 1);
        raw[row] = 1; // deterministic PNG Sub filter
        for (let index = 0; index < SIZE * 4; index++) {
            const source = y * SIZE * 4 + index;
            const left = index >= 4 ? rgba[source - 4] : 0;
            raw[row + 1 + index] = (rgba[source] - left + 256) & 0xff;
        }
    }
    return Buffer.concat([
        Buffer.from("89504e470d0a1a0a", "hex"),
        chunk("IHDR", ihdr),
        chunk("IDAT", encodeDeterministicZlibFixed(raw)),
        chunk("IEND", Buffer.alloc(0)),
    ]);
}

// Native zlib output is allowed to vary by zlib/Node version even for the same decoded pixels.
// Emit a fixed-Huffman DEFLATE stream with a repository-owned greedy matcher so encoded PNG bytes
// are a cross-platform contract rather than an implementation-dependent side effect.
function encodeDeterministicZlibFixed(bytes) {
    const output = [];
    let bits = 0;
    let bitCount = 0;
    const writeBits = (value, count) => {
        bits |= value << bitCount;
        bitCount += count;
        while (bitCount >= 8) {
            output.push(bits & 0xff);
            bits >>>= 8;
            bitCount -= 8;
        }
    };
    const reverse = (value, count) => {
        let reversed = 0;
        for (let index = 0; index < count; index++) {
            reversed = (reversed << 1) | ((value >>> index) & 1);
        }
        return reversed;
    };
    const writeLiteral = (symbol) => {
        if (symbol <= 143) writeBits(reverse(0x30 + symbol, 8), 8);
        else if (symbol <= 255) writeBits(reverse(0x190 + symbol - 144, 9), 9);
        else if (symbol <= 279) writeBits(reverse(symbol - 256, 7), 7);
        else writeBits(reverse(0xc0 + symbol - 280, 8), 8);
    };
    const lengthBases = [
        3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67,
        83, 99, 115, 131, 163, 195, 227, 258,
    ];
    const lengthExtra = [
        0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5,
        5, 5, 5, 0,
    ];
    const distanceBases = [
        1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513,
        769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577,
    ];
    const distanceExtra = [
        0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10,
        11, 11, 12, 12, 13, 13,
    ];
    const writeMatch = (length, distance) => {
        const lengthIndex = lengthBases.findLastIndex((base) => base <= length);
        writeLiteral(257 + lengthIndex);
        writeBits(length - lengthBases[lengthIndex], lengthExtra[lengthIndex]);
        const distanceIndex = distanceBases.findLastIndex((base) => base <= distance);
        writeBits(reverse(distanceIndex, 5), 5);
        writeBits(distance - distanceBases[distanceIndex], distanceExtra[distanceIndex]);
    };

    writeBits(1, 1); // final block
    writeBits(1, 2); // fixed Huffman block (01, least-significant bit first)
    const lastPosition = new Map();
    const keyAt = (index) =>
        index + 2 < bytes.length
            ? (bytes[index] << 16) | (bytes[index + 1] << 8) | bytes[index + 2]
            : null;
    for (let position = 0; position < bytes.length; ) {
        const key = keyAt(position);
        const previous = key == null ? undefined : lastPosition.get(key);
        let length = 0;
        if (previous != null && position - previous <= 0x8000) {
            const maximum = Math.min(258, bytes.length - position);
            while (length < maximum && bytes[previous + length] === bytes[position + length]) {
                length++;
            }
        }
        if (length >= 3) {
            writeMatch(length, position - previous);
            for (let skipped = 0; skipped < length; skipped++) {
                const skippedKey = keyAt(position + skipped);
                if (skippedKey != null) lastPosition.set(skippedKey, position + skipped);
            }
            position += length;
        } else {
            writeLiteral(bytes[position]);
            if (key != null) lastPosition.set(key, position);
            position++;
        }
    }
    writeLiteral(256);
    if (bitCount > 0) output.push(bits & 0xff);
    let first = 1;
    let second = 0;
    for (const byte of bytes) {
        first = (first + byte) % 65521;
        second = (second + first) % 65521;
    }
    const checksum = Buffer.alloc(4);
    checksum.writeUInt32BE(((second << 16) | first) >>> 0);
    return Buffer.concat([Buffer.from([0x78, 0x01]), Buffer.from(output), checksum]);
}

const generated = variants.map((spec) => {
    const decoded = renderArtwork(spec);
    const encoded = encodePng(decoded);
    return { spec, decoded, encoded };
});
for (const { spec, decoded, encoded } of generated) {
    const expected = expectedHashes.get(spec.assetKey);
    if (sha256(encoded) !== expected?.encoded || sha256(decoded) !== expected?.decoded) {
        throw new Error(`cross-platform artwork hash contract changed for ${spec.assetKey}`);
    }
}

const manifest = {
    schema: "compose-preview-project-owned-artwork/v1",
    source: {
        kind: "project-owned-procedural",
        generator: "scripts/generate-ui-builder-artwork.mjs",
        pngEncoding: "zlib-fixed-lz77-v1",
        upstreamArtwork: false,
        networkRequired: false,
    },
    license: {
        spdx: "Apache-2.0",
        file: "LICENSE",
        scope: "Artwork pixels generated by this repository; no upstream podcast artwork copied.",
    },
    assets: generated.map(({ spec, decoded, encoded }) => ({
        assetKey: spec.assetKey,
        sourceVariant: spec.id,
        widthPx: SIZE,
        heightPx: SIZE,
        encodedSha256: sha256(encoded),
        decodedRgbaSha256: sha256(decoded),
        drawableResource: `drawable/${spec.fileStem}.png`,
        byteResource: `files/artwork/${spec.fileStem}.png`,
    })),
};
const manifestBytes = Buffer.from(`${JSON.stringify(manifest, null, 2)}\n`);

const outputs = new Map();
for (const { spec, encoded } of generated) {
    outputs.set(resolve(FILE_ROOT, `drawable/${spec.fileStem}.png`), encoded);
    outputs.set(resolve(FILE_ROOT, `files/artwork/${spec.fileStem}.png`), encoded);
}
outputs.set(resolve(FILE_ROOT, "files/artwork/manifest-v1.json"), manifestBytes);

if (process.argv.includes("--check")) {
    const failures = [];
    for (const [path, expected] of outputs) {
        try {
            const actual = await readFile(path);
            if (!actual.equals(expected)) failures.push(`${path} is stale`);
        } catch {
            failures.push(`${path} is missing`);
        }
    }
    if (failures.length) throw new Error(failures.join("\n"));
} else {
    for (const [path, bytes] of outputs) {
        await mkdir(dirname(path), { recursive: true });
        await writeFile(path, bytes);
    }
}
