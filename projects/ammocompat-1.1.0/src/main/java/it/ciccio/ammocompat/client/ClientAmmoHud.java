package it.ciccio.ammocompat.client;

import com.atsuishio.superbwarfare.data.gun.Ammo;
import com.tacz.guns.api.item.IGun;
import it.ciccio.ammocompat.AmmoCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = AmmoCompat.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientAmmoHud {
    private ClientAmmoHud() {}

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        AmmoCompat.clearClientSuperbAmmoCounts();
    }

    @SubscribeEvent
    public static void render(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.screen != null) {
            return;
        }

        ItemStack gun = mc.player.getMainHandItem();
        if (gun.isEmpty() || IGun.getIGunOrNull(gun) == null) {
            return;
        }

        Ammo type = AmmoCompat.compatibleSuperbType(gun);
        if (type == null) {
            return;
        }

        int ammo = AmmoCompat.compatibleSuperbAmmoCount(mc.player, gun);
        String text = type.displayName + ": " + ammo;

        GuiGraphics graphics = event.getGuiGraphics();
        Font font = mc.font;
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        // Above the TaCZ ammo counter in the lower-right corner.
        int x = screenWidth - font.width(text) - 18;
        int y = screenHeight - 72;
        int color = type.color.getColor() == null ? 0xFFFFFF : type.color.getColor();

        graphics.fill(x - 4, y - 3, x + font.width(text) + 4, y + font.lineHeight + 3, 0x66000000);
        graphics.drawString(font, text, x, y, color, true);
    }
}
