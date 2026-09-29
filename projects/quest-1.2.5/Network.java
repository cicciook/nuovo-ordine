package com.nuovoordine.quests;

import io.netty.buffer.ByteBuf;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.*;

/**
 * Network bridge for Nuovo Ordine quests.
 *
 * 1.2.5 keeps the Forge SimpleChannel for page synchronization and as a
 * compatibility fallback, but browser state/claim requests use vanilla player
 * chat + system messages. Mohist and Connector already handle those packets,
 * which removes the fragile custom-payload round trip that could hang /quest.
 */
final class Network {
    static final int CHUNK = 24 * 1024;
    static final int MAX_CHUNKS = (SiteArchive.MAX_BYTES + CHUNK - 1) / CHUNK;
    static final int MAX_SHOP_REQUEST = 64 * 1024;
    static final int MAX_SHOP_RESULT = 768 * 1024;

    static final String VANILLA_REQ_PREFIX = "~NOQ125~";
    static final String VANILLA_RES_PREFIX = "__NOQUESTS125_RES__";
    private static final int MAX_CHAT_BRIDGE = 240;

    record Request(long id) {}
    record Reply(long id, int index, int count, int total, byte[] hash, byte[] data, String error) {}
    record ShopRequest(long id, String json) {}
    record ShopResult(long id, String json) {}

    private static Object channel;
    private static Method replyMethod;
    private static Method sendToServer;
    private static Method enqueue;
    private static Method handled;
    private static Method sender;
    private static Object playerDistributor;
    private static Method playerWith;
    private static Method sendPacket;
    private static final Map<Long, Object> shopTargets = new ConcurrentHashMap<>();
    private static Object serverChatListener;
    private static final ThreadLocal<Object> VANILLA_REPLY_PLAYER = new ThreadLocal<>();

    static synchronized void init() {
        if (channel != null) return;
        try {
            Class<?> location = Class.forName("net.minecraft.resources.ResourceLocation");
            Object name = location.getConstructor(String.class, String.class)
                    .newInstance("noquests", "site_sync");
            Class<?> registry = Class.forName("net.minecraftforge.network.NetworkRegistry");
            Predicate<String> accepts = "2"::equals;
            channel = registry.getMethod("newSimpleChannel", location, Supplier.class, Predicate.class, Predicate.class)
                    .invoke(null, name, (Supplier<String>) () -> "2", accepts, accepts);
            Class<?> context = Class.forName("net.minecraftforge.network.NetworkEvent$Context");
            enqueue = context.getMethod("enqueueWork", Runnable.class);
            handled = context.getMethod("setPacketHandled", boolean.class);
            sender = context.getMethod("getSender");
            replyMethod = channel.getClass().getMethod("reply", Object.class, context);
            sendToServer = channel.getClass().getMethod("sendToServer", Object.class);

            Class<?> distributor = Class.forName("net.minecraftforge.network.PacketDistributor");
            playerDistributor = distributor.getField("PLAYER").get(null);
            for (Method m : playerDistributor.getClass().getMethods()) {
                if (m.getName().equals("with") && m.getParameterCount() == 1) {
                    playerWith = m;
                    break;
                }
            }
            for (Method m : channel.getClass().getMethods()) {
                if (m.getName().equals("send") && m.getParameterCount() == 2) {
                    sendPacket = m;
                    break;
                }
            }
            if (playerWith == null || sendPacket == null)
                throw new NoSuchMethodException("PacketDistributor.PLAYER / SimpleChannel.send non disponibili");

            register(0, Request.class, Network::encodeRequest, Network::decodeRequest,
                    Network::receiveRequest, "PLAY_TO_SERVER");
            register(1, Reply.class, Network::encodeReply, Network::decodeReply,
                    Network::receiveReply, "PLAY_TO_CLIENT");
            register(2, ShopRequest.class, (p,b) -> encodeJson(p.id(), p.json(), b, MAX_SHOP_REQUEST),
                    b -> new ShopRequest(b.readLong(), decodeJson(b, MAX_SHOP_REQUEST)), Network::receiveShopRequest, "PLAY_TO_SERVER");
            register(3, ShopResult.class, (p,b) -> encodeJson(p.id(), p.json(), b, MAX_SHOP_RESULT),
                    b -> new ShopResult(b.readLong(), decodeJson(b, MAX_SHOP_RESULT)), Network::receiveShopResult, "PLAY_TO_CLIENT");

            registerVanillaServerBridge();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Impossibile inizializzare la rete Quest Nuovo Ordine", e);
        }
    }

    private static <T> void register(int id, Class<T> type, BiConsumer<T, ByteBuf> encoder,
            Function<ByteBuf,T> decoder, BiConsumer<T,Supplier<?>> handler, String direction)
            throws ReflectiveOperationException {
        Object dir = Class.forName("net.minecraftforge.network.NetworkDirection").getField(direction).get(null);
        Method register = channel.getClass().getMethod("registerMessage", int.class, Class.class,
                BiConsumer.class, Function.class, BiConsumer.class, Optional.class);
        register.invoke(channel, id, type, encoder, decoder, handler, Optional.of(dir));
    }

    static void encodeRequest(Request packet, ByteBuf buffer) { buffer.writeLong(packet.id()); }
    static Request decodeRequest(ByteBuf buffer) {
        if (buffer.readableBytes() != 8) throw new IllegalArgumentException("Invalid request");
        return new Request(buffer.readLong());
    }
    static void encodeReply(Reply p, ByteBuf b) {
        byte[] error = p.error().getBytes(StandardCharsets.UTF_8);
        if (p.data().length > CHUNK || error.length > 2048 || p.hash().length != 32)
            throw new IllegalArgumentException("Invalid reply");
        b.writeLong(p.id()).writeInt(p.index()).writeInt(p.count()).writeInt(p.total());
        b.writeBytes(p.hash());
        b.writeInt(p.data().length).writeBytes(p.data());
        b.writeInt(error.length).writeBytes(error);
    }
    static Reply decodeReply(ByteBuf b) {
        long id = b.readLong();
        int index = b.readInt(), count = b.readInt(), total = b.readInt();
        byte[] hash = new byte[32]; b.readBytes(hash);
        int length = b.readInt();
        if (length < 0 || length > CHUNK || length > b.readableBytes() - 4)
            throw new IllegalArgumentException("Invalid chunk size");
        byte[] data = new byte[length]; b.readBytes(data);
        int errorLength = b.readInt();
        if (errorLength < 0 || errorLength > 2048 || errorLength != b.readableBytes())
            throw new IllegalArgumentException("Invalid error size");
        byte[] error = new byte[errorLength]; b.readBytes(error);
        if (errorLength == 0 && (total <= 0 || total > SiteArchive.MAX_BYTES || count <= 0
                || count > MAX_CHUNKS || count != (total + CHUNK - 1) / CHUNK || index < 0 || index >= count
                || length != Math.min(CHUNK, total - index * CHUNK)))
            throw new IllegalArgumentException("Invalid transfer header");
        return new Reply(id, index, count, total, hash, data, new String(error, StandardCharsets.UTF_8));
    }

    static void request(long id) { invoke(sendToServer, channel, new Request(id)); }

    static void encodeJson(long id, String json, ByteBuf buffer, int maximum) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maximum) throw new IllegalArgumentException("Dati shop troppo grandi");
        buffer.writeLong(id).writeInt(bytes.length).writeBytes(bytes);
    }
    static String decodeJson(ByteBuf buffer, int maximum) {
        int size = buffer.readInt();
        if (size < 0 || size > maximum || size != buffer.readableBytes())
            throw new IllegalArgumentException("Pacchetto shop non valido");
        byte[] bytes = new byte[size]; buffer.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static void shopRequest(ShopRequest request) {
        if (sendVanillaRequest(request)) return;
        System.getLogger("Quest Nuovo Ordine").log(System.Logger.Level.WARNING,
                "Bridge /quest 1.2.5: fallback al canale Forge per id=" + request.id());
        invoke(sendToServer, channel, request);
    }

    private static boolean sendVanillaRequest(ShopRequest request) {
        try {
            String encoded = Base64.getEncoder().encodeToString(request.json().getBytes(StandardCharsets.UTF_8));
            String message = VANILLA_REQ_PREFIX + request.id() + ":" + encoded;
            if (message.length() > MAX_CHAT_BRIDGE) return false;

            Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft");
            Object mc = invokeNamedStatic(minecraft, new String[]{"m_91087_", "getInstance"});
            Object connection = invokeNamed(mc, new String[]{"m_91403_", "getConnection"});
            if (connection == null) return false;
            Method sendChat = findMethod(connection.getClass(), new String[]{"m_246175_", "sendChat"}, String.class);
            if (sendChat == null) return false;
            sendChat.invoke(connection, message);
            System.getLogger("Quest Nuovo Ordine").log(System.Logger.Level.INFO,
                    "Bridge /quest 1.2.5: richiesta vanilla inviata, id=" + request.id());
            return true;
        } catch (Throwable e) {
            System.getLogger("Quest Nuovo Ordine").log(System.Logger.Level.WARNING,
                    "Bridge /quest 1.2.5: trasporto vanilla client non disponibile", e);
            return false;
        }
    }

    static void shopReply(Object context, ShopResult response) {
        Object vanillaPlayer = VANILLA_REPLY_PLAYER.get();
        if (vanillaPlayer != null) {
            sendVanillaResult(vanillaPlayer, response);
            return;
        }

        Object player = shopTargets.remove(response.id());
        if (player == null && context != null) {
            try { player = invoke(sender, context); } catch (RuntimeException ignored) {}
        }
        if (player == null) throw new IllegalStateException("Player server non disponibile per la risposta quest");
        final Object targetPlayer = player;
        Class<?> wanted = playerWith.getParameterTypes()[0];
        Object argument = Supplier.class.isAssignableFrom(wanted) ? (Supplier<Object>) () -> targetPlayer : targetPlayer;
        Object target = invoke(playerWith, playerDistributor, argument);
        invoke(sendPacket, channel, target, response);
        System.getLogger("Quest Nuovo Ordine").log(System.Logger.Level.INFO,
                "Risposta /quest Forge inviata al client, id=" + response.id());
    }

    private static void receiveShopRequest(ShopRequest request, Supplier<?> supplier) {
        Object ctx = supplier.get(); invoke(handled, ctx, true);
        onMain(ctx, () -> {
            Object player = invoke(sender, ctx);
            if (player != null) {
                shopTargets.put(request.id(), player);
                System.getLogger("Quest Nuovo Ordine").log(System.Logger.Level.INFO,
                        "Richiesta /quest Forge ricevuta dal server, id=" + request.id());
                try { ShopRuntime.receive(ctx, player, request); }
                catch (RuntimeException e) { shopTargets.remove(request.id()); throw e; }
            }
        });
    }
    private static void receiveShopResult(ShopResult result, Supplier<?> supplier) {
        Object ctx = supplier.get(); invoke(handled, ctx, true);
        onMain(ctx, () -> ShopBrowserClient.response(result));
    }

    private static void registerVanillaServerBridge() {
        try {
            Class<?> forge = Class.forName("net.minecraftforge.common.MinecraftForge");
            Object bus = forge.getField("EVENT_BUS").get(null);
            Class<?> busApi = Class.forName("net.minecraftforge.eventbus.api.IEventBus");
            Class<?> eventType = Class.forName("net.minecraftforge.event.ServerChatEvent");
            Consumer<Object> consumer = Network::onServerChat;
            busApi.getMethod("addListener", Class.class, Consumer.class).invoke(bus, eventType, consumer);
            serverChatListener = consumer;
            System.getLogger("Quest Nuovo Ordine").log(System.Logger.Level.INFO,
                    "Bridge /quest 1.2.5 server registrato sul canale vanilla");
        } catch (Throwable e) {
            System.getLogger("Quest Nuovo Ordine").log(System.Logger.Level.WARNING,
                    "Bridge /quest 1.2.5 server vanilla non registrato; resta disponibile il fallback Forge", e);
        }
    }

    private static void onServerChat(Object event) {
        try {
            String raw = String.valueOf(event.getClass().getMethod("getRawText").invoke(event));
            if (!raw.startsWith(VANILLA_REQ_PREFIX)) return;
            event.getClass().getMethod("setCanceled", boolean.class).invoke(event, true);

            String body = raw.substring(VANILLA_REQ_PREFIX.length());
            int split = body.indexOf(':');
            if (split <= 0 || split > 32) return;
            long id = Long.parseLong(body.substring(0, split));
            byte[] decoded = Base64.getDecoder().decode(body.substring(split + 1));
            if (decoded.length > MAX_SHOP_REQUEST) return;
            String json = new String(decoded, StandardCharsets.UTF_8);
            Object player = event.getClass().getMethod("getPlayer").invoke(event);
            if (player == null) return;

            System.getLogger("Quest Nuovo Ordine").log(System.Logger.Level.INFO,
                    "Bridge /quest 1.2.5: richiesta vanilla ricevuta, id=" + id);
            VANILLA_REPLY_PLAYER.set(player);
            try {
                ShopRuntime.receive(null, player, new ShopRequest(id, json));
            } finally {
                VANILLA_REPLY_PLAYER.remove();
            }
        } catch (Throwable e) {
            System.getLogger("Quest Nuovo Ordine").log(System.Logger.Level.ERROR,
                    "Bridge /quest 1.2.5: errore richiesta vanilla", e);
        }
    }

    private static void sendVanillaResult(Object player, ShopResult response) {
        try {
            byte[] bytes = response.json().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_SHOP_RESULT) throw new IllegalArgumentException("Risposta quest troppo grande");
            String payload = Base64.getEncoder().encodeToString(bytes);
            String message = VANILLA_RES_PREFIX + response.id() + ":" + payload;
            Method getBukkit = findMethod(player.getClass(), new String[]{"getBukkitEntity"});
            if (getBukkit == null) throw new NoSuchMethodException("getBukkitEntity");
            Object bukkit = getBukkit.invoke(player);
            Method send = findMethod(bukkit.getClass(), new String[]{"sendMessage"}, String.class);
            if (send == null) throw new NoSuchMethodException("Bukkit Player.sendMessage(String)");
            send.invoke(bukkit, message);
            System.getLogger("Quest Nuovo Ordine").log(System.Logger.Level.INFO,
                    "Bridge /quest 1.2.5: risposta vanilla inviata, id=" + response.id());
        } catch (Throwable e) {
            throw new IllegalStateException("Impossibile inviare la risposta /quest via vanilla", e);
        }
    }

    static void reply(Object context, Reply packet) { invoke(replyMethod, channel, packet, context); }
    static void error(Object context, long id, String message) {
        if (message.length() > 400) message = message.substring(0, 400);
        reply(context, new Reply(id, -1, 0, 0, new byte[32], new byte[0], message));
    }
    static void onMain(Object context, Runnable action) { invoke(enqueue, context, action); }
    private static void receiveRequest(Request p, Supplier<?> contextSupplier) {
        Object ctx = contextSupplier.get();
        invoke(handled, ctx, true);
        onMain(ctx, () -> {
            Object player = invoke(sender, ctx);
            if (player != null) ServerEvents.request(ctx, player, p.id());
        });
    }
    private static void receiveReply(Reply p, Supplier<?> contextSupplier) {
        Object ctx = contextSupplier.get();
        invoke(handled, ctx, true);
        onMain(ctx, () -> ClientTransfer.accept(p));
    }

    private static Method findMethod(Class<?> type, String[] names, Class<?>... params) {
        for (String name : names) {
            try { return type.getMethod(name, params); }
            catch (NoSuchMethodException ignored) {}
        }
        return null;
    }
    private static Object invokeNamed(Object target, String[] names, Object... args) throws ReflectiveOperationException {
        Class<?>[] params = Arrays.stream(args).map(Object::getClass).toArray(Class<?>[]::new);
        Method method = findMethod(target.getClass(), names, params);
        if (method == null) throw new NoSuchMethodException(Arrays.toString(names));
        return method.invoke(target, args);
    }
    private static Object invokeNamedStatic(Class<?> target, String[] names, Object... args) throws ReflectiveOperationException {
        Class<?>[] params = Arrays.stream(args).map(Object::getClass).toArray(Class<?>[]::new);
        Method method = findMethod(target, names, params);
        if (method == null) throw new NoSuchMethodException(Arrays.toString(names));
        return method.invoke(null, args);
    }
    private static Object invoke(Method method, Object owner, Object... args) {
        try { return method.invoke(owner, args); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("Errore rete Quest Nuovo Ordine", e); }
    }
}
