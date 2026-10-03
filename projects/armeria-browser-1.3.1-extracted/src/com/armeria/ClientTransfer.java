package com.armeria;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.*;

final class ClientTransfer {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Armeria-client-files"); thread.setDaemon(true); return thread;
    });
    private static long nextId = System.nanoTime(), currentId, started;
    private static Assembly assembly;
    private static CompletableFuture<Path> extraction;
    private static Path displayedDirectory;

    static final class Assembly {
        private final long id;
        private int next, count, total, offset;
        private byte[] bytes, hash;
        Assembly(long id) { this.id = id; }
        byte[] add(Network.Reply p) {
            if (p.id() != id) return null;
            if (!p.error().isEmpty()) throw new IllegalArgumentException(p.error());
            if (p.index() != next) throw new IllegalArgumentException("Ordine dei file non valido");
            if (next == 0) {
                if (p.total() <= 0 || p.total() > SiteArchive.MAX_BYTES || p.count() <= 0
                        || p.count() != (p.total() + Network.CHUNK - 1) / Network.CHUNK)
                    throw new IllegalArgumentException("Dimensioni non valide");
                total = p.total(); count = p.count(); hash = p.hash(); bytes = new byte[total];
            }
            if (p.total() != total || p.count() != count || !Arrays.equals(p.hash(), hash)
                    || p.data().length != Math.min(Network.CHUNK, total - offset))
                throw new IllegalArgumentException("Trasferimento incoerente");
            System.arraycopy(p.data(), 0, bytes, offset, p.data().length);
            offset += p.data().length; next++;
            if (next != count) return null;
            if (offset != total || !Arrays.equals(SiteArchive.digest(bytes), hash))
                throw new IllegalArgumentException("Verifica dei file fallita");
            return bytes;
        }
    }

    static void request() throws Exception {
        if (!BrowserBridge.connected()) throw new IllegalStateException("Entra prima nel mondo");
        if (assembly != null || extraction != null) {
            BrowserBridge.reportError("Armeria: download gia in corso."); return;
        }
        currentId = ++nextId;
        started = System.nanoTime();
        assembly = new Assembly(currentId);
        BrowserBridge.reportError("Armeria: scaricamento della pagina dal server...");
        try { Network.request(currentId); }
        catch (RuntimeException e) { cancel(); throw e; }
    }

    static void accept(Network.Reply packet) {
        if (assembly == null || packet.id() != currentId) return;
        try {
            byte[] bytes = assembly.add(packet);
            if (bytes == null) return;
            assembly = null;
            Path cache = SiteArchive.gameDirectory().resolve("armeria-cache");
            extraction = CompletableFuture.supplyAsync(() -> {
                try { return SiteArchive.unpack(bytes, cache); }
                catch (Exception e) { throw new CompletionException(e); }
            }, WORKER);
        } catch (Exception e) { fail(e); }
    }

    static void tick() {
        if ((assembly != null || extraction != null) && System.nanoTime() - started > 90_000_000_000L) {
            fail(new IllegalStateException("Il server non ha completato il download. Riprova /armeria.")); return;
        }
        if (extraction == null || !extraction.isDone()) return;
        CompletableFuture<Path> result = extraction;
        extraction = null;
        Path page = null;
        try {
            page = result.join();
            if (!BrowserBridge.connected()) { SiteArchive.deleteTree(page.getParent()); return; }
            Path shop = page.resolveSibling("shop.html");
            BrowserBridge.open(java.nio.file.Files.isRegularFile(shop) ? shop : page);
            Path previous = displayedDirectory;
            displayedDirectory = page.getParent();
            WORKER.execute(() -> SiteArchive.deleteTree(previous));
        } catch (Exception | LinkageError e) {
            if (page != null) SiteArchive.deleteTree(page.getParent());
            fail(e);
        }
    }

    static void cancel() {
        assembly = null;
        currentId = ++nextId;
        if (extraction != null) {
            extraction.thenAcceptAsync(page -> SiteArchive.deleteTree(page.getParent()), WORKER);
            extraction = null;
        }
        Path old = displayedDirectory;
        displayedDirectory = null;
        WORKER.execute(() -> SiteArchive.deleteTree(old));
    }

    private static void fail(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof java.lang.reflect.InvocationTargetException)
                && cause.getCause() != null) cause = cause.getCause();
        System.getLogger("Armeria").log(System.Logger.Level.ERROR, "Apertura pagina fallita", cause);
        BrowserBridge.reportError("Armeria: " + cause.getMessage());
        cancel();
    }
}
