package com.armeria;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** Fixed, server-owned list of TACZ definitions present in the selected addon versions. */
final class AddonCatalog {
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Map<String, Entry> ENTRIES = load();
    record Entry(String id, String pack, String kind, String category, String name, String mode) {
        String stackNbt() {
            if (!ID.matcher(id).matches()) throw new IllegalArgumentException("ID addon non valido.");
            return switch (kind) {
                case "guns" -> "{id:\"tacz:modern_kinetic_gun\",Count:1b,tag:{GunId:\"" + id
                        + "\",GunFireMode:\"" + (mode != null && mode.matches("[A-Z_]+") ? mode : "SEMI")
                        + "\",GunCurrentAmmoCount:0}}";
                case "attachments" -> "{id:\"tacz:attachment\",Count:1b,tag:{AttachmentId:\"" + id + "\"}}";
                case "ammo" -> "{id:\"tacz:ammo\",Count:1b,tag:{AmmoId:\"" + id + "\"}}";
                default -> throw new IllegalArgumentException("Categoria addon non valida.");
            };
        }
        String baseItem() {
            return switch (kind) {
                case "guns" -> "tacz:modern_kinetic_gun";
                case "attachments" -> "tacz:attachment";
                case "ammo" -> "tacz:ammo";
                default -> throw new IllegalArgumentException("Categoria addon non valida.");
            };
        }
    }

    private static Map<String, Entry> load() {
        try (InputStream stream = AddonCatalog.class.getResourceAsStream("/armeria_addons.json")) {
            if (stream == null) throw new IOException("Catalogo addon mancante");
            JsonArray array = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonArray();
            Map<String, Entry> entries = new LinkedHashMap<>();
            for (JsonElement value : array) {
                JsonObject row = value.getAsJsonObject();
                String id = row.get("id").getAsString();
                if (!ID.matcher(id).matches()) throw new IOException("ID addon non valido: " + id);
                Entry entry = new Entry(id, row.get("pack").getAsString(), row.get("kind").getAsString(),
                        row.get("category").getAsString(), row.get("name").getAsString(),
                        row.has("mode") ? row.get("mode").getAsString() : "SEMI");
                entry.stackNbt();
                String key = entry.kind() + "|" + id;
                if (entries.put(key, entry) != null) throw new IOException("ID addon duplicato: " + key);
            }
            return Collections.unmodifiableMap(entries);
        } catch (IOException e) { throw new IllegalStateException("Impossibile caricare gli addon TACZ", e); }
    }

    static Entry get(String key) {
        Entry entry = ENTRIES.get(key);
        if (entry == null) throw new IllegalArgumentException("Oggetto addon non presente nel catalogo del server.");
        return entry;
    }

    static JsonArray listing() {
        JsonArray result = new JsonArray();
        for (Map.Entry<String, Entry> selected : ENTRIES.entrySet()) {
            Entry entry = selected.getValue();
            JsonObject row = new JsonObject();
            row.addProperty("key", selected.getKey());
            row.addProperty("id", entry.id()); row.addProperty("pack", entry.pack());
            row.addProperty("category", entry.category()); row.addProperty("name", entry.name());
            result.add(row);
        }
        return result;
    }
}
