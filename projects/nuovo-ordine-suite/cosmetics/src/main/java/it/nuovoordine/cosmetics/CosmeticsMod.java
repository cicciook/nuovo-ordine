package it.nuovoordine.cosmetics;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;

import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;

@Mod("nocosmetics")
public class CosmeticsMod {
    static final int CHUNK_BYTES = 24 * 1024;

    static final SimpleChannel NET = NetworkRegistry.newSimpleChannel(
        new ResourceLocation("nocosmetics", "main"),
        () -> "2",
        "2"::equals,
        "2"::equals
    );

    static final Map<UUID, Appearance> active = new HashMap<>();
    static final Map<UUID, IncomingUpload> pending = new HashMap<>();
    static final Map<UUID, Long> last = new HashMap<>();

    public CosmeticsMod() {
        NET.registerMessage(
            0,
            UploadStart.class,
            UploadStart::encode,
            UploadStart::decode,
            CosmeticsMod::uploadStart,
            Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        NET.registerMessage(
            1,
            UploadChunk.class,
            UploadChunk::encode,
            UploadChunk::decode,
            CosmeticsMod::uploadChunk,
            Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        NET.registerMessage(
            2,
            AppearanceHeader.class,
            AppearanceHeader::encode,
            AppearanceHeader::decode,
            (p, c) -> {
                c.get().enqueueWork(() -> CosmeticsClient.beginAppearance(p));
                c.get().setPacketHandled(true);
            },
            Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        NET.registerMessage(
            3,
            AppearanceChunk.class,
            AppearanceChunk::encode,
            AppearanceChunk::decode,
            (p, c) -> {
                c.get().enqueueWork(() -> CosmeticsClient.receiveAppearanceChunk(p));
                c.get().setPacketHandled(true);
            },
            Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        NET.registerMessage(
            4,
            AppearanceRemove.class,
            AppearanceRemove::encode,
            AppearanceRemove::decode,
            (p, c) -> {
                c.get().enqueueWork(() -> CosmeticsClient.removeAppearance(p.id()));
                c.get().setPacketHandled(true);
            },
            Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

        MinecraftForge.EVENT_BUS.addListener(this::login);
        MinecraftForge.EVENT_BUS.addListener(this::logout);
        MinecraftForge.EVENT_BUS.addListener(this::stop);
    }

    static int expectedChunks(int bytes) {
        if (bytes <= 0) return 0;
        return (bytes + CHUNK_BYTES - 1) / CHUNK_BYTES;
    }

    void login(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer player)) return;
        for (var appearance : active.values()) {
            sendAppearance(player, appearance);
        }
    }

    void logout(PlayerEvent.PlayerLoggedOutEvent e) {
        UUID id = e.getEntity().getUUID();
        active.remove(id);
        pending.remove(id);
        last.remove(id);
        NET.send(PacketDistributor.ALL.noArg(), new AppearanceRemove(id));
    }

    void stop(ServerStoppedEvent e) {
        active.clear();
        pending.clear();
        last.clear();
    }

    record UploadStart(byte[] skin, boolean slim, int capeBytes, int capeChunks) {
        static void encode(UploadStart p, FriendlyByteBuf b) {
            b.writeByteArray(p.skin());
            b.writeBoolean(p.slim());
            b.writeVarInt(p.capeBytes());
            b.writeVarInt(p.capeChunks());
        }

        static UploadStart decode(FriendlyByteBuf b) {
            return new UploadStart(
                b.readByteArray(PngGuard.MAX_SKIN_BYTES),
                b.readBoolean(),
                b.readVarInt(),
                b.readVarInt()
            );
        }
    }

    record UploadChunk(int index, int total, byte[] data) {
        static void encode(UploadChunk p, FriendlyByteBuf b) {
            b.writeVarInt(p.index());
            b.writeVarInt(p.total());
            b.writeByteArray(p.data());
        }

        static UploadChunk decode(FriendlyByteBuf b) {
            return new UploadChunk(
                b.readVarInt(),
                b.readVarInt(),
                b.readByteArray(CHUNK_BYTES)
            );
        }
    }

    record Appearance(UUID id, byte[] skin, byte[] cape, boolean slim) {}

    record AppearanceHeader(UUID id, byte[] skin, boolean slim, int capeBytes, int capeChunks) {
        static void encode(AppearanceHeader p, FriendlyByteBuf b) {
            b.writeUUID(p.id());
            b.writeByteArray(p.skin());
            b.writeBoolean(p.slim());
            b.writeVarInt(p.capeBytes());
            b.writeVarInt(p.capeChunks());
        }

        static AppearanceHeader decode(FriendlyByteBuf b) {
            return new AppearanceHeader(
                b.readUUID(),
                b.readByteArray(PngGuard.MAX_SKIN_BYTES),
                b.readBoolean(),
                b.readVarInt(),
                b.readVarInt()
            );
        }
    }

    record AppearanceChunk(UUID id, int index, int total, byte[] data) {
        static void encode(AppearanceChunk p, FriendlyByteBuf b) {
            b.writeUUID(p.id());
            b.writeVarInt(p.index());
            b.writeVarInt(p.total());
            b.writeByteArray(p.data());
        }

        static AppearanceChunk decode(FriendlyByteBuf b) {
            return new AppearanceChunk(
                b.readUUID(),
                b.readVarInt(),
                b.readVarInt(),
                b.readByteArray(CHUNK_BYTES)
            );
        }
    }

    record AppearanceRemove(UUID id) {
        static void encode(AppearanceRemove p, FriendlyByteBuf b) {
            b.writeUUID(p.id());
        }

        static AppearanceRemove decode(FriendlyByteBuf b) {
            return new AppearanceRemove(b.readUUID());
        }
    }

    static final class IncomingUpload {
        final byte[] skin;
        final boolean slim;
        final int totalBytes;
        final byte[][] chunks;
        int received;

        IncomingUpload(byte[] skin, boolean slim, int totalBytes, int totalChunks) {
            this.skin = skin;
            this.slim = slim;
            this.totalBytes = totalBytes;
            this.chunks = new byte[totalChunks][];
        }

        boolean accept(UploadChunk chunk) {
            if (chunk.total() != chunks.length) return false;
            if (chunk.index() < 0 || chunk.index() >= chunks.length) return false;
            if (chunk.data().length == 0 || chunk.data().length > CHUNK_BYTES) return false;
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

    static void uploadStart(UploadStart packet, Supplier<NetworkEvent.Context> ctx) {
        var c = ctx.get();
        c.enqueueWork(() -> {
            var player = c.getSender();
            if (player == null) return;

            UUID id = player.getUUID();
            long now = System.currentTimeMillis();
            if (now - last.getOrDefault(id, 0L) < 5000L) return;
            last.put(id, now);

            try {
                if (packet.capeBytes() < 0 || packet.capeBytes() > PngGuard.MAX_CAPE_BYTES)
                    throw new IOException("Cape size");
                if (packet.capeChunks() != expectedChunks(packet.capeBytes()))
                    throw new IOException("Cape chunks");

                byte[] skin = PngGuard.validate(packet.skin(), true);

                if (packet.capeBytes() == 0) {
                    finishUpload(player, skin, new byte[0], packet.slim());
                    return;
                }

                pending.put(
                    id,
                    new IncomingUpload(
                        skin,
                        packet.slim(),
                        packet.capeBytes(),
                        packet.capeChunks()
                    )
                );
            } catch (Exception ex) {
                pending.remove(id);
                reject(player, ex.getMessage());
            }
        });
        c.setPacketHandled(true);
    }

    static void uploadChunk(UploadChunk packet, Supplier<NetworkEvent.Context> ctx) {
        var c = ctx.get();
        c.enqueueWork(() -> {
            var player = c.getSender();
            if (player == null) return;

            UUID id = player.getUUID();
            IncomingUpload upload = pending.get(id);
            if (upload == null) return;

            try {
                if (!upload.accept(packet)) throw new IOException("Cape chunk");
                if (!upload.complete()) return;

                pending.remove(id);
                byte[] cape = PngGuard.validate(upload.join(), false);
                finishUpload(player, upload.skin, cape, upload.slim);
            } catch (Exception ex) {
                pending.remove(id);
                reject(player, ex.getMessage());
            }
        });
        c.setPacketHandled(true);
    }

    static void finishUpload(ServerPlayer player, byte[] skin, byte[] cape, boolean slim) {
        Appearance appearance = new Appearance(player.getUUID(), skin, cape, slim);
        active.put(player.getUUID(), appearance);
        broadcastAppearance(appearance);
    }

    static void reject(ServerPlayer player, String detail) {
        player.sendSystemMessage(
            net.minecraft.network.chat.Component.literal(
                "Skin/mantello rifiutato: file non valido o troppo grande"
                + (detail == null || detail.isBlank() ? "" : " (" + detail + ")")
            )
        );
    }

    static void sendAppearance(ServerPlayer player, Appearance appearance) {
        int total = expectedChunks(appearance.cape().length);
        NET.send(
            PacketDistributor.PLAYER.with(() -> player),
            new AppearanceHeader(
                appearance.id(),
                appearance.skin(),
                appearance.slim(),
                appearance.cape().length,
                total
            )
        );
        for (int index = 0; index < total; index++) {
            int start = index * CHUNK_BYTES;
            int end = Math.min(start + CHUNK_BYTES, appearance.cape().length);
            NET.send(
                PacketDistributor.PLAYER.with(() -> player),
                new AppearanceChunk(
                    appearance.id(),
                    index,
                    total,
                    Arrays.copyOfRange(appearance.cape(), start, end)
                )
            );
        }
    }

    static void broadcastAppearance(Appearance appearance) {
        int total = expectedChunks(appearance.cape().length);
        NET.send(
            PacketDistributor.ALL.noArg(),
            new AppearanceHeader(
                appearance.id(),
                appearance.skin(),
                appearance.slim(),
                appearance.cape().length,
                total
            )
        );
        for (int index = 0; index < total; index++) {
            int start = index * CHUNK_BYTES;
            int end = Math.min(start + CHUNK_BYTES, appearance.cape().length);
            NET.send(
                PacketDistributor.ALL.noArg(),
                new AppearanceChunk(
                    appearance.id(),
                    index,
                    total,
                    Arrays.copyOfRange(appearance.cape(), start, end)
                )
            );
        }
    }
}
