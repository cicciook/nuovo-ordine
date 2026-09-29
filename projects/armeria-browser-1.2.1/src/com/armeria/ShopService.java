package com.armeria;

import com.google.gson.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Server-authoritative shop. All entrypoints execute on the server thread. */
final class ShopService {
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    record Captured(String nbt, String name, String itemId, int count, String category, int maxStack) {
        Captured(String nbt, String name, String itemId, int count) { this(nbt, name, itemId, count, "", 64); }
    }
    interface Port {
        String playerId() throws Exception;
        boolean isOp() throws Exception;
        Captured capture() throws Exception;
        String economyName() throws Exception;
        BigDecimal balance() throws Exception;
        String format(BigDecimal value) throws Exception;
        boolean withdraw(BigDecimal value) throws Exception;
        boolean deposit(BigDecimal value) throws Exception;
        int[] emptySlots(int needed) throws Exception;
        int maxStack(String nbt) throws Exception;
        boolean slotEmpty(int slot) throws Exception;
        void validateItem(String nbt) throws Exception;
        void deliver(String nbt, int[] slots, int[] counts) throws Exception;
    }
    static final class Offer {
        String id, name, description, image, category, price, itemId, nbt;
        boolean enabled = true;
        int quantity = 1;
    }
    static final class Catalog {
        int schema = 1;
        long revision = 1;
        String title = "Armeria";
        List<Offer> offers = new ArrayList<>();
    }
    private final Path file, transactions;
    private Catalog catalog;
    ShopService(Path config) throws Exception {
        Files.createDirectories(config);
        file = config.resolve("catalogo.json");
        transactions = config.resolve("transazioni");
        Files.createDirectories(transactions);
        if (Files.exists(file)) {
            if (Files.size(file) > 20 * 1024 * 1024) throw new IllegalStateException("Catalogo troppo grande");
            catalog = GSON.fromJson(Files.readString(file), Catalog.class);
            if (catalog == null || catalog.schema != 1 || catalog.offers == null || catalog.offers.size() > 128)
                throw new IllegalStateException("Catalogo non valido: controlla config/armeria/catalogo.json");
        } else { catalog = new Catalog(); atomic(file, GSON.toJson(catalog)); }
    }
    static void atomic(Path path, String data) throws Exception {
        Path temp = Files.createTempFile(path.getParent(), ".write-", ".tmp");
        try {
            Files.writeString(temp, data, StandardCharsets.UTF_8);
            try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    static BigDecimal price(String text) {
        if (text == null || !text.matches("[0-9]{1,10}(\\.[0-9]{1,2})?"))
            throw new IllegalArgumentException("Inserisci un prezzo valido, con massimo due decimali.");
        BigDecimal value = new BigDecimal(text);
        if (value.signum() <= 0 || value.compareTo(new BigDecimal("1000000000")) > 0)
            throw new IllegalArgumentException("Il prezzo deve essere tra 0,01 e 1.000.000.000.");
        return value.setScale(2);
    }
    static int quantity(JsonObject data) {
        if (!data.has("quantity") || !data.get("quantity").isJsonPrimitive())
            throw new IllegalArgumentException("Inserisci la quantita del lotto.");
        String text = data.get("quantity").getAsString();
        if (!text.matches("[0-9]{1,4}")) throw new IllegalArgumentException("Quantita: usa un intero da 1 a 2304.");
        int value = Integer.parseInt(text);
        if (value < 1 || value > 2304) throw new IllegalArgumentException("Quantita: usa un intero da 1 a 2304.");
        return value;
    }
    static int[] split(int quantity, int stackLimit) {
        if (quantity < 1 || quantity > 2304 || stackLimit < 1)
            throw new IllegalArgumentException("Quantita o limite stack non valido.");
        int size = Math.min(64, stackLimit);
        int needed = (quantity + size - 1) / size;
        if (needed > 36) throw new IllegalArgumentException("Il lotto richiede piu di 36 slot. Chiedi a un OP di ridurre la quantita.");
        int[] counts = new int[needed];
        for (int i = 0; i < needed; i++) counts[i] = Math.min(size, quantity - i * size);
        return counts;
    }
    static String text(JsonObject json, String key, int max) {
        if (!json.has(key) || !json.get(key).isJsonPrimitive() || !json.get(key).getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Campo mancante: " + key);
        String s = json.get(key).getAsString().strip();
        if (s.length() > max || s.indexOf('\0') >= 0) throw new IllegalArgumentException("Campo troppo lungo: " + key);
        return s;
    }
    private static void op(Port port) throws Exception {
        if (!port.isOp()) throw new IllegalArgumentException("Solo i giocatori OP possono modificare il catalogo.");
    }
    private void revision(JsonObject data) {
        if (!data.has("revision") || data.get("revision").getAsLong() != catalog.revision)
            throw new IllegalArgumentException("Il catalogo e cambiato. Aggiorna la pagina e riprova.");
    }
    private Offer offer(String id) {
        return catalog.offers.stream().filter(o -> o.id.equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Articolo non disponibile."));
    }
    synchronized JsonObject handle(JsonObject data, Port port) {
        JsonObject response = new JsonObject();
        try {
            String action = text(data, "action", 32);
            String message = "";
            switch (action) {
                case "state" -> { }
                case "capture" -> {
                    op(port);
                    Captured item = port.capture();
                    JsonObject held = new JsonObject(); held.addProperty("name", item.name()); held.addProperty("itemId", item.itemId());
                    held.addProperty("count", item.count()); held.addProperty("category", item.category());
                    held.addProperty("maxQuantity", Math.min(2304, item.maxStack() * 36));
                    response.add("held", held);
                }
                case "addons" -> { op(port); response.add("addons", AddonCatalog.listing()); }
                case "save" -> { save(data, port); message = "Articolo salvato nel catalogo."; }
                case "delete" -> {
                    op(port); revision(data);
                    String id = text(data, "id", 40); offer(id);
                    Catalog next = GSON.fromJson(GSON.toJson(catalog), Catalog.class);
                    next.offers.removeIf(o -> o.id.equals(id)); commit(next);
                    message = "Articolo rimosso dal catalogo.";
                }
                case "settings" -> {
                    op(port); revision(data);
                    Catalog next = GSON.fromJson(GSON.toJson(catalog), Catalog.class);
                    next.title = text(data, "title", 64);
                    if (next.title.isEmpty()) throw new IllegalArgumentException("Inserisci il nome della armeria.");
                    commit(next); message = "Nome della armeria aggiornato.";
                }
                case "buy" -> message = buy(data, port);
                default -> throw new IllegalArgumentException("Operazione non riconosciuta.");
            }
            response.addProperty("ok", true); response.addProperty("message", message);
        } catch (Exception e) {
            response.addProperty("ok", false);
            Throwable cause = e;
            while (cause instanceof java.lang.reflect.InvocationTargetException && cause.getCause() != null) cause = cause.getCause();
            response.addProperty("message", cause.getMessage() == null ? "Operazione non riuscita." : cause.getMessage());
            if (!(cause instanceof IllegalArgumentException)) System.getLogger("Armeria").log(System.Logger.Level.ERROR, "Shop", cause);
        }
        try { response.add("state", state(port)); }
        catch (Exception e) { response.addProperty("ok", false); response.addProperty("message", "Impossibile leggere il catalogo: " + e.getMessage()); }
        return response;
    }
    private void commit(Catalog next) throws Exception {
        next.revision = catalog.revision + 1;
        String serialized = GSON.toJson(next);
        if (serialized.getBytes(StandardCharsets.UTF_8).length > 20 * 1024 * 1024)
            throw new IllegalArgumentException("Il catalogo supera il limite di 20 MiB.");
        atomic(file, serialized); catalog = next;
    }
    private void save(JsonObject data, Port port) throws Exception {
        op(port); revision(data);
        String id = text(data, "id", 40);
        Offer existing = id.isEmpty() ? null : offer(id);
        if (existing == null && catalog.offers.size() >= 128) throw new IllegalArgumentException("Massimo 128 offerte.");
        Offer value = new Offer();
        value.id = existing == null ? UUID.randomUUID().toString() : existing.id;
        value.name = text(data, "name", 80);
        if (value.name.isEmpty()) throw new IllegalArgumentException("Inserisci il nome del articolo.");
        value.description = text(data, "description", 600);
        value.category = text(data, "category", 40);
        value.image = text(data, "image", 400);
        if (!value.image.isEmpty() && !(value.image.startsWith("https://") || SiteArchive.allowed(value.image)))
            throw new IllegalArgumentException("Immagine: usa un percorso relativo o un URL https://.");
        value.price = price(text(data, "price", 32)).toPlainString();
        value.quantity = quantity(data);
        value.enabled = data.get("enabled").getAsBoolean();
        String addonId = data.has("addonId") ? text(data, "addonId", 120) : "";
        boolean useHeld = addonId.isEmpty() && (existing == null || (data.has("useHeld") && data.get("useHeld").getAsBoolean()));
        if (!addonId.isEmpty()) {
            AddonCatalog.Entry addon = AddonCatalog.get(addonId);
            value.nbt = addon.stackNbt(); value.itemId = addon.baseItem();
        } else if (useHeld) {
            Captured capture = port.capture();
            if (capture.nbt().length() > 131072) throw new IllegalArgumentException("L'articolo contiene troppi dati.");
            value.nbt = capture.nbt(); value.itemId = capture.itemId();
        } else { value.nbt = existing.nbt; value.itemId = existing.itemId; }
        port.validateItem(value.nbt); split(value.quantity, port.maxStack(value.nbt));
        Catalog next = GSON.fromJson(GSON.toJson(catalog), Catalog.class);
        next.offers.removeIf(o -> o.id.equals(value.id)); next.offers.add(value);
        commit(next);
    }
    private String buy(JsonObject data, Port port) throws Exception {
        String operation = UUID.fromString(text(data, "operation", 40)).toString();
        String player = UUID.fromString(port.playerId()).toString();
        Path receipt = transactions.resolve(player + "_" + operation + ".json");
        if (Files.exists(receipt)) {
            JsonObject old = JsonParser.parseString(Files.readString(receipt)).getAsJsonObject();
            if (old.get("status").getAsString().equals("COMPLETED")) return "Acquisto gia completato: nessun nuovo addebito.";
            throw new IllegalArgumentException("Questa transazione e gia stata elaborata o richiede verifica OP. Non viene ripetuta.");
        }
        try (DirectoryStream<Path> previous = Files.newDirectoryStream(transactions, player + "_*.json")) {
            for (Path path : previous) {
                String status = JsonParser.parseString(Files.readString(path)).getAsJsonObject().get("status").getAsString();
                if (!Set.of("COMPLETED", "REFUNDED", "DECLINED", "RESOLVED").contains(status))
                    throw new IllegalArgumentException("Hai una transazione da verificare con un OP. Gli acquisti sono sospesi per evitare doppi addebiti.");
            }
        }
        revision(data);
        Offer product = offer(text(data, "id", 40));
        if (!product.enabled) throw new IllegalArgumentException("Questo articolo non e in vendita.");
        BigDecimal cost = price(product.price);
        port.economyName(); port.validateItem(product.nbt);
        int[] counts = split(product.quantity, port.maxStack(product.nbt));
        int[] slots = port.emptySlots(counts.length);
        if (slots.length != counts.length) throw new IllegalArgumentException("Libera almeno " + counts.length + " slot nell'inventario per questo lotto.");
        Set<Integer> uniqueSlots = new HashSet<>();
        for (int slot : slots) if (slot < 0 || slot >= 36 || !uniqueSlots.add(slot) || !port.slotEmpty(slot))
            throw new IllegalArgumentException("Inventario cambiato: aggiorna e riprova.");
        if (port.balance().compareTo(cost) < 0) throw new IllegalArgumentException("Saldo insufficiente per questo articolo.");
        JsonObject tx = new JsonObject();
        tx.addProperty("operation", operation); tx.addProperty("player", player);
        tx.addProperty("offer", product.id); tx.addProperty("vehicle", product.name);
        tx.addProperty("price", product.price); tx.addProperty("created", java.time.Instant.now().toString());
        tx.add("slots", GSON.toJsonTree(slots)); tx.add("counts", GSON.toJsonTree(counts)); tx.addProperty("quantity", product.quantity); tx.addProperty("nbt", product.nbt); tx.addProperty("status", "PREPARED"); atomic(receipt, GSON.toJson(tx));
        boolean paid;
        try { paid = port.withdraw(cost); }
        catch (Exception uncertain) { mark(receipt, tx, "REVIEW_PAYMENT"); throw new IllegalStateException("Risposta del pagamento incerta: chiedi a un OP di verificare la transazione.", uncertain); }
        if (!paid) { mark(receipt, tx, "DECLINED"); throw new IllegalArgumentException("TNE ha rifiutato il pagamento. Nessun articolo consegnato."); }
        try {
            mark(receipt, tx, "PAID");
            port.deliver(product.nbt, slots, counts);
        } catch (Exception delivery) {
            boolean empty = false;
            try { empty = true; for (int slot : slots) if (!port.slotEmpty(slot)) empty = false; } catch (Exception ignored) { empty = false; }
            if (empty) {
                try {
                    if (port.deposit(cost)) { mark(receipt, tx, "REFUNDED"); throw new Refunded(); }
                } catch (Refunded done) { throw new IllegalArgumentException("Consegna non riuscita: pagamento rimborsato."); }
                catch (Exception refund) { delivery.addSuppressed(refund); }
            }
            mark(receipt, tx, "REVIEW_DELIVERY");
            throw new IllegalStateException("Consegna da verificare con un OP. Conserva il riferimento " + operation, delivery);
        }
        try { mark(receipt, tx, "COMPLETED"); }
        catch (Exception journal) { System.getLogger("Armeria").log(System.Logger.Level.ERROR, "Articolo consegnato, ricevuta da verificare: " + receipt, journal); }
        return "Acquisto completato! " + product.quantity + " x " + product.name + " nel tuo inventario.";
    }
    private static final class Refunded extends Exception {}
    private static void mark(Path path, JsonObject tx, String status) throws Exception {
        tx.addProperty("status", status); atomic(path, GSON.toJson(tx));
    }
    private JsonObject state(Port port) throws Exception {
        JsonObject state = new JsonObject();
        boolean operator = port.isOp();
        state.addProperty("op", operator); state.addProperty("revision", catalog.revision); state.addProperty("title", catalog.title);
        boolean economy = false;
        try {
            String name = port.economyName(); BigDecimal balance = port.balance();
            state.addProperty("economy", name); state.addProperty("balance", balance.toPlainString());
            state.addProperty("balanceFormatted", port.format(balance)); economy = true;
        } catch (Exception e) { state.addProperty("economyError", "TNE/Vault non disponibile: " + e.getMessage()); }
        state.addProperty("economyAvailable", economy);
        JsonArray offers = new JsonArray();
        for (Offer offer : catalog.offers) {
            if (!offer.enabled && !operator) continue;
            JsonObject row = new JsonObject();
            row.addProperty("id", offer.id); row.addProperty("name", offer.name);
            row.addProperty("description", offer.description); row.addProperty("category", offer.category);
            row.addProperty("image", offer.image); row.addProperty("price", offer.price);
            row.addProperty("quantity", offer.quantity);
            row.addProperty("enabled", offer.enabled); row.addProperty("itemId", offer.itemId);
            String formatted = offer.price;
            if (economy) try { formatted = port.format(new BigDecimal(offer.price)); } catch (Exception ignored) {}
            row.addProperty("priceFormatted", formatted); offers.add(row);
        }
        state.add("offers", offers); return state;
    }
}
