/**
 * {@code ImageMetaStrip}: the privacy filter that removes EXIF/GPS/XMP/comments from
 * uploaded photos before they are written to disk.
 *
 * <p>Everything here is byte-array work in memory. {@code strip}, {@code clean} and the
 * {@code Result} holder never touch the filesystem, so no image files are needed: each
 * fixture is assembled from PNG/JPEG/GIF/WebP chunks by the helpers below.
 *
 * <p>{@code copyStripped} writes into a static {@code typewriter-images} directory
 * resolved from {@code user.dir}, so it is only called from one test that runs under
 * {@code @TempDir} (a bare temp name, not {@code @TempDir} itself, because the temp root
 * is shared and parallel).
 */
package org.ashoreprospect.GUI;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageMetaStripTest {

    private static final String BAD = "could not strip image metadata";

    // ---------------------------------------------------------------- PNG fixtures

    /** One PNG chunk: 4-byte big-endian length, 4-byte type, payload, 4-byte CRC. */
    private static byte[] pngChunk(String type, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int len = payload.length;
        out.writeBytes(new byte[] {
                (byte) (len >>> 24), (byte) (len >>> 16), (byte) (len >>> 8), (byte) len
        });
        out.writeBytes(type.getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(payload);
        // The stripper never verifies the CRC, so a fixed 4-byte stub is enough.
        out.writeBytes(new byte[] {0, 0, 0, 0});
        return out.toByteArray();
    }

    private static byte[] png(String... typesAndPayloads) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        for (int i = 0; i < typesAndPayloads.length; i += 2) {
            out.writeBytes(pngChunk(typesAndPayloads[i],
                    typesAndPayloads[i + 1].getBytes(StandardCharsets.US_ASCII)));
        }
        return out.toByteArray();
    }

    private static String chunkTypes(byte[] png) {
        StringBuilder sb = new StringBuilder();
        int i = 8;
        while (i + 12 <= png.length) {
            int len = ((png[i] & 0xff) << 24) | ((png[i + 1] & 0xff) << 16)
                    | ((png[i + 2] & 0xff) << 8) | (png[i + 3] & 0xff);
            String type = new String(png, i + 4, 4, StandardCharsets.US_ASCII);
            if (sb.length() > 0) sb.append(',');
            sb.append(type);
            i += 12 + len;
            if ("IEND".equals(type)) break;
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- JPEG fixtures

    /** A JPEG segment: 0xFF, marker, 2-byte big-endian length (payload + 2). */
    private static byte[] jpegSegment(int marker, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int len = payload.length + 2;
        out.write(0xff);
        out.write(marker);
        out.write((len >>> 8) & 0xff);
        out.write(len & 0xff);
        out.writeBytes(payload);
        return out.toByteArray();
    }

    private static byte[] jpeg(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0xff, (byte) 0xd8});
        for (byte[] p : parts) out.writeBytes(p);
        out.writeBytes(new byte[] {(byte) 0xff, (byte) 0xd9});
        return out.toByteArray();
    }

    private static byte[] bytes(int... ints) {
        byte[] b = new byte[ints.length];
        for (int i = 0; i < ints.length; i++) b[i] = (byte) ints[i];
        return b;
    }

    // ---------------------------------------------------------------- GIF fixtures

    /**
     * The 13-byte logical screen descriptor. {@code packed} bits 0-2 are the colour
     * resolution; bit 7 says a global colour table follows with 3 * 2^(n+1) bytes.
     */
    private static byte[] gifHeader(int packed, byte[] globalColourTable) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("GIF89a".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(bytes(0x10, 0x00, 0x10, 0x00, packed, 0x00, 0x00));
        if (globalColourTable != null) out.writeBytes(globalColourTable);
        return out.toByteArray();
    }

    /** A sub-block list terminated by a zero-length block. */
    private static byte[] subBlocks(byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(payload.length);
        out.writeBytes(payload);
        out.write(0);
        return out.toByteArray();
    }

    private static byte[] gifConcat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- WebP fixtures

    private static byte[] u32le(int v) {
        return bytes(v & 0xff, (v >>> 8) & 0xff, (v >>> 16) & 0xff, (v >>> 24) & 0xff);
    }

    private static byte[] webpChunk(String fourcc, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(fourcc.getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(u32le(payload.length));
        out.writeBytes(payload);
        if ((payload.length & 1) == 1) out.write(0);   // RIFF pad byte
        return out.toByteArray();
    }

    private static byte[] webp(byte[]... parts) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] p : parts) body.writeBytes(p);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(u32le(body.size() + 4));
        out.writeBytes("WEBP".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(body.toByteArray());
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- PNG

    @Test
    void pngKeepsOnlyTheFiveAllowedChunkTypes() {
        byte[] raw = png(
                "IHDR", "hdr",
                "tEXt", "Comment=gps",
                "PLTE", "rgb",
                "tRNS", "alpha",
                "IDAT", "pixels",
                "IEND", "");

        byte[] out = ImageMetaStrip.strip(raw, "a.png").bytes;

        assertEquals("IHDR,PLTE,tRNS,IDAT,IEND", chunkTypes(out));
        assertFalse(new String(out, StandardCharsets.US_ASCII).contains("Comment=gps"));
    }

    @Test
    void pngKeepsTheSurvivingChunksInTheirOriginalOrder() {
        byte[] raw = png(
                "IHDR", "a",
                "iCCP", "profile",
                "IDAT", "b",
                "IDAT", "c",
                "IEND", "");

        assertEquals("IHDR,IDAT,IDAT,IEND", chunkTypes(ImageMetaStrip.strip(raw, "x.png").bytes));
    }

    @Test
    void pngChunkThatOverrunsTheBufferStopsTheLoop() {
        byte[] raw = png("IHDR", "a");
        // Claim a 40-byte payload but supply only the 12-byte chunk header.
        byte[] lying = gifConcat(raw, bytes(0, 0, 0, 40, 't', 'E', 'X', 't', 1, 2, 3, 4));

        byte[] out = ImageMetaStrip.strip(lying, "x.png").bytes;

        assertEquals("IHDR", chunkTypes(out));
        // PINNED: only the 8-byte signature plus the 13-byte IHDR chunk survive (21
        // bytes). The 12-byte lying header is skipped, not copied, so the tail is lost.
        assertEquals(21, out.length);
        assertArrayEquals(java.util.Arrays.copyOf(raw, 21), out);
    }

    @Test
    void pngParsingStopsAtIendEvenWhenMoreBytesFollow() {
        byte[] raw = gifConcat(
                png("IHDR", "a", "IEND", ""),
                pngChunk("tEXt", "after-the-end".getBytes(StandardCharsets.US_ASCII)));

        assertEquals("IHDR,IEND", chunkTypes(ImageMetaStrip.strip(raw, "x.png").bytes));
    }

    @Test
    void pngResultIsRenamedToPngAndTypedAsPng() {
        ImageMetaStrip.Result r = ImageMetaStrip.strip(png("IHDR", "a", "IEND", ""), "photo.jpg");

        assertEquals("image/png", r.mime);
        assertEquals("photo.png", r.name);
    }

    // ---------------------------------------------------------------- JPEG

    @Test
    void jpegDropsApp1ExifAndKeepsTheSoiMarker() {
        byte[] raw = jpeg(
                jpegSegment(0xe0, "JFIF".getBytes(StandardCharsets.US_ASCII)),
                jpegSegment(0xe1, "Exif\u0000\u0000GPS".getBytes(StandardCharsets.US_ASCII)));

        byte[] out = ImageMetaStrip.strip(raw, "a.jpg").bytes;

        assertEquals((byte) 0xff, out[0]);
        assertEquals((byte) 0xd8, out[1]);
        String text = new String(out, StandardCharsets.US_ASCII);
        assertFalse(text.contains("Exif"));
        assertFalse(text.contains("GPS"));
        assertTrue(text.contains("JFIF"), "APP0 must survive");
    }

    @Test
    void jpegKeepsApp0ButDropsTheWholeE1ToEfRange() {
        byte[] app0 = jpegSegment(0xe0, "0".getBytes(StandardCharsets.US_ASCII));
        byte[] raw = jpeg(
                app0,
                jpegSegment(0xe1, "1".getBytes(StandardCharsets.US_ASCII)),
                jpegSegment(0xe2, "2".getBytes(StandardCharsets.US_ASCII)),
                jpegSegment(0xef, "f".getBytes(StandardCharsets.US_ASCII)));

        byte[] out = ImageMetaStrip.strip(raw, "a.jpg").bytes;

        // SOI + the APP0 segment + EOI, and nothing else.
        assertArrayEquals(gifConcat(bytes(0xff, 0xd8), app0, bytes(0xff, 0xd9)), out);

        // PINNED: 0xF0 and up fall outside both drop ranges, so such a segment is copied
        // through even though it is not one of the usual EXIF/comment markers.
        byte[] app16 = jpegSegment(0xf0, "k".getBytes(StandardCharsets.US_ASCII));
        assertArrayEquals(gifConcat(bytes(0xff, 0xd8), app16, bytes(0xff, 0xd9)),
                ImageMetaStrip.strip(jpeg(app16), "a.jpg").bytes);
    }

    @Test
    void jpegCommentSegmentIsDropped() {
        byte[] raw = jpeg(jpegSegment(0xfe, "made by a camera".getBytes(StandardCharsets.US_ASCII)));

        String text = new String(ImageMetaStrip.strip(raw, "a.jpg").bytes,
                StandardCharsets.US_ASCII);

        assertFalse(text.contains("made by a camera"));
    }

    @Test
    void jpegScanDataAfterSosIsCopiedVerbatim() {
        byte[] scan = bytes(1, 2, 0xff, 0x00, 3, 4);
        byte[] raw = gifConcat(
                new byte[] {(byte) 0xff, (byte) 0xd8},
                jpegSegment(0xe1, "exif".getBytes(StandardCharsets.US_ASCII)),
                jpegSegment(0xda, bytes(8, 1, 2, 3)),
                scan);

        byte[] out = ImageMetaStrip.strip(raw, "a.jpg").bytes;

        // Everything from the SOS marker to the end, including the entropy-coded scan.
        int sos = indexOf(out, bytes(0xff, 0xda));
        assertTrue(sos > 0, "SOS should still be present");
        byte[] tail = new byte[out.length - sos];
        System.arraycopy(out, sos, tail, 0, tail.length);
        assertArrayEquals(gifConcat(jpegSegment(0xda, bytes(8, 1, 2, 3)), scan), tail);
        assertEquals(2 + jpegSegment(0xda, bytes(8, 1, 2, 3)).length + scan.length, out.length);
    }

    @Test
    void jpegRestartMarkersAreCopiedWithoutConsumingALength() {
        byte[] raw = gifConcat(
                new byte[] {(byte) 0xff, (byte) 0xd8},
                bytes(0xff, 0xd0),   // RST0 — no length field
                bytes(0xff, 0xd7),   // RST7
                bytes(0xff, 0xd9));  // EOI

        byte[] out = ImageMetaStrip.strip(raw, "a.jpg").bytes;

        assertArrayEquals(raw, out);
    }

    @Test
    void jpegNonMarkerByteCopiesTheRemainderAndStops() {
        byte[] tail = "not-a-marker".getBytes(StandardCharsets.US_ASCII);
        byte[] raw = gifConcat(new byte[] {(byte) 0xff, (byte) 0xd8}, tail);

        byte[] out = ImageMetaStrip.strip(raw, "a.jpg").bytes;

        assertArrayEquals(raw, out);
    }

    @Test
    void jpegResultIsRenamedToJpgAndTypedAsJpeg() {
        ImageMetaStrip.Result r = ImageMetaStrip.strip(
                jpeg(jpegSegment(0xe1, "x".getBytes(StandardCharsets.US_ASCII))), "shot.JPEG");

        assertEquals("image/jpeg", r.mime);
        assertEquals("shot.jpg", r.name);
    }

    // ---------------------------------------------------------------- GIF

    @Test
    void gifPreservesTheHeaderAndGlobalColourTable() {
        // packed = 0x80 (has GCT) | 0x00 → 3 * 2^(0+1) = 6 bytes of colour table.
        byte[] gct = bytes(1, 2, 3, 4, 5, 6);
        byte[] raw = gifConcat(gifHeader(0x80, gct), bytes(0x3b));

        byte[] out = ImageMetaStrip.strip(raw, "a.gif").bytes;

        assertArrayEquals(raw, out);
    }

    @Test
    void gifKeepsGraphicControlAndDropsComments() {
        byte[] gce = gifConcat(bytes(0x21, 0xf9, 0x04, 0, 0, 0, 0), bytes(0x00));
        byte[] comment = gifConcat(bytes(0x21, 0xfe), subBlocks(
                "secret".getBytes(StandardCharsets.US_ASCII)));
        byte[] raw = gifConcat(gifHeader(0x00, null), comment, gce, bytes(0x3b));

        byte[] out = ImageMetaStrip.strip(raw, "a.gif").bytes;

        assertArrayEquals(gifConcat(gifHeader(0x00, null), gce, bytes(0x3b)), out);
    }

    @Test
    void gifKeepsTheNetscapeLoopExtensionOnly() {
        byte[] netscape = gifConcat(
                bytes(0x21, 0xff, 0x0b),
                "NETSCAPE2.0".getBytes(StandardCharsets.US_ASCII),
                bytes(0x03, 0x01, 0x00, 0x00, 0x00));
        byte[] otherApp = gifConcat(
                bytes(0x21, 0xff, 0x0b),
                "XMP DataXMP".getBytes(StandardCharsets.US_ASCII),
                bytes(0x03, 0x01, 0x00, 0x00, 0x00));
        byte[] raw = gifConcat(gifHeader(0x00, null), otherApp, netscape, bytes(0x3b));

        byte[] out = ImageMetaStrip.strip(raw, "a.gif").bytes;

        assertArrayEquals(gifConcat(gifHeader(0x00, null), netscape, bytes(0x3b)), out);
    }

    @Test
    void gifShorterThanThirteenBytesIsReturnedUnchanged() {
        byte[] raw = "GIF89a".getBytes(StandardCharsets.US_ASCII);

        assertArrayEquals(raw, ImageMetaStrip.strip(raw, "a.gif").bytes);
    }

    @Test
    void gifColourTableThatOverrunsTheBufferReturnsTheInputUnchanged() {
        byte[] raw = gifConcat(gifHeader(0x80, null), bytes(1, 2));

        assertArrayEquals(raw, ImageMetaStrip.strip(raw, "a.gif").bytes);
    }

    @Test
    void gifResultIsTypedAsGif() {
        ImageMetaStrip.Result r = ImageMetaStrip.strip(gifHeader(0x00, null), "loop.bin");

        assertEquals("image/gif", r.mime);
        assertEquals("loop.gif", r.name);
    }

    // ---------------------------------------------------------------- WebP

    @Test
    void webpDropsExifXmpAndIccpAndRewritesTheRiffSize() {
        byte[] raw = webp(
                webpChunk("VP8 ", bytes(1, 2, 3)),
                webpChunk("EXIF", bytes(9, 9)),
                webpChunk("XMP ", bytes(8, 8)),
                webpChunk("ICCP", bytes(7, 7)));

        byte[] out = ImageMetaStrip.strip(raw, "a.webp").bytes;

        assertEquals("RIFF", new String(out, 0, 4, StandardCharsets.US_ASCII));
        assertEquals("WEBP", new String(out, 8, 4, StandardCharsets.US_ASCII));
        int riffSize = (out[4] & 0xff) | ((out[5] & 0xff) << 8)
                | ((out[6] & 0xff) << 16) | ((out[7] & 0xff) << 24);
        assertEquals(out.length - 8, riffSize, "RIFF size covers everything after the first 8 bytes");
        assertEquals("VP8 ", new String(out, 12, 4, StandardCharsets.US_ASCII));
        assertEquals(webp(webpChunk("VP8 ", bytes(1, 2, 3))).length, out.length);
    }

    @Test
    void webpOddSizedChunkAbsorbsItsPadByte() {
        // "VP8 " payload is 3 bytes, so it is padded to 4 before the next fourcc.
        byte[] raw = webp(
                webpChunk("VP8 ", bytes(1, 2, 3)),
                webpChunk("ANIM", bytes(4, 5, 6, 7, 8)));

        byte[] out = ImageMetaStrip.strip(raw, "a.webp").bytes;

        assertArrayEquals(raw, out);
        assertEquals("ANIM", new String(out, 12 + 8 + 4, 4, StandardCharsets.US_ASCII));
    }

    @Test
    void webpResultIsTypedAsWebp() {
        ImageMetaStrip.Result r = ImageMetaStrip.strip(webp(webpChunk("VP8 ", bytes(1))), "pic.gif");

        assertEquals("image/webp", r.mime);
        assertEquals("pic.webp", r.name);
    }

    // ---------------------------------------------------------------- strip / clean

    @Test
    void emptyOrUnknownInputIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ImageMetaStrip.strip(null, "a.png"));
        assertThrows(IllegalArgumentException.class, () -> ImageMetaStrip.strip(new byte[0], "a.png"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ImageMetaStrip.strip("just text".getBytes(StandardCharsets.US_ASCII), "a.txt"));
        assertEquals(BAD, e.getMessage());
    }

    @Test
    void cleanIsStripWithoutTheHolderAndToleratesNull() {
        assertNull(ImageMetaStrip.clean(null, "a.png"));

        byte[] raw = png("IHDR", "a", "tEXt", "leak", "IEND", "");
        assertArrayEquals(ImageMetaStrip.strip(raw, "a.png").bytes,
                ImageMetaStrip.clean(raw, "a.png"));
    }

    // ---------------------------------------------------------------- naming

    @Test
    void renameExtTakesTheBasenameAndForcesTheRealExtension() {
        byte[] pngBytes = png("IHDR", "a", "IEND", "");
        byte[] jpgBytes = jpeg(jpegSegment(0xe1, bytes(1)));
        assertEquals("image.png", ImageMetaStrip.strip(pngBytes, null).name);
        assertEquals("image.png", ImageMetaStrip.strip(pngBytes, "   ").name);
        // PINNED: `dot > 0` rather than `>= 0`, so ".hidden" keeps its dot and reads as a
        // basename of ".hidden" (Linux dotfile semantics).
        assertEquals(".hidden.png", ImageMetaStrip.strip(pngBytes, ".hidden").name);
        assertEquals("b.png", ImageMetaStrip.strip(pngBytes, "dir/sub/b.jpeg").name);
        assertEquals("b.png", ImageMetaStrip.strip(pngBytes, "dir\\sub\\b.jpeg").name);
        assertEquals("b.jpg", ImageMetaStrip.strip(jpgBytes, "b.png").name);
    }

    @Test
    void longBasenamesAreClippedToAHundredChars() {
        String longName = "n".repeat(250) + ".png";

        String name = ImageMetaStrip.strip(png("IHDR", "a", "IEND", ""), longName).name;

        assertEquals("n".repeat(100) + ".png", name);
        assertEquals(104, name.length());
    }

    // ---------------------------------------------------------------- copyStripped

    @Test
    void copyStrippedWritesTheStrippedBytesIntoTypewriterImages(@TempDir Path temp) throws Exception {
        // The output directory is a static File("typewriter-images"), resolved against
        // user.dir at call time, so a bare temp name keeps this away from other tests.
        Path work = temp.resolve("imagestrip");
        Files.createDirectories(work);
        String oldDir = System.getProperty("user.dir");
        File src = null;
        try {
            System.setProperty("user.dir", work.toAbsolutePath().toString());

            src = work.resolve("in.png").toFile();
            byte[] raw = png("IHDR", "a", "tEXt", "leak", "IEND", "");
            Files.write(src.toPath(), raw);

            File out = ImageMetaStrip.copyStripped(src);

            assertEquals("typewriter-images", out.getParentFile().getName());
            assertEquals(".png", out.getName().substring(out.getName().length() - 4));
            byte[] written = Files.readAllBytes(out.toPath());
            assertArrayEquals(ImageMetaStrip.strip(raw, "in.png").bytes, written);
            assertEquals(sha256Hex(written), out.getName().substring(0, 64));
            assertFalse(new String(written, StandardCharsets.US_ASCII).contains("leak"));
        } finally {
            System.setProperty("user.dir", oldDir);
        }
    }

    @Test
    void copyStrippedRejectsMissingFilesAndUnreadableBytes(@TempDir Path temp) throws Exception {
        assertThrows(java.io.IOException.class, () -> ImageMetaStrip.copyStripped(null));
        assertThrows(java.io.IOException.class,
                () -> ImageMetaStrip.copyStripped(temp.resolve("absent.png").toFile()));

        File junk = temp.resolve("junk.png").toFile();
        Files.writeString(junk.toPath(), "not an image");
        java.io.IOException e = assertThrows(java.io.IOException.class,
                () -> ImageMetaStrip.copyStripped(junk));
        assertEquals(BAD, e.getMessage());
    }

    // ---------------------------------------------------------------- helpers

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
