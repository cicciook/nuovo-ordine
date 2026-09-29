package it.nuovoordine.market;
import com.google.gson.*;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.*;
import java.util.function.Supplier;

@Mod("nomarket")
public class MarketMod {
    static final SimpleChannel NET=NetworkRegistry.newSimpleChannel(new ResourceLocation("nomarket","main"),()->"1","1"::equals,"1"::equals);
    private static MarketService service;
    private static final Map<UUID,Long> last=new HashMap<>();
    public MarketMod(){
        NET.registerMessage(0,Request.class,Request::encode,Request::decode,MarketMod::request,Optional.of(NetworkDirection.PLAY_TO_SERVER));
        NET.registerMessage(1,Response.class,Response::encode,Response::decode,(p,c)->{c.get().enqueueWork(()->MarketClient.receive(p));c.get().setPacketHandled(true);},Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        MinecraftForge.EVENT_BUS.addListener(this::commands);
        MinecraftForge.EVENT_BUS.addListener(this::started);
        MinecraftForge.EVENT_BUS.addListener(this::stopped);
        MinecraftForge.EVENT_BUS.addListener(this::logout);
    }
    void started(ServerStartedEvent e){try{service=new MarketService(e.getServer().getWorldPath(LevelResource.ROOT).resolve("nuovoordine/market.json"),System::currentTimeMillis);}catch(Exception ex){throw new IllegalStateException("Archivio mercato non leggibile: preservato senza sovrascrittura",ex);}}
    void stopped(ServerStoppedEvent e){service=null;last.clear();}
    void logout(PlayerEvent.PlayerLoggedOutEvent e){last.remove(e.getEntity().getUUID());}
    void commands(RegisterCommandsEvent e){e.getDispatcher().register(Commands.literal("mercatonero").executes(c->{ServerPlayer p=c.getSource().getPlayerOrException();NET.send(PacketDistributor.PLAYER.with(()->p),new Response(-1,"{}"));return 1;}));}
    record Request(long id,String json){static void encode(Request p,FriendlyByteBuf b){b.writeLong(p.id);b.writeUtf(p.json,8192);}static Request decode(FriendlyByteBuf b){return new Request(b.readLong(),b.readUtf(8192));}}
    record Response(long id,String json){static void encode(Response p,FriendlyByteBuf b){b.writeLong(p.id);b.writeUtf(p.json,262144);}static Response decode(FriendlyByteBuf b){return new Response(b.readLong(),b.readUtf(262144));}}
    static void request(Request p,Supplier<NetworkEvent.Context> context){var c=context.get();c.enqueueWork(()->{
        ServerPlayer player=c.getSender();if(player==null||service==null)return;JsonObject result;
        try{
            long now=System.currentTimeMillis();long previous=last.getOrDefault(player.getUUID(),0L);
            if(now-previous<100)throw new IllegalArgumentException("Attendi un momento prima di riprovare");last.put(player.getUUID(),now);
            var stack=player.getMainHandItem();String item=stack.isEmpty()?"":stack.getHoverName().getString()+" ×"+stack.getCount()+" ["+ForgeRegistries.ITEMS.getKey(stack.getItem())+"]";
            var pos=player.blockPosition();var actor=new MarketService.Actor(player.getUUID().toString(),player.getGameProfile().getName(),item,player.level().dimension().location().toString(),pos.getX(),pos.getY(),pos.getZ());
            result=service.handle(actor,JsonParser.parseString(p.json).getAsJsonObject());
        }catch(IllegalArgumentException ex){result=new JsonObject();result.addProperty("ok",false);result.addProperty("error",ex.getMessage());}
        catch(Exception ex){System.getLogger("nomarket").log(System.Logger.Level.ERROR,"Richiesta mercato fallita",ex);result=new JsonObject();result.addProperty("ok",false);result.addProperty("error","Errore archivio: operazione non completata");}
        String json=result.toString();if(json.length()>262144)json="{\"ok\":false,\"error\":\"Risposta troppo grande\"}";
        NET.send(PacketDistributor.PLAYER.with(()->player),new Response(p.id,json));
    });c.setPacketHandled(true);}
}
