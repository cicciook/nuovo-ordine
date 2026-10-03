package it.nuovoordine.cosmetics;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import com.mojang.blaze3d.platform.NativeImage;
import java.nio.file.*;
import java.io.*;
import java.util.*;

@Mod.EventBusSubscriber(modid = "nocosmetics", value = Dist.CLIENT)
public class CosmeticsClient {
    public record Textures(ResourceLocation skin, ResourceLocation cape, boolean slim, AnimatedCape animation) {}
    public static final Map<UUID, Textures> TEXTURES = new HashMap<>();
    static boolean sent;
    static int ticks;

    static byte[] read(Path p, int max) throws IOException {
        if (!Files.isRegularFile(p)) return new byte[0];
        if (Files.size(p) > max) throw new IOException("Texture troppo grande");
        return Files.readAllBytes(p);
    }

    static int readFrameMillis(Path p) {
        try {
            if (!Files.isRegularFile(p) || Files.size(p) > 16) return 0;
            int value = Integer.parseInt(Files.readString(p).trim());
            return Math.max(40, Math.min(1000, value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    @SubscribeEvent
    public static void login(ClientPlayerNetworkEvent.LoggingIn e) { sent = false; ticks = 0; }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        for (Textures textures : TEXTURES.values()) {
            if (textures.animation != null) textures.animation.tick();
        }

        var mc = Minecraft.getInstance();
        if (mc.player == null || sent || ++ticks < 40) return;
        sent = true;
        if (!CosmeticsMod.NET.isRemotePresent(mc.getConnection().getConnection())) return;
        try {
            Path root = mc.gameDirectory.toPath().resolve("config/nuovoordine-cosmetics");
            boolean slim = Files.exists(root.resolve("slim"));
            byte[] skin = read(root.resolve("skin.png"), PngGuard.MAX_SKIN_BYTES);
            byte[] cape = read(root.resolve("cape.png"), PngGuard.MAX_CAPE_BYTES);
            int frameMillis = readFrameMillis(root.resolve("cape.frame_ms"));
            CosmeticsMod.NET.sendToServer(new CosmeticsMod.Upload(skin, cape, slim, frameMillis));
        } catch (Exception ex) {
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "Skin/mantello non caricati: " + ex.getMessage()), false);
        }
    }

    static ResourceLocation texture(UUID id, String kind, byte[] png) throws Exception {
        if (png.length == 0) return null;
        NativeImage image = NativeImage.read(new ByteArrayInputStream(png));
        if (kind.equals("skin")) {
            opaque(image, 0, 0, 32, 16); opaque(image, 0, 16, 64, 32); opaque(image, 16, 48, 48, 64);
        }
        DynamicTexture texture = new DynamicTexture(image);
        ResourceLocation location = new ResourceLocation("nocosmetics", kind + "/" + id);
        Minecraft.getInstance().getTextureManager().register(location, texture);
        return location;
    }

    static void opaque(NativeImage image, int x1, int y1, int x2, int y2) {
        for (int x = x1; x < x2; x++) for (int y = y1; y < y2; y++) {
            image.setPixelRGBA(x, y, image.getPixelRGBA(x, y) | 0xff000000);
        }
    }

    static void release(Textures t) {
        if (t == null) return;
        var manager = Minecraft.getInstance().getTextureManager();
        if (t.skin != null) manager.release(t.skin);
        if (t.animation != null) t.animation.close();
        else if (t.cape != null) manager.release(t.cape);
    }

    static void receive(CosmeticsMod.Appearance p) {
        release(TEXTURES.remove(p.id()));
        ResourceLocation skin = null, cape = null;
        AnimatedCape animation = null;
        try {
            skin = texture(p.id(), "skin", p.skin());
            if (p.cape().length > 0) {
                NativeImage image = NativeImage.read(new ByteArrayInputStream(p.cape()));
                int frames = image.getHeight() / 32;
                if (frames > 1) {
                    animation = new AnimatedCape(p.id(), image, p.frameMillis());
                    cape = animation.location;
                } else {
                    image.close();
                    cape = texture(p.id(), "cape", p.cape());
                }
            }
            TEXTURES.put(p.id(), new Textures(skin, cape, p.slim(), animation));
        } catch (Exception ex) {
            release(new Textures(skin, cape, p.slim(), animation));
            System.getLogger("nocosmetics").log(System.Logger.Level.WARNING, "Texture non caricata", ex);
        }
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut e) {
        TEXTURES.values().forEach(CosmeticsClient::release);
        TEXTURES.clear();
        sent = false;
    }

    public static final class AnimatedCape implements AutoCloseable {
        final ResourceLocation location;
        final NativeImage sheet;
        final DynamicTexture texture;
        final NativeImage frame;
        final int frames;
        final int frameMillis;
        int currentFrame = -1;

        AnimatedCape(UUID id, NativeImage sheet, int frameMillis) {
            this.sheet = sheet;
            this.frames = Math.max(1, sheet.getHeight() / 32);
            this.frameMillis = Math.max(40, Math.min(1000, frameMillis <= 0 ? 100 : frameMillis));
            this.frame = new NativeImage(64, 32, true);
            copyFrame(0);
            this.texture = new DynamicTexture(frame);
            this.location = new ResourceLocation("nocosmetics", "cape/" + id);
            Minecraft.getInstance().getTextureManager().register(location, texture);
            this.currentFrame = 0;
        }

        void tick() {
            int wanted = (int) ((System.currentTimeMillis() / frameMillis) % frames);
            if (wanted == currentFrame) return;
            copyFrame(wanted);
            texture.upload();
            currentFrame = wanted;
        }

        void copyFrame(int index) {
            int yOffset = index * 32;
            for (int x = 0; x < 64; x++) for (int y = 0; y < 32; y++) {
                frame.setPixelRGBA(x, y, sheet.getPixelRGBA(x, y + yOffset));
            }
        }

        @Override
        public void close() {
            Minecraft.getInstance().getTextureManager().release(location);
            sheet.close();
        }
    }
}
