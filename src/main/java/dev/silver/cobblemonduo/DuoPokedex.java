package dev.silver.cobblemonduo;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.pokedex.PokedexManager;
import com.cobblemon.mod.common.api.pokedex.SpeciesDexRecord;
import com.cobblemon.mod.common.api.storage.player.PlayerInstancedDataStoreTypes;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Shares the NATIVE Cobblemon Pokédex, never a custom substitute.
 *
 * Merge rules: max(knowledge), union(forms, genders, shiny states, aspects).
 * No record is deleted or downgraded. Existing records merge when the duo is
 * formed and when a player joins; future changes propagate even if the partner
 * is offline. Cobblemon itself sends the normal Pokédex UI packets.
 */
public final class DuoPokedex {
    private DuoPokedex() {}

    public static void initialize() {
        CobblemonEvents.POKEDEX_DATA_CHANGED_POST.subscribe(event -> {
            UUID partner = CobblemonDuo.partnerOf(event.getPlayerUUID());
            if (partner == null) return;
            MinecraftServer server = CobblemonDuo.serverInstance();
            if (server == null) return;
            Identifier species = event.getRecord().getSpeciesDexRecord().getId();
            server.execute(() -> synchronizeSpecies(server, event.getPlayerUUID(), partner, species));
        });
        // Reconcile missed updates when someone reconnects, and sync old Pokédex
        // entries from both players. The one-tick deferred task avoids join-order races.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            UUID uuid = handler.getPlayer().getUuid();
            server.execute(() -> synchronizePair(server, uuid));
        });
    }

    public static void synchronizePair(MinecraftServer server, UUID player) {
        UUID partner = CobblemonDuo.partnerOf(player);
        if (partner == null || server == null) return;
        try {
            var store = Cobblemon.INSTANCE.getPlayerDataManager();
            PokedexManager a = store.getPokedexData(player);
            PokedexManager b = store.getPokedexData(partner);
            Set<Identifier> allSpecies = new HashSet<>(a.getSpeciesRecords().keySet());
            allSpecies.addAll(b.getSpeciesRecords().keySet());

            int changesA = 0, changesB = 0;
            for (Identifier id : allSpecies) {
                JsonObject left = encodeOrNull(a.getSpeciesRecord(id));
                JsonObject right = encodeOrNull(b.getSpeciesRecord(id));
                JsonObject union = unionSpecies(left, right);
                if (apply(a, id, left, union)) changesA++;
                if (apply(b, id, right, union)) changesB++;
            }
            if (changesA > 0) flush(server, player, a, true);
            if (changesB > 0) flush(server, partner, b, true);
            if (changesA + changesB > 0)
                System.out.println("[Cobblemon Duo] Native Pokédex merge: " + changesA + " + " + changesB + " records synced.");
        } catch (Exception e) {
            System.err.println("[Cobblemon Duo] Native Pokédex merge failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void synchronizeSpecies(MinecraftServer server, UUID player, UUID partner, Identifier id) {
        // The duo may have been administratively dissolved since this event was queued.
        if (!partner.equals(CobblemonDuo.partnerOf(player))) return;
        try {
            var store = Cobblemon.INSTANCE.getPlayerDataManager();
            PokedexManager a = store.getPokedexData(player);
            PokedexManager b = store.getPokedexData(partner);
            JsonObject left = encodeOrNull(a.getSpeciesRecord(id));
            JsonObject right = encodeOrNull(b.getSpeciesRecord(id));
            JsonObject union = unionSpecies(left, right);
            if (apply(a, id, left, union)) flush(server, player, a, false, id);
            if (apply(b, id, right, union)) flush(server, partner, b, false, id);
        } catch (Exception e) {
            System.err.println("[Cobblemon Duo] Native Pokédex update failed for " + id + ": " + e.getMessage());
        }
    }

    private static void flush(MinecraftServer server, UUID player, PokedexManager manager, boolean complete, Identifier... species) {
        var store = Cobblemon.INSTANCE.getPlayerDataManager();
        // Explicit write-through is required when the other trainer is offline.
        store.saveSingle(manager, PlayerInstancedDataStoreTypes.INSTANCE.getPOKEDEX());
        ServerPlayerEntity online = server.getPlayerManager().getPlayer(player);
        if (online == null) return;
        if (complete) {
            store.syncAllToPlayer(online);
        } else {
            for (Identifier id : species) {
                SpeciesDexRecord record = manager.getSpeciesRecord(id);
                if (record != null) manager.onSpeciesRecordUpdated(record);
            }
        }
    }

    private static JsonObject encodeOrNull(SpeciesDexRecord record) {
        if (record == null) return null;
        Optional<JsonElement> result = SpeciesDexRecord.Companion.getCODEC().encodeStart(JsonOps.INSTANCE, record).result();
        if (result.isEmpty() || !result.get().isJsonObject())
            throw new IllegalStateException("Cobblemon could not serialize a Pokédex species record");
        return result.get().getAsJsonObject();
    }

    private static boolean apply(PokedexManager manager, Identifier id, JsonObject previous, JsonObject merged) {
        if (merged == null || merged.equals(previous)) return false;
        Optional<SpeciesDexRecord> parsed = SpeciesDexRecord.Companion.getCODEC().parse(JsonOps.INSTANCE, merged).result();
        if (parsed.isEmpty()) throw new IllegalStateException("Could not decode Pokédex data for " + id);
        SpeciesDexRecord record = parsed.get();
        record.initialize(manager, id);
        manager.getSpeciesRecords().put(id, record);
        return true;
    }

    private static JsonObject unionSpecies(JsonObject a, JsonObject b) {
        if (a == null) return b == null ? null : b.deepCopy();
        if (b == null) return a.deepCopy();
        JsonObject out = a.deepCopy();
        out.add("aspects", unionArray(a.getAsJsonArray("aspects"), b.getAsJsonArray("aspects")));
        JsonObject aForms = a.getAsJsonObject("formRecords");
        JsonObject bForms = b.getAsJsonObject("formRecords");
        JsonObject forms = new JsonObject();
        Set<String> names = new LinkedHashSet<>();
        if (aForms != null) names.addAll(aForms.keySet());
        if (bForms != null) names.addAll(bForms.keySet());
        for (String name : names) {
            JsonObject af = aForms == null || !aForms.has(name) ? null : aForms.getAsJsonObject(name);
            JsonObject bf = bForms == null || !bForms.has(name) ? null : bForms.getAsJsonObject(name);
            if (af == null) { forms.add(name, bf.deepCopy()); continue; }
            if (bf == null) { forms.add(name, af.deepCopy()); continue; }
            JsonObject form = af.deepCopy();
            form.add("genders", unionArray(af.getAsJsonArray("genders"), bf.getAsJsonArray("genders")));
            form.add("seenShinyStates", unionArray(af.getAsJsonArray("seenShinyStates"), bf.getAsJsonArray("seenShinyStates")));
            String ak = af.get("knowledge").getAsString(), bk = bf.get("knowledge").getAsString();
            form.addProperty("knowledge", rank(bk) > rank(ak) ? bk : ak);
            forms.add(name, form);
        }
        out.add("formRecords", forms);
        return out;
    }

    private static int rank(String knowledge) {
        return switch (knowledge) {
            case "CAUGHT" -> 2;
            case "ENCOUNTERED" -> 1;
            default -> 0;
        };
    }

    private static JsonArray unionArray(JsonArray a, JsonArray b) {
        Set<String> values = new LinkedHashSet<>();
        if (a != null) for (JsonElement e : a) values.add(e.getAsString());
        if (b != null) for (JsonElement e : b) values.add(e.getAsString());
        JsonArray result = new JsonArray();
        for (String value : values) result.add(new JsonPrimitive(value));
        return result;
    }
}
