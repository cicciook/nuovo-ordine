package com.nuovoordine.quests;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JavaScript bridge for the quest browser.
 *
 * 1.2.4 deliberately does not use CefMessageRouter. On some Forge 1.20.1
 * stacks (especially when Connector is present) the renderer-side message
 * router is not installed in the MCEF browser context, so noquestsQuery never
 * appears on window even though the Java router was registered successfully.
 *
 * Instead we inject the same noquestsQuery API into the page and transport
 * requests through a private console-message channel handled by MCEF's
 * CefDisplayHandler. Server replies are delivered back with executeJavaScript.
 */
final class ShopBrowserClient {
    private static final String REQUEST_PREFIX = "__NOQUESTS_123_REQ__";
    private static final long TIMEOUT_NS = 30_000_000_000L;
    private static final int MAX_REQUEST = 65_536;

    private static final AtomicLong NEXT = new AtomicLong(System.nanoTime());
    private static final Map<Long, Pending> PENDING = new ConcurrentHashMap<>();

    private static volatile Object browser;
    private static volatile Path trustedRoot;
    private static volatile boolean prepared;
    private static Object displayHandler;
    private static Object loadHandler;

    private record Pending(String clientId, long time) {}

    private ShopBrowserClient() {}

    static synchronized void prepare() throws Exception {
        if (prepared) return;

        Class<?> mcef = Class.forName("com.cinemamod.mcef.MCEF");
        Object client = mcef.getMethod("getClient").invoke(null);

        Class<?> displayType = Class.forName("org.cef.handler.CefDisplayHandler");
        displayHandler = Proxy.newProxyInstance(
                displayType.getClassLoader(), new Class<?>[]{displayType},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("toString")) return "NuovoOrdineQuestDisplayBridge";
                    if (name.equals("hashCode")) return System.identityHashCode(proxy);
                    if (name.equals("equals")) return proxy == (args == null ? null : args[0]);
                    if (name.equals("onConsoleMessage") && args != null && args.length >= 3) {
                        Object cefBrowser = args[0];
                        String message = String.valueOf(args[2]);
                        if (message.startsWith(REQUEST_PREFIX) && isTrustedBrowser(cefBrowser)) {
                            acceptConsoleRequest(message.substring(REQUEST_PREFIX.length()));
                            return true;
                        }
                    }
                    return defaultValue(method.getReturnType());
                });
        client.getClass().getMethod("addDisplayHandler", displayType).invoke(client, displayHandler);

        Class<?> loadType = Class.forName("org.cef.handler.CefLoadHandler");
        loadHandler = Proxy.newProxyInstance(
                loadType.getClassLoader(), new Class<?>[]{loadType},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("toString")) return "NuovoOrdineQuestLoadBridge";
                    if (name.equals("hashCode")) return System.identityHashCode(proxy);
                    if (name.equals("equals")) return proxy == (args == null ? null : args[0]);
                    if ((name.equals("onLoadStart") || name.equals("onLoadEnd")) && args != null && args.length >= 2) {
                        Object cefBrowser = args[0];
                        Object frame = args[1];
                        if (isMainFrame(frame) && isTrustedBrowser(cefBrowser)) {
                            injectBridge(cefBrowser);
                            if (name.equals("onLoadEnd")) kickPage(cefBrowser);
                        }
                    } else if (name.equals("onLoadingStateChange") && args != null && args.length >= 2) {
                        Object cefBrowser = args[0];
                        boolean loading = Boolean.TRUE.equals(args[1]);
                        if (!loading && isTrustedBrowser(cefBrowser)) {
                            injectBridge(cefBrowser);
                            kickPage(cefBrowser);
                        }
                    }
                    return defaultValue(method.getReturnType());
                });
        client.getClass().getMethod("addLoadHandler", loadType).invoke(client, loadHandler);

        prepared = true;
        System.getLogger("Quest Nuovo Ordine").log(System.Logger.Level.INFO,
                "Bridge /quest 1.2.4 pronto (console transport, CefMessageRouter non richiesto)");
    }

    static void bind(Object cefBrowser, Path trustedPage) {
        clear();
        browser = cefBrowser;
        Path page = trustedPage.toAbsolutePath().normalize();
        trustedRoot = page.getParent();
        try { injectBridge(cefBrowser); } catch (Throwable ignored) {}
    }

    static void response(Network.ShopResult result) {
        Pending pending = PENDING.remove(result.id());
        if (pending == null) return;
        deliver(pending.clientId(), true, result.json());
    }

    static void tick() {
        long now = System.nanoTime();
        for (Map.Entry<Long, Pending> entry : PENDING.entrySet()) {
            Pending pending = entry.getValue();
            if (now - pending.time() > TIMEOUT_NS && PENDING.remove(entry.getKey(), pending)) {
                deliver(pending.clientId(), false, "Il server non ha risposto. Premi Aggiorna e riprova.");
            }
        }
    }

    static void clear() {
        for (Pending pending : PENDING.values()) {
            deliver(pending.clientId(), false, "Pagina chiusa");
        }
        PENDING.clear();
        browser = null;
        trustedRoot = null;
    }

    private static void acceptConsoleRequest(String body) {
        int split = body.indexOf(':');
        if (split <= 0 || split > 40) return;
        String clientId = body.substring(0, split);
        if (!clientId.matches("[0-9]{1,32}")) return;

        final String request;
        try {
            byte[] raw = Base64.getDecoder().decode(body.substring(split + 1));
            if (raw.length > MAX_REQUEST) throw new IllegalArgumentException("request too large");
            request = new String(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            deliver(clientId, false, "Richiesta bridge non valida");
            return;
        }

        try {
            BrowserBridge.execute(() -> {
                long id = NEXT.incrementAndGet();
                PENDING.put(id, new Pending(clientId, System.nanoTime()));
                try {
                    Network.shopRequest(new Network.ShopRequest(id, request));
                } catch (RuntimeException e) {
                    PENDING.remove(id);
                    deliver(clientId, false, "Connessione al server non disponibile");
                }
            });
        } catch (ReflectiveOperationException | LinkageError e) {
            deliver(clientId, false, "Minecraft client non disponibile");
        }
    }

    private static boolean isTrustedBrowser(Object cefBrowser) {
        Object active = browser;
        Path root = trustedRoot;
        if (active == null || root == null || cefBrowser == null) return false;
        if (active != cefBrowser) {
            try {
                int a = ((Number) invoke(active, "getIdentifier")).intValue();
                int b = ((Number) invoke(cefBrowser, "getIdentifier")).intValue();
                if (a != b) return false;
            } catch (Throwable e) {
                return false;
            }
        }
        try {
            String url = String.valueOf(invoke(cefBrowser, "getURL"));
            URI uri = URI.create(url);
            if (!"file".equalsIgnoreCase(uri.getScheme())) return false;
            Path page = Paths.get(uri).toAbsolutePath().normalize();
            return page.startsWith(root);
        } catch (Throwable e) {
            return active == cefBrowser;
        }
    }

    private static boolean isMainFrame(Object frame) {
        try { return Boolean.TRUE.equals(invoke(frame, "isMain")); }
        catch (Throwable e) { return false; }
    }

    private static void injectBridge(Object cefBrowser) {
        String js = """
            (function(){
              if(window.__noquests123Installed) return;
              window.__noquests123Installed=true;
              var pending=Object.create(null), seq=0;
              function enc(s){return btoa(unescape(encodeURIComponent(String(s))));}
              function dec(s){return decodeURIComponent(escape(atob(String(s||''))));}
              window.noquestsQuery=function(o){
                o=o||{}; var id=String(++seq);
                pending[id]={ok:(typeof o.onSuccess==='function'?o.onSuccess:null), fail:(typeof o.onFailure==='function'?o.onFailure:null)};
                try{console.log('%s'+id+':'+enc(o.request||''));}
                catch(e){var p=pending[id];delete pending[id];if(p&&p.fail)p.fail(500,String(e));}
              };
              window.noquestsCancel=function(id){delete pending[String(id)];};
              window.__noquests123Resolve=function(id,ok,payload){
                id=String(id);var p=pending[id];if(!p)return;delete pending[id];
                var text='';try{text=dec(payload);}catch(e){text='';}
                if(ok){if(p.ok)p.ok(text);}else{if(p.fail)p.fail(500,text||'Errore bridge');}
              };
              try{window.dispatchEvent(new Event('noquestsBridgeReady'));}catch(e){}
            })();
            """.formatted(REQUEST_PREFIX);
        executeJs(cefBrowser, js);
    }

    private static void kickPage(Object cefBrowser) {
        String js = """
            (function(){
              function retry(){
                try{
                  if(typeof window.refreshState==='function'){window.refreshState(false);return;}
                  if(typeof window.refresh==='function'){window.refresh(false);return;}
                  var b=document.getElementById('refresh'); if(b&&typeof b.click==='function'){b.click();return;}
                  var b2=document.getElementById('refreshBtn'); if(b2&&typeof b2.click==='function') b2.click();
                }catch(e){}
              }
              retry(); setTimeout(retry,150); setTimeout(retry,700); setTimeout(retry,1600);
            })();
            """;
        executeJs(cefBrowser, js);
    }

    private static void deliver(String clientId, boolean ok, String text) {
        Object active = browser;
        if (active == null) return;
        String payload = Base64.getEncoder().encodeToString(String.valueOf(text == null ? "" : text)
                .getBytes(StandardCharsets.UTF_8));
        String safeId = clientId.replaceAll("[^0-9]", "");
        if (safeId.isEmpty()) return;
        String js = "window.__noquests123Resolve&&window.__noquests123Resolve('" + safeId + "',"
                + (ok ? "true" : "false") + ",'" + payload + "');";
        try { executeJs(active, js); } catch (Throwable ignored) {}
    }

    private static void executeJs(Object cefBrowser, String js) {
        try {
            Method method = cefBrowser.getClass().getMethod("executeJavaScript", String.class, String.class, int.class);
            String url = "file:///nuovo-ordine/quest";
            try { url = String.valueOf(invoke(cefBrowser, "getURL")); } catch (Throwable ignored) {}
            method.invoke(cefBrowser, js, url, 0);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Impossibile eseguire JavaScript nel browser MCEF", e);
        }
    }

    private static Object invoke(Object target, String method) throws ReflectiveOperationException {
        return target.getClass().getMethod(method).invoke(target);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return (char) 0;
        return null;
    }
}
