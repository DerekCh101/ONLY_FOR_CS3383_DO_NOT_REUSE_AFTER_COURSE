/** EXIF/GPS/XMP stripping while keeping pixels, plus extension normalisation. */
import { test } from "node:test";
import assert from "node:assert/strict";
import { stripImageMeta } from "./stripImageMeta.js";

// ---------- helpers to build minimal container files ----------

function pngChunk(type, data) {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length, 0);
    const body = Buffer.concat([Buffer.from(type, "ascii"), data]);
    // CRC is not validated by the stripper, so a zero tail is fine here.
    return Buffer.concat([len, body, Buffer.alloc(4)]);
}

function tinyPng(extraChunks = []) {
    const sig = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
    const ihdr = pngChunk("IHDR", Buffer.alloc(13, 1));
    const idat = pngChunk("IDAT", Buffer.from([1, 2, 3]));
    const iend = pngChunk("IEND", Buffer.alloc(0));
    return Buffer.concat([sig, ihdr, ...extraChunks, idat, iend]);
}

function segment(marker, payload) {
    const len = Buffer.alloc(2);
    len.writeUInt16BE(payload.length + 2, 0);
    return Buffer.concat([Buffer.from([0xff, marker]), len, payload]);
}

// ---------- generic / fallback ----------

test("unknown data falls back to octet-stream and keeps the name", () => {
    const out = stripImageMeta(Buffer.from("not an image"), "notes.txt", "text/plain");
    assert.equal(out.mime, "application/octet-stream");
    assert.equal(out.name, "notes.txt");
    assert.deepEqual(Buffer.from(out.bytes), Buffer.from("not an image"));
});

test("missing name falls back to image.bin", () => {
    const out = stripImageMeta(Buffer.from("x"), null, null);
    assert.equal(out.name, "image.bin");
});

test("accepts plain strings and array-like input", () => {
    const a = stripImageMeta("hello", "a.bin", "application/octet-stream");
    const b = stripImageMeta([0x68, 0x65, 0x6c, 0x6c, 0x6f], "b.bin");
    assert.deepEqual(Buffer.from(a.bytes), Buffer.from("hello"));
    assert.deepEqual(Buffer.from(b.bytes), Buffer.from("hello"));
});

// ---------- PNG ----------

test("PNG keeps IHDR/IDAT/IEND and drops metadata chunks", () => {
    const text = pngChunk("tEXt", Buffer.from("Author\0me"));
    const exif = pngChunk("eXIf", Buffer.from([0x00, 0x01]));
    const out = stripImageMeta(tinyPng([text, exif]), "photo.png", "image/png");
    assert.equal(out.mime, "image/png");
    assert.equal(out.name, "photo.png");
    const ascii = out.bytes.toString("latin1");
    assert.ok(ascii.includes("IHDR"));
    assert.ok(ascii.includes("IDAT"));
    assert.ok(ascii.includes("IEND"));
    assert.ok(!ascii.includes("tEXt"));
    assert.ok(!ascii.includes("eXIf"));
});

test("PNG survives an unknown original extension", () => {
    const out = stripImageMeta(tinyPng(), "capture", "image/png");
    assert.equal(out.name, "capture.png");
});

test("PNG names keep the base but swap the extension", () => {
    const out = stripImageMeta(tinyPng(), "holiday.jpeg", "image/png");
    assert.equal(out.name, "holiday.png");
});

test("PNG name is clipped and path separators removed", () => {
    const out = stripImageMeta(tinyPng(), "../../" + "a".repeat(300) + ".png", "image/png");
    assert.equal(out.name.length, 104); // 100 base chars + ".png"
    assert.ok(!out.name.includes("/"));
});

// ---------- JPEG ----------

function tinyJpeg() {
    const soi = Buffer.from([0xff, 0xd8]);
    const app0 = segment(0xe0, Buffer.from("JFIF"));
    const exif = segment(0xe1, Buffer.from("Exif\0\0GPS"));
    const com = segment(0xfe, Buffer.from("a comment"));
    const sof = segment(0xc0, Buffer.alloc(15, 8));
    const sos = Buffer.concat([Buffer.from([0xff, 0xda]), Buffer.alloc(2, 0), Buffer.from([1, 2, 3])]);
    return Buffer.concat([soi, app0, exif, com, sof, sos]);
}

test("JPEG drops APP1 EXIF and COM but keeps APP0 and the scan", () => {
    const out = stripImageMeta(tinyJpeg(), "p.jpg", "image/jpeg");
    assert.equal(out.mime, "image/jpeg");
    const ascii = out.bytes.toString("latin1");
    assert.ok(ascii.includes("JFIF"));
    assert.ok(!ascii.includes("GPS"));
    assert.ok(!ascii.includes("a comment"));
    assert.deepEqual(out.bytes.subarray(-3), Buffer.from([1, 2, 3]));
});

test("JPEG names normalise to .jpg", () => {
    assert.equal(stripImageMeta(tinyJpeg(), "shot.PNG", "image/jpeg").name, "shot.jpg");
});

// ---------- GIF ----------

function tinyGif() {
    const header = Buffer.from("GIF89a", "ascii");
    const lsd = Buffer.from([0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00]);
    const comment = Buffer.from([0x21, 0xfe, 0x03, 0x61, 0x62, 0x63, 0x00]);
    const trailer = Buffer.from([0x3b]);
    return Buffer.concat([header, lsd, comment, trailer]);
}

test("GIF drops comment extension blocks", () => {
    const out = stripImageMeta(tinyGif(), "anim.gif", "image/gif");
    assert.equal(out.mime, "image/gif");
    assert.equal(out.name, "anim.gif");
    assert.equal(out.bytes.toString("latin1").includes("abc"), false);
    assert.equal(out.bytes[out.bytes.length - 1], 0x3b);
});

// ---------- WebP ----------

function webpChunk(fourcc, data) {
    const head = Buffer.alloc(8);
    head.write(fourcc, 0, 4, "ascii");
    head.writeUInt32LE(data.length, 4);
    const pad = data.length & 1 ? Buffer.from([0]) : Buffer.alloc(0);
    return Buffer.concat([head, data, pad]);
}

function tinyWebp(chunks) {
    const body = Buffer.concat(chunks);
    const head = Buffer.alloc(12);
    head.write("RIFF", 0, 4, "ascii");
    head.writeUInt32LE(body.length + 4, 4);
    head.write("WEBP", 8, 4, "ascii");
    return Buffer.concat([head, body]);
}

test("WebP drops EXIF/XMP/ICCP chunks and fixes the RIFF length", () => {
    const input = tinyWebp([
        webpChunk("VP8 ", Buffer.from([1, 2, 3])),
        webpChunk("EXIF", Buffer.from("gps-data")),
        webpChunk("XMP ", Buffer.from("<xmp/>")),
        webpChunk("ICCP", Buffer.from("icc")),
    ]);
    const out = stripImageMeta(input, "pic.webp", "image/webp");
    assert.equal(out.mime, "image/webp");
    const ascii = out.bytes.toString("latin1");
    assert.ok(!ascii.includes("gps-data"));
    assert.ok(!ascii.includes("<xmp/>"));
    assert.ok(ascii.includes("VP8 "));
    assert.equal(out.bytes.readUInt32LE(4), out.bytes.length - 8);
});

// ---------- signatures ----------

test("signature checks need the full magic bytes", () => {
    // Too short to be recognised, so it must fall through to the generic branch.
    assert.equal(stripImageMeta(Buffer.from([0x89, 0x50, 0x4e]), "x.png").mime, "application/octet-stream");
    assert.equal(stripImageMeta(Buffer.from([0xff, 0xd8]), "x.jpg").mime, "application/octet-stream");
    assert.equal(stripImageMeta(Buffer.from("GIF"), "x.gif").mime, "application/octet-stream");
});
