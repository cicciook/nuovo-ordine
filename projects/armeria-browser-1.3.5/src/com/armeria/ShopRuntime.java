package com.armeria;

import com.google.gson.*;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.util.*;

final class ShopRuntime {
    private static ShopService service;
    private static final Map<Object, Long> lastRequest = new WeakHashMap<>();
    static void reset() { service = null; lastRequest.clear(); }
    static void receive(Object context, Object player, Network.ShopRequest request) {
        JsonObject result;
        try {
            long now = System.nanoTime();
            Long last = lastRequest.get(player);
            if (last != null && now - last < 100_000_000L) throw new IllegalArgumentException("Attendi un istante e riprova.");
            lastRequest.put(player, now);
            if (service == null) service = new ShopService(SiteArchive.gameDirectory().resolve("config/armeria"));
            JsonObject data = JsonParser.parseString(request.json()).getAsJsonObject();
            result = service.handle(data, new Port(player));
        } catch (Exception e) {
            result = new JsonObject(); result.addProperty("ok", false);
            result.addProperty("message", "Armeria: " + e.getMessage());
            System.getLogger("Armeria").log(System.Logger.Level.ERROR, "Richiesta shop fallita", e);
        }
        Network.shopReply(context, new Network.ShopResult(request.id(), result.toString()));
    }
    static Method method(Class<?> type, String srg, String name, Class<?>... args) throws Exception {
        try { return type.getMethod(srg, args); }
        catch (NoSuchMethodException e) { return type.getMethod(name, args); }
    }
    static Object call(Object object, String name, Class<?>[] types, Object... args) throws Exception {
        return object.getClass().getMethod(name, types).invoke(object, args);
    }
    private static Object call(Object object, String name) throws Exception { return call(object, name, new Class<?>[0]); }
    static final class Port implements ShopService.Port {
        private final Object player, bukkit;
        private Object economy;
        private Class<?> economyApi, offlineApi;
        Port(Object player) throws Exception {
            this.player = player;
            bukkit = call(player, "getBukkitEntity");
        }
        public String playerId() throws Exception { return call(bukkit, "getUniqueId").toString(); }
        public boolean isOp() throws Exception { return (boolean) call(bukkit, "isOp"); }
        private Object inventory() throws Exception { return method(player.getClass(), "m_150109_", "getInventory").invoke(player); }
        private Class<?> stackClass() throws Exception { return Class.forName("net.minecraft.world.item.ItemStack"); }
        private boolean empty(Object stack) throws Exception { return (boolean) method(stackClass(), "m_41619_", "isEmpty").invoke(stack); }
        private void validateProduct(Object stack) throws Exception {
            if (empty(stack)) throw new IllegalArgumentException("Tieni in mano una munizione, una ammo box o un accessorio TACZ / Superb Warfare.");
            Object item = method(stackClass(), "m_41720_", "getItem").invoke(stack);
            if (!AmmoSupport.accepts(item)) throw new IllegalArgumentException("L'oggetto deve essere una munizione, una ammo box o un accessorio TACZ / Superb Warfare. Le armi non sono ammesse.");
        }
        public ShopService.Captured capture() throws Exception {
            Object held = method(player.getClass(), "m_21205_", "getMainHandItem").invoke(player);
            validateProduct(held);
            Object copy = method(stackClass(), "m_41777_", "copy").invoke(held);
            method(stackClass(), "m_41764_", "setCount", int.class).invoke(copy, 1);
            Class<?> compound = Class.forName("net.minecraft.nbt.CompoundTag");
            Object tag = method(stackClass(), "m_41739_", "save", compound).invoke(copy, compound.getConstructor().newInstance());
            Object name = method(stackClass(), "m_41786_", "getHoverName").invoke(copy);
            String itemName = (String) Class.forName("net.minecraft.network.chat.Component").getMethod("getString").invoke(name);
            String itemId = (String) method(compound, "m_128461_", "getString", String.class).invoke(tag, "id");
            return new ShopService.Captured(tag.toString(), itemName, itemId, (int)method(stackClass(), "m_41613_", "getCount").invoke(held),
                    AmmoSupport.category(method(stackClass(), "m_41720_", "getItem").invoke(held)),
                    Math.min(64, (int)method(stackClass(), "m_41741_", "getMaxStackSize").invoke(held)));
        }
        private Object item(String nbt) throws Exception {
            Class<?> compound = Class.forName("net.minecraft.nbt.CompoundTag");
            Class<?> parser = Class.forName("net.minecraft.nbt.TagParser");
            Object tag = method(parser, "m_129359_", "parseTag", String.class).invoke(null, nbt);
            Object stack = method(stackClass(), "m_41712_", "of", compound).invoke(null, tag);
            validateProduct(stack); method(stackClass(), "m_41764_", "setCount", int.class).invoke(stack, 1); return stack;
        }
        public void validateItem(String nbt) throws Exception { item(nbt); }
        public int maxStack(String nbt) throws Exception {
            return (int)method(stackClass(), "m_41741_", "getMaxStackSize").invoke(item(nbt));
        }
        public int[] emptySlots(int needed) throws Exception {
            int[] slots = new int[needed]; int found = 0;
            for (int i = 0; i < 36 && found < needed; i++) if (slotEmpty(i)) slots[found++] = i;
            return Arrays.copyOf(slots, found);
        }
        public boolean slotEmpty(int slot) throws Exception {
            Object inv = inventory(); return empty(method(inv.getClass(), "m_8020_", "getItem", int.class).invoke(inv, slot));
        }
        public void deliver(String nbt, int[] slots, int[] counts) throws Exception {
            if (slots.length == 0 || slots.length != counts.length) throw new IllegalArgumentException("Lotto non valido.");
            Object inv = inventory();
            ArrayList<Object> stacks = new ArrayList<>();
            Set<Integer> seen = new HashSet<>();
            for (int i = 0; i < slots.length; i++) {
                if (slots[i] < 0 || slots[i] >= 36 || !seen.add(slots[i]) || !slotEmpty(slots[i]))
                    throw new IllegalArgumentException("Slot inventario non disponibile.");
                Object stack = item(nbt);
                int limit = Math.min(64, (int)method(stackClass(), "m_41741_", "getMaxStackSize").invoke(stack));
                if (counts[i] < 1 || counts[i] > limit) throw new IllegalArgumentException("Dimensione stack non valida.");
                method(stackClass(), "m_41764_", "setCount", int.class).invoke(stack, counts[i]);
                stacks.add(stack);
            }
            // Construct and validate every stack before touching the inventory.
            for (int i = 0; i < slots.length; i++)
                method(inv.getClass(), "m_6836_", "setItem", int.class, stackClass()).invoke(inv, slots[i], stacks.get(i));
            method(inv.getClass(), "m_6596_", "setChanged").invoke(inv);
            call(bukkit, "saveData");
        }
        private Object provider() throws Exception {
            if (economy != null) return economy;
            Object server = call(bukkit, "getServer");
            Object manager = call(server, "getPluginManager");
            Object vault = call(manager, "getPlugin", new Class<?>[]{String.class}, "Vault");
            if (vault == null) throw new IllegalArgumentException("Vault non installato.");
            economyApi = vault.getClass().getClassLoader().loadClass("net.milkbowl.vault.economy.Economy");
            offlineApi = vault.getClass().getClassLoader().loadClass("org.bukkit.OfflinePlayer");
            Object services = call(server, "getServicesManager");
            Collection<?> registrations = (Collection<?>) call(services, "getRegistrations", new Class<?>[]{Class.class}, economyApi);
            for (Object registration : registrations) {
                Object candidate = call(registration, "getProvider");
                String providerName = String.valueOf(economyApi.getMethod("getName").invoke(candidate));
                if (!"EssentialsX Economy".equals(providerName) && !"Essentials Economy".equals(providerName)) continue;
                if ((boolean) economyApi.getMethod("isEnabled").invoke(candidate)) { economy = candidate; return candidate; }
            }
            throw new IllegalArgumentException("EssentialsX Economy non registrato in Vault.");
        }
        public String economyName() throws Exception { provider(); return "EssentialsX Economy / Vault"; }
        public BigDecimal balance() throws Exception {
            Object p = provider(); double value = (double) economyApi.getMethod("getBalance", offlineApi).invoke(p, bukkit);
            if (!Double.isFinite(value)) throw new IllegalStateException("Saldo non valido.");
            return BigDecimal.valueOf(value);
        }
        public String format(BigDecimal value) throws Exception {
            Object p = provider(); return (String) economyApi.getMethod("format", double.class).invoke(p, value.doubleValue());
        }
        private boolean money(String action, BigDecimal amount) throws Exception {
            Object p = provider();
            Object response = economyApi.getMethod(action, offlineApi, double.class).invoke(p, bukkit, amount.doubleValue());
            return (boolean) call(response, "transactionSuccess");
        }
        public boolean withdraw(BigDecimal value) throws Exception { return money("withdrawPlayer", value); }
        public boolean deposit(BigDecimal value) throws Exception { return money("depositPlayer", value); }
    }
}
