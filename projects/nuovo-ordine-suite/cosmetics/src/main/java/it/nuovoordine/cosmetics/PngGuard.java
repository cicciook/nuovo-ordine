package it.nuovoordine.cosmetics;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.Set;
import javax.imageio.ImageIO;

public final class PngGuard {
    public static final int MAX_SKIN_BYTES = 32768;
    public static final int MAX_CAPE_BYTES = 4 * 1024 * 1024;
    public static final int MAX_CAPE_FRAMES = 24;
    public static final Set<Integer> CAPE_WIDTHS = Set.of(64, 128, 256, 512);

    public static int frameHeight(int width) throws IOException {
        if (!CAPE_WIDTHS.contains(width)) throw new IOException("Cape width");
        return width / 2;
    }

    public static int frameCount(int width, int totalHeight) throws IOException {
        int frameHeight = frameHeight(width);
        if (totalHeight < frameHeight || totalHeight % frameHeight != 0)
            throw new IOException("Cape dimensions");
        int frames = totalHeight / frameHeight;
        if (frames > MAX_CAPE_FRAMES) throw new IOException("Too many cape frames");
        return frames;
    }

    public static byte[] validate(byte[] data, boolean skin) throws IOException {
        if (data.length == 0) return data;
        int max = skin ? MAX_SKIN_BYTES : MAX_CAPE_BYTES;
        if (data.length > max || data.length < 33) throw new IOException("PNG size");

        ByteBuffer b = ByteBuffer.wrap(data);
        if (b.getLong() != 0x89504e470d0a1a0aL || b.getInt() != 13 || b.getInt() != 0x49484452)
            throw new IOException("PNG signature");

        int w = b.getInt(), h = b.getInt();
        if (skin) {
            if (w != 64 || h != 64) throw new IOException("PNG dimensions");
        } else {
            frameCount(w, h);
        }

        var image = ImageIO.read(new ByteArrayInputStream(data));
        if (image == null || image.getWidth() != w || image.getHeight() != h)
            throw new IOException("Invalid PNG");

        // The image was decoded successfully and its dimensions were checked.
        // Keep the original compressed bytes: re-encoding multi-frame HD+ sheets
        // with ImageIO can inflate them significantly for no validation benefit.
        return data;
    }
}
