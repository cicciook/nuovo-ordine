package it.nuovoordine.gameplay;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Mod("nogameplay")
public final class GameplayMod {
    private static final String TAG_CIVIL = "nuovoordine_civil_rep";
    private static final String TAG_UNDERGROUND = "nuovoordine_underground_rep";
    private static final String TAG_BLEED = "nuovoordine_bleed";
    private static final String TAG_BLEED_UNTIL = "nuovoordine_bleed_until";
    private static final String TAG_MEDIC_COOLDOWN = "nuovoordine_medic_cooldown";

    static final ForgeConfigSpec SPEC;
    static final ForgeConfigSpec.DoubleValue BLEED_MIN_DAMAGE;
    static final ForgeConfigSpec.IntValue BLEED_SECONDS;
    static final ForgeConfigSpec.IntValue BLEED_INTERVAL_TICKS;
    static final ForgeConfigSpec.DoubleValue BLEED_DAMAGE_PER_LEVEL;
    static final ForgeConfigSpec.IntValue BANDAGE_TICKS;
    static final ForgeConfigSpec.DoubleValue TREATMENT_MOVE_LIMIT;
    static final ForgeConfigSpec.ConfigValue<List<? extends String>> BANDAGE_ITEMS;
    static final ForgeConfigSpec.IntValue MEDIC_REP_REQUIRED;
    static final ForgeConfigSpec.IntValue MEDIC_COST;
    static final ForgeConfigSpec.IntValue MEDIC_TICKS;
    static final ForgeConfigSpec.IntValue MEDIC_COOLDOWN_MINUTES;
    static final ForgeConfigSpec.IntValue CONVOY_RADIUS;
    static final ForgeConfigSpec.IntValue CONVOY_CLAIM_TICKS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("injuries");
        BLEED_MIN_DAMAGE = b.comment("Danno singolo minimo che puo' causare sanguinamento").defineInRange("bleedMinDamage", 6.0, 1.0, 40.0);
        BLEED_SECONDS = b.defineInRange("bleedDurationSeconds", 90, 5, 1800);
        BLEED_INTERVAL_TICKS = b.defineInRange("bleedIntervalTicks", 80, 20, 400);
        BLEED_DAMAGE_PER_LEVEL = b.defineInRange("bleedDamagePerLevel", 0.5, 0.1, 5.0);
        BANDAGE_TICKS = b.comment("Tempo di cura con benda: 60 tick = 3 secondi").defineInRange("bandageTreatmentTicks", 60, 20, 400);
        TREATMENT_MOVE_LIMIT = b.comment("Distanza massima percorribile durante una cura prima che venga interrotta").defineInRange("treatmentMoveLimit", 1.5, 0.0, 10.0);
        BANDAGE_ITEMS = b.comment("Item utilizzabili come benda. Personalizzali con gli item medici reali del pack.").defineListAllowEmpty("bandageItems", List.of("minecraft:white_wool"), value -> value instanceof String s && ResourceLocation.tryParse(s) != null);
        b.pop();

        b.push("reputation");
        MEDIC_REP_REQUIRED = b.comment("Reputazione civile richiesta per sbloccare /medico").defineInRange("medicCivilReputationRequired", 25, -1000, 1000);
        MEDIC_COST = b.comment("Costo TNE del contatto medico").defineInRange("medicCost", 2500, 0, 10_000_000);
        MEDIC_TICKS = b.comment("Tempo di trattamento del medico").defineInRange("medicTreatmentTicks", 200, 20, 1200);
        MEDIC_COOLDOWN_MINUTES = b.defineInRange("medicCooldownMinutes", 20, 0, 1440);
        b.pop();

        b.push("convoys");
        CONVOY_RADIUS = b.comment("Raggio in blocchi per arrivo e conquista del carico").defineInRange("objectiveRadius", 32, 4, 256);
        CONVOY_CLAIM_TICKS = b.comment("Tempo da mantenere l'area per conquistare il carico").defineInRange("claimTicks", 600, 20, 7200);
        b.pop();
        SPEC = b.build();
    }

    private enum TreatmentKind { BANDAGE, MEDIC }

    private record Treatment(TreatmentKind kind, long finishAt, double startX, double startY, double startZ, InteractionHand hand, String itemId) {}

    private final Map<UUID, Treatment> treatments = new HashMap<>();
    private final Set<UUID> bleedDamage = new HashSet<>();

    public GameplayMod() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC);
        MinecraftForge.EVENT_BUS.addListener(this::commands);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::damage);
        MinecraftForge.EVENT_BUS.addListener(this::rightClickItem);
        MinecraftForge.EVENT_BUS.addListener(this::playerTick);
        MinecraftForge.EVENT_BUS.addListener(this::serverTick);
        MinecraftForge.EVENT_BUS.addListener(this::login);
        MinecraftForge.EVENT_BUS.addListener(this::logout);
        MinecraftForge.EVENT_BUS.addListener(this::stopped);
    }

    private void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ferite")
                .executes(ctx -> injuryStatus(ctx.getSource().getPlayerOrException())));

        var reputation = Commands.literal("reputazione")
                .executes(ctx -> reputationStatus(ctx.getSource().getPlayerOrException()));
        var reputationAdmin = Commands.literal("admin").requires(src -> src.hasPermission(2));
        reputationAdmin.then(Commands.literal("add")
                .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("tipo", StringArgumentType.word())
                                .then(Commands.argument("valore", IntegerArgumentType.integer(-1000, 1000))
                                        .executes(ctx -> reputationAdmin(
                                                EntityArgument.getPlayer(ctx, "player"),
                                                StringArgumentType.getString(ctx, "tipo"),
                                                IntegerArgumentType.getInteger(ctx, "valore"),
                                                false))))));
        reputationAdmin.then(Commands.literal("set")
                .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("tipo", StringArgumentType.word())
                                .then(Commands.argument("valore", IntegerArgumentType.integer(-1000, 1000))
                                        .executes(ctx -> reputationAdmin(
                                                EntityArgument.getPlayer(ctx, "player"),
                                                StringArgumentType.getString(ctx, "tipo"),
                                                IntegerArgumentType.getInteger(ctx, "valore"),
                                                true))))));
        reputation.then(reputationAdmin);
        event.getDispatcher().register(reputation);

        event.getDispatcher().register(Commands.literal("medico")
                .executes(ctx -> startMedic(ctx.getSource().getPlayerOrException())));

        event.getDispatcher().register(Commands.literal("classifica")
                .then(Commands.literal("giocatori")
                        .then(Commands.argument("metrica", StringArgumentType.word())
                                .executes(ctx -> playerLeaderboard(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "metrica")))))
                .then(Commands.literal("town")
                        .then(Commands.argument("metrica", StringArgumentType.word())
                                .executes(ctx -> townLeaderboard(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "metrica"))))));

        event.getDispatcher().register(Commands.literal("convoglio")
                .then(Commands.literal("stato")
                        .executes(ctx -> convoyStatus(ctx.getSource().getPlayerOrException())))
                .then(Commands.literal("candidati")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> convoyApply(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "id")))))
                .then(Commands.literal("arrivo")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> convoyArrival(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "id")))))
                .then(Commands.literal("conquista")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> convoyClaim(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "id")))))
                .then(Commands.literal("programma").requires(src -> src.hasPermission(2))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .then(Commands.argument("minuti", IntegerArgumentType.integer(0, 1440))
                                        .then(Commands.argument("destinazione", Vec3Argument.vec3())
                                                .executes(ctx -> convoySchedule(
                                                        ctx.getSource().getPlayerOrException(),
                                                        StringArgumentType.getString(ctx, "id"),
                                                        IntegerArgumentType.getInteger(ctx, "minuti"),
                                                        Vec3Argument.getVec3(ctx, "destinazione")))))))
                .then(Commands.literal("annulla").requires(src -> src.hasPermission(2))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> convoyCancel(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "id"))))));
    }

    private int injuryStatus(ServerPlayer player) {
        int severity = bleedSeverity(player);
        if (severity <= 0) {
            player.sendSystemMessage(Component.literal("Ferite: nessun sanguinamento attivo."));
            return 1;
        }
        long ticks = Math.max(0L, player.getPersistentData().getLong(TAG_BLEED_UNTIL) - player.level().getGameTime());
        player.sendSystemMessage(Component.literal("Ferite: sanguinamento livello " + severity + " - circa " + (ticks / 20) + "s rimanenti."));
        return 1;
    }

    private int reputationStatus(ServerPlayer player) {
        player.sendSystemMessage(Component.literal("Reputazione - civile: " + civil(player) + " | clandestina: " + underground(player)));
        return 1;
    }

    private int reputationAdmin(ServerPlayer player, String type, int value, boolean set) {
        String normalized = normalizeMetric(type);
        if (!normalized.equals("civil") && !normalized.equals("underground")) {
            player.sendSystemMessage(Component.literal("Tipo reputazione non valido: usa civil oppure underground."));
            return 0;
        }
        int current = normalized.equals("civil") ? civil(player) : underground(player);
        int next = clampRep(set ? value : current + value);
        if (normalized.equals("civil")) player.getPersistentData().putInt(TAG_CIVIL, next);
        else player.getPersistentData().putInt(TAG_UNDERGROUND, next);
        updateScore(player);
        player.sendSystemMessage(Component.literal("Reputazione " + normalized + " aggiornata a " + next + "."));
        return 1;
    }

    private int startMedic(ServerPlayer player) {
        if (bleedSeverity(player) <= 0) {
            player.sendSystemMessage(Component.literal("Non hai ferite che richiedono il contatto medico."));
            return 0;
        }
        if (civil(player) < MEDIC_REP_REQUIRED.get()) {
            player.sendSystemMessage(Component.literal("Contatto medico bloccato: servono " + MEDIC_REP_REQUIRED.get() + " punti di reputazione civile."));
            return 0;
        }
        long now = player.level().getGameTime();
        long cooldown = player.getPersistentData().getLong(TAG_MEDIC_COOLDOWN);
        if (cooldown > now) {
            player.sendSystemMessage(Component.literal("Contatto medico in cooldown per altri " + ((cooldown - now + 19) / 20) + "s."));
            return 0;
        }
        if (treatments.containsKey(player.getUUID())) {
            player.sendSystemMessage(Component.literal("Hai gia' una cura in corso."));
            return 0;
        }
        treatments.put(player.getUUID(), new Treatment(TreatmentKind.MEDIC, now + MEDIC_TICKS.get(), player.getX(), player.getY(), player.getZ(), null, ""));
        player.sendSystemMessage(Component.literal("Contatto medico avviato. Resta fermo e non subire danni. Costo al completamento: $" + MEDIC_COST.get() + "."));
        return 1;
    }

    private int playerLeaderboard(ServerPlayer viewer, String rawMetric) {
        String metric = normalizeMetric(rawMetric);
        if (!validMetric(metric)) {
            viewer.sendSystemMessage(Component.literal("Metrica non valida: civil, underground oppure convoys."));
            return 0;
        }
        List<WorldData.ScoreEntry> entries = WorldData.get(viewer.server).topPlayers(metric, 10);
        viewer.sendSystemMessage(Component.literal("Classifica giocatori - " + metric + ":"));
        int pos = 1;
        for (WorldData.ScoreEntry entry : entries) {
            int value = scoreValue(entry, metric);
            viewer.sendSystemMessage(Component.literal(pos++ + ". " + entry.name + " - " + value));
        }
        return 1;
    }

    private int townLeaderboard(ServerPlayer viewer, String rawMetric) {
        String metric = normalizeMetric(rawMetric);
        if (!validMetric(metric)) {
            viewer.sendSystemMessage(Component.literal("Metrica non valida: civil, underground oppure convoys."));
            return 0;
        }
        List<WorldData.TownScore> entries = WorldData.get(viewer.server).topTowns(metric, 10);
        viewer.sendSystemMessage(Component.literal("Classifica Town - " + metric + ":"));
        int pos = 1;
        for (WorldData.TownScore entry : entries) {
            int value = switch (metric) {
                case "underground" -> entry.underground;
                case "convoys" -> entry.convoys;
                default -> entry.civil;
            };
            viewer.sendSystemMessage(Component.literal(pos++ + ". " + entry.name + " - " + value));
        }
        return 1;
    }

    private int convoySchedule(ServerPlayer admin, String id, int minutes, Vec3 destination) {
        WorldData data = WorldData.get(admin.server);
        WorldData.Convoy existing = data.convoy(id);
        if (existing != null && existing.active()) {
            admin.sendSystemMessage(Component.literal("Esiste gia' un convoglio attivo con id " + id + "."));
            return 0;
        }
        long departAt = admin.level().getGameTime() + minutes * 1200L;
        String dimension = admin.serverLevel().dimension().location().toString();
        data.createConvoy(id, departAt, dimension, (int) Math.floor(destination.x), (int) Math.floor(destination.y), (int) Math.floor(destination.z));
        broadcast(admin.server, "Convoglio " + id + " programmato: partenza tra " + minutes + " minuti. Usa /convoglio candidati " + id + " per candidarti come autista.");
        return 1;
    }

    private int convoyCancel(ServerPlayer admin, String id) {
        WorldData data = WorldData.get(admin.server);
        WorldData.Convoy convoy = data.convoy(id);
        if (convoy == null || !convoy.active()) {
            admin.sendSystemMessage(Component.literal("Convoglio non trovato o gia' concluso."));
            return 0;
        }
        convoy.state = WorldData.ConvoyState.CANCELLED;
        convoy.claimant = null;
        data.setDirty();
        broadcast(admin.server, "Convoglio " + convoy.id + " annullato dallo staff.");
        return 1;
    }

    private int convoyApply(ServerPlayer player, String id) {
        WorldData data = WorldData.get(player.server);
        WorldData.Convoy convoy = data.convoy(id);
        if (convoy == null || !convoy.active()) {
            player.sendSystemMessage(Component.literal("Convoglio non trovato o non attivo."));
            return 0;
        }
        if (convoy.state != WorldData.ConvoyState.RECRUITING && convoy.state != WorldData.ConvoyState.WAITING_DRIVER) {
            player.sendSystemMessage(Component.literal("Le candidature per questo convoglio sono chiuse."));
            return 0;
        }
        convoy.candidates.add(player.getUUID());
        data.setDirty();
        if (convoy.state == WorldData.ConvoyState.WAITING_DRIVER) {
            assignDriver(data, convoy, player);
            broadcast(player.server, player.getGameProfile().getName() + " e' diventato autista del convoglio " + convoy.id + ".");
        } else {
            player.sendSystemMessage(Component.literal("Candidatura registrata per il convoglio " + convoy.id + "."));
        }
        return 1;
    }

    private int convoyStatus(ServerPlayer player) {
        WorldData data = WorldData.get(player.server);
        int count = 0;
        for (WorldData.Convoy convoy : data.convoys()) {
            if (!convoy.active()) continue;
            count++;
            long remaining = Math.max(0L, convoy.departAt - player.level().getGameTime());
            player.sendSystemMessage(Component.literal("[" + convoy.id + "] " + convoy.state + " | partenza " + (remaining / 20) + "s | destinazione " + convoy.x + " " + convoy.y + " " + convoy.z + (convoy.driver == null ? "" : " | autista " + convoy.driverName)));
        }
        if (count == 0) player.sendSystemMessage(Component.literal("Nessun convoglio attivo."));
        return 1;
    }

    private int convoyArrival(ServerPlayer player, String id) {
        WorldData data = WorldData.get(player.server);
        WorldData.Convoy convoy = data.convoy(id);
        if (convoy == null || convoy.state != WorldData.ConvoyState.ENROUTE) {
            player.sendSystemMessage(Component.literal("Il convoglio non e' in viaggio."));
            return 0;
        }
        if (!player.getUUID().equals(convoy.driver)) {
            player.sendSystemMessage(Component.literal("Solo l'autista assegnato puo' dichiarare l'arrivo."));
            return 0;
        }
        if (!nearObjective(player, convoy)) {
            player.sendSystemMessage(Component.literal("Devi essere entro " + CONVOY_RADIUS.get() + " blocchi dalla destinazione."));
            return 0;
        }
        convoy.state = WorldData.ConvoyState.CONTESTED;
        convoy.claimant = null;
        convoy.claimStartedAt = 0L;
        data.setDirty();
        broadcast(player.server, "Il convoglio " + convoy.id + " e' arrivato. Il carico e' ora conteso: /convoglio conquista " + convoy.id + ".");
        return 1;
    }

    private int convoyClaim(ServerPlayer player, String id) {
        WorldData data = WorldData.get(player.server);
        WorldData.Convoy convoy = data.convoy(id);
        if (convoy == null || convoy.state != WorldData.ConvoyState.CONTESTED) {
            player.sendSystemMessage(Component.literal("Il carico non e' attualmente contendibile."));
            return 0;
        }
        if (!nearObjective(player, convoy)) {
            player.sendSystemMessage(Component.literal("Devi essere nell'area del carico per iniziare la conquista."));
            return 0;
        }
        if (convoy.claimant != null && !convoy.claimant.equals(player.getUUID())) {
            player.sendSystemMessage(Component.literal("Il carico e' gia' in conquista da " + convoy.claimantName + ". Allontanalo o sconfiggilo per interrompere il tentativo."));
            return 0;
        }
        convoy.claimant = player.getUUID();
        convoy.claimantName = player.getGameProfile().getName();
        convoy.claimStartedAt = player.level().getGameTime();
        data.setDirty();
        broadcast(player.server, convoy.claimantName + " sta tentando di conquistare il carico del convoglio " + convoy.id + ". Deve mantenere l'area per " + (CONVOY_CLAIM_TICKS.get() / 20) + "s.");
        return 1;
    }

    private void damage(LivingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        UUID uuid = player.getUUID();
        if (bleedDamage.remove(uuid)) return;
        if (event.getAmount() <= 0) return;

        if (treatments.remove(uuid) != null) {
            player.sendSystemMessage(Component.literal("Cura interrotta: hai subito danni."));
        }

        if (event.getAmount() < BLEED_MIN_DAMAGE.get()) return;
        int add = event.getAmount() >= BLEED_MIN_DAMAGE.get() * 2.0 ? 2 : 1;
        int severity = Math.min(3, bleedSeverity(player) + add);
        player.getPersistentData().putInt(TAG_BLEED, severity);
        long now = player.level().getGameTime();
        long until = now + BLEED_SECONDS.get() * 20L;
        player.getPersistentData().putLong(TAG_BLEED_UNTIL, Math.max(until, player.getPersistentData().getLong(TAG_BLEED_UNTIL)));
        player.displayClientMessage(Component.literal("Sanguinamento livello " + severity + ". Usa una benda e resta fermo durante la cura."), true);
    }

    private void rightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (bleedSeverity(player) <= 0) return;
        ItemStack stack = event.getItemStack();
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null || !BANDAGE_ITEMS.get().contains(id.toString())) return;
        if (treatments.containsKey(player.getUUID())) {
            player.sendSystemMessage(Component.literal("Hai gia' una cura in corso."));
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }
        long now = player.level().getGameTime();
        treatments.put(player.getUUID(), new Treatment(TreatmentKind.BANDAGE, now + BANDAGE_TICKS.get(), player.getX(), player.getY(), player.getZ(), event.getHand(), id.toString()));
        player.sendSystemMessage(Component.literal("Bendaggio avviato. Resta fermo e non subire danni."));
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    private void playerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        long now = player.level().getGameTime();
        if (now % 100L == 0L) updateScore(player);

        Treatment treatment = treatments.get(player.getUUID());
        if (treatment != null) {
            double limit = TREATMENT_MOVE_LIMIT.get();
            double dx = player.getX() - treatment.startX;
            double dy = player.getY() - treatment.startY;
            double dz = player.getZ() - treatment.startZ;
            if (dx * dx + dy * dy + dz * dz > limit * limit) {
                treatments.remove(player.getUUID());
                player.sendSystemMessage(Component.literal("Cura interrotta: ti sei mosso troppo."));
            } else if (now >= treatment.finishAt) {
                finishTreatment(player, treatment);
            }
        }

        int severity = bleedSeverity(player);
        if (severity <= 0) return;
        long until = player.getPersistentData().getLong(TAG_BLEED_UNTIL);
        if (now >= until) {
            clearBleeding(player);
            player.displayClientMessage(Component.literal("Il sanguinamento si e' fermato."), true);
            return;
        }
        if (now % BLEED_INTERVAL_TICKS.get() == 0L) {
            bleedDamage.add(player.getUUID());
            player.hurt(player.damageSources().magic(), BLEED_DAMAGE_PER_LEVEL.get().floatValue() * severity);
        }
    }

    private void finishTreatment(ServerPlayer player, Treatment treatment) {
        treatments.remove(player.getUUID());
        if (treatment.kind == TreatmentKind.BANDAGE) {
            ItemStack held = player.getItemInHand(treatment.hand);
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(held.getItem());
            if (id == null || !id.toString().equals(treatment.itemId) || held.isEmpty()) {
                player.sendSystemMessage(Component.literal("Cura annullata: non stai piu' tenendo la benda."));
                return;
            }
            if (!player.getAbilities().instabuild) held.shrink(1);
            clearBleeding(player);
            player.sendSystemMessage(Component.literal("Bendaggio completato: sanguinamento fermato."));
            return;
        }

        int cost = MEDIC_COST.get();
        if (cost > 0) {
            String command = "money take " + player.getGameProfile().getName() + " " + cost;
            int result = player.server.getCommands().performPrefixedCommand(player.server.createCommandSourceStack(), command);
            if (result <= 0) {
                player.sendSystemMessage(Component.literal("Cura medica non completata: pagamento TNE fallito. Controlla saldo o configurazione economia."));
                return;
            }
        }
        clearBleeding(player);
        long cooldown = player.level().getGameTime() + MEDIC_COOLDOWN_MINUTES.get() * 1200L;
        player.getPersistentData().putLong(TAG_MEDIC_COOLDOWN, cooldown);
        player.sendSystemMessage(Component.literal("Il contatto medico ha completato il trattamento."));
    }

    private void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        long now = server.overworld().getGameTime();
        WorldData data = WorldData.get(server);
        boolean dirty = false;
        for (WorldData.Convoy convoy : data.convoys()) {
            if (!convoy.active()) continue;
            if (convoy.state == WorldData.ConvoyState.RECRUITING && now >= convoy.departAt) {
                ServerPlayer driver = firstOnlineCandidate(server, convoy);
                if (driver == null) {
                    convoy.state = WorldData.ConvoyState.WAITING_DRIVER;
                    broadcast(server, "Convoglio " + convoy.id + " pronto ma senza autista. Le candidature restano aperte.");
                } else {
                    assignDriver(data, convoy, driver);
                    broadcast(server, "Convoglio " + convoy.id + " partito. Autista: " + convoy.driverName + ".");
                }
                dirty = true;
            }
            if (convoy.state == WorldData.ConvoyState.CONTESTED && convoy.claimant != null) {
                ServerPlayer claimant = server.getPlayerList().getPlayer(convoy.claimant);
                if (claimant == null || !claimant.isAlive() || !nearObjective(claimant, convoy)) {
                    broadcast(server, "Conquista del carico " + convoy.id + " interrotta.");
                    convoy.claimant = null;
                    convoy.claimantName = "";
                    convoy.claimStartedAt = 0L;
                    dirty = true;
                } else if (now - convoy.claimStartedAt >= CONVOY_CLAIM_TICKS.get()) {
                    convoy.state = WorldData.ConvoyState.COMPLETE;
                    data.addConvoyWin(claimant);
                    updateScore(claimant);
                    broadcast(server, claimant.getGameProfile().getName() + " ha conquistato il carico del convoglio " + convoy.id + ".");
                    dirty = true;
                }
            }
        }
        if (dirty) data.setDirty();
    }

    private void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) updateScore(player);
    }

    private void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        treatments.remove(event.getEntity().getUUID());
        bleedDamage.remove(event.getEntity().getUUID());
    }

    private void stopped(ServerStoppedEvent event) {
        treatments.clear();
        bleedDamage.clear();
    }

    private void assignDriver(WorldData data, WorldData.Convoy convoy, ServerPlayer player) {
        convoy.driver = player.getUUID();
        convoy.driverName = player.getGameProfile().getName();
        convoy.state = WorldData.ConvoyState.ENROUTE;
        data.setDirty();
    }

    private ServerPlayer firstOnlineCandidate(MinecraftServer server, WorldData.Convoy convoy) {
        for (UUID uuid : convoy.candidates) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null && player.isAlive()) return player;
        }
        return null;
    }

    private boolean nearObjective(ServerPlayer player, WorldData.Convoy convoy) {
        if (!player.serverLevel().dimension().location().toString().equals(convoy.dimension)) return false;
        double radius = CONVOY_RADIUS.get();
        return player.distanceToSqr(convoy.x + 0.5, convoy.y + 0.5, convoy.z + 0.5) <= radius * radius;
    }

    private void updateScore(ServerPlayer player) {
        WorldData.get(player.server).updatePlayer(player, civil(player), underground(player), TownyBridge.town(player));
    }

    private static int civil(ServerPlayer player) {
        return player.getPersistentData().getInt(TAG_CIVIL);
    }

    private static int underground(ServerPlayer player) {
        return player.getPersistentData().getInt(TAG_UNDERGROUND);
    }

    private static int clampRep(int value) {
        return Math.max(-1000, Math.min(1000, value));
    }

    private static int bleedSeverity(ServerPlayer player) {
        return Math.max(0, Math.min(3, player.getPersistentData().getInt(TAG_BLEED)));
    }

    private static void clearBleeding(ServerPlayer player) {
        player.getPersistentData().remove(TAG_BLEED);
        player.getPersistentData().remove(TAG_BLEED_UNTIL);
    }

    private static String normalizeMetric(String metric) {
        String value = metric.toLowerCase(Locale.ROOT);
        return switch (value) {
            case "civile", "civil" -> "civil";
            case "clandestina", "clandestino", "underground", "criminale" -> "underground";
            case "convogli", "convoglio", "convoys" -> "convoys";
            default -> value;
        };
    }

    private static boolean validMetric(String metric) {
        return metric.equals("civil") || metric.equals("underground") || metric.equals("convoys");
    }

    private static int scoreValue(WorldData.ScoreEntry entry, String metric) {
        return switch (metric) {
            case "underground" -> entry.underground;
            case "convoys" -> entry.convoysWon;
            default -> entry.civil;
        };
    }

    private static void broadcast(MinecraftServer server, String message) {
        server.getPlayerList().broadcastSystemMessage(Component.literal(message), false);
    }
}
