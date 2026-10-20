/** Drop EXIF / GPS / XMP / comments. Pixels stay. Used on every Typewriter image ingest. */

const PNG_KEEP = new Set(["IHDR", "PLTE", "IDAT", "IEND", "tRNS"]);

export function stripImageMeta(input, name, mime) {
    const buf = Buffer.isBuffer(input) ? input : Buffer.from(input);
    if (isPng(buf)) {
        return pack(stripPng(buf), "image/png", renameExt(name, ".png"));
    }
    if (isJpeg(buf)) {
        return pack(stripJpeg(buf), "image/jpeg", renameExt(name, ".jpg"));
    }
    if (isGif(buf)) {
        return pack(stripGif(buf), "image/gif", renameExt(name, ".gif"));
    }
    if (isWebp(buf)) {
        return pack(stripWebp(buf), "image/webp", renameExt(name, ".webp"));
    }
    void mime;
    return pack(buf, "application/octet-stream", name || "image.bin");
}

function pack(bytes, mime, name) {
    return { bytes, mime, name };
}

function renameExt(name, ext) {
    const raw = String(name || "image").replace(/[/\\]/g, "");
    const base = raw.replace(/\.[A-Za-z0-9]+$/, "");
    const clip = (base || "image").slice(0, 100);
    return clip + ext;
}

function isPng(b) {
    return b.length >= 8 && b[0] === 0x89 && b[1] === 0x50 && b[2] === 0x4e && b[3] === 0x47;
}

function isJpeg(b) {
    return b.length >= 4 && b[0] === 0xff && b[1] === 0xd8;
}

function isGif(b) {
    return b.length >= 6 && b[0] === 0x47 && b[1] === 0x49 && b[2] === 0x46;
}

function isWebp(b) {
    return b.length >= 12
        && b[0] === 0x52 && b[1] === 0x49 && b[2] === 0x46 && b[3] === 0x46
        && b[8] === 0x57 && b[9] === 0x45 && b[10] === 0x42 && b[11] === 0x50;
}

function stripPng(buf) {
    if (!isPng(buf)) return buf;
    const out = [buf.subarray(0, 8)];
    let i = 8;
    while (i + 12 <= buf.length) {
        const len = buf.readUInt32BE(i);
        if (len < 0 || i + 12 + len > buf.length) break;
        const type = buf.toString("ascii", i + 4, i + 8);
        const chunk = buf.subarray(i, i + 12 + len);
        if (PNG_KEEP.has(type)) out.push(chunk);
        i += 12 + len;
        if (type === "IEND") break;
    }
    return Buffer.concat(out);
}

/** Keep SOF/DHT/DQT/SOS/image, drop APP1 EXIF, COM, XMP, IPTC. */
function stripJpeg(buf) {
    if (!isJpeg(buf)) return buf;
    const out = [buf.subarray(0, 2)];
    let i = 2;
    while (i + 2 <= buf.length) {
        if (buf[i] !== 0xff) {
            out.push(buf.subarray(i));
            break;
        }
        let j = i;
        while (j < buf.length && buf[j] === 0xff) j++;
        if (j >= buf.length) break;
        const marker = buf[j];
        if (marker === 0xd9) {
            out.push(buf.subarray(i, j + 1));
            break;
        }
        if (marker === 0xda) {
            out.push(buf.subarray(i));
            break;
        }
        if (marker >= 0xd0 && marker <= 0xd7) {
            out.push(buf.subarray(i, j + 1));
            i = j + 1;
            continue;
        }
        if (j + 3 > buf.length) break;
        const len = buf.readUInt16BE(j + 1);
        const end = j + 1 + len;
        if (end > buf.length) break;
        const drop = marker === 0xfe || (marker >= 0xe1 && marker <= 0xef);
        if (!drop) out.push(buf.subarray(i, end));
        i = end;
    }
    return Buffer.concat(out);
}

function stripGif(buf) {
    if (!isGif(buf)) return buf;
    const out = [];
    let i = 0;
    if (buf.length < 13) return buf;
    out.push(buf.subarray(0, 13));
    i = 13;
    const packed = buf[10];
    if (packed & 0x80) {
        const gct = 3 * (1 << ((packed & 7) + 1));
        if (i + gct > buf.length) return buf;
        out.push(buf.subarray(i, i + gct));
        i += gct;
    }
    while (i < buf.length) {
        const b = buf[i];
        if (b === 0x3b) {
            out.push(buf.subarray(i, i + 1));
            break;
        }
        if (b === 0x2c) {
            if (i + 10 > buf.length) break;
            const imgPacked = buf[i + 9];
            let n = 10;
            if (imgPacked & 0x80) n += 3 * (1 << ((imgPacked & 7) + 1));
            n += 1;
            n += skipSubBlocks(buf, i + n);
            out.push(buf.subarray(i, i + n));
            i += n;
            continue;
        }
        if (b === 0x21) {
            if (i + 2 > buf.length) break;
            const label = buf[i + 1];
            let n = 2;
            if (label === 0xf9) {
                n = 8;
                out.push(buf.subarray(i, i + n));
                i += n;
                continue;
            }
            n += skipSubBlocks(buf, i + n);
            const keepLoop = label === 0xff && buf.length >= i + 13
                && buf.toString("ascii", i + 3, i + 11) === "NETSCAPE";
            if (keepLoop) out.push(buf.subarray(i, i + n));
            i += n;
            continue;
        }
        break;
    }
    return Buffer.concat(out);
}

function skipSubBlocks(buf, start) {
    let i = start;
    while (i < buf.length) {
        const sz = buf[i];
        i += 1;
        if (sz === 0) return i - start;
        i += sz;
    }
    return i - start;
}

function stripWebp(buf) {
    if (!isWebp(buf) || buf.length < 12) return buf;
    const chunks = [];
    let i = 12;
    while (i + 8 <= buf.length) {
        const fourcc = buf.toString("ascii", i, i + 4);
        const size = buf.readUInt32LE(i + 4);
        let total = 8 + size;
        if (total & 1) total += 1;
        if (i + total > buf.length) break;
        if (fourcc !== "EXIF" && fourcc !== "XMP " && fourcc !== "ICCP") {
            chunks.push(buf.subarray(i, i + total));
        }
        i += total;
    }
    const body = Buffer.concat(chunks);
    const out = Buffer.alloc(12 + body.length);
    buf.copy(out, 0, 0, 8);
    out.writeUInt32LE(body.length + 4, 4);
    buf.copy(out, 8, 8, 12);
    body.copy(out, 12);
    return out;
}
