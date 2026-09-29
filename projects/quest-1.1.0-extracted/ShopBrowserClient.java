package com.nuovoordine.quests;

import java.nio.file.*;
import java.net.URI;
import java.util.*;
import org.cef.CefClient;
import org.cef.browser.*;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefMessageRouterHandlerAdapter;

/** This class is referenced only by the client-side browser bridge. */
final class ShopBrowserClient {
    private static CefMessageRouter router;
    private static volatile CefBrowser browser;
    private static volatile Path trustedPage;
    private static long next = System.nanoTime();
    private record Pending(CefQueryCallback callback, long time) {}
    private static final Map<Long, Pending> pending = new HashMap<>();
    static void prepare() throws Exception {
        if (router != null) return;
        Object mcefClient = Class.forName("com.cinemamod.mcef.MCEF").getMethod("getClient").invoke(null);
        CefClient handle = (CefClient) mcefClient.getClass().getMethod("getHandle").invoke(mcefClient);
        router = CefMessageRouter.create(new CefMessageRouter.CefMessageRouterConfig("noquestsQuery", "noquestsCancel"),
            new CefMessageRouterHandlerAdapter() {
                @Override public boolean onQuery(CefBrowser from, CefFrame frame, long queryId,
                        String request, boolean persistent, CefQueryCallback callback) {
                    CefBrowser current = browser;
                    if (current == null || from.getIdentifier() != current.getIdentifier() || !frame.isMain()) return false;
                    try {
                        if (!Paths.get(URI.create(frame.getURL())).toAbsolutePath().normalize().equals(trustedPage)) return false;
                        if (persistent || request.length() > Network.MAX_SHOP_REQUEST) { callback.failure(400, "Richiesta non valida"); return true; }
                        BrowserBridge.execute(() -> {
                            if (pending.size() >= 16) { callback.failure(429, "Troppe richieste"); return; }
                            long id = ++next; pending.put(id, new Pending(callback, System.nanoTime()));
                            try { Network.shopRequest(new Network.ShopRequest(id, request)); }
                            catch (RuntimeException e) { pending.remove(id); callback.failure(500, "Connessione non disponibile"); }
                        });
                    } catch (Exception e) { callback.failure(403, "Pagina non autorizzata"); }
                    return true;
                }
            });
        handle.addMessageRouter(router);
    }
    static void bind(Object instance, Path page) {
        clear(); browser = (CefBrowser) instance; trustedPage = page.toAbsolutePath().normalize();
    }
    static void response(Network.ShopResult result) {
        Pending request = pending.remove(result.id());
        if (request != null) try { request.callback().success(result.json()); } catch (RuntimeException ignored) {}
    }
    static void tick() {
        long now = System.nanoTime();
        var iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            Pending p = iterator.next().getValue();
            if (now - p.time() > 30_000_000_000L) {
                iterator.remove(); try { p.callback().failure(408, "Il server non ha risposto. Aggiorna il catalogo prima di riprovare."); } catch (RuntimeException ignored) {}
            }
        }
    }
    static void clear() {
        for (Pending p : pending.values()) try { p.callback().failure(499, "Pagina chiusa"); } catch (RuntimeException ignored) {}
        pending.clear(); browser = null; trustedPage = null;
    }
}
