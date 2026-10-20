package dev.silver.cobblemonduo;

import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * A two-player, timed catch challenge. Both partners must independently
 * catch different species while adventuring near each other.
 *
 * The active challenge and cooldown persist through server restarts.
 * Only pending invites are ephemeral.
 */
public final class DuoChallenge {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, Challenge> ACTIVE = new HashMap<>();
    private static final Map<String, Long> COOLDOWNS = new HashMap<>();
    private static final Map<String, Invite> PENDING = new HashMap<>();
    private static Path file;

    private DuoChallenge() {}

    public static void initialize() {
        CobblemonEvents.POKEMON_CAPTURED.subscribe(event ->
            onCapture(event.getPlayer(), event.getPokemon().getSpecies().getResourceIdentifier().toString()));
    }

    public static void load(MinecraftServer server) {
        ACTIVE.clear(); COOLDOWNS.clear(); PENDING.clear();
        file = server.getSavePath(WorldSavePath.ROOT).resolve("cobblemon-duo-challenges.json");
        if (!Files.exists(file)) return;
        try {
            Stored stored = JSON.fromJson(Files.readString(file), Stored.class);
            if (stored != null) {
                if (stored.active != null) ACTIVE.putAll(stored.active);
                if (stored.cooldowns != null) COOLDOWNS.putAll(stored.cooldowns);
            }
        } catch (Exception e) {
            System.err.println("[Cobblemon Duo] Could not load duo challenges: " + e.getMessage());
        }
    }

    public static void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, JSON.toJson(new Stored(ACTIVE, COOLDOWNS)));
            try { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception e) {
            System.err.println("[Cobblemon Duo] Could not save duo challenges: " + e.getMessage());
        }
    }

    public static void clear(UUID a, UUID b) {
        String key = key(a, b);
        ACTIVE.remove(key); COOLDOWNS.remove(key); PENDING.remove(key);
        save();
    }

    public static int start(ServerPlayerEntity p) {
        UUID partnerId = CobblemonDuo.partnerOf(p.getUuid());
        if (partnerId == null) return message(p, "You must be in a duo.");
        String key = key(p.getUuid(), partnerId);
        long now = System.currentTimeMillis();
        if (getActive(key, now) != null) return message(p, "Your duo already has an active challenge.");
        long cooldown = COOLDOWNS.getOrDefault(key, 0L) - now;
        if (cooldown > 0) return message(p, "Next challenge available in " + (cooldown / 60000 + 1) + " minutes.");
        ServerPlayerEntity partner = p.getServer().getPlayerManager().getPlayer(partnerId);
        if (!nearby(p, partner)) return message(p, "Your partner must be online, in the same dimension and nearby.");
        PENDING.put(key, new Invite(p.getUuid().toString(), now + 60_000L));
        message(p, "Challenge invitation sent to your partner.");
        message(partner, p.getName().getString() + " invites you to a Duo Catch Sprint! Use /duo challenge accept within 60 seconds.");
        return 1;
    }

    public static int accept(ServerPlayerEntity p) {
        UUID partnerId = CobblemonDuo.partnerOf(p.getUuid());
        if (partnerId == null) return message(p, "You are not in a duo.");
        String key = key(p.getUuid(), partnerId);
        Invite invitation = PENDING.get(key);
        long now = System.currentTimeMillis();
        if (invitation == null || invitation.expiresAt < now || !invitation.inviter.equals(partnerId.toString()))
            return message(p, "No valid challenge invitation from your partner.");
        ServerPlayerEntity partner = p.getServer().getPlayerManager().getPlayer(partnerId);
        if (!nearby(p, partner)) return message(p, "Stay nearby to begin the challenge.");
        if (getActive(key, now) != null || COOLDOWNS.getOrDefault(key, 0L) > now) {
            PENDING.remove(key);
            return message(p, "Challenge is no longer available.");
        }
        PENDING.remove(key);
        Challenge challenge = new Challenge();
        challenge.expiresAt = now + DuoConfig.INSTANCE.challengeDurationMinutes * 60_000L;
        ACTIVE.put(key, challenge);
        COOLDOWNS.put(key, now + DuoConfig.INSTANCE.challengeCooldownMinutes * 60_000L);
        save();
        notifyBoth(p, partner, "Duo Catch Sprint started! Each player must catch " +
            DuoConfig.INSTANCE.challengeSpeciesPerPlayer + " different species within " +
            DuoConfig.INSTANCE.challengeDurationMinutes + " minutes. All species must be unique across the duo!");
        return 1;
    }

    public static int deny(ServerPlayerEntity p) {
        UUID partnerId = CobblemonDuo.partnerOf(p.getUuid());
        if (partnerId == null) return message(p, "You are not in a duo.");
        String key = key(p.getUuid(), partnerId);
        Invite invitation = PENDING.get(key);
        if (invitation == null || !invitation.inviter.equals(partnerId.toString())) return message(p, "No invitation to decline.");
        PENDING.remove(key);
        message(p, "Challenge declined.");
        ServerPlayerEntity partner = p.getServer().getPlayerManager().getPlayer(partnerId);
        if (partner != null) message(partner, "Your partner declined the catch sprint.");
        return 1;
    }

    public static int status(ServerPlayerEntity p) {
        UUID partnerId = CobblemonDuo.partnerOf(p.getUuid());
        if (partnerId == null) return message(p, "You are not in a duo.");
        long now = System.currentTimeMillis();
        Challenge c = getActive(key(p.getUuid(), partnerId), now);
        if (c == null) return message(p, "No active sprint. Start one with /duo challenge start.");
        int you = c.catches.getOrDefault(p.getUuid().toString(), Set.of()).size();
        int them = c.catches.getOrDefault(partnerId.toString(), Set.of()).size();
        return message(p, "Catch Sprint: You " + you + "/" + DuoConfig.INSTANCE.challengeSpeciesPerPlayer +
            ", partner " + them + "/" + DuoConfig.INSTANCE.challengeSpeciesPerPlayer +
            ", time left " + ((c.expiresAt - now) / 1000) + "s.");
    }

    private static void onCapture(ServerPlayerEntity p, String species) {
        UUID partnerId = CobblemonDuo.partnerOf(p.getUuid());
        if (partnerId == null) return;
        String key = key(p.getUuid(), partnerId);
        long now = System.currentTimeMillis();
        Challenge c = getActive(key, now);
        if (c == null) return;
        ServerPlayerEntity partner = p.getServer().getPlayerManager().getPlayer(partnerId);
        if (!nearby(p, partner)) return;
        if (c.catches == null) c.catches = new HashMap<>();
        if (c.catches.values().stream().anyMatch(s -> s.contains(species))) return;
        Set<String> mine = c.catches.computeIfAbsent(p.getUuid().toString(), ignored -> new HashSet<>());
        if (mine.size() >= DuoConfig.INSTANCE.challengeSpeciesPerPlayer || !mine.add(species)) return;
        int theirs = c.catches.getOrDefault(partnerId.toString(), Set.of()).size();
        int goal = DuoConfig.INSTANCE.challengeSpeciesPerPlayer;
        if (mine.size() >= goal && theirs >= goal) {
            ACTIVE.remove(key);
            DuoAdventure.grantExperience(p.getUuid(), partnerId, DuoConfig.INSTANCE.challengeRewardExperience,
                "Catch Sprint complete!");
            notifyBoth(p, partner, "Catch Sprint complete! Both trainers contributed. +" +
                DuoConfig.INSTANCE.challengeRewardExperience + " bond XP");
        } else {
            notifyBoth(p, partner, "Catch Sprint progress: " + mine.size() + "/" + goal +
                " for " + p.getName().getString() + ", " + theirs + "/" + goal + " for partner.");
        }
        save();
    }

    private static boolean nearby(ServerPlayerEntity a, ServerPlayerEntity b) {
        return a != null && b != null && a.getWorld() == b.getWorld()
            && a.squaredDistanceTo(b) <= Math.pow(DuoConfig.INSTANCE.maximumDistanceBlocks, 2);
    }

    private static Challenge getActive(String key, long now) {
        Challenge c = ACTIVE.get(key);
        if (c != null && c.expiresAt <= now) {
            ACTIVE.remove(key);
            save();
            return null;
        }
        return c;
    }

    private static int message(ServerPlayerEntity p, String value) {
        p.sendMessage(Text.literal("[Duo] " + value), false);
        return 1;
    }

    private static void notifyBoth(ServerPlayerEntity a, ServerPlayerEntity b, String value) {
        message(a, value); message(b, value);
    }

    private static String key(UUID a, UUID b) {
        return a.compareTo(b) < 0 ? a + ":" + b : b + ":" + a;
    }

    private static final class Challenge {
        long expiresAt;
        Map<String, Set<String>> catches = new HashMap<>();
    }
    private record Invite(String inviter, long expiresAt) {}
    private record Stored(Map<String, Challenge> active, Map<String, Long> cooldowns) {}
}
