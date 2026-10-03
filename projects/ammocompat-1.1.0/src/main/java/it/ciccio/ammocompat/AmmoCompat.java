package it.ciccio.ammocompat;

import com.atsuishio.superbwarfare.data.gun.Ammo;
import com.atsuishio.superbwarfare.item.gun.GunItem;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.init.ModItems;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Locale;
import java.util.Set;

@Mod(AmmoCompat.MODID)
public class AmmoCompat {
    public static final String MODID = "ammocompat";

    private static final Set<String> HANDGUN_AMMO = Set.of(
            "tacz:9mm", "tacz:45acp", "tacz:57x28", "tacz:46x30",
            "tacz:357mag", "tacz:500mag", "tacz:50ae",
            "ea:10mm", "ea:27kolibri", "ea:30mauser", "ea:32acp",
            "ea:357sig", "ea:380auto", "ea:40sw", "ea:44magnum",
            "ea:44special", "ea:454casull", "ea:50gi", "ea:58x21",
            "ea:75fk", "ea:9x18", "ea:9x21",
            "sfms:30scp"
    );

    private static final Set<String> SHOTGUN_AMMO = Set.of(
            "tacz:12g",
            "ea:10g", "ea:20g", "ea:410bore", "ea:4g",
            "maxstuff:12g_db", "maxstuff:12g_fl",
            "sfms:12ap"
    );

    private static final Set<String> RIFLE_AMMO = Set.of(
            "tacz:556x45", "tacz:762x39", "tacz:545x39",
            "tacz:58x42", "tacz:68x51fury",
            "apdf:0950x38", "apdf:1163x39",
            "ea:300blk", "ea:50beowulf", "ea:602x41", "ea:9x39",
            "ea:17hmr", "ea:22hornet", "ea:22lr", "ea:3030win",
            "ea:366magnum", "ea:366tkm", "ea:458hamr", "ea:458socom",
            "ea:458winmag", "ea:556x30", "ea:68tvcm", "ea:6arc",
            "ea:792x33", "ea:86blk",
            "sfms:300ms", "sfms:c812", "sfms:c901", "sfms:m995"
    );

    private static final Set<String> SNIPER_AMMO = Set.of(
            "tacz:308", "tacz:792x57", "tacz:22wmr", "tacz:30_06",
            "tacz:45_70", "tacz:762x54",
            "ea:127x55", "ea:300winmag", "ea:65creedmoor", "ea:792x57"
    );

    private static final Set<String> BLOCKED_AMMO = Set.of(
            "tacz:338", "tacz:50bmg", "tacz:40mm", "tacz:rpg_rocket",
            "ea:127x108", "ea:145x114", "ea:20x102", "ea:338arc",
            "ea:338norma", "ea:408cheytac", "ea:416barrett", "ea:950jdj",
            "maxstuff:bannana", "maxstuff:laser", "maxstuff:nails",
            "maxstuff:can_blanks",
            "sfms:25gl", "sfms:408", "sfms:50arms", "sfms:50ich",
            "sfms:arrow117", "sfms:inf"
    );

    public AmmoCompat() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void playerTick(TickEvent.PlayerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || e.player.level().isClientSide || e.player.tickCount % 4 != 0) {
            return;
        }

        Player player = e.player;
        ItemStack held = player.getMainHandItem();
        if (held.getItem() instanceof GunItem) {
            feedSuperbWarfare(player, held);
        } else if (IGun.getIGunOrNull(held) != null) {
            feedTacz(player, held);
        }
    }

    private static void feedSuperbWarfare(Player player, ItemStack gun) {
        Ammo type = sbwType(gun);
        if (type == null || type.get(player) > 0) {
            return;
        }

        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            IAmmo ammo = IAmmo.getIAmmoOrNull(stack);
            if (ammo == null) {
                continue;
            }

            ResourceLocation id = ammo.getAmmoId(stack);
            if (id != null && matches(type, id)) {
                int amount = Math.min(stack.getCount(), 64);
                stack.shrink(amount);
                type.add(player, amount);
                player.getInventory().setChanged();
                return;
            }
        }
    }

    private static void feedTacz(Player player, ItemStack gun) {
        IGun taczGun = IGun.getIGunOrNull(gun);
        if (taczGun == null || taczGun.hasInventoryAmmo(player, gun, false)) {
            return;
        }

        ResourceLocation gunId = taczGun.getGunId(gun);
        ResourceLocation ammoId = TimelessAPI.getCommonGunIndex(gunId)
                .map(index -> index.getGunData().getAmmoId())
                .orElse(null);
        if (ammoId == null) {
            return;
        }

        Ammo type = fromTacz(ammoId);
        if (type == null || type.get(player) <= 0) {
            return;
        }

        int amount = Math.min(type.get(player), 64);
        if (amount <= 0) {
            return;
        }

        ItemStack ammoStack = new ItemStack(ModItems.AMMO.get(), amount);
        IAmmo ammo = IAmmo.getIAmmoOrNull(ammoStack);
        if (ammo == null) {
            return;
        }

        ammo.setAmmoId(ammoStack, ammoId);
        type.add(player, -amount);
        if (!player.getInventory().add(ammoStack)) {
            player.drop(ammoStack, false);
        }
    }

    private static Ammo sbwType(ItemStack gun) {
        String className = gun.getItem().getClass().getName().toLowerCase(Locale.ROOT);
        if (className.contains(".handgun.") || className.contains(".smg.")) {
            return Ammo.HANDGUN;
        }
        if (className.contains(".shotgun.")) {
            return Ammo.SHOTGUN;
        }
        if (className.contains(".rifle.")) {
            return Ammo.RIFLE;
        }
        if (className.contains(".sniper.")) {
            return Ammo.SNIPER;
        }
        return null;
    }

    private static Ammo fromTacz(ResourceLocation id) {
        if (id == null) {
            return null;
        }

        String key = id.toString().toLowerCase(Locale.ROOT);
        if (BLOCKED_AMMO.contains(key)) {
            return null;
        }
        if (HANDGUN_AMMO.contains(key)) {
            return Ammo.HANDGUN;
        }
        if (SHOTGUN_AMMO.contains(key)) {
            return Ammo.SHOTGUN;
        }
        if (SNIPER_AMMO.contains(key)) {
            return Ammo.SNIPER;
        }
        if (RIFLE_AMMO.contains(key)) {
            return Ammo.RIFLE;
        }

        String s = id.getPath().toLowerCase(Locale.ROOT);
        if (containsAny(s,
                "rpg", "rocket", "grenade", "explosive", "40mm", "25gl",
                "50bmg", "338", "408cheytac", "416barrett", "950jdj",
                "20x102", "145x114", "127x108", "laser", "arrow", "nails")) {
            return null;
        }
        if (containsAny(s, "12g", "12_gauge", "10g", "20g", "410bore", "shotgun")) {
            return Ammo.SHOTGUN;
        }
        if (containsAny(s,
                "9mm", "45acp", "357", "10mm", "30mauser", "32acp",
                "380auto", "40sw", "44magnum", "44special", "454casull",
                "50gi", "50ae", "57x28", "46x30", "58x21", "75fk",
                "9x18", "9x21", "30scp", "handgun", "pistol")) {
            return Ammo.HANDGUN;
        }
        if (containsAny(s,
                "308", "792x57", "7.92x57", "30_06", "300winmag",
                "65creedmoor", "762x54", "7.62x54", "22wmr", "45_70",
                "sniper")) {
            return Ammo.SNIPER;
        }
        if (containsAny(s,
                "556", "5.56", "545", "5.45", "762x39", "7.62x39",
                "300blk", "50beowulf", "602x41", "9x39", "17hmr",
                "22hornet", "22lr", "3030win", "366", "458", "556x30",
                "68tvcm", "68x51", "6arc", "792x33", "86blk", "58x42",
                "rifle")) {
            return Ammo.RIFLE;
        }
        return null;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(Ammo type, ResourceLocation id) {
        return fromTacz(id) == type;
    }
}
