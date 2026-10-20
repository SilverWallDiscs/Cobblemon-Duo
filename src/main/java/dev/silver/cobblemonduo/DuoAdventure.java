package dev.silver.cobblemonduo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Server-only cooperative exploration progression.
 * No client packets, custom blocks, items or resource packs are required.
 */
public final class DuoAdventure {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, Progress> PROGRESS = new HashMap<>();
    private static final int MAX_LEVEL = 50;
    private static final long INTERVAL_TICKS = 20L * 60;
    private static long ticks;
    private static final Map<UUID, net.minecraft.util.math.Vec3d> LAST_POSITIONS = new HashMap<>();
    private static Path file;
    private DuoAdventure() {}

    public static void initialize() {
        ServerTickEvents.END_SERVER_TICK.register(DuoAdventure::tick);
        CobblemonEvents.POKEMON_CAPTURED.subscribe(event ->
            onCatch(event.getPlayer(), event.getPokemon().getSpecies().getResourceIdentifier().toString()));
    }

    public static void load(MinecraftServer server) {
        PROGRESS.clear();
        ticks = 0;
        LAST_POSITIONS.clear();
        file = server.getSavePath(WorldSavePath.ROOT).resolve("cobblemon-duo-adventures.json");
        if (!Files.exists(file)) return;
        try {
            Stored stored = JSON.fromJson(Files.readString(file), Stored.class);
            if (stored != null && stored.progress != null) PROGRESS.putAll(stored.progress);
        } catch (Exception e) {
            System.err.println("[Cobblemon Duo] Unable to load adventure data: " + e.getMessage());
        }
    }

    public static void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, JSON.toJson(new Stored(PROGRESS)));
            try { Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException ex) { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception e) {
            System.err.println("[Cobblemon Duo] Unable to save adventure data: " + e.getMessage());
        }
    }

    public static void clear(UUID a, UUID b) {
        PROGRESS.remove(key(a,b));
        save();
    }

    public static String status(UUID a, UUID b) {
        Progress p = PROGRESS.getOrDefault(key(a,b), new Progress());
        int level = level(p.xp);
        return "Bond level " + level + "/" + MAX_LEVEL + " | XP: " + p.xp +
            " | Together: " + p.minutes + " min | Expeditions: " + p.expeditions +
            " | Catch combos: " + p.teamworkCombos +
            " | Next: " + (level == MAX_LEVEL ? "MAX" : xpForLevel(level + 1) + " XP");
    }

    private static void tick(MinecraftServer server) {
        if (++ticks % INTERVAL_TICKS != 0) return;
        Map<UUID, Boolean> moved = new HashMap<>();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            net.minecraft.util.math.Vec3d previous = LAST_POSITIONS.put(p.getUuid(), p.getPos());
            moved.put(p.getUuid(), previous != null && previous.squaredDistanceTo(p.getPos()) >= 16.0);
        }
        LAST_POSITIONS.keySet().retainAll(moved.keySet());
        Set<String> visited = new HashSet<>();
        for (ServerPlayerEntity a : server.getPlayerManager().getPlayerList()) {
            UUID partnerId = CobblemonDuo.partnerOf(a.getUuid());
            if (partnerId == null) continue;
            String key = key(a.getUuid(), partnerId);
            if (!visited.add(key)) continue;
            ServerPlayerEntity b = server.getPlayerManager().getPlayer(partnerId);
            if (b == null || a.getWorld() != b.getWorld() || a.squaredDistanceTo(b) > Math.pow(DuoConfig.INSTANCE.maximumDistanceBlocks, 2)) continue;
            // Both must be moving: idle/AFK proximity does not generate XP.
            if (!Boolean.TRUE.equals(moved.get(a.getUuid())) || !Boolean.TRUE.equals(moved.get(b.getUuid()))) continue;
            Progress p = PROGRESS.computeIfAbsent(key, k -> new Progress());
            int before = level(p.xp);
            p.minutes++;
            p.xp += DuoConfig.INSTANCE.bondExperiencePerMinute;
            if (p.minutes % DuoConfig.INSTANCE.expeditionMinutes == 0) {
                p.expeditions++;
                p.xp += DuoConfig.INSTANCE.expeditionExperienceBonus;
                announce(a,b,"Co-op expedition completed! +" + DuoConfig.INSTANCE.expeditionExperienceBonus + " bond XP");
            }
            int after = level(p.xp);
            if (after > before) announce(a,b,"Your duo reached bond level " + after + "!");
            if (p.minutes % 5 == 0) save();
        }
    }

    /** Catching different species together charges team synergy, without AFK farming. */
    private static void onCatch(ServerPlayerEntity catcher, String species) {
        MinecraftServer server = catcher.getServer();
        if (server == null) return;
        UUID partnerId = CobblemonDuo.partnerOf(catcher.getUuid());
        if (partnerId == null) return;
        ServerPlayerEntity partner = server.getPlayerManager().getPlayer(partnerId);
        if (partner == null || catcher.getWorld() != partner.getWorld()) return;
        if (catcher.squaredDistanceTo(partner) > Math.pow(DuoConfig.INSTANCE.maximumDistanceBlocks, 2)) return;
        Progress p = PROGRESS.computeIfAbsent(key(catcher.getUuid(), partnerId), k -> new Progress());
        long day = System.currentTimeMillis() / 86_400_000L;
        if (p.catchDay != day) {
            p.catchDay = day;
            p.catchesToday = new HashSet<>();
            p.lastCatcher = null;
            p.lastCatchTime = 0;
        }
        if (p.catchesToday == null) p.catchesToday = new HashSet<>();
        if (p.catchesToday.size() >= DuoConfig.INSTANCE.dailyUniqueSpeciesLimit || !p.catchesToday.add(species)) return;

        int previousLevel = level(p.xp);
        p.xp += DuoConfig.INSTANCE.uniqueCatchExperience;
        long now = System.currentTimeMillis();
        String playerId = catcher.getUuid().toString();
        if (p.lastCatcher != null && !p.lastCatcher.equals(playerId)
            && now - p.lastCatchTime <= DuoConfig.INSTANCE.teamworkCatchWindowSeconds * 1000L) {
            p.xp += DuoConfig.INSTANCE.teamworkCatchBonusExperience;
            p.teamworkCombos++;
            announce(catcher, partner, "Teamwork catch combo! +" + DuoConfig.INSTANCE.teamworkCatchBonusExperience + " bond XP");
            p.lastCatcher = null; // each catch can be used in only one combo
            p.lastCatchTime = 0;
        } else {
            p.lastCatcher = playerId;
            p.lastCatchTime = now;
        }
        if (level(p.xp) > previousLevel)
            announce(catcher, partner, "Your duo reached bond level " + level(p.xp) + "!");
        save();
    }

    private static void announce(ServerPlayerEntity a, ServerPlayerEntity b, String message) {
        a.sendMessage(Text.literal("[Duo] " + message), false);
        b.sendMessage(Text.literal("[Duo] " + message), false);
    }

    private static int level(long xp) {
        int level = 1;
        while (level < MAX_LEVEL && xp >= xpForLevel(level + 1)) level++;
        return level;
    }
    private static long xpForLevel(int level) { return 50L * (level - 1) * level; }
    private static String key(UUID a, UUID b) {
        return a.compareTo(b) < 0 ? a + ":" + b : b + ":" + a;
    }
    private static final class Progress {
        long xp;
        long minutes;
        long expeditions;
        long teamworkCombos;
        long catchDay;
        Set<String> catchesToday = new HashSet<>();
        String lastCatcher;
        long lastCatchTime;
    }
    private record Stored(Map<String, Progress> progress) {}
}
