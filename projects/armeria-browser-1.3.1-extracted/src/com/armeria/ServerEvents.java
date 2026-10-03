package com.armeria;

import java.util.*;
import java.util.concurrent.*;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "armeria", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ServerEvents {
    private static final Map<Object, Long> LAST_REQUEST = new WeakHashMap<>();
    private static final Set<Object> ACTIVE = ConcurrentHashMap.newKeySet();
    private static final ExecutorService WORKER = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), task -> {
                Thread thread = new Thread(task, "Armeria-server-files");
                thread.setDaemon(true); return thread;
            });

    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { ShopRuntime.reset(); LAST_REQUEST.clear(); }

    @SubscribeEvent
    public static void starting(ServerStartingEvent event) {
        try { SiteArchive.ensureServerPage(SiteArchive.gameDirectory()); }
        catch (Exception e) { System.getLogger("Armeria").log(System.Logger.Level.ERROR,
                "Impossibile preparare la pagina sul server", e); }
    }

    static void request(Object context, Object player, long id) {
        long now = System.nanoTime();
        synchronized (LAST_REQUEST) {
            Long last = LAST_REQUEST.get(player);
            if (ACTIVE.contains(player) || (last != null && now - last < 2_000_000_000L)) {
                Network.error(context, id, "Attendi due secondi e riprova /armeria."); return;
            }
            LAST_REQUEST.put(player, now);
        }
        ACTIVE.add(player);
        try {
            WORKER.execute(() -> {
                try {
                    byte[] zip = SiteArchive.pack(SiteArchive.ensureServerPage(SiteArchive.gameDirectory()));
                    byte[] hash = SiteArchive.digest(zip);
                    int count = (zip.length + Network.CHUNK - 1) / Network.CHUNK;
                    for (int index = 0; index < count; index++) {
                        int from = index * Network.CHUNK;
                        byte[] part = Arrays.copyOfRange(zip, from, Math.min(from + Network.CHUNK, zip.length));
                        Network.reply(context, new Network.Reply(id, index, count, zip.length, hash, part, ""));
                    }
                } catch (Exception e) {
                    System.getLogger("Armeria").log(System.Logger.Level.ERROR, "Trasferimento pagina fallito", e);
                    Network.error(context, id, "Errore pagina del server: " + e.getMessage());
                } finally { ACTIVE.remove(player); }
            });
        } catch (RejectedExecutionException full) {
            ACTIVE.remove(player);
            Network.error(context, id, "Server occupato, riprova tra qualche secondo.");
        }
    }
}
