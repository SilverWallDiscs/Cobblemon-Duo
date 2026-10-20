package dev.silver.cobblemonduo;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.pokedex.PokedexEntryProgress;
import com.cobblemon.mod.common.api.pokedex.PokedexManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import java.util.UUID;

/**
 * Mirrors Cobblemon's real Pokédex progress to a partner's existing Pokédex.
 * There is no secondary Pokédex, GUI, command or save file.
 */
public final class DuoPokedex {
    private static boolean mirroring = false;
    private DuoPokedex() {}

    public static void initialize() {
        CobblemonEvents.POKEDEX_DATA_CHANGED_POST.subscribe(event -> {
            if (mirroring) return;
            UUID partnerId = CobblemonDuo.partnerOf(event.getPlayerUUID());
            if (partnerId == null) return;
            MinecraftServer server = CobblemonDuo.serverInstance();
            if (server == null) return;
            // Only synchronize live players for now; offline updates need a
            // separately verified lifecycle/data-store integration.
            ServerPlayerEntity partner = server.getPlayerManager().getPlayer(partnerId);
            if (partner == null) return;
            try {
                mirroring = true;
                PokedexManager manager = Cobblemon.INSTANCE.getPlayerDataManager().getPokedexData(partnerId);
                if (event.getKnowledge() == PokedexEntryProgress.CAUGHT) {
                    manager.obtain(event.getDataSource().getPokemon());
                } else if (event.getKnowledge() == PokedexEntryProgress.ENCOUNTERED) {
                    manager.encounter(event.getDataSource());
                }
            } catch (Exception e) {
                System.err.println("[Cobblemon Duo] Pokédex synchronization failed: " + e.getMessage());
            } finally {
                mirroring = false;
            }
        });
    }
}
