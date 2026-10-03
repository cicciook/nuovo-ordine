package it.nuovoordine.taczfix;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@Mod(TaczSyncFixMod.MODID)
public final class TaczSyncFixMod {
    public static final String MODID = "notaczfix";
    private static final Logger LOGGER = LogUtils.getLogger();

    @Mod.EventBusSubscriber(modid = MODID, value = Dist.CLIENT)
    public static final class ClientEvents {
        private static int ticks;
        private static boolean synced;

        @SubscribeEvent
        public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
            ticks = 0;
            synced = false;
        }

        @SubscribeEvent
        public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
            ticks = 0;
            synced = false;
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END || synced) return;
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            if (player == null || mc.getConnection() == null) return;
            if (!ModList.get().isLoaded("tacz")) {
                synced = true;
                return;
            }
            if (++ticks < 60) return;
            synced = true;
            try {
                resync(player);
                LOGGER.info("Nuovo Ordine: TaCZ post-login synchronization refreshed.");
            } catch (Throwable error) {
                LOGGER.warn("Nuovo Ordine: TaCZ synchronization refresh failed; leaving TaCZ untouched.", error);
            }
        }

        private static void resync(LocalPlayer player) throws Exception {
            Class<?> operatorType = Class.forName("com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator");
            Method fromLocalPlayer = operatorType.getMethod("fromLocalPlayer", LocalPlayer.class);
            Object operator = fromLocalPlayer.invoke(null, player);

            Object holder = operatorType.getMethod("getDataHolder").invoke(operator);
            Field clientBaseTimestamp = holder.getClass().getField("clientBaseTimestamp");
            clientBaseTimestamp.setLong(holder, System.currentTimeMillis());

            Class<?> syncMessageType = Class.forName("com.tacz.guns.network.message.ClientMessageSyncBaseTimestamp");
            Object syncMessage = syncMessageType.getConstructor().newInstance();
            Class<?> networkHandlerType = Class.forName("com.tacz.guns.network.NetworkHandler");
            Object channel = networkHandlerType.getField("CHANNEL").get(null);
            channel.getClass().getMethod("sendToServer", Object.class).invoke(channel, syncMessage);

            operatorType.getMethod("draw", ItemStack.class).invoke(operator, ItemStack.EMPTY);
        }
    }
}
