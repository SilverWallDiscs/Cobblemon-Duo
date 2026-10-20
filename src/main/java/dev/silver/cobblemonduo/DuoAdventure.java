package dev.silver.cobblemonduo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
    private static Path file;
    private DuoAdventure() {}

    public static void initialize() {
        ServerTickEvents.END_SERVER_TICK.register(DuoAdventure::tick);
    }

    public static void load(MinecraftServer server) {
        PROGRESS.clear();
        ticks = 0;
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
            " | Next: " + (level == MAX_LEVEL ? "MAX" : xpForLevel(level + 1) + " XP");
    }

    private static void tick(MinecraftServer server) {
        if (++ticks % INTERVAL_TICKS != 0) return;
        Set<String> visited = new HashSet<>();
        for (ServerPlayerEntity a : server.getPlayerManager().getPlayerList()) {
            UUID partnerId = CobblemonDuo.partnerOf(a.getUuid());
            if (partnerId == null) continue;
            String key = key(a.getUuid(), partnerId);
            if (!visited.add(key)) continue;
            ServerPlayerEntity b = server.getPlayerManager().getPlayer(partnerId);
            if (b == null || a.getWorld() != b.getWorld() || a.squaredDistanceTo(b) > 64 * 64) continue;
            // Both must be moving: idle/AFK proximity does not generate XP.
            if (!CobblemonDuo.hasMovedRecently(a.getUuid()) || !CobblemonDuo.hasMovedRecently(b.getUuid())) continue;
            Progress p = PROGRESS.computeIfAbsent(key, k -> new Progress());
            int before = level(p.xp);
            p.minutes++;
            p.xp += 5;
            if (p.minutes % 15 == 0) {
                p.expeditions++;
                p.xp += 50;
                announce(a,b,"Co-op expedition completed! +50 bond XP");
            }
            int after = level(p.xp);
            if (after > before) announce(a,b,"Your duo reached bond level " + after + "!");
            if (p.minutes % 5 == 0) save();
        }
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
    }
    private record Stored(Map<String, Progress> progress) {}
}
