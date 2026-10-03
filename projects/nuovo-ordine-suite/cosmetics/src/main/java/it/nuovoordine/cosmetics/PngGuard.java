package it.nuovoordine.cosmetics;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import javax.imageio.ImageIO;

public final class PngGuard {
    public static final int MAX_SKIN_BYTES = 32768;
    public static final int MAX_CAPE_BYTES = 524288;
    public static final int MAX_CAPE_FRAMES = 64;

    public static byte[] validate(byte[] data, boolean skin) throws IOException {
        if (data.length == 0) return data;
        int maxBytes = skin ? MAX_SKIN_BYTES : MAX_CAPE_BYTES;
        if (data.length > maxBytes || data.length < 33) throw new IOException("PNG size");
        ByteBuffer b = ByteBuffer.wrap(data);
        if (b.getLong() != 0x89504e470d0a1a0aL || b.getInt() != 13 || b.getInt() != 0x49484452) {
            throw new IOException("PNG signature");
        }
        int w = b.getInt(), h = b.getInt();
        if (skin) {
            if (w != 64 || h != 64) throw new IOException("PNG dimensions");
        } else if (w != 64 || h < 32 || h % 32 != 0 || h / 32 > MAX_CAPE_FRAMES) {
            throw new IOException("PNG dimensions");
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
        if (image == null || image.getWidth() != w || image.getHeight() != h) throw new IOException("Invalid PNG");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", out);
        if (out.size() > maxBytes) throw new IOException("PNG size");
        return out.toByteArray();
    }

    public static int capeFrames(byte[] data) throws IOException {
        if (data.length == 0) return 0;
        if (data.length < 33) throw new IOException("PNG size");
        ByteBuffer b = ByteBuffer.wrap(data);
        if (b.getLong() != 0x89504e470d0a1a0aL || b.getInt() != 13 || b.getInt() != 0x49484452) {
            throw new IOException("PNG signature");
        }
        int w = b.getInt(), h = b.getInt();
        if (w != 64 || h < 32 || h % 32 != 0 || h / 32 > MAX_CAPE_FRAMES) throw new IOException("PNG dimensions");
        return h / 32;
    }
}
