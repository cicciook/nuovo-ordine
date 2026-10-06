package it.ciccio.ammocompat;

import com.atsuishio.superbwarfare.data.gun.Ammo;
import com.atsuishio.superbwarfare.item.gun.GunItem;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.config.sync.SyncConfig;
import com.tacz.guns.entity.shooter.ShooterDataHolder;
import com.tacz.guns.network.NetworkHandler;
import com.tacz.guns.network.message.ServerMessageSyncBaseTimestamp;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Locale;
import java.util.Set;

@Mod(AmmoCompat.MODID)
public class AmmoCompat {
    public static final String MODID = "ammocompat";
    private static final System.Logger LOG = System.getLogger("ammocompat");
    private static volatile boolean shootCompatibilityApplied;
    private static final long MAX_FUTURE_SHOOT_TIMESTAMP_MS = 750L;

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

    private static final Set<String> HEAVY_AMMO = Set.of(
            "tacz:338", "tacz:50bmg",
            "ea:127x108", "ea:145x114", "ea:20x102", "ea:338arc",
            "ea:338norma", "ea:408cheytac", "ea:416barrett", "ea:950jdj",
            "sfms:408", "sfms:50arms", "sfms:50ich"
    );

    private static final Set<String> BLOCKED_AMMO = Set.of(
            "tacz:40mm", "tacz:rpg_rocket",
            "maxstuff:bannana", "maxstuff:laser", "maxstuff:nails",
            "maxstuff:can_blanks",
            "sfms:25gl", "sfms:arrow117", "sfms:inf"
    );

    public AmmoCompat() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void serverStarted(ServerStartedEvent e) {
        applyTaczShootCompatibility();
    }

    @SubscribeEvent
    public void playerLoggedIn(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity().level().isClientSide) {
            return;
        }
        Player player = e.getEntity();
        try {
            IGunOperator operator = IGunOperator.fromLivingEntity(player);
            operator.initialData();
            NetworkHandler.sendToClientPlayer(new ServerMessageSyncBaseTimestamp(), player);
            LOG.log(System.Logger.Level.INFO,
                    "TaCZ: stato arma e base timestamp sincronizzati al login di " + player.getGameProfile().getName());
        } catch (Throwable problem) {
            LOG.log(System.Logger.Level.WARNING,
                    "TaCZ: sync iniziale stato arma non riuscita; verra ritentata al tick.", problem);
        }
    }

    @SubscribeEvent
    public void playerTick(TickEvent.PlayerTickEvent e) {
        if (e.player.level().isClientSide || e.phase != TickEvent.Phase.END) {
            return;
        }
        if (!shootCompatibilityApplied) {
            applyTaczShootCompatibility();
        }

        Player player = e.player;
        repairTaczState(player);

        ItemStack held = player.getMainHandItem();
        if (held.getItem() instanceof GunItem && e.player.tickCount % 4 == 0) {
            feedSuperbWarfare(player, held);
        }
    }

    private static void repairTaczState(Player player) {
        try {
            IGunOperator operator = IGunOperator.fromLivingEntity(player);
            ShooterDataHolder data = operator.getDataHolder();

            // Mohist può lasciare TaCZ senza currentGunItem appena il player entra.
            // In quel caso il primo sparo viene rifiutato finché un'altra azione (es. melee)
            // non forza una sincronizzazione. Inizializziamo noi lo stato.
            if (data.currentGunItem == null) {
                operator.initialData();
            }

            // Con ServerShootNetworkCheck disabilitato TaCZ accetta il timestamp client.
            // Se client/server hanno baseTimestamp fuori sync, il timestamp può risultare
            // nel futuro e getShootCoolDown() resta positivo per molto tempo: il client
            // blocca reload e melee finché un cambio slot non resetta ShooterDataHolder.
            long nowRelative = System.currentTimeMillis() - data.baseTimestamp;
            if (data.shootTimestamp > nowRelative + MAX_FUTURE_SHOOT_TIMESTAMP_MS) {
                long badTimestamp = data.shootTimestamp;
                data.shootTimestamp = nowRelative;
                if (data.lastShootTimestamp > nowRelative) {
                    data.lastShootTimestamp = -1L;
                }
                LOG.log(System.Logger.Level.WARNING,
                        "TaCZ/Mohist: corretto timestamp sparo fuori sync (" + badTimestamp
                                + " -> " + nowRelative + ") per " + player.getGameProfile().getName());
            }
        } catch (Throwable problem) {
            LOG.log(System.Logger.Level.DEBUG, "TaCZ: controllo stato sparo non disponibile.", problem);
        }
    }

    private static synchronized void applyTaczShootCompatibility() {
        if (shootCompatibilityApplied) {
            return;
        }
        try {
            if (SyncConfig.SERVER_SHOOT_NETWORK_V == null) {
                return;
            }
            SyncConfig.SERVER_SHOOT_NETWORK_V.set(false);
            shootCompatibilityApplied = !SyncConfig.SERVER_SHOOT_NETWORK_V.get();
            if (shootCompatibilityApplied) {
                LOG.log(System.Logger.Level.INFO,
                        "TaCZ ServerShootNetworkCheck disabilitato: lo sparo e disponibile senza premere V.");
            }
        } catch (Throwable problem) {
            LOG.log(System.Logger.Level.WARNING,
                    "Impossibile applicare il fix ServerShootNetworkCheck di TaCZ; verra ritentato.", problem);
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

    public static Ammo compatibleSuperbType(ItemStack gun) {
        IGun taczGun = IGun.getIGunOrNull(gun);
        if (taczGun == null) {
            return null;
        }
        ResourceLocation gunId = taczGun.getGunId(gun);
        ResourceLocation ammoId = TimelessAPI.getCommonGunIndex(gunId)
                .map(index -> index.getGunData().getAmmoId())
                .orElse(null);
        return typeForTaczGun(gunId, ammoId);
    }

    public static boolean hasCompatibleSuperbAmmo(net.minecraft.world.entity.LivingEntity shooter, ItemStack gun) {
        if (shooter == null) {
            return false;
        }
        return shooter.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER, null)
                .map(cap -> hasCompatibleSuperbAmmo(cap, gun))
                .orElse(false);
    }

    public static boolean hasCompatibleSuperbAmmo(IItemHandler itemHandler, ItemStack gun) {
        Ammo type = compatibleSuperbType(gun);
        if (type == null || itemHandler == null) {
            return false;
        }
        Object expected = type.getItem();
        for (int i = 0; i < itemHandler.getSlots(); i++) {
            ItemStack stack = itemHandler.getStackInSlot(i);
            if (!stack.isEmpty() && stack.getItem() == expected && stack.getCount() > 0) {
                return true;
            }
        }
        return false;
    }

    public static int extractCompatibleSuperbAmmo(IItemHandler itemHandler, ItemStack gun, int requested) {
        if (requested <= 0 || itemHandler == null) {
            return 0;
        }
        Ammo type = compatibleSuperbType(gun);
        if (type == null) {
            return 0;
        }
        Object expected = type.getItem();
        int remaining = requested;
        for (int i = 0; i < itemHandler.getSlots() && remaining > 0; i++) {
            ItemStack stack = itemHandler.getStackInSlot(i);
            if (stack.isEmpty() || stack.getItem() != expected) {
                continue;
            }
            ItemStack extracted = itemHandler.extractItem(i, remaining, false);
            remaining -= extracted.getCount();
        }
        return requested - remaining;
    }

    static Ammo typeForTaczGun(ResourceLocation gunId, ResourceLocation ammoId) {
        if (ammoId == null) {
            return null;
        }

        String ammo = ammoId.toString().toLowerCase(Locale.ROOT);
        String path = ammoId.getPath().toLowerCase(Locale.ROOT);
        if (BLOCKED_AMMO.contains(ammo) || containsAny(path,
                "rpg", "rocket", "grenade", "explosive", "40mm", "25gl",
                "laser", "arrow", "nails", "missile", "mortar")) {
            return null;
        }
        if (HEAVY_AMMO.contains(ammo) || containsAny(path,
                "50bmg", "127x99", "12.7x99", "127x108", "12.7x108",
                "145x114", "14.5x114", "20x102", "338", "408cheytac",
                "416barrett", "950jdj", "50arms", "50ich")) {
            return Ammo.HEAVY;
        }

        Ammo known = fromTacz(ammoId);
        if (known != null) {
            return known;
        }

        // Gunpacks can add completely new AmmoIds. TaCZ still exposes the gun family,
        // so use it as the authoritative fallback instead of maintaining a fixed caliber list.
        try {
            String gunType = TimelessAPI.getCommonGunIndex(gunId)
                    .map(index -> index.getType())
                    .orElse("")
                    .toLowerCase(Locale.ROOT);
            return switch (gunType) {
                case "pistol", "smg" -> Ammo.HANDGUN;
                case "shotgun" -> Ammo.SHOTGUN;
                case "sniper" -> Ammo.SNIPER;
                case "rifle", "mg" -> Ammo.RIFLE;
                default -> null;
            };
        } catch (Throwable ignored) {
            return null;
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
        if (HEAVY_AMMO.contains(key)) {
            return Ammo.HEAVY;
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
                "laser", "arrow", "nails", "missile", "mortar")) {
            return null;
        }
        if (containsAny(s,
                "50bmg", "127x99", "12.7x99", "127x108", "12.7x108",
                "145x114", "14.5x114", "20x102", "338", "408cheytac",
                "416barrett", "950jdj", "50arms", "50ich")) {
            return Ammo.HEAVY;
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
