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
 * A separate duo collection. Never changes Cobblemon's individual Pokédex.
 * A capture is credited once per species and per partnership.
 */
public final class DuoPokedex {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, Collection> COLLECTIONS = new HashMap<>();
    private static Path file;
    private DuoPokedex() {}

    public static void initialize() {
        CobblemonEvents.POKEMON_CAPTURED.subscribe(event -> {
            UUID player = event.getPlayer().getUuid();
            UUID partner = CobblemonDuo.partnerOf(player);
            if (partner == null) return;
            String species = event.getPokemon().getSpecies().getResourceIdentifier().toString();
            capture(player, partner, species);
        });
    }

    public static void load(MinecraftServer server) {
        COLLECTIONS.clear();
        file = server.getSavePath(WorldSavePath.ROOT).resolve("cobblemon-duo-pokedex.json");
        if (!Files.exists(file)) return;
        try {
            Stored stored = JSON.fromJson(Files.readString(file), Stored.class);
            if (stored != null && stored.collections != null) COLLECTIONS.putAll(stored.collections);
        } catch (Exception e) {
            System.err.println("[Cobblemon Duo] Unable to load shared Pokédex: " + e.getMessage());
        }
    }

    public static void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, JSON.toJson(new Stored(COLLECTIONS)));
            try { Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception e) {
            System.err.println("[Cobblemon Duo] Unable to save shared Pokédex: " + e.getMessage());
        }
    }

    private static void capture(UUID player, UUID partner, String species) {
        String key = key(player, partner);
        Collection collection = COLLECTIONS.computeIfAbsent(key, ignored -> new Collection());
        if (!collection.species.add(species)) return;
        collection.contributions.merge(player.toString(), 1, Integer::sum);
        save();
    }

    public static String status(UUID player, UUID partner) {
        Collection c = COLLECTIONS.get(key(player, partner));
        if (c == null) return "Shared Pokédex: 0 species discovered. Capture Pokémon to begin!";
        int personal = c.contributions.getOrDefault(player.toString(), 0);
        return "Shared Pokédex: " + c.species.size() + " unique species | Your first discoveries: " + personal;
    }

    public static void clear(UUID player, UUID partner) {
        COLLECTIONS.remove(key(player, partner));
        save();
    }

    private static String key(UUID a, UUID b) {
        return a.compareTo(b) < 0 ? a + ":" + b : b + ":" + a;
    }

    private static final class Collection {
        Set<String> species = new HashSet<>();
        Map<String, Integer> contributions = new HashMap<>();
    }
    private record Stored(Map<String, Collection> collections) {}
}
