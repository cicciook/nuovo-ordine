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
    static boolean sent;
    static int ticks;
    static long animationTicks;

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
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        animationTicks++;

        var mc = Minecraft.getInstance();
        if (mc.player == null || sent || ++ticks < 40) return;
        sent = true;
        if (!CosmeticsMod.NET.isRemotePresent(mc.getConnection().getConnection())) return;

        try {
            Path root = mc.gameDirectory.toPath().resolve("config/nuovoordine-cosmetics");
            boolean slim = Files.exists(root.resolve("slim"));
            CosmeticsMod.NET.sendToServer(new CosmeticsMod.Upload(
                read(root.resolve("skin.png"), PngGuard.MAX_SKIN_BYTES),
                read(root.resolve("cape.png"), PngGuard.MAX_CAPE_BYTES),
                slim
            ));
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
            if (sheet.getWidth() != 64 || sheet.getHeight() < 32 || sheet.getHeight() % 32 != 0)
                throw new IOException("Dimensioni mantello non valide");
            int frames = sheet.getHeight() / 32;
            if (frames > PngGuard.MAX_CAPE_FRAMES)
                throw new IOException("Troppi frame mantello");

            List<ResourceLocation> result = new ArrayList<>(frames);
            for (int frameIndex = 0; frameIndex < frames; frameIndex++) {
                NativeImage frame = new NativeImage(64, 32, true);
                for (int y = 0; y < 32; y++) {
                    for (int x = 0; x < 64; x++) {
                        frame.setPixelRGBA(x, y, sheet.getPixelRGBA(x, frameIndex * 32 + y));
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

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut e) {
        TEXTURES.values().forEach(CosmeticsClient::release);
        TEXTURES.clear();
        sent = false;
        animationTicks = 0;
    }
}
