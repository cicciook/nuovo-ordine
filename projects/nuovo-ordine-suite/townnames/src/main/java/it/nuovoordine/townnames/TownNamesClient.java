package it.nuovoordine.townnames;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.client.event.RenderNameTagEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import java.util.*;
@Mod.EventBusSubscriber(modid="notownnames",value=Dist.CLIENT)
public class TownNamesClient {
    static Map<UUID,String> names=Map.of();
    @SubscribeEvent public static void render(RenderNameTagEvent e){if(e.getEntity() instanceof Player p){String name=names.get(p.getUUID());if(name!=null)e.setContent(e.getContent().copy().append(Component.literal(" ["+name+"]").withStyle(ChatFormatting.GOLD)));}}
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut e){names=Map.of();}
}
