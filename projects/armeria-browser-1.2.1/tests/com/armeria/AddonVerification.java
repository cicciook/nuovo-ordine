package com.armeria;

import com.google.gson.*;
import java.math.BigDecimal;
import java.nio.file.Files;

public final class AddonVerification {
    private static final class Port implements ShopService.Port {
        boolean op = true;
        String lastNbt;
        public String playerId() { return "00000000-0000-0000-0000-000000000001"; }
        public boolean isOp() { return op; }
        public ShopService.Captured capture() { throw new AssertionError("Selector must not capture the held item"); }
        public String economyName() { return "TNE"; }
        public BigDecimal balance() { return BigDecimal.TEN; }
        public String format(BigDecimal value) { return value.toString(); }
        public boolean withdraw(BigDecimal value) { return true; }
        public boolean deposit(BigDecimal value) { return true; }
        public int[] emptySlots(int count) { return new int[count]; }
        public int maxStack(String nbt) { return nbt.contains("tacz:ammo") ? 64 : 1; }
        public boolean slotEmpty(int slot) { return true; }
        public void validateItem(String nbt) { lastNbt = nbt; }
        public void deliver(String nbt, int[] slots, int[] counts) { }
    }

    private static void check(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }

    public static void main(String[] args) throws Exception {
        ShopService service = new ShopService(Files.createTempDirectory("armeria-addon-test"));
        Port port = new Port();
        JsonObject listing = service.handle(JsonParser.parseString("{\"action\":\"addons\"}").getAsJsonObject(), port);
        check(listing.get("ok").getAsBoolean(), listing.toString());
        JsonArray addons = listing.getAsJsonArray("addons");
        check(addons.size() >= 400, "Catalogo incompleto");
        int saved = 0;
        for (String kind : new String[]{"guns", "attachments", "ammo"}) {
            AddonCatalog.Entry entry = null;
            for (JsonElement row : addons) {
                String id = row.getAsJsonObject().get("id").getAsString();
                AddonCatalog.Entry next = AddonCatalog.get(row.getAsJsonObject().get("key").getAsString());
                if (next.kind().equals(kind)) { entry = next; break; }
            }
            check(entry != null, "Tipo mancante: " + kind);
            JsonObject data = JsonParser.parseString("{\"action\":\"save\",\"revision\":"
                    + (saved + 1) + ",\"id\":\"\",\"name\":\"Test\",\"price\":\"100\",\"quantity\":1,"
                    + "\"category\":\"" + entry.category() + "\",\"description\":\"\",\"image\":\"\","
                    + "\"enabled\":true,\"useHeld\":false,\"addonId\":\"" + entry.kind() + "|" + entry.id() + "\"}").getAsJsonObject();
            JsonObject response = service.handle(data, port);
            check(response.get("ok").getAsBoolean(), response.toString());
            check(port.lastNbt.contains(entry.id()), "NBT errato: " + port.lastNbt);
            check(response.getAsJsonObject("state").getAsJsonArray("offers").size() == ++saved, "Offerta assente");
        }
        port.op = false;
        JsonObject denied = service.handle(JsonParser.parseString("{\"action\":\"addons\"}").getAsJsonObject(), port);
        check(!denied.get("ok").getAsBoolean() && !denied.has("addons"), "Elenco OP pubblico");
        port.op = true;
        try { AddonCatalog.get("guns|fake:gun"); throw new AssertionError("ID sconosciuto accettato"); }
        catch (IllegalArgumentException expected) { }
        System.out.println("OK: " + addons.size() + " definizioni, 3 tipi importati, autorizzazione verificata");
    }
}
