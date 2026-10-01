package it.nuovoordine.gameplay;

import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;

final class TownyBridge {
    private TownyBridge() {}

    static String town(ServerPlayer player) {
        try {
            Class<?> apiClass = Class.forName("com.palmergames.bukkit.towny.TownyAPI");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            Object resident = resident(api, player.getUUID(), player.getGameProfile().getName());
            if (resident == null) return "";
            Object town = invokeNoArgs(resident, "getTownOrNull");
            if (town == null) town = invokeNoArgs(resident, "getTown");
            if (town == null) return "";
            Object name = invokeNoArgs(town, "getName");
            return name == null ? "" : String.valueOf(name);
        } catch (ClassNotFoundException ignored) {
            return "";
        } catch (Throwable ex) {
            System.getLogger("nogameplay").log(System.Logger.Level.DEBUG, "Towny bridge non disponibile", ex);
            return "";
        }
    }

    private static Object resident(Object api, UUID uuid, String name) throws Exception {
        for (Method method : api.getClass().getMethods()) {
            if (!method.getName().equals("getResident") || method.getParameterCount() != 1) continue;
            Class<?> type = method.getParameterTypes()[0];
            try {
                if (type == UUID.class) return method.invoke(api, uuid);
                if (type == String.class) return method.invoke(api, name);
            } catch (InvocationTargetException ex) {
                // Resident missing is a normal state for a player not registered in Towny.
            }
        }
        return null;
    }

    private static Object invokeNoArgs(Object target, String methodName) {
        try {
            return target.getClass().getMethod(methodName).invoke(target);
        } catch (NoSuchMethodException ignored) {
            return null;
        } catch (InvocationTargetException ignored) {
            return null;
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }
}
