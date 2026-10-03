package it.nuovoordine.cosmetics;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@Mod.EventBusSubscriber(modid = "nocosmetics", value = Dist.CLIENT)
public class CosmeticsClient {
    public record Textures(ResourceLocation skin, List<ResourceLocation> capes, boolean slim) {}

    public static final Map<UUID, Textures> TEXTURES = new HashMap<>();
    static final Map<UUID, IncomingAppearance> INCOMING = new HashMap<>();

    static boolean sent;
    static int ticks;
    static long animationTicks;

    static byte[] outboundCape;
    static int outboundChunk;
    static int outboundChunks;

    static byte[] read(Path p, int max) throws IOException {
        if (!Files.isRegularFile(p)) return new byte[0];
        if (Files.size(p) > max) throw new IOException("PNG troppo grande");
        return Files.readAllBytes(p);
    }

    @SubscribeEvent
    public static void login(ClientPlayerNetworkEvent.LoggingIn e) {
        sent = false;
        ticks = 0;
        animationTicks = 0;
        outboundCape = null;
        outboundChunk = 0;
        outboundChunks = 0;
        INCOMING.clear();
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        animationTicks++;

        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return;
        if (!CosmeticsMod.NET.isRemotePresent(mc.getConnection().getConnection())) return;

        // Send large cape uploads gradually instead of one oversized custom payload.
        if (outboundCape != null) {
            for (int sentNow = 0; sentNow < 8 && outboundChunk < outboundChunks; sentNow++) {
                int start = outboundChunk * CosmeticsMod.CHUNK_BYTES;
                int end = Math.min(start + CosmeticsMod.CHUNK_BYTES, outboundCape.length);
                CosmeticsMod.NET.sendToServer(
                    new CosmeticsMod.UploadChunk(
                        outboundChunk,
                        outboundChunks,
                        Arrays.copyOfRange(outboundCape, start, end)
                    )
                );
                outboundChunk++;
            }
            if (outboundChunk >= outboundChunks) {
                outboundCape = null;
                outboundChunk = 0;
                outboundChunks = 0;
            }
        }

        if (sent || ++ticks < 40) return;
        sent = true;

        try {
            Path root = mc.gameDirectory.toPath().resolve("config/nuovoordine-cosmetics");
            boolean slim = Files.exists(root.resolve("slim"));
            byte[] skin = read(root.resolve("skin.png"), PngGuard.MAX_SKIN_BYTES);
            byte[] cape = read(root.resolve("cape.png"), PngGuard.MAX_CAPE_BYTES);
            int chunks = CosmeticsMod.expectedChunks(cape.length);

            CosmeticsMod.NET.sendToServer(
                new CosmeticsMod.UploadStart(skin, slim, cape.length, chunks)
            );

            if (cape.length > 0) {
                outboundCape = cape;
                outboundChunk = 0;
                outboundChunks = chunks;
            }
        } catch (Exception ex) {
            mc.player.displayClientMessage(
                net.minecraft.network.chat.Component.literal(
                    "Skin/mantello non caricati: " + ex.getMessage()
                ),
                false
            );
        }
    }

    static ResourceLocation skinTexture(UUID id, byte[] png) throws Exception {
        if (png.length == 0) return null;
        NativeImage image = NativeImage.read(new ByteArrayInputStream(png));
        opaque(image, 0, 0, 32, 16);
        opaque(image, 0, 16, 64, 32);
        opaque(image, 16, 48, 48, 64);
        var texture = new DynamicTexture(image);
        ResourceLocation location = new ResourceLocation("nocosmetics", "skin/" + id);
        Minecraft.getInstance().getTextureManager().register(location, texture);
        return location;
    }

    static List<ResourceLocation> capeTextures(UUID id, byte[] png) throws Exception {
        if (png.length == 0) return List.of();
        NativeImage sheet = NativeImage.read(new ByteArrayInputStream(png));
        try {
            int width = sheet.getWidth();
            int frameHeight = PngGuard.frameHeight(width);
            int frames = PngGuard.frameCount(width, sheet.getHeight());

            List<ResourceLocation> result = new ArrayList<>(frames);
            for (int frameIndex = 0; frameIndex < frames; frameIndex++) {
                NativeImage frame = new NativeImage(width, frameHeight, true);
                for (int y = 0; y < frameHeight; y++) {
                    for (int x = 0; x < width; x++) {
                        frame.setPixelRGBA(
                            x,
                            y,
                            sheet.getPixelRGBA(x, frameIndex * frameHeight + y)
                        );
                    }
                }
                ResourceLocation location = new ResourceLocation(
                    "nocosmetics", "cape/" + id + "/" + frameIndex
                );
                Minecraft.getInstance().getTextureManager().register(
                    location, new DynamicTexture(frame)
                );
                result.add(location);
            }
            return List.copyOf(result);
        } finally {
            sheet.close();
        }
    }

    static void opaque(NativeImage image, int x1, int y1, int x2, int y2) {
        for (int x = x1; x < x2; x++)
            for (int y = y1; y < y2; y++)
                image.setPixelRGBA(x, y, image.getPixelRGBA(x, y) | 0xff000000);
    }

    public static ResourceLocation currentCape(UUID id) {
        Textures textures = TEXTURES.get(id);
        if (textures == null || textures.capes().isEmpty()) return null;
        if (textures.capes().size() == 1) return textures.capes().get(0);
        // Minecraft runs at 20 ticks/s. Two ticks per frame gives a stable 10 FPS loop.
        int frame = (int) ((animationTicks / 2L) % textures.capes().size());
        return textures.capes().get(frame);
    }

    static void release(Textures t) {
        if (t == null) return;
        var manager = Minecraft.getInstance().getTextureManager();
        if (t.skin() != null) manager.release(t.skin());
        for (ResourceLocation cape : t.capes()) manager.release(cape);
    }

    static void receive(CosmeticsMod.Appearance p) {
        release(TEXTURES.remove(p.id()));
        ResourceLocation skin = null;
        List<ResourceLocation> capes = List.of();
        try {
            skin = skinTexture(p.id(), p.skin());
            capes = capeTextures(p.id(), p.cape());
            TEXTURES.put(p.id(), new Textures(skin, capes, p.slim()));
        } catch (Exception ex) {
            release(new Textures(skin, capes, p.slim()));
            System.getLogger("nocosmetics").log(
                System.Logger.Level.WARNING, "Texture non caricata", ex
            );
        }
    }

    static void beginAppearance(CosmeticsMod.AppearanceHeader p) {
        try {
            if (p.capeBytes() < 0 || p.capeBytes() > PngGuard.MAX_CAPE_BYTES)
                throw new IOException("Cape size");
            if (p.capeChunks() != CosmeticsMod.expectedChunks(p.capeBytes()))
                throw new IOException("Cape chunks");

            INCOMING.remove(p.id());
            if (p.capeBytes() == 0) {
                receive(new CosmeticsMod.Appearance(p.id(), p.skin(), new byte[0], p.slim()));
                return;
            }
            INCOMING.put(
                p.id(),
                new IncomingAppearance(p.skin(), p.slim(), p.capeBytes(), p.capeChunks())
            );
        } catch (Exception ex) {
            INCOMING.remove(p.id());
            System.getLogger("nocosmetics").log(
                System.Logger.Level.WARNING, "Header mantello rifiutato", ex
            );
        }
    }

    static void receiveAppearanceChunk(CosmeticsMod.AppearanceChunk p) {
        IncomingAppearance incoming = INCOMING.get(p.id());
        if (incoming == null) return;
        try {
            if (!incoming.accept(p)) throw new IOException("Cape chunk");
            if (!incoming.complete()) return;

            INCOMING.remove(p.id());
            receive(
                new CosmeticsMod.Appearance(
                    p.id(),
                    incoming.skin,
                    incoming.join(),
                    incoming.slim
                )
            );
        } catch (Exception ex) {
            INCOMING.remove(p.id());
            System.getLogger("nocosmetics").log(
                System.Logger.Level.WARNING, "Chunk mantello rifiutato", ex
            );
        }
    }

    static void removeAppearance(UUID id) {
        INCOMING.remove(id);
        release(TEXTURES.remove(id));
    }

    static final class IncomingAppearance {
        final byte[] skin;
        final boolean slim;
        final int totalBytes;
        final byte[][] chunks;
        int received;

        IncomingAppearance(byte[] skin, boolean slim, int totalBytes, int totalChunks) {
            this.skin = skin;
            this.slim = slim;
            this.totalBytes = totalBytes;
            this.chunks = new byte[totalChunks][];
        }

        boolean accept(CosmeticsMod.AppearanceChunk chunk) {
            if (chunk.total() != chunks.length) return false;
            if (chunk.index() < 0 || chunk.index() >= chunks.length) return false;
            if (chunk.data().length == 0 || chunk.data().length > CosmeticsMod.CHUNK_BYTES)
                return false;
            if (chunks[chunk.index()] == null) {
                chunks[chunk.index()] = chunk.data();
                received++;
            }
            return true;
        }

        boolean complete() {
            return received == chunks.length;
        }

        byte[] join() throws IOException {
            byte[] result = new byte[totalBytes];
            int offset = 0;
            for (byte[] chunk : chunks) {
                if (chunk == null) throw new IOException("Missing cape chunk");
                if (offset + chunk.length > result.length) throw new IOException("Cape size");
                System.arraycopy(chunk, 0, result, offset, chunk.length);
                offset += chunk.length;
            }
            if (offset != result.length) throw new IOException("Cape size");
            return result;
        }
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut e) {
        TEXTURES.values().forEach(CosmeticsClient::release);
        TEXTURES.clear();
        INCOMING.clear();
        sent = false;
        animationTicks = 0;
        outboundCape = null;
        outboundChunk = 0;
        outboundChunks = 0;
    }
}
