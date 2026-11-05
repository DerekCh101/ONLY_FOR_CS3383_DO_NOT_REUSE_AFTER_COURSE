/** Chunk-strip Typewriter photos so EXIF, GPS, XMP, and comments never stay on disk. */
package org.ashoreprospect.GUI;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class ImageMetaStrip {

    private static final File IMAGE_DIR = new File("typewriter-images");
    private static final Set<String> PNG_KEEP = Set.of("IHDR", "PLTE", "IDAT", "IEND", "tRNS");

    public static final class Result {
        public final byte[] bytes;
        public final String mime;
        public final String name;

        Result(byte[] bytes, String mime, String name) {
            this.bytes = bytes;
            this.mime = mime;
            this.name = name;
        }
    }

    private ImageMetaStrip() {}

    public static File copyStripped(File src) throws IOException {
        if (src == null || !src.isFile()) throw new IOException("image missing");
        Result r;
        try {
            r = strip(Files.readAllBytes(src.toPath()), src.getName());
        } catch (RuntimeException e) {
            throw new IOException(e.getMessage() == null ? "could not strip image metadata" : e.getMessage());
        }
        IMAGE_DIR.mkdirs();
        String sha = sha256Hex(r.bytes);
        File out = new File(IMAGE_DIR, sha + extOf(r.name));
        Files.write(out.toPath(), r.bytes);
        return out;
    }

    public static byte[] clean(byte[] raw, String name) {
        if (raw == null) return null;
        return strip(raw, name).bytes;
    }

    public static Result strip(byte[] raw, String name) {
        if (raw == null || raw.length == 0) {
            throw new IllegalArgumentException("could not strip image metadata");
        }
        if (isPng(raw)) return new Result(stripPng(raw), "image/png", renameExt(name, ".png"));
        if (isJpeg(raw)) return new Result(stripJpeg(raw), "image/jpeg", renameExt(name, ".jpg"));
        if (isGif(raw)) return new Result(stripGif(raw), "image/gif", renameExt(name, ".gif"));
        if (isWebp(raw)) return new Result(stripWebp(raw), "image/webp", renameExt(name, ".webp"));
        throw new IllegalArgumentException("could not strip image metadata");
    }

    private static boolean isPng(byte[] b) {
        return b.length >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }

    private static boolean isJpeg(byte[] b) {
        return b.length >= 2 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8;
    }

    private static boolean isGif(byte[] b) {
        return b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F';
    }

    private static boolean isWebp(byte[] b) {
        return b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
    }

    private static byte[] stripPng(byte[] buf) {
        if (!isPng(buf)) return buf;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(buf, 0, 8);
        int i = 8;
        while (i + 12 <= buf.length) {
            int len = u32be(buf, i);
            if (len < 0 || i + 12 + len > buf.length) break;
            String type = new String(buf, i + 4, 4, StandardCharsets.US_ASCII);
            if (PNG_KEEP.contains(type)) out.write(buf, i, 12 + len);
            i += 12 + len;
            if ("IEND".equals(type)) break;
        }
        return out.toByteArray();
    }

    private static byte[] stripJpeg(byte[] buf) {
        if (!isJpeg(buf)) return buf;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(buf, 0, 2);
        int i = 2;
        while (i + 2 <= buf.length) {
            if ((buf[i] & 0xff) != 0xff) {
                out.write(buf, i, buf.length - i);
                break;
            }
            int j = i;
            while (j < buf.length && (buf[j] & 0xff) == 0xff) j++;
            if (j >= buf.length) break;
            int marker = buf[j] & 0xff;
            if (marker == 0xd9) {
                out.write(buf, i, j + 1 - i);
                break;
            }
            if (marker == 0xda) {
                out.write(buf, i, buf.length - i);
                break;
            }
            if (marker >= 0xd0 && marker <= 0xd7) {
                out.write(buf, i, j + 1 - i);
                i = j + 1;
                continue;
            }
            if (j + 3 > buf.length) break;
            int len = u16be(buf, j + 1);
            int end = j + 1 + len;
            if (end > buf.length) break;
            boolean drop = marker == 0xfe || (marker >= 0xe1 && marker <= 0xef);
            if (!drop) out.write(buf, i, end - i);
            i = end;
        }
        return out.toByteArray();
    }

    private static byte[] stripGif(byte[] buf) {
        if (!isGif(buf) || buf.length < 13) return buf;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(buf, 0, 13);
        int i = 13;
        int packed = buf[10] & 0xff;
        if ((packed & 0x80) != 0) {
            int gct = 3 * (1 << ((packed & 7) + 1));
            if (i + gct > buf.length) return buf;
            out.write(buf, i, gct);
            i += gct;
        }
        while (i < buf.length) {
            int b = buf[i] & 0xff;
            if (b == 0x3b) {
                out.write(buf, i, 1);
                break;
            }
            if (b == 0x2c) {
                if (i + 10 > buf.length) break;
                int imgPacked = buf[i + 9] & 0xff;
                int n = 10;
                if ((imgPacked & 0x80) != 0) n += 3 * (1 << ((imgPacked & 7) + 1));
                n += 1;
                n += skipSubBlocks(buf, i + n);
                out.write(buf, i, n);
                i += n;
                continue;
            }
            if (b == 0x21) {
                if (i + 2 > buf.length) break;
                int label = buf[i + 1] & 0xff;
                if (label == 0xf9) {
                    out.write(buf, i, 8);
                    i += 8;
                    continue;
                }
                int n = 2 + skipSubBlocks(buf, i + 2);
                boolean keepLoop = label == 0xff && buf.length >= i + 13
                        && "NETSCAPE".equals(new String(buf, i + 3, 8, StandardCharsets.US_ASCII));
                if (keepLoop) out.write(buf, i, n);
                i += n;
                continue;
            }
            break;
        }
        return out.toByteArray();
    }

    private static int skipSubBlocks(byte[] buf, int start) {
        int i = start;
        while (i < buf.length) {
            int sz = buf[i] & 0xff;
            i += 1;
            if (sz == 0) return i - start;
            i += sz;
        }
        return i - start;
    }

    private static byte[] stripWebp(byte[] buf) {
        if (!isWebp(buf) || buf.length < 12) return buf;
        List<byte[]> keep = new ArrayList<>();
        int i = 12;
        int body = 0;
        while (i + 8 <= buf.length) {
            String fourcc = new String(buf, i, 4, StandardCharsets.US_ASCII);
            int size = u32le(buf, i + 4);
            int total = 8 + size;
            if ((total & 1) == 1) total += 1;
            if (i + total > buf.length) break;
            if (!"EXIF".equals(fourcc) && !"XMP ".equals(fourcc) && !"ICCP".equals(fourcc)) {
                byte[] chunk = new byte[total];
                System.arraycopy(buf, i, chunk, 0, total);
                keep.add(chunk);
                body += total;
            }
            i += total;
        }
        byte[] out = new byte[12 + body];
        System.arraycopy(buf, 0, out, 0, 8);
        putU32le(out, 4, body + 4);
        System.arraycopy(buf, 8, out, 8, 4);
        int p = 12;
        for (byte[] c : keep) {
            System.arraycopy(c, 0, out, p, c.length);
            p += c.length;
        }
        return out;
    }

    private static String renameExt(String name, String ext) {
        String raw = name == null ? "image" : name.replace('\\', '/');
        int slash = raw.lastIndexOf('/');
        if (slash >= 0) raw = raw.substring(slash + 1);
        int dot = raw.lastIndexOf('.');
        String base = dot > 0 ? raw.substring(0, dot) : raw;
        if (base.isBlank()) base = "image";
        if (base.length() > 100) base = base.substring(0, 100);
        return base + ext;
    }

    private static String extOf(String name) {
        String n = name == null ? "" : name.toLowerCase();
        int dot = n.lastIndexOf('.');
        return dot >= 0 ? n.substring(dot) : ".png";
    }

    private static int u16be(byte[] b, int i) {
        return ((b[i] & 0xff) << 8) | (b[i + 1] & 0xff);
    }

    private static int u32be(byte[] b, int i) {
        return ((b[i] & 0xff) << 24) | ((b[i + 1] & 0xff) << 16)
                | ((b[i + 2] & 0xff) << 8) | (b[i + 3] & 0xff);
    }

    private static int u32le(byte[] b, int i) {
        return ByteBuffer.wrap(b, i, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static void putU32le(byte[] b, int i, int v) {
        ByteBuffer.wrap(b, i, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(v);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(64);
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
