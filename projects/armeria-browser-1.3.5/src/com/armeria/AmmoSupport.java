package com.armeria;

import java.lang.reflect.Method;
import java.util.*;

/** Optional runtime integrations for ammunition and attachments only. */
final class AmmoSupport {
    private static Map<Object,String> superbItems;
    private AmmoSupport() {}

    static boolean accepts(Object item) throws Exception {
        return !category(item).isEmpty();
    }

    static String category(Object item) throws Exception {
        // Nuovo Ordine: ammunition is sold only from Superb Warfare.
        // TACZ remains accepted only for gun attachments/sights.
        String[][] types = {
            {"com.tacz.guns.api.item.IAttachment", "Accessori TACZ"}
        };
        for (String[] type : types) {
            try { if (Class.forName(type[0]).isInstance(item)) return type[1]; }
            catch (ClassNotFoundException absent) { /* TACZ is optional. */ }
        }
        if (superbItems == null) {
            Map<Object,String> found = new IdentityHashMap<>();
            try {
                Class<?> modItems = Class.forName("com.atsuishio.superbwarfare.init.ModItems");
                registerGroup(found, modItems, "AMMO", "Munizioni Superb Warfare");
                registerGroup(found, modItems, "PERKS", "Accessori Superb Warfare");
                for (String field : new String[]{"AMMO_PERK_DATA_CHIP", "FUNCTIONAL_PERK_DATA_CHIP", "DAMAGE_PERK_DATA_CHIP"})
                    registerItem(found, modItems, field, "Accessori Superb Warfare");
            } catch (ClassNotFoundException absent) { /* Superb Warfare is optional. */ }
            superbItems = found;
        }
        return superbItems.getOrDefault(item, "");
    }

    /** Resolve the Forge item registry key without linking against Forge at compile time. */
    private static String registryId(Object item) {
        if (item == null) return "";
        try {
            Class<?> forgeRegistries = Class.forName("net.minecraftforge.registries.ForgeRegistries");
            Object items = forgeRegistries.getField("ITEMS").get(null);
            for (Method method : items.getClass().getMethods()) {
                if (!method.getName().equals("getKey") || method.getParameterCount() != 1) continue;
                try {
                    Object key = method.invoke(items, item);
                    if (key != null) return key.toString().toLowerCase(Locale.ROOT);
                } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
                    // Keep trying compatible getKey overloads / bridges.
                }
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Forge is always present in production, but the filter remains testable in isolation.
        }
        return "";
    }

    private static void registerGroup(Map<Object,String> found, Class<?> modItems, String field, String category) throws Exception {
        Object register = modItems.getField(field).get(null);
        Collection<?> entries = (Collection<?>)register.getClass().getMethod("getEntries").invoke(register);
        for (Object entry : entries) found.put(entry.getClass().getMethod("get").invoke(entry), category);
    }
    private static void registerItem(Map<Object,String> found, Class<?> modItems, String field, String category) throws Exception {
        try {
            Object entry = modItems.getField(field).get(null);
            found.put(entry.getClass().getMethod("get").invoke(entry), category);
        } catch (NoSuchFieldException absentInVersion) { /* Optional supplementary item. */ }
    }
}
