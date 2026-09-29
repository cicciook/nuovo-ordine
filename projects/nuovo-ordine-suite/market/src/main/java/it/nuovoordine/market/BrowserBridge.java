package it.nuovoordine.market;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/** Uses the screen already shipped in MCEF 2.1.6, without copying its implementation.
 * Minecraft reflection names are the Forge 1.20.1 SRG names, with dev-name fallbacks.
 */
final class BrowserBridge {
    private static Method method(Class<?> owner, String production, String development,
                                 Class<?>... parameters) throws NoSuchMethodException {
        try { return owner.getMethod(production, parameters); }
        catch (NoSuchMethodException e) { return owner.getMethod(development, parameters); }
    }

    private static Field field(Class<?> owner, String production, String development)
            throws NoSuchFieldException {
        Field result;
        try { result = owner.getField(production); }
        catch (NoSuchFieldException e) { result = owner.getField(development); }
        return result;
    }

    private static Object minecraft() throws ReflectiveOperationException {
        Class<?> type = Class.forName("net.minecraft.client.Minecraft");
        return method(type, "m_91087_", "getInstance").invoke(null);
    }

    private static Object component(String text) throws ReflectiveOperationException {
        Class<?> type = Class.forName("net.minecraft.network.chat.Component");
        return method(type, "m_237113_", "literal", String.class).invoke(null, text);
    }

    static boolean connected() throws ReflectiveOperationException {
        Object mc = minecraft();
        return method(mc.getClass(), "m_91403_", "getConnection").invoke(mc) != null;
    }

    static void execute(Runnable task) throws ReflectiveOperationException {
        ((java.util.concurrent.Executor) minecraft()).execute(task);
    }

    static void open(Path page) throws Exception {
        Object mc = minecraft();
        if (!Files.isRegularFile(page)) throw new IllegalStateException("Pagina del server mancante");
        Class<?> mcef = Class.forName("com.cinemamod.mcef.MCEF");
        if (!Boolean.TRUE.equals(mcef.getMethod("isInitialized").invoke(null)))
            throw new IllegalStateException("MCEF non inizializzato: attendi e riprova");
        MarketClient.prepare();
        Class<?> screenType = Class.forName("com.cinemamod.mcef.example.ExampleScreen");
        Class<?> componentType = Class.forName("net.minecraft.network.chat.Component");
        Class<?> baseScreen = Class.forName("net.minecraft.client.gui.screens.Screen");
        Constructor<?> constructor = screenType.getDeclaredConstructor(componentType);
        constructor.setAccessible(true);
        Object screen = constructor.newInstance(component("Mercato Nero"));
        Class<?> clientType = Class.forName("com.cinemamod.mcef.MCEFClient");
        Class<?> browserType = Class.forName("com.cinemamod.mcef.MCEFBrowser");
        Object client = mcef.getMethod("getClient").invoke(null);
        // Same creation sequence as MCEF.createBrowser, but bind the JS bridge
        // before native creation and pass the real page as the initial URL.
        Object browser = browserType.getConstructor(clientType, String.class, boolean.class)
                .newInstance(client, page.toUri().toASCIIString(), false);
        try {
            browserType.getMethod("setCloseAllowed").invoke(browser);
            MarketClient.bind(browser, page);
            browserType.getMethod("createImmediately").invoke(browser);
            Field browserField = screenType.getDeclaredField("browser");
            browserField.setAccessible(true);
            browserField.set(screen, browser);
            // Preassigning the browser keeps ExampleScreen.init from loading its demo URL.
            method(mc.getClass(), "m_91152_", "setScreen", baseScreen).invoke(mc, screen);
            Method resize = screenType.getDeclaredMethod("resizeBrowser");
            resize.setAccessible(true);
            resize.invoke(screen);
        } catch (Exception | LinkageError failure) {
            try { browser.getClass().getMethod("close").invoke(browser); }
            catch (ReflectiveOperationException ignored) {}
            try { method(mc.getClass(), "m_91152_", "setScreen", baseScreen).invoke(mc, new Object[]{null}); }
            catch (ReflectiveOperationException ignored) {}
            throw failure;
        }
    }

    static void reportError(String message) {
        try {
            Object mc = minecraft();
            Object gui = field(mc.getClass(), "f_91065_", "gui").get(mc);
            Object chat = method(gui.getClass(), "m_93076_", "getChat").invoke(gui);
            method(chat.getClass(), "m_93785_", "addMessage",
                    Class.forName("net.minecraft.network.chat.Component"))
                    .invoke(chat, component(message));
        } catch (ReflectiveOperationException | LinkageError ignored) {
            System.err.println(message);
        }
    }
}
