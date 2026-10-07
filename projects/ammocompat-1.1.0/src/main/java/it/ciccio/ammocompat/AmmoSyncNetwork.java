package it.ciccio.ammocompat;

import com.atsuishio.superbwarfare.data.gun.Ammo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.Supplier;

public final class AmmoSyncNetwork {
    private static final String PROTOCOL = "1";
    private static final SimpleChannel NET = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(AmmoCompat.MODID, "ammo_sync"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );
    private static boolean initialized;

    private AmmoSyncNetwork() {}

    public static synchronized void init() {
        if (initialized) return;
        NET.registerMessage(
                0,
                AmmoCounts.class,
                AmmoCounts::encode,
                AmmoCounts::decode,
                AmmoSyncNetwork::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        initialized = true;
    }

    public static void send(ServerPlayer player) {
        if (player == null) return;
        NET.send(
                PacketDistributor.PLAYER.with(() -> player),
                new AmmoCounts(
                        Ammo.HANDGUN.get(player),
                        Ammo.RIFLE.get(player),
                        Ammo.SHOTGUN.get(player),
                        Ammo.SNIPER.get(player),
                        Ammo.HEAVY.get(player)
                )
        );
    }

    public record AmmoCounts(int handgun, int rifle, int shotgun, int sniper, int heavy) {
        static void encode(AmmoCounts p, FriendlyByteBuf b) {
            b.writeVarInt(Math.max(0, p.handgun()));
            b.writeVarInt(Math.max(0, p.rifle()));
            b.writeVarInt(Math.max(0, p.shotgun()));
            b.writeVarInt(Math.max(0, p.sniper()));
            b.writeVarInt(Math.max(0, p.heavy()));
        }

        static AmmoCounts decode(FriendlyByteBuf b) {
            return new AmmoCounts(
                    b.readVarInt(),
                    b.readVarInt(),
                    b.readVarInt(),
                    b.readVarInt(),
                    b.readVarInt()
            );
        }
    }

    private static void handle(AmmoCounts p, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context ctx = supplier.get();
        ctx.enqueueWork(() -> AmmoCompat.setClientSuperbAmmoCounts(
                p.handgun(), p.rifle(), p.shotgun(), p.sniper(), p.heavy()
        ));
        ctx.setPacketHandled(true);
    }
}
