package it.nuovoordine.townnames;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.server.ServerLifecycleHooks;
import java.util.*;

@Mod("notownnames")
public class TownNamesMod {
    static final SimpleChannel NET=NetworkRegistry.newSimpleChannel(new ResourceLocation("notownnames","main"),()->"1",v->true,"1"::equals);
    static final ForgeConfigSpec.ConfigValue<String> MODE;
    static {var b=new ForgeConfigSpec.Builder();MODE=b.comment("town, nation oppure both").define("display","both");SPEC=b.build();}
    static final ForgeConfigSpec SPEC;
    static Map<UUID,String> names=Map.of();int ticks;boolean warned;
    public TownNamesMod(){
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON,SPEC);
        NET.registerMessage(0,Tags.class,Tags::encode,Tags::decode,(p,c)->{c.get().enqueueWork(()->TownNamesClient.names=p.values);c.get().setPacketHandled(true);},Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        MinecraftForge.EVENT_BUS.addListener(this::tick);MinecraftForge.EVENT_BUS.addListener(this::stop);
    }
    void stop(ServerStoppedEvent e){names=Map.of();ticks=0;warned=false;}
    void tick(TickEvent.ServerTickEvent e){if(e.phase!=TickEvent.Phase.END||++ticks%100!=0)return;MinecraftServer server=ServerLifecycleHooks.getCurrentServer();if(server==null)return;
        Map<UUID,String> result=new HashMap<>();
        try{
            Class<?> bukkit=Class.forName("org.bukkit.Bukkit");Object pm=bukkit.getMethod("getPluginManager").invoke(null);Object plugin=pm.getClass().getMethod("getPlugin",String.class).invoke(pm,"Towny");
            if(plugin!=null){Class<?> apiClass=Class.forName("com.palmergames.bukkit.towny.TownyAPI",true,plugin.getClass().getClassLoader());Object api=apiClass.getMethod("getInstance").invoke(null);
                for(var p:server.getPlayerList().getPlayers()){
                    Object resident=apiClass.getMethod("getResident",UUID.class).invoke(api,p.getUUID());if(resident==null)continue;
                    Object town=resident.getClass().getMethod("getTownOrNull").invoke(resident);if(town==null)continue;
                    String t=clean(String.valueOf(town.getClass().getMethod("getName").invoke(town)));Object nation=town.getClass().getMethod("getNationOrNull").invoke(town);
                    String n=nation==null?"":clean(String.valueOf(nation.getClass().getMethod("getName").invoke(nation)));
                    String label=switch(MODE.get()){case "town"->t;case "nation"->n;default->t+(n.isBlank()?"":" • "+n);};if(!label.isBlank())result.put(p.getUUID(),label);
                }
            }
        }catch(Exception ex){if(!warned){System.getLogger("notownnames").log(System.Logger.Level.WARNING,"Towny non disponibile: nessun prefisso aggiunto",ex);warned=true;}}
        names=Map.copyOf(result);Tags packet=new Tags(names);
        for(var player:server.getPlayerList().getPlayers())if(NET.isRemotePresent(player.connection.connection))NET.send(PacketDistributor.PLAYER.with(()->player),packet);
    }
    static String clean(String s){return s.replaceAll("[\\p{Cntrl}§]","").substring(0,Math.min(60,s.replaceAll("[\\p{Cntrl}§]","").length()));}
    record Tags(Map<UUID,String> values){static void encode(Tags p,FriendlyByteBuf b){b.writeVarInt(p.values.size());p.values.forEach((id,name)->{b.writeUUID(id);b.writeUtf(name,160);});}static Tags decode(FriendlyByteBuf b){int n=b.readVarInt();if(n<0||n>2000)throw new IllegalArgumentException("Too many tags");Map<UUID,String> map=new HashMap<>();for(int i=0;i<n;i++)map.put(b.readUUID(),b.readUtf(160));return new Tags(Map.copyOf(map));}}
}
