package it.nuovoordine.cosmetics;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import com.mojang.blaze3d.platform.NativeImage;
import java.nio.file.*;
import java.io.*;
import java.util.*;

@Mod.EventBusSubscriber(modid="nocosmetics",value=Dist.CLIENT)
public class CosmeticsClient {
    public record Textures(ResourceLocation skin,ResourceLocation cape,boolean slim){}
    public static final Map<UUID,Textures> TEXTURES=new HashMap<>();static boolean sent;static int ticks;
    static byte[] read(Path p)throws IOException{if(!Files.isRegularFile(p))return new byte[0];if(Files.size(p)>32768)throw new IOException("PNG troppo grande");return Files.readAllBytes(p);}
    @SubscribeEvent public static void login(ClientPlayerNetworkEvent.LoggingIn e){sent=false;ticks=0;}
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent e){var mc=Minecraft.getInstance();if(e.phase!=TickEvent.Phase.END||mc.player==null||sent||++ticks<40)return;sent=true;
        if(!CosmeticsMod.NET.isRemotePresent(mc.getConnection().getConnection()))return;
        try{Path root=mc.gameDirectory.toPath().resolve("config/nuovoordine-cosmetics");boolean slim=Files.exists(root.resolve("slim"));CosmeticsMod.NET.sendToServer(new CosmeticsMod.Upload(read(root.resolve("skin.png")),read(root.resolve("cape.png")),slim));}
        catch(Exception ex){mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("Skin/mantello non caricati: "+ex.getMessage()),false);}
    }
    static ResourceLocation texture(UUID id,String kind,byte[] png)throws Exception{
        if(png.length==0)return null;
        NativeImage image=NativeImage.read(new ByteArrayInputStream(png));
        if(kind.equals("skin")) {
            opaque(image,0,0,32,16);opaque(image,0,16,64,32);opaque(image,16,48,48,64);
        }
        var texture=new DynamicTexture(image);ResourceLocation location=new ResourceLocation("nocosmetics",kind+"/"+id);Minecraft.getInstance().getTextureManager().register(location,texture);return location;
    }
    static void opaque(NativeImage image,int x1,int y1,int x2,int y2){for(int x=x1;x<x2;x++)for(int y=y1;y<y2;y++)image.setPixelRGBA(x,y,image.getPixelRGBA(x,y)|0xff000000);}
    static void release(Textures t){if(t==null)return;var manager=Minecraft.getInstance().getTextureManager();if(t.skin!=null)manager.release(t.skin);if(t.cape!=null)manager.release(t.cape);}
    static void receive(CosmeticsMod.Appearance p){
        release(TEXTURES.remove(p.id()));ResourceLocation skin=null,cape=null;
        try{skin=texture(p.id(),"skin",p.skin());cape=texture(p.id(),"cape",p.cape());TEXTURES.put(p.id(),new Textures(skin,cape,p.slim()));}
        catch(Exception ex){release(new Textures(skin,cape,p.slim()));System.getLogger("nocosmetics").log(System.Logger.Level.WARNING,"Texture non caricata",ex);}
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut e){TEXTURES.values().forEach(CosmeticsClient::release);TEXTURES.clear();sent=false;}
}
