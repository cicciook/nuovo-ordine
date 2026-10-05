package com.nuovoordine.quests;

import com.google.gson.*;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.util.*;

final class ShopRuntime {
    private static QuestService service;
    private static final Map<Object, Long> lastRequest = new WeakHashMap<>();
    static QuestService get() throws Exception {
        if(service==null)service=new QuestService(SiteArchive.gameDirectory().resolve("config/noquests"),java.time.Clock.systemUTC());
        return service;
    }
    static void flush() {try{if(service!=null)service.flush();}catch(Exception e){System.getLogger("NoQuests").log(System.Logger.Level.ERROR,"Salvataggio quest fallito",e);}}
    static void reset() { flush(); service = null; lastRequest.clear(); }

    static void receive(Object context, Object player, Network.ShopRequest request) {
        JsonObject result;
        try {
            long now = System.nanoTime();
            Long last = lastRequest.get(player);
            if (last != null && now - last < 100_000_000L) throw new IllegalArgumentException("Attendi un istante e riprova.");
            lastRequest.put(player, now);
            if (service == null) service = get();
            JsonObject data = JsonParser.parseString(request.json()).getAsJsonObject();
            result = service.handle(data, new Port(player));
        } catch (Exception e) {
            result = new JsonObject(); result.addProperty("ok", false);
            result.addProperty("message", "Quest Nuovo Ordine: " + e.getMessage());
            System.getLogger("Quest Nuovo Ordine").log(System.Logger.Level.ERROR, "Richiesta shop fallita", e);
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
    static final class Port implements QuestService.Port {
        private final Object player, bukkit;
        private Object economy;
        private Class<?> economyApi, offlineApi;
        Port(Object player) throws Exception {
            this.player = player;
            bukkit = call(player, "getBukkitEntity");
        }
        public String id() throws Exception { return call(bukkit, "getUniqueId").toString(); }
        public boolean admin() throws Exception { return (boolean) call(bukkit, "isOp"); }
        private Object inventory() throws Exception { return method(player.getClass(), "m_150109_", "getInventory").invoke(player); }
        private Class<?> stackClass() throws Exception { return Class.forName("net.minecraft.world.item.ItemStack"); }
        private boolean empty(Object stack) throws Exception { return (boolean) method(stackClass(), "m_41619_", "isEmpty").invoke(stack); }
        private void validateProduct(Object stack) throws Exception {
            if(empty(stack)) throw new IllegalArgumentException("Oggetto premio non presente nel server");
        }
        private Object item(String nbt) throws Exception {
            Class<?> compound = Class.forName("net.minecraft.nbt.CompoundTag");
            Class<?> parser = Class.forName("net.minecraft.nbt.TagParser");
            Object tag = method(parser, "m_129359_", "parseTag", String.class).invoke(null, nbt);
            Object stack = method(stackClass(), "m_41712_", "of", compound).invoke(null, tag);
            validateProduct(stack); method(stackClass(), "m_41764_", "setCount", int.class).invoke(stack, 1); return stack;
        }
        public boolean valid(String nbt) {
            try {
                item(nbt);
                java.util.regex.Matcher m=java.util.regex.Pattern.compile("GunId:\"([^\"]+)\"").matcher(nbt);
                if(m.find()) {
                    Class<?> loc=Class.forName("net.minecraft.resources.ResourceLocation");
                    Object id=loc.getConstructor(String.class).newInstance(m.group(1));
                    Object value=Class.forName("com.tacz.guns.api.TimelessAPI").getMethod("getCommonGunIndex",loc).invoke(null,id);
                    if(((java.util.Optional<?>)value).isEmpty())return false;
                }
                return true;
            }catch(Exception|LinkageError e){return false;}
        }
        public int[] reserve(List<String> items) throws Exception {
            provider();
            for(String nbt:items)if(!valid(nbt))throw new IllegalArgumentException("Premio non disponibile");
            int[] slots=new int[items.size()];int found=0;Object inv=inventory();
            for(int i=0;i<36 && found<slots.length;i++)if(empty(method(inv.getClass(),"m_8020_","getItem",int.class).invoke(inv,i)))slots[found++]=i;
            if(found<slots.length)throw new IllegalArgumentException("Libera "+slots.length+" slot nell'inventario e riprova.");return slots;
        }
        public void deliver(List<String> items,int[] slots) throws Exception {
            if(items.isEmpty())return;
            Object inv=inventory();List<Object> stacks=new ArrayList<>();
            for(int i=0;i<items.size();i++){
                if(!empty(method(inv.getClass(),"m_8020_","getItem",int.class).invoke(inv,slots[i])))throw new IllegalStateException("Slot occupato");
                stacks.add(item(items.get(i)));
            }
            for(int i=0;i<stacks.size();i++)method(inv.getClass(),"m_6836_","setItem",int.class,stackClass()).invoke(inv,slots[i],stacks.get(i));
            method(inv.getClass(),"m_6596_","setChanged").invoke(inv);call(bukkit,"saveData");
        }
        public boolean deposit(long value)throws Exception{return money("depositPlayer",BigDecimal.valueOf(value));}
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
