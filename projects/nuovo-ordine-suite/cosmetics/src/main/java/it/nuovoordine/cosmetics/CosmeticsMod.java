package it.nuovoordine.cosmetics;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;
import java.util.function.Supplier;

@Mod("nocosmetics")
public class CosmeticsMod {
    static final SimpleChannel NET = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("nocosmetics", "main"), () -> "1", "1"::equals, "1"::equals);
    static final Map<UUID, Appearance> active = new HashMap<>();
    static final Map<UUID, Long> last = new HashMap<>();

    public CosmeticsMod() {
        NET.registerMessage(0, Upload.class, Upload::encode, Upload::decode, CosmeticsMod::upload,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        NET.registerMessage(1, Appearance.class, Appearance::encode, Appearance::decode,
                (p, c) -> { c.get().enqueueWork(() -> CosmeticsClient.receive(p)); c.get().setPacketHandled(true); },
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        MinecraftForge.EVENT_BUS.addListener(this::login);
        MinecraftForge.EVENT_BUS.addListener(this::logout);
        MinecraftForge.EVENT_BUS.addListener(this::stop);
    }

    void login(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            for (var a : active.values()) NET.send(PacketDistributor.PLAYER.with(() -> p), a);
        }
    }

    void logout(PlayerEvent.PlayerLoggedOutEvent e) {
        UUID id = e.getEntity().getUUID();
        active.remove(id);
        last.remove(id);
        NET.send(PacketDistributor.ALL.noArg(), new Appearance(id, new byte[0], new byte[0], false, 0));
    }

    void stop(ServerStoppedEvent e) { active.clear(); last.clear(); }

    record Upload(byte[] skin, byte[] cape, boolean slim, int frameMillis) {
        static void encode(Upload p, FriendlyByteBuf b) {
            b.writeByteArray(p.skin); b.writeByteArray(p.cape); b.writeBoolean(p.slim); b.writeVarInt(p.frameMillis);
        }
        static Upload decode(FriendlyByteBuf b) {
            return new Upload(b.readByteArray(PngGuard.MAX_SKIN_BYTES), b.readByteArray(PngGuard.MAX_CAPE_BYTES),
                    b.readBoolean(), b.readableBytes() > 0 ? b.readVarInt() : 0);
        }
    }

    record Appearance(UUID id, byte[] skin, byte[] cape, boolean slim, int frameMillis) {
        static void encode(Appearance p, FriendlyByteBuf b) {
            b.writeUUID(p.id); b.writeByteArray(p.skin); b.writeByteArray(p.cape); b.writeBoolean(p.slim); b.writeVarInt(p.frameMillis);
        }
        static Appearance decode(FriendlyByteBuf b) {
            return new Appearance(b.readUUID(), b.readByteArray(PngGuard.MAX_SKIN_BYTES),
                    b.readByteArray(PngGuard.MAX_CAPE_BYTES), b.readBoolean(), b.readableBytes() > 0 ? b.readVarInt() : 0);
        }
    }

    static int normalizeFrameMillis(int frames, int value) {
        if (frames <= 1) return 0;
        return Math.max(40, Math.min(1000, value <= 0 ? 100 : value));
    }

    static void upload(Upload p, Supplier<NetworkEvent.Context> ctx) {
        var c = ctx.get();
        c.enqueueWork(() -> {
            var player = c.getSender();
            if (player == null) return;
            UUID id = player.getUUID();
            long now = System.currentTimeMillis();
            if (now - last.getOrDefault(id, 0L) < 5000) return;
            last.put(id, now);
            try {
                byte[] skin = PngGuard.validate(p.skin, true);
                byte[] cape = PngGuard.validate(p.cape, false);
                int frameMillis = normalizeFrameMillis(PngGuard.capeFrames(cape), p.frameMillis);
                Appearance a = new Appearance(id, skin, cape, p.slim, frameMillis);
                active.put(id, a);
                NET.send(PacketDistributor.ALL.noArg(), a);
            } catch (Exception ex) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "Skin/mantello rifiutato: PNG non valido o animazione troppo grande"));
            }
        });
        c.setPacketHandled(true);
    }
}
