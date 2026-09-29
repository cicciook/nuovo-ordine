package it.nuovoordine.market;
import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Mod.EventBusSubscriber(modid="nomarket",value=Dist.CLIENT)
public class MarketClient {
    static Object browser;static Path page;static Object handler;static int ticks;
    static final Map<Long,Long> pending=new HashMap<>();
    static void prepare()throws Exception{
        if(handler!=null)return;
        Object client=Class.forName("com.cinemamod.mcef.MCEF").getMethod("getClient").invoke(null);
        Class<?> type=Class.forName("org.cef.handler.CefDisplayHandler");
        handler=Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},(proxy,m,a)->{
            if(m.getName().equals("hashCode"))return System.identityHashCode(proxy);
            if(m.getName().equals("equals"))return proxy==a[0];
            if(m.getName().equals("toString"))return "NuovoOrdineMarketBridge";
            if(m.getName().equals("onConsoleMessage")&&a!=null&&a.length>=3&&trusted(a[0])){
                String text=String.valueOf(a[2]);if(text.startsWith("__NOMARKET__")){
                    if(text.length()>16000)return true;
                    try{String[] parts=text.substring(12).split(":",2);long id=Long.parseLong(parts[0]);String body=new String(Base64.getDecoder().decode(parts[1]),StandardCharsets.UTF_8);
                        if(body.length()>8192||id<=0)return true;
                        Minecraft.getInstance().execute(()->{JsonObject request=JsonParser.parseString(body).getAsJsonObject();
                            if(request.has("action")&&request.get("action").getAsString().equals("waypoint")){
                                JsonObject result=new JsonObject();result.addProperty("ok",true);result.addProperty("notice",XaeroMeetings.enable(request.get("id").getAsString()));resolve(id,result.toString());
                            }else if(pending.size()<32){pending.put(id,System.currentTimeMillis());MarketMod.NET.sendToServer(new MarketMod.Request(id,body));}});
                    }catch(Exception ignored){}return true;
                }
            }
            return m.getReturnType()==boolean.class?false:null;
        });
        client.getClass().getMethod("addDisplayHandler",type).invoke(client,handler);
    }
    static boolean trusted(Object b){
        if(browser==null||page==null)return false;
        try{return b.getClass().getMethod("getIdentifier").invoke(b).equals(browser.getClass().getMethod("getIdentifier").invoke(browser))&&page.toUri().toASCIIString().equals(b.getClass().getMethod("getURL").invoke(b));}catch(Exception e){return false;}
    }
    static void bind(Object b,Path p){browser=b;page=p.toAbsolutePath().normalize();pending.clear();}
    static void receive(MarketMod.Response packet){
        if(packet.id()==-1){try{
            Path p=Minecraft.getInstance().gameDirectory.toPath().resolve("config/nuovoordine-market/index.html");Files.createDirectories(p.getParent());
            try(var stream=MarketClient.class.getResourceAsStream("/market/index.html")){Files.copy(Objects.requireNonNull(stream),p,StandardCopyOption.REPLACE_EXISTING);}BrowserBridge.open(p);
        }catch(Exception ex){BrowserBridge.reportError("Mercato: "+ex.getMessage());}return;}
        JsonObject response=JsonParser.parseString(packet.json()).getAsJsonObject();
        if(response.has("meetings"))XaeroMeetings.sync(response.getAsJsonArray("meetings"),response.get("now").getAsLong());
        if(packet.id()==0)return;
        if(pending.remove(packet.id())!=null)resolve(packet.id(),packet.json());
    }
    static void resolve(long id,String json){
        if(browser==null)return;String payload=Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        try{browser.getClass().getMethod("executeJavaScript",String.class,String.class,int.class).invoke(browser,"window.resolveMarket&&window.resolveMarket("+id+",'"+payload+"');",page.toUri().toASCIIString(),0);}catch(Exception ignored){}
    }
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent e){
        if(e.phase!=TickEvent.Phase.END)return;
        var mc=Minecraft.getInstance();
        if(mc.player==null){browser=null;pending.clear();XaeroMeetings.clear();return;}
        if(++ticks%100==0&&MarketMod.NET.isRemotePresent(mc.getConnection().getConnection()))MarketMod.NET.sendToServer(new MarketMod.Request(0,"{\"action\":\"meetings\"}"));
        XaeroMeetings.tick();
        pending.entrySet().removeIf(x->{if(System.currentTimeMillis()-x.getValue()>15000){resolve(x.getKey(),"{\"ok\":false,\"error\":\"Il server non ha risposto\"}");return true;}return false;});
    }
}
