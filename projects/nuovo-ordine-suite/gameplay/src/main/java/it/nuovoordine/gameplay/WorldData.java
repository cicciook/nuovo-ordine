package it.nuovoordine.gameplay;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class WorldData extends SavedData {
    static final String ID = "nuovoordine_gameplay";

    static final class ScoreEntry {
        UUID uuid;
        String name = "?";
        String town = "";
        int civil;
        int underground;
        int convoysWon;

        ScoreEntry(UUID uuid) {
            this.uuid = uuid;
        }
    }

    enum ConvoyState {
        RECRUITING,
        WAITING_DRIVER,
        ENROUTE,
        CONTESTED,
        COMPLETE,
        CANCELLED
    }

    static final class Convoy {
        String id;
        long departAt;
        String dimension;
        int x;
        int y;
        int z;
        ConvoyState state = ConvoyState.RECRUITING;
        final Set<UUID> candidates = new LinkedHashSet<>();
        UUID driver;
        String driverName = "";
        UUID claimant;
        String claimantName = "";
        long claimStartedAt;

        Convoy(String id) {
            this.id = id;
        }

        boolean active() {
            return state != ConvoyState.COMPLETE && state != ConvoyState.CANCELLED;
        }
    }

    private final Map<UUID, ScoreEntry> scores = new LinkedHashMap<>();
    private final Map<String, Convoy> convoys = new LinkedHashMap<>();

    static WorldData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(WorldData::load, WorldData::new, ID);
    }

    static WorldData load(CompoundTag root) {
        WorldData data = new WorldData();
        ListTag scoreList = root.getList("scores", Tag.TAG_COMPOUND);
        for (int i = 0; i < scoreList.size(); i++) {
            CompoundTag tag = scoreList.getCompound(i);
            try {
                UUID uuid = UUID.fromString(tag.getString("uuid"));
                ScoreEntry entry = new ScoreEntry(uuid);
                entry.name = tag.getString("name");
                entry.town = tag.getString("town");
                entry.civil = tag.getInt("civil");
                entry.underground = tag.getInt("underground");
                entry.convoysWon = tag.getInt("convoysWon");
                data.scores.put(uuid, entry);
            } catch (IllegalArgumentException ignored) {
            }
        }

        ListTag convoyList = root.getList("convoys", Tag.TAG_COMPOUND);
        for (int i = 0; i < convoyList.size(); i++) {
            CompoundTag tag = convoyList.getCompound(i);
            String id = tag.getString("id");
            if (id.isBlank()) continue;
            Convoy convoy = new Convoy(id);
            convoy.departAt = tag.getLong("departAt");
            convoy.dimension = tag.getString("dimension");
            convoy.x = tag.getInt("x");
            convoy.y = tag.getInt("y");
            convoy.z = tag.getInt("z");
            try {
                convoy.state = ConvoyState.valueOf(tag.getString("state"));
            } catch (IllegalArgumentException ignored) {
                convoy.state = ConvoyState.RECRUITING;
            }
            if (tag.contains("driver")) {
                try { convoy.driver = UUID.fromString(tag.getString("driver")); } catch (IllegalArgumentException ignored) {}
            }
            convoy.driverName = tag.getString("driverName");
            if (tag.contains("claimant")) {
                try { convoy.claimant = UUID.fromString(tag.getString("claimant")); } catch (IllegalArgumentException ignored) {}
            }
            convoy.claimantName = tag.getString("claimantName");
            convoy.claimStartedAt = tag.getLong("claimStartedAt");
            ListTag candidates = tag.getList("candidates", Tag.TAG_COMPOUND);
            for (int j = 0; j < candidates.size(); j++) {
                try { convoy.candidates.add(UUID.fromString(candidates.getCompound(j).getString("uuid"))); } catch (IllegalArgumentException ignored) {}
            }
            data.convoys.put(convoy.id.toLowerCase(), convoy);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        ListTag scoreList = new ListTag();
        for (ScoreEntry entry : scores.values()) {
            CompoundTag tag = new CompoundTag();
            tag.putString("uuid", entry.uuid.toString());
            tag.putString("name", entry.name);
            tag.putString("town", entry.town);
            tag.putInt("civil", entry.civil);
            tag.putInt("underground", entry.underground);
            tag.putInt("convoysWon", entry.convoysWon);
            scoreList.add(tag);
        }
        root.put("scores", scoreList);

        ListTag convoyList = new ListTag();
        for (Convoy convoy : convoys.values()) {
            CompoundTag tag = new CompoundTag();
            tag.putString("id", convoy.id);
            tag.putLong("departAt", convoy.departAt);
            tag.putString("dimension", convoy.dimension == null ? "" : convoy.dimension);
            tag.putInt("x", convoy.x);
            tag.putInt("y", convoy.y);
            tag.putInt("z", convoy.z);
            tag.putString("state", convoy.state.name());
            if (convoy.driver != null) tag.putString("driver", convoy.driver.toString());
            tag.putString("driverName", convoy.driverName == null ? "" : convoy.driverName);
            if (convoy.claimant != null) tag.putString("claimant", convoy.claimant.toString());
            tag.putString("claimantName", convoy.claimantName == null ? "" : convoy.claimantName);
            tag.putLong("claimStartedAt", convoy.claimStartedAt);
            ListTag candidates = new ListTag();
            for (UUID uuid : convoy.candidates) {
                CompoundTag c = new CompoundTag();
                c.putString("uuid", uuid.toString());
                candidates.add(c);
            }
            tag.put("candidates", candidates);
            convoyList.add(tag);
        }
        root.put("convoys", convoyList);
        return root;
    }

    void updatePlayer(ServerPlayer player, int civil, int underground, String town) {
        ScoreEntry entry = scores.computeIfAbsent(player.getUUID(), ScoreEntry::new);
        entry.name = player.getGameProfile().getName();
        entry.civil = civil;
        entry.underground = underground;
        entry.town = town == null ? "" : town;
        setDirty();
    }

    void addConvoyWin(ServerPlayer player) {
        ScoreEntry entry = scores.computeIfAbsent(player.getUUID(), ScoreEntry::new);
        entry.name = player.getGameProfile().getName();
        entry.convoysWon++;
        setDirty();
    }

    List<ScoreEntry> topPlayers(String metric, int limit) {
        List<ScoreEntry> list = new ArrayList<>(scores.values());
        Comparator<ScoreEntry> comparator = switch (metric) {
            case "underground" -> Comparator.comparingInt(e -> e.underground);
            case "convoys" -> Comparator.comparingInt(e -> e.convoysWon);
            default -> Comparator.comparingInt(e -> e.civil);
        };
        list.sort(comparator.reversed().thenComparing(e -> e.name, String.CASE_INSENSITIVE_ORDER));
        return list.subList(0, Math.min(limit, list.size()));
    }

    static final class TownScore {
        final String name;
        int civil;
        int underground;
        int convoys;

        TownScore(String name) { this.name = name; }
    }

    List<TownScore> topTowns(String metric, int limit) {
        Map<String, TownScore> byTown = new LinkedHashMap<>();
        for (ScoreEntry entry : scores.values()) {
            if (entry.town == null || entry.town.isBlank()) continue;
            TownScore score = byTown.computeIfAbsent(entry.town, TownScore::new);
            score.civil += entry.civil;
            score.underground += entry.underground;
            score.convoys += entry.convoysWon;
        }
        List<TownScore> list = new ArrayList<>(byTown.values());
        Comparator<TownScore> comparator = switch (metric) {
            case "underground" -> Comparator.comparingInt(e -> e.underground);
            case "convoys" -> Comparator.comparingInt(e -> e.convoys);
            default -> Comparator.comparingInt(e -> e.civil);
        };
        list.sort(comparator.reversed().thenComparing(e -> e.name, String.CASE_INSENSITIVE_ORDER));
        return list.subList(0, Math.min(limit, list.size()));
    }

    Convoy createConvoy(String id, long departAt, String dimension, int x, int y, int z) {
        String key = id.toLowerCase();
        Convoy convoy = new Convoy(id);
        convoy.departAt = departAt;
        convoy.dimension = dimension;
        convoy.x = x;
        convoy.y = y;
        convoy.z = z;
        convoys.put(key, convoy);
        setDirty();
        return convoy;
    }

    Convoy convoy(String id) {
        return convoys.get(id.toLowerCase());
    }

    Collection<Convoy> convoys() {
        return convoys.values();
    }
}
