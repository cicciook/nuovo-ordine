package it.nuovoordine.cosmetics;
import java.io.*;
import java.nio.ByteBuffer;
import javax.imageio.ImageIO;

public final class PngGuard {
    public static final int MAX_SKIN_BYTES = 32768;
    public static final int MAX_CAPE_BYTES = 262144;
    public static final int MAX_CAPE_FRAMES = 24;

    public static byte[] validate(byte[] data, boolean skin) throws IOException {
        if (data.length == 0) return data;
        int max = skin ? MAX_SKIN_BYTES : MAX_CAPE_BYTES;
        if (data.length > max || data.length < 33) throw new IOException("PNG size");

        ByteBuffer b = ByteBuffer.wrap(data);
        if (b.getLong() != 0x89504e470d0a1a0aL || b.getInt() != 13 || b.getInt() != 0x49484452)
            throw new IOException("PNG signature");

        int w = b.getInt(), h = b.getInt();
        if (w != 64) throw new IOException("PNG width");
        if (skin) {
            if (h != 64) throw new IOException("PNG dimensions");
        } else {
            if (h < 32 || h % 32 != 0 || h / 32 > MAX_CAPE_FRAMES)
                throw new IOException("PNG dimensions");
        }

        var image = ImageIO.read(new ByteArrayInputStream(data));
        if (image == null || image.getWidth() != w || image.getHeight() != h)
            throw new IOException("Invalid PNG");

        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", out);
        if (out.size() > max) throw new IOException("PNG size");
        return out.toByteArray();
    }
}
