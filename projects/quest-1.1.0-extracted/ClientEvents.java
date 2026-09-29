package com.nuovoordine.quests;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "noquests", value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientEvents {
    private static boolean pendingOpen;

    @SubscribeEvent
    public static void registerCommands(RegisterClientCommandsEvent event) {
        try {
            // Reflection here avoids linking the generic Minecraft command source type.
            Method getter = RegisterClientCommandsEvent.class.getMethod("getDispatcher");
            @SuppressWarnings("unchecked")
            CommandDispatcher<Object> dispatcher = (CommandDispatcher<Object>) getter.invoke(event);
            register(dispatcher);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot register /quest", e);
        }
    }

    static void register(CommandDispatcher<Object> dispatcher) {
        dispatcher.register(LiteralArgumentBuilder.<Object>literal("quest")
                .executes(context -> {
                    pendingOpen = true;
                    return 1;
                }));
    }

    @SubscribeEvent
    public static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        ShopBrowserClient.tick();
        ClientTransfer.tick();
        if (!pendingOpen) return;
        // ChatScreen closes itself after command dispatch. Open on the next tick.
        pendingOpen = false;
        try {
            ClientTransfer.request();
        } catch (Exception | LinkageError e) {
            Throwable cause = e;
            while (cause instanceof InvocationTargetException && cause.getCause() != null)
                cause = cause.getCause();
            System.getLogger("Quest Nuovo Ordine").log(System.Logger.Level.ERROR,
                    "Impossibile aprire la noquests", cause);
            BrowserBridge.reportError("Quest Nuovo Ordine: " + cause.getClass().getSimpleName()
                    + ". Consulta logs/latest.log.");
        }
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        pendingOpen = false;
        ClientTransfer.cancel();
        ShopBrowserClient.clear();
    }
}
