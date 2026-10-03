package it.nuovoordine;

import it.nuovoordine.cosmetics.PngGuard;
import org.junit.Test;

import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;

import static org.junit.Assert.*;

public class PngGuardTest {
    byte[] image(int w, int h) throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB), "PNG", out);
        return out.toByteArray();
    }

    @Test public void skinValid() throws Exception {
        assertTrue(PngGuard.validate(image(64, 64), true).length > 0);
    }

    @Test public void capeValid() throws Exception {
        assertTrue(PngGuard.validate(image(64, 32), false).length > 0);
    }

    @Test public void animatedCapeSheetValid() throws Exception {
        assertTrue(PngGuard.validate(image(64, 32 * 12), false).length > 0);
    }

    @Test public void removal() throws Exception {
        assertEquals(0, PngGuard.validate(new byte[0], true).length);
    }

    @Test(expected = IOException.class) public void malformed() throws Exception {
        PngGuard.validate(new byte[100], true);
    }

    @Test(expected = IOException.class) public void oversizedSkin() throws Exception {
        PngGuard.validate(new byte[PngGuard.MAX_SKIN_BYTES + 1], true);
    }

    @Test(expected = IOException.class) public void badDimensions() throws Exception {
        PngGuard.validate(image(128, 128), true);
    }

    @Test(expected = IOException.class) public void tooManyCapeFrames() throws Exception {
        PngGuard.validate(image(64, 32 * (PngGuard.MAX_CAPE_FRAMES + 1)), false);
    }
}
