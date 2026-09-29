package com.nuovoordine.quests;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
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
        Path root = gameDirectory.resolve("noquests");
        if (Files.isSymbolicLink(root)) throw new IOException("La cartella noquests non puo essere un collegamento");
        Files.createDirectories(root);
        migrateLegacyConfig(gameDirectory);
        Path index = root.resolve("index.html");
        boolean installDefault = !Files.exists(index, LinkOption.NOFOLLOW_LINKS) || legacyDefaultPage(index);
        if (installDefault) {
            try (InputStream in = SiteArchive.class.getResourceAsStream("/noquests_default/index.html")) {
                if (in == null) throw new IOException("Pagina predefinita mancante");
                Files.copy(in, index, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (!Files.isRegularFile(index, LinkOption.NOFOLLOW_LINKS)) throw new IOException("index.html non valido");
        Path shop = root.resolve("shop.html");
        if (!Files.exists(shop, LinkOption.NOFOLLOW_LINKS)) {
            try (InputStream in = SiteArchive.class.getResourceAsStream("/noquests_default/shop.html")) {
                if (in != null) Files.copy(in, shop);
            }
        }
        return root;
    }


    private static boolean legacyDefaultPage(Path index) {
        try {
            String page = Files.readString(index, StandardCharsets.UTF_8);
            return page.contains("Proteggi il territorio. Completa gli incarichi. Ritira la tua ricompensa.")
                    && page.contains("q.event==='kill:hostile'") && page.contains("active_seconds");
        } catch (IOException e) { return false; }
    }

    /** Upgrade only the untouched 1.0 stock quest set; custom configs are preserved. */
    private static void migrateLegacyConfig(Path gameDirectory) throws IOException {
        Path config = gameDirectory.resolve("config/noquests/config.json");
        if (!Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS)) return;
        String text = Files.readString(config, StandardCharsets.UTF_8);
        String compact = text.replaceAll("\\s+", "");
        boolean stock = compact.contains("\"id\":\"zombies\"")
                && compact.contains("\"target\":40") && compact.contains("\"target\":600")
                && compact.contains("\"id\":\"hostiles\"") && compact.contains("\"target\":80")
                && compact.contains("\"target\":1500") && compact.contains("\"id\":\"patrol\"")
                && compact.contains("\"target\":1800") && compact.contains("\"target\":18000");
        if (!stock) return;
        String daily = "[\n"+
                "    {\n      \"id\": \"scavenger\",\n      \"title\": \"Scavenger urbano\",\n      \"event\": \"loot:lootr_daily\",\n      \"target\": 10\n    },\n"+
                "    {\n      \"id\": \"bounty\",\n      \"title\": \"Taglie attive\",\n      \"event\": \"pvp:kill\",\n      \"target\": 2\n    },\n"+
                "    {\n      \"id\": \"firefight\",\n      \"title\": \"Scontro a fuoco\",\n      \"event\": \"pvp:damage\",\n      \"target\": 60\n    }\n  ]";
        String weekly = "[\n"+
                "    {\n      \"id\": \"redzone\",\n      \"title\": \"Zona rossa\",\n      \"event\": \"loot:lootr_weekly\",\n      \"target\": 75\n    },\n"+
                "    {\n      \"id\": \"hunter\",\n      \"title\": \"Cacciatore di taglie\",\n      \"event\": \"pvp:kill\",\n      \"target\": 12\n    },\n"+
                "    {\n      \"id\": \"attrition\",\n      \"title\": \"Guerra d’attrito\",\n      \"event\": \"pvp:damage\",\n      \"target\": 500\n    }\n  ]";
        String migrated = replaceJsonArray(text, "daily", daily);
        migrated = replaceJsonArray(migrated, "weekly", weekly);
        if (!migrated.equals(text)) {
            Path backup = config.resolveSibling("config.json.pre-1.1-backup");
            if (!Files.exists(backup)) Files.copy(config, backup);
            Path tmp = config.resolveSibling(config.getFileName()+".tmp");
            Files.writeString(tmp, migrated, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try { Files.move(tmp, config, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(tmp, config, StandardCopyOption.REPLACE_EXISTING); }
        }
    }

    private static String replaceJsonArray(String json, String field, String replacement) throws IOException {
        int key=json.indexOf("\""+field+"\""); if(key<0) throw new IOException("Campo "+field+" mancante in config.json");
        int start=json.indexOf('[',key); if(start<0) throw new IOException("Array "+field+" non valido");
        int depth=0,end=-1;boolean string=false,escape=false;
        for(int i=start;i<json.length();i++){
            char c=json.charAt(i);
            if(string){if(escape)escape=false;else if(c=='\\')escape=true;else if(c=='\"')string=false;continue;}
            if(c=='\"'){string=true;continue;}if(c=='[')depth++;else if(c==']'&&--depth==0){end=i+1;break;}
        }
        if(end<0)throw new IOException("Array "+field+" non chiuso");return json.substring(0,start)+replacement+json.substring(end);
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
        if (files.size() > MAX_FILES) throw new IOException("Troppi file nella noquests (massimo 2048)");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Set<String> names = new HashSet<>();
        long total = 0;
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            byte[] buffer = new byte[8192];
            for (Path file : files) {
                String name = root.relativize(file).toString().replace(File.separatorChar, '/');
                if (!allowed(name)) continue;
                if (!file.toRealPath().startsWith(root)) throw new IOException("File fuori dalla cartella noquests");
                if (!names.add(name.toLowerCase(Locale.ROOT))) throw new IOException("Nomi file duplicati: " + name);
                ZipEntry entry = new ZipEntry(name);
                entry.setTime(0);
                zip.putNextEntry(entry);
                try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        if (total > MAX_BYTES) throw new IOException("La noquests supera 32 MiB");
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
