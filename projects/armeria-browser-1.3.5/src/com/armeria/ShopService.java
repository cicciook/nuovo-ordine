package com.armeria;

import com.google.gson.*;
import java.math.BigDecimal;
import java.lang.reflect.*;
import java.security.MessageDigest;
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
        boolean automatic = false;
        String autoKey = "";
    }
    static final class Catalog {
        int schema = 1;
        long revision = 1;
        String title = "Armeria";
        List<Offer> offers = new ArrayList<>();
        Set<String> autoExcluded = new LinkedHashSet<>();
    }
    record AutoPlan(int quantity, String price) {}
    private static final int MAX_OFFERS = 2048;
    private static final long AUTO_SYNC_NS = 10_000_000_000L;
    private final Path file, transactions;
    private Catalog catalog;
    private long lastAutoSync;
    ShopService(Path config) throws Exception {
        Files.createDirectories(config);
        file = config.resolve("catalogo.json");
        transactions = config.resolve("transazioni");
        Files.createDirectories(transactions);
        if (Files.exists(file)) {
            if (Files.size(file) > 20 * 1024 * 1024) throw new IllegalStateException("Catalogo troppo grande");
            catalog = GSON.fromJson(Files.readString(file), Catalog.class);
            if (catalog == null || catalog.schema != 1 || catalog.offers == null || catalog.offers.size() > MAX_OFFERS)
                throw new IllegalStateException("Catalogo non valido: controlla config/armeria/catalogo.json");
            if (catalog.autoExcluded == null) catalog.autoExcluded = new LinkedHashSet<>();
            for (Offer o : catalog.offers) {
                if (o.autoKey == null) o.autoKey = "";
                if (o.category == null) o.category = "";
                if (o.description == null) o.description = "";
                if (o.image == null) o.image = "";
            }
        } else { catalog = new Catalog(); atomic(file, GSON.toJson(catalog)); }
        syncAutomaticAmmo();
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
    private static boolean allowedCategory(String category) {
        if (category == null) return false;
        String c = category.toLowerCase(Locale.ROOT);
        return c.contains("munizioni") || c.contains("ammo box") || c.contains("accessori");
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
            if ("state".equals(action) && (catalog.offers.isEmpty() || System.nanoTime() - lastAutoSync >= AUTO_SYNC_NS)) syncAutomaticAmmo();
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
                case "save" -> { save(data, port); message = "Articolo salvato nel catalogo."; }
                case "delete" -> {
                    op(port); revision(data);
                    String id = text(data, "id", 40); Offer removing = offer(id);
                    Catalog next = GSON.fromJson(GSON.toJson(catalog), Catalog.class);
                    if (next.autoExcluded == null) next.autoExcluded = new LinkedHashSet<>();
                    if (removing.automatic && removing.autoKey != null && !removing.autoKey.isEmpty())
                        next.autoExcluded.add(removing.autoKey);
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
        if (existing == null && catalog.offers.size() >= MAX_OFFERS) throw new IllegalArgumentException("Massimo " + MAX_OFFERS + " offerte.");
        Offer value = new Offer();
        value.id = existing == null ? UUID.randomUUID().toString() : existing.id;
        value.name = text(data, "name", 80);
        if (value.name.isEmpty()) throw new IllegalArgumentException("Inserisci il nome del articolo.");
        value.description = text(data, "description", 600);
        value.category = text(data, "category", 40);
        if (!allowedCategory(value.category))
            throw new IllegalArgumentException("Categoria non ammessa: l'armeria vende solo munizioni, ammo box e accessori.");
        // Armeria 1.3.5 uses compact text-only cards: images are intentionally disabled.
        value.image = "";
        value.price = price(text(data, "price", 32)).toPlainString();
        value.quantity = quantity(data);
        value.enabled = data.get("enabled").getAsBoolean();
        if (existing != null) {
            value.automatic = existing.automatic;
            value.autoKey = existing.autoKey == null ? "" : existing.autoKey;
        }
        boolean useHeld = existing == null || (data.has("useHeld") && data.get("useHeld").getAsBoolean());
        if (useHeld) {
            Captured capture = port.capture();
            if (capture.nbt().length() > 131072) throw new IllegalArgumentException("L'articolo contiene troppi dati.");
            value.nbt = capture.nbt(); value.itemId = capture.itemId();
            if (capture.category() != null && !capture.category().isBlank()) value.category = capture.category();
        } else { value.nbt = existing.nbt; value.itemId = existing.itemId; }
        port.validateItem(value.nbt); split(value.quantity, port.maxStack(value.nbt));
        Catalog next = GSON.fromJson(GSON.toJson(catalog), Catalog.class);
        next.offers.removeIf(o -> o.id.equals(value.id)); next.offers.add(value);
        commit(next);
    }

    private static Method namedMethod(Class<?> type, String obfuscated, String named, Class<?>... parameters) throws Exception {
        try { return type.getMethod(obfuscated, parameters); }
        catch (NoSuchMethodException ignored) { return type.getMethod(named, parameters); }
    }
    private static Object stackItem(Object stack) throws Exception {
        Class<?> stackClass = Class.forName("net.minecraft.world.item.ItemStack");
        return namedMethod(stackClass, "m_41720_", "getItem").invoke(stack);
    }
    private static Captured captureStack(Object source) throws Exception {
        Class<?> stackClass = Class.forName("net.minecraft.world.item.ItemStack");
        Object copy = namedMethod(stackClass, "m_41777_", "copy").invoke(source);
        namedMethod(stackClass, "m_41764_", "setCount", int.class).invoke(copy, 1);
        Class<?> compound = Class.forName("net.minecraft.nbt.CompoundTag");
        Object tag = namedMethod(stackClass, "m_41739_", "save", compound).invoke(copy, compound.getConstructor().newInstance());
        Object component = namedMethod(stackClass, "m_41786_", "getHoverName").invoke(copy);
        String displayName = (String) Class.forName("net.minecraft.network.chat.Component").getMethod("getString").invoke(component);
        String itemId = (String) namedMethod(compound, "m_128461_", "getString", String.class).invoke(tag, "id");
        Object item = stackItem(copy);
        int maxStack = Math.min(64, (int) namedMethod(stackClass, "m_41741_", "getMaxStackSize").invoke(copy));
        return new Captured(tag.toString(), displayName, itemId, 1, AmmoSupport.category(item), maxStack);
    }
    private static Object stackForItem(Object item) throws Exception {
        Class<?> stackClass = Class.forName("net.minecraft.world.item.ItemStack");
        for (Constructor<?> constructor : stackClass.getConstructors()) {
            Class<?>[] p = constructor.getParameterTypes();
            if (p.length == 1 && p[0].isInstance(item)) return constructor.newInstance(item);
        }
        throw new NoSuchMethodException("Costruttore ItemStack(ItemLike) non trovato");
    }
    private static String tacZAmmoId(Object stack) {
        try {
            Object item = stackItem(stack);
            Class<?> stackClass = Class.forName("net.minecraft.world.item.ItemStack");
            Object id = item.getClass().getMethod("getAmmoId", stackClass).invoke(item, stack);
            return id == null ? "" : id.toString();
        } catch (Exception ignored) { return ""; }
    }
    private static String prettyAmmoName(String ammoId) {
        if (ammoId == null || ammoId.isBlank()) return "Munizioni TACZ";
        int colon = ammoId.indexOf(':');
        String namespace = colon >= 0 ? ammoId.substring(0, colon) : "tacz";
        String path = colon >= 0 ? ammoId.substring(colon + 1) : ammoId;
        String caliber = switch (path.toLowerCase(Locale.ROOT)) {
            case "556x45" -> "5.56x45 mm";
            case "545x39" -> "5.45x39 mm";
            case "762x39" -> "7.62x39 mm";
            case "762x54" -> "7.62x54 mm";
            case "792x57" -> "7.92x57 mm";
            case "57x28" -> "5.7x28 mm";
            case "46x30" -> "4.6x30 mm";
            case "58x42" -> "5.8x42 mm";
            case "68x51fury" -> "6.8x51 Fury";
            case "45acp" -> ".45 ACP";
            case "357mag" -> ".357 Magnum";
            case "50ae" -> ".50 AE";
            case "50bmg" -> ".50 BMG";
            case "308" -> ".308";
            case "338" -> ".338";
            case "30_06" -> ".30-06";
            case "12g" -> "12 Gauge";
            case "22wmr" -> ".22 WMR";
            case "rpg_rocket" -> "RPG Rocket";
            default -> null;
        };
        if (caliber == null) {
            StringBuilder b = new StringBuilder();
            for (String word : path.replace('-', '_').split("_")) {
                if (word.isBlank()) continue;
                if (!b.isEmpty()) b.append(' ');
                b.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
            caliber = b.isEmpty() ? path : b.toString();
        }
        if ("tacz".equalsIgnoreCase(namespace)) return caliber;
        String ns = namespace.substring(0, 1).toUpperCase(Locale.ROOT) + namespace.substring(1);
        return ns + " · " + caliber;
    }
    private static boolean placeholderName(String name) {
        if (name == null || name.isBlank()) return true;
        String v = name.strip().toLowerCase(Locale.ROOT);
        return v.startsWith("item.") || v.startsWith("block.")
                || v.equals("item.tacz.attachment") || v.equals("item.tacz.ammo");
    }

    private static String prettyIdentifier(String resource) {
        if (resource == null || resource.isBlank()) return "Accessorio";
        String value = resource.strip();
        String namespace = "";
        int colon = value.indexOf(':');
        if (colon >= 0) {
            namespace = value.substring(0, colon);
            value = value.substring(colon + 1);
        }
        value = value.replace('/', ' ').replace('_', ' ').replace('-', ' ').replace('.', ' ');
        StringBuilder out = new StringBuilder();
        for (String word : value.split("\\s+")) {
            if (word.isBlank()) continue;
            if (!out.isEmpty()) out.append(' ');
            String lower = word.toLowerCase(Locale.ROOT);
            String pretty = switch (lower) {
                case "ar" -> "AR";
                case "ak" -> "AK";
                case "smg" -> "SMG";
                case "lmg" -> "LMG";
                case "dmr" -> "DMR";
                case "acog" -> "ACOG";
                case "holo" -> "Holo";
                case "scope" -> "Scope";
                case "sight" -> "Sight";
                case "suppressor", "silencer" -> "Soppressore";
                case "grip" -> "Grip";
                case "stock" -> "Calcio";
                case "laser" -> "Laser";
                case "flashlight" -> "Torcia";
                default -> Character.toUpperCase(word.charAt(0)) + word.substring(1);
            };
            out.append(pretty);
        }
        String title = out.isEmpty() ? "Accessorio" : out.toString();
        if (!namespace.isBlank() && !namespace.equalsIgnoreCase("tacz"))
            return namespace.substring(0, 1).toUpperCase(Locale.ROOT) + namespace.substring(1) + " · " + title;
        return title;
    }

    private static String readableName(String current, String sourceId) {
        return placeholderName(current) ? prettyIdentifier(sourceId) : current;
    }

    private static String caliberLabel(String text) {
        if (text == null) return "";
        String v = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (v.contains("50bmg") || v.contains("127x99")) return ".50 BMG";
        if (v.contains("338lapua") || v.contains("338")) return ".338";
        if (v.contains("3006") || v.contains("30cal06")) return ".30-06";
        if (v.contains("762x54")) return "7.62x54R";
        if (v.contains("792x57")) return "7.92x57 mm";
        if (v.contains("762x51") || v.contains("308win") || v.contains("308")) return "7.62x51 / .308";
        if (v.contains("68x51")) return "6.8x51 mm";
        if (v.contains("556x45") || v.contains("556nato")) return "5.56x45 mm";
        if (v.contains("545x39")) return "5.45x39 mm";
        if (v.contains("762x39")) return "7.62x39 mm";
        if (v.contains("58x42")) return "5.8x42 mm";
        if (v.contains("57x28")) return "5.7x28 mm";
        if (v.contains("46x30")) return "4.6x30 mm";
        if (v.contains("45acp")) return ".45 ACP";
        if (v.contains("357mag") || v.contains("357magnum")) return ".357 Magnum";
        if (v.contains("50ae")) return ".50 AE";
        if (v.contains("22wmr")) return ".22 WMR";
        if (v.contains("9x19") || v.contains("9mm")) return "9 mm";
        if (v.contains("12gauge") || v.contains("12ga") || v.contains("12g")) return "12 Gauge";
        if (v.contains("40mm")) return "40 mm";
        if (v.contains("rpgrocket") || v.contains("rpg")) return "RPG";
        if (v.contains("mortar")) return "Mortaio";
        if (v.contains("missile")) return "Missili";
        return "";
    }

    private static String calibratedCategory(String category, String hint) {
        if (category == null) return "";
        String lower = category.toLowerCase(Locale.ROOT);
        if (!lower.contains("munizioni")) return category;
        String caliber = caliberLabel(hint);
        return caliber.isBlank() ? category : "Munizioni " + caliber;
    }

    private static boolean standardSixtyRoundAmmo(String text) {
        if (text == null) return false;
        String v = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (v.contains("dmr") || v.contains("marksman") || v.contains("sniper")
                || v.contains("762x51") || v.contains("308") || v.contains("762x54")
                || v.contains("792x57") || v.contains("338") || v.contains("3006")
                || v.contains("50bmg") || v.contains("127x99") || v.contains("68x51")
                || v.contains("12gauge") || v.contains("12ga") || v.contains("40mm")
                || v.contains("rocket") || v.contains("rpg") || v.contains("mortar")
                || v.contains("missile") || v.contains("shell")) return false;
        return v.contains("556x45") || v.contains("556nato") || v.contains("545x39")
                || v.contains("762x39") || v.contains("58x42")
                || v.contains("9x19") || v.contains("9mm") || v.contains("45acp")
                || v.contains("57x28") || v.contains("46x30")
                || v.contains("357mag") || v.contains("50ae") || v.contains("22wmr")
                || v.contains("rifleammo") || v.contains("handgunammo");
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 12; i++) out.append(String.format(Locale.ROOT, "%02x", digest[i]));
            return out.toString();
        } catch (Exception impossible) { return Integer.toHexString(value.hashCode()); }
    }
    private static void discovered(Map<String,Captured> found, String key, Captured capture) {
        if (capture == null || capture.nbt() == null || capture.nbt().isEmpty()) return;
        String id = capture.itemId() == null ? "" : capture.itemId().toLowerCase(Locale.ROOT);
        String rawName = capture.name() == null ? "" : capture.name();
        if (id.contains("creative") || rawName.toLowerCase(Locale.ROOT).contains("creative")) return;

        String source = capture.itemId();
        if (key.startsWith("tacz:attachment:")) source = key.substring("tacz:attachment:".length());
        else if (key.startsWith("tacz:")) source = key.substring("tacz:".length());

        String name = readableName(rawName, source);
        String category = calibratedCategory(capture.category(),
                name + " " + capture.itemId() + " " + key);
        found.putIfAbsent(key, new Captured(capture.nbt(), name, capture.itemId(),
                capture.count(), category, capture.maxStack()));
    }
    private static String taczDataId(Object stack, String methodName) {
        try {
            Object item = stackItem(stack);
            Class<?> stackClass = Class.forName("net.minecraft.world.item.ItemStack");
            Object id = item.getClass().getMethod(methodName, stackClass).invoke(item, stack);
            return id == null ? "" : id.toString();
        } catch (Throwable ignored) { return ""; }
    }

    private static void discoverTaczFamily(Map<String,Captured> found, String className,
            String dataMethod, String keyPrefix) {
        try {
            Class<?> type = Class.forName(className);
            for (Method fill : type.getMethods()) {
                if (!fill.getName().equals("fillItemCategory") || fill.getParameterCount() != 1
                        || !Modifier.isStatic(fill.getModifiers()) || !fill.getParameterTypes()[0].isEnum()) continue;
                for (Object tab : fill.getParameterTypes()[0].getEnumConstants()) {
                    Object stacks = fill.invoke(null, tab);
                    if (!(stacks instanceof Iterable<?> iterable)) continue;
                    for (Object stack : iterable) {
                        Captured capture = captureStack(stack);
                        String dataId = taczDataId(stack, dataMethod);
                        String key = keyPrefix + (dataId.isEmpty() ? hash(capture.nbt()) : dataId);
                        discovered(found, key, capture);
                    }
                }
                return;
            }
        } catch (ClassNotFoundException absent) {
            // TACZ is optional.
        } catch (Throwable problem) {
            System.getLogger("Armeria").log(System.Logger.Level.WARNING,
                    "Rilevamento automatico " + keyPrefix + " non riuscito", problem);
        }
    }

    private static Map<String,Captured> discoverAutomaticAmmo() {
        Map<String,Captured> found = new LinkedHashMap<>();

        // TACZ is data-driven. Enumerate the same generated stacks used by its
        // creative categories so gunpack weapons/attachments keep their real NBT.
        discoverTaczFamily(found, "com.tacz.guns.item.AttachmentItem",
                "getAttachmentId", "tacz:attachment:");

        try {
            Class<?> ammoItem = Class.forName("com.tacz.guns.item.AmmoItem");
            Object stacks = ammoItem.getMethod("fillItemCategory").invoke(null);
            if (stacks instanceof Iterable<?> iterable) {
                for (Object stack : iterable) {
                    Captured capture = captureStack(stack);
                    String ammoId = tacZAmmoId(stack);
                    if (!ammoId.isEmpty()) capture = new Captured(capture.nbt(), prettyAmmoName(ammoId),
                            capture.itemId(), 1, calibratedCategory(capture.category(), ammoId), capture.maxStack());
                    String key = "tacz:" + (ammoId.isEmpty() ? hash(capture.nbt()) : ammoId);
                    discovered(found, key, capture);
                }
            }
        } catch (ClassNotFoundException absent) {
            // TACZ is optional.
        } catch (Throwable problem) {
            System.getLogger("Armeria").log(System.Logger.Level.WARNING,
                    "Rilevamento automatico munizioni TACZ non riuscito", problem);
        }

        // Forge registry scan catches only physical ammunition/accessories accepted
        // by AmmoSupport. TACZ generic base items are skipped because their actual
        // attachment/ammo identity is enumerated above.
        try {
            Class<?> forgeRegistries = Class.forName("net.minecraftforge.registries.ForgeRegistries");
            Object items = forgeRegistries.getField("ITEMS").get(null);
            Object values = items.getClass().getMethod("getValues").invoke(items);
            if (values instanceof Iterable<?> iterable) {
                for (Object item : iterable) {
                    if (item == null || item.getClass().getName().startsWith("com.tacz.guns.")) continue;
                    String category;
                    try { category = AmmoSupport.category(item); }
                    catch (Throwable ignored) { continue; }
                    if (category == null || category.isBlank()) continue;
                    try {
                        Captured capture = captureStack(stackForItem(item));
                        discovered(found, "item:" + capture.itemId(), capture);
                    } catch (Throwable perItem) {
                        System.getLogger("Armeria").log(System.Logger.Level.DEBUG,
                                "Oggetto automatico ignorato: " + item.getClass().getName(), perItem);
                    }
                }
            }
        } catch (ClassNotFoundException absent) {
            // Forge registry is only present in game.
        } catch (Throwable problem) {
            System.getLogger("Armeria").log(System.Logger.Level.WARNING,
                    "Rilevamento automatico oggetti registrati non riuscito", problem);
        }
        return found;
    }
    static AutoPlan balancedPlan(String text, int maxStack) {
        String v = text == null ? "" : text.toLowerCase(Locale.ROOT).replace('-', '_');
        int q; int dollars;
        if (v.contains("accessori tacz") || v.contains("accessori superb")) {
            q = 1;
            if (v.contains("silen") || v.contains("suppress") || v.contains("silencer")) dollars = 1600;
            else if (v.contains("scope") || v.contains("optic") || v.contains("mirino")) dollars = 1200;
            else dollars = 850;
        }
        else if (v.contains("armi tacz") || v.contains("armi superb")) {
            q = 1;
            if (v.contains("50bmg") || v.contains("50_bmg") || v.contains("anti_materiel") || v.contains("heavy_sniper")) dollars = 14000;
            else if (v.contains("sniper") || v.contains("dmr") || v.contains("marksman")) dollars = 9000;
            else if (v.contains("shotgun")) dollars = 5200;
            else if (v.contains("pistol") || v.contains("handgun") || v.contains("revolver")) dollars = 2800;
            else if (v.contains("smg")) dollars = 4200;
            else dollars = 6500;
        }
        else if (v.contains("lesraisins tactical")
                && !(v.contains("ammo") || v.contains("bullet") || v.contains("shell") || v.contains("cartridge"))) {
            q = 1; dollars = 3200;
        }
        else if (v.contains("javelin")) { q = 1; dollars = 12000; }
        else if (v.contains("extra_large") && v.contains("missile")) { q = 1; dollars = 12000; }
        else if ((v.contains("anti_ground") || v.contains("anti_air")) && v.contains("missile")) { q = 1; dollars = 9000; }
        else if (v.contains("large_shell")) { q = 2; dollars = 7000; }
        else if (v.contains("medium_shell")) { q = 3; dollars = 4800; }
        else if (v.contains("small_shell")) { q = 4; dollars = 3200; }
        else if (v.contains("rpg") || v.contains("rocket")) { q = 1; dollars = 4500; }
        else if (v.contains("mortar")) { q = 2; dollars = 3600; }
        else if (v.contains("40mm") || v.contains("grenade")) { q = 4; dollars = 2400; }
        else if (v.contains("flare") || v.contains("smoke_ammo")) { q = 4; dollars = 1200; }
        else if (v.contains("50bmg") || v.contains("50_bmg") || v.contains("heavy_ammo") ||
                 v.contains("12.7") || v.contains("14.5") || v.contains("20mm")) { q = 10; dollars = 1800; }
        else if (v.contains("338") || v.contains("308") || v.contains("30_06") ||
                 v.contains("762x54") || v.contains("792x57") || v.contains("sniper_ammo")) { q = 10; dollars = 1200; }
        else if (v.contains("12g") || v.contains("shotgun")) { q = 16; dollars = 900; }
        else if (standardSixtyRoundAmmo(v) && (v.contains("556") || v.contains("545")
                || v.contains("762x39") || v.contains("58x42") || v.contains("rifle_ammo"))) {
            q = 60; dollars = 900;
        }
        else if (standardSixtyRoundAmmo(v) && (v.contains("9mm") || v.contains("45acp")
                || v.contains("57x28") || v.contains("46x30") || v.contains("357")
                || v.contains("50ae") || v.contains("22wmr") || v.contains("handgun_ammo"))) {
            q = 60; dollars = 600;
        }
        else { q = 20; dollars = 1000; }

        if (v.contains("incendiary") || v.contains("dragon") || v.contains("flechette") ||
            v.contains("_ap") || v.contains("_he") || v.contains("armor_pier") ||
            v.contains("explosive")) dollars = (int)Math.ceil(dollars * 1.35 / 50.0) * 50;

        int safeStack = Math.max(1, Math.min(64, maxStack));
        q = Math.min(q, safeStack * 2);
        return new AutoPlan(Math.max(1, q), Integer.toString(Math.max(100, dollars)));
    }
    private synchronized void syncAutomaticAmmo() throws Exception {
        lastAutoSync = System.nanoTime();

        Catalog next = GSON.fromJson(GSON.toJson(catalog), Catalog.class);
        if (next.autoExcluded == null) next.autoExcluded = new LinkedHashSet<>();
        boolean changed = next.offers.removeIf(o -> !allowedCategory(o.category)
                || (o.autoKey != null && o.autoKey.startsWith("tacz:gun:")));
        for (Offer o : next.offers) {
            if (o.image != null && !o.image.isBlank()) { o.image = ""; changed = true; }
        }

        Map<String,Captured> found = discoverAutomaticAmmo();
        if (found.isEmpty()) {
            if (changed) commit(next);
            return;
        }

        Map<String,Offer> automatic = new HashMap<>();
        Set<String> manualNbt = new HashSet<>();
        for (Offer offer : next.offers) {
            if (offer.automatic && offer.autoKey != null && !offer.autoKey.isEmpty()) automatic.put(offer.autoKey, offer);
            else if (offer.nbt != null) manualNbt.add(offer.nbt);
        }

        for (Map.Entry<String,Captured> entry : found.entrySet()) {
            String key = entry.getKey();
            Captured capture = entry.getValue();
            Offer current = automatic.get(key);
            if (current != null) {
                if (!Objects.equals(current.nbt, capture.nbt())) { current.nbt = capture.nbt(); changed = true; }
                if (!Objects.equals(current.itemId, capture.itemId())) { current.itemId = capture.itemId(); changed = true; }

                String migratedName = readableName(current.name, capture.name());
                if (placeholderName(current.name)) migratedName = capture.name();
                if (!Objects.equals(current.name, migratedName)) { current.name = migratedName; changed = true; }

                String migratedCategory = calibratedCategory(capture.category(),
                        capture.name() + " " + key + " " + capture.itemId());
                if (!Objects.equals(current.category, migratedCategory)) {
                    current.category = migratedCategory; changed = true;
                }

                AutoPlan migratedPlan = balancedPlan(capture.name() + " " + key + " " + capture.itemId(), capture.maxStack());
                if (current.category != null && current.category.toLowerCase(Locale.ROOT).contains("munizioni")
                        && current.quantity != migratedPlan.quantity()) {
                    current.quantity = migratedPlan.quantity(); changed = true;
                }
                if (current.image != null && !current.image.isBlank()) { current.image = ""; changed = true; }
                continue;
            }
            if (!allowedCategory(capture.category()) || next.autoExcluded.contains(key)
                    || manualNbt.contains(capture.nbt()) || next.offers.size() >= MAX_OFFERS) continue;
            AutoPlan plan = balancedPlan(capture.name() + " " + key + " " + capture.itemId(), capture.maxStack());
            Offer offer = new Offer();
            offer.id = UUID.nameUUIDFromBytes(("armeria:auto:" + key).getBytes(StandardCharsets.UTF_8)).toString();
            offer.name = capture.name();
            offer.description = "Articolo rilevato automaticamente. Prezzo iniziale bilanciato e modificabile dagli OP.";
            offer.image = "";
            offer.category = calibratedCategory(
                    capture.category() == null || capture.category().isBlank() ? "Munizioni" : capture.category(),
                    capture.name() + " " + key + " " + capture.itemId());
            offer.price = plan.price();
            offer.itemId = capture.itemId();
            offer.nbt = capture.nbt();
            offer.quantity = plan.quantity();
            offer.enabled = true;
            offer.automatic = true;
            offer.autoKey = key;
            next.offers.add(offer);
            changed = true;
        }
        if (changed) commit(next);
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
        if (!allowedCategory(product.category))
            throw new IllegalArgumentException("Questo articolo non appartiene piu al catalogo munizioni/accessori.");
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
        if (!paid) { mark(receipt, tx, "DECLINED"); throw new IllegalArgumentException("EssentialsX Economy ha rifiutato il pagamento. Nessun articolo consegnato."); }
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
        } catch (Exception e) { state.addProperty("economyError", "EssentialsX Economy/Vault non disponibile: " + e.getMessage()); }
        state.addProperty("economyAvailable", economy);
        JsonArray offers = new JsonArray();
        for (Offer offer : catalog.offers) {
            if (!allowedCategory(offer.category)) continue;
            if (!offer.enabled && !operator) continue;
            JsonObject row = new JsonObject();
            row.addProperty("id", offer.id); row.addProperty("name", offer.name);
            row.addProperty("description", offer.description); row.addProperty("category", offer.category);
            row.addProperty("price", offer.price);
            row.addProperty("quantity", offer.quantity);
            row.addProperty("enabled", offer.enabled); row.addProperty("itemId", offer.itemId);
            row.addProperty("automatic", offer.automatic);
            String formatted = offer.price;
            if (economy) try { formatted = port.format(new BigDecimal(offer.price)); } catch (Exception ignored) {}
            row.addProperty("priceFormatted", formatted); offers.add(row);
        }
        state.add("offers", offers); return state;
    }
}
