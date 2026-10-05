package com.armeria;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

final class SiteArchive {
    static final int MAX_BYTES = 32 * 1024 * 1024;
    static final int MAX_FILES = 2048;
    private static final Set<String> EXTENSIONS = Set.of("html", "htm", "css", "js", "mjs",
            "json", "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif",
            "woff", "woff2", "ttf", "otf", "txt", "mp3", "ogg", "wav", "mp4", "webm");

    static Path gameDirectory() throws ReflectiveOperationException {
        Class<?> paths = Class.forName("net.minecraftforge.fml.loading.FMLPaths");
        return ((Path) paths.getMethod("get").invoke(paths.getField("GAMEDIR").get(null)))
                .toAbsolutePath().normalize();
    }

    static Path ensureServerPage(Path gameDirectory) throws IOException {
        Path root = gameDirectory.resolve("armeria");
        if (Files.isSymbolicLink(root)) throw new IOException("La cartella armeria non puo essere un collegamento");
        Files.createDirectories(root);
        Path index = root.resolve("index.html");
        if (!Files.exists(index, LinkOption.NOFOLLOW_LINKS)) {
            try (InputStream in = SiteArchive.class.getResourceAsStream("/armeria_default/index.html")) {
                if (in == null) throw new IOException("Pagina predefinita mancante");
                Files.copy(in, index);
            }
        }
        if (!Files.isRegularFile(index, LinkOption.NOFOLLOW_LINKS)) throw new IOException("index.html non valido");
        Path shop = root.resolve("shop.html");
        boolean installShop = !Files.exists(shop, LinkOption.NOFOLLOW_LINKS);
        if (!installShop && Files.isRegularFile(shop, LinkOption.NOFOLLOW_LINKS)
                && Files.size(shop) < 1024 * 1024) {
            byte[] existingBytes = Files.readAllBytes(shop);
            String existingHash = HexFormat.of().formatHex(digest(existingBytes));
            String existingText = new String(existingBytes, java.nio.charset.StandardCharsets.UTF_8);
            boolean bundledDefault = existingHash.equals(
                    "e8935ae6b2853b4cc307ab67ac8c6f3c8224e57d4c4f902321dd0c912adb3106")
                    || existingText.contains("nuovo-ordine-armeria-default:")
                    || (existingText.contains("ANTEPRIMA — questi articoli sono esempi grafici.")
                        && existingText.contains("function native(){return typeof window.armeriaQuery==='function'}")
                        && existingText.contains("window.addEventListener('armeriaBridgeReady'"));
            if (bundledDefault && !existingText.contains("nuovo-ordine-armeria-default:1.3.8")) {
                Path backup = root.resolve("shop.pre-1.3.8.bak");
                if (!Files.exists(backup, LinkOption.NOFOLLOW_LINKS))
                    Files.copy(shop, backup, StandardCopyOption.COPY_ATTRIBUTES);
                installShop = true;
            }
        }
        if (installShop) {
            try (InputStream in = SiteArchive.class.getResourceAsStream("/armeria_default/shop.html")) {
                if (in == null) throw new IOException("Pagina predefinita armeria mancante");
                Path temp = Files.createTempFile(root, ".shop-update-", ".tmp");
                try {
                    Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
                    try { Files.move(temp, shop, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                    catch (AtomicMoveNotSupportedException e) {
                        Files.move(temp, shop, StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally { Files.deleteIfExists(temp); }
            }
        }
        return root;
    }

    static boolean allowed(String name) {
        if (name.isEmpty() || name.length() > 512 || name.startsWith("/")
                || name.contains("\\") || name.contains(":") || name.indexOf('\0') >= 0) return false;
        for (String part : name.split("/", -1))
            if (part.isEmpty() || part.startsWith(".") || part.endsWith(".") || part.endsWith(" ")) return false;
        int dot = name.lastIndexOf('.');
        return dot >= 0 && EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    static byte[] pack(Path root) throws IOException {
        root = root.toRealPath();
        List<Path> files;
        try (var walk = Files.walk(root)) {
            files = walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .sorted().limit(MAX_FILES + 1L).toList();
        }
        if (files.size() > MAX_FILES) throw new IOException("Troppi file nella armeria (massimo 2048)");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Set<String> names = new HashSet<>();
        long total = 0;
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            byte[] buffer = new byte[8192];
            for (Path file : files) {
                String name = root.relativize(file).toString().replace(File.separatorChar, '/');
                if (!allowed(name)) continue;
                if (!file.toRealPath().startsWith(root)) throw new IOException("File fuori dalla cartella armeria");
                if (!names.add(name.toLowerCase(Locale.ROOT))) throw new IOException("Nomi file duplicati: " + name);
                ZipEntry entry = new ZipEntry(name);
                entry.setTime(0);
                zip.putNextEntry(entry);
                try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        if (total > MAX_BYTES) throw new IOException("La armeria supera 32 MiB");
                        zip.write(buffer, 0, count);
                    }
                }
                zip.closeEntry();
                if (bytes.size() > MAX_BYTES) throw new IOException("Archivio troppo grande");
            }
        }
        if (!names.contains("index.html") || !Files.isRegularFile(root.resolve("index.html")))
            throw new IOException("index.html mancante sul server");
        if (bytes.size() > MAX_BYTES) throw new IOException("Archivio troppo grande");
        return bytes.toByteArray();
    }

    static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    static Path unpack(byte[] bytes, Path cacheRoot) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("Archivio troppo grande");
        Files.createDirectories(cacheRoot);
        Path destination = Files.createTempDirectory(cacheRoot, "page-");
        boolean success = false;
        try {
            int files = 0;
            long total = 0;
            Set<String> names = new HashSet<>();
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                ZipEntry entry;
                byte[] buffer = new byte[8192];
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (++files > MAX_FILES || entry.isDirectory() || !allowed(name)
                            || !names.add(name.toLowerCase(Locale.ROOT))) throw new IOException("Archivio non valido");
                    Path target = destination.resolve(name).normalize();
                    if (!target.startsWith(destination)) throw new IOException("Percorso non valido");
                    Files.createDirectories(target.getParent());
                    try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                        int n;
                        while ((n = zip.read(buffer)) != -1) {
                            total += n;
                            if (total > MAX_BYTES) throw new IOException("File decompressi oltre 32 MiB");
                            output.write(buffer, 0, n);
                        }
                    }
                }
            }
            if (!Files.isRegularFile(destination.resolve("index.html"))) throw new IOException("index.html mancante");
            success = true;
            return destination.resolve("index.html");
        } finally {
            if (!success) deleteTree(destination);
        }
    }

    static void deleteTree(Path root) {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException ignored) {}
    }
}
