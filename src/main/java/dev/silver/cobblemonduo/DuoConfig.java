package dev.silver.cobblemonduo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/** Editable server config at config/cobblemon-duo.json. No client config. */
public final class DuoConfig {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    public static DuoConfig INSTANCE = new DuoConfig();

    public String _info = "Cobblemon Duo server-only configuration. Restart the server after editing.";
    public int inviteExpirySeconds = 60;
    public int maximumDistanceBlocks = 64;
    public int bondExperiencePerMinute = 5;
    public int expeditionMinutes = 15;
    public int expeditionExperienceBonus = 50;
    public int uniqueCatchExperience = 20;
    public int dailyUniqueSpeciesLimit = 15;
    public int teamworkCatchWindowSeconds = 600;
    public int teamworkCatchBonusExperience = 40;
    public int challengeDurationMinutes = 10;
    public int challengeCooldownMinutes = 120;
    public int challengeSpeciesPerPlayer = 2;
    public int challengeRewardExperience = 300;
    public boolean shareSeenPokemon = true;
    public boolean shareCaughtPokemon = true;
    public boolean mergePreviousPokedexEntries = true;

    public static void load() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("cobblemon-duo.json");
        try {
            if (Files.exists(file)) {
                DuoConfig loaded = JSON.fromJson(Files.readString(file), DuoConfig.class);
                if (loaded != null) INSTANCE = loaded;
            }
            // Invalid/negative settings should not create exploits or divide-by-zero.
            INSTANCE.inviteExpirySeconds = clamp(INSTANCE.inviteExpirySeconds, 10, 600);
            INSTANCE.maximumDistanceBlocks = clamp(INSTANCE.maximumDistanceBlocks, 4, 256);
            INSTANCE.bondExperiencePerMinute = clamp(INSTANCE.bondExperiencePerMinute, 0, 100);
            INSTANCE.expeditionMinutes = clamp(INSTANCE.expeditionMinutes, 1, 120);
            INSTANCE.expeditionExperienceBonus = clamp(INSTANCE.expeditionExperienceBonus, 0, 1000);
            INSTANCE.uniqueCatchExperience = clamp(INSTANCE.uniqueCatchExperience, 0, 200);
            INSTANCE.dailyUniqueSpeciesLimit = clamp(INSTANCE.dailyUniqueSpeciesLimit, 1, 200);
            INSTANCE.teamworkCatchWindowSeconds = clamp(INSTANCE.teamworkCatchWindowSeconds, 30, 3600);
            INSTANCE.teamworkCatchBonusExperience = clamp(INSTANCE.teamworkCatchBonusExperience, 0, 1000);
            INSTANCE.challengeDurationMinutes = clamp(INSTANCE.challengeDurationMinutes, 2, 60);
            INSTANCE.challengeCooldownMinutes = clamp(INSTANCE.challengeCooldownMinutes, 10, 1440);
            INSTANCE.challengeSpeciesPerPlayer = clamp(INSTANCE.challengeSpeciesPerPlayer, 1, 8);
            INSTANCE.challengeRewardExperience = clamp(INSTANCE.challengeRewardExperience, 0, 2000);
            Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.toJson(INSTANCE));
        } catch (Exception e) {
            System.err.println("[Cobblemon Duo] Could not load config: " + e.getMessage());
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
