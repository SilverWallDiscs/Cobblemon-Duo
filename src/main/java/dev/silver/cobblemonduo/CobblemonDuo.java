package dev.silver.cobblemonduo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class CobblemonDuo implements ModInitializer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<UUID, UUID> PARTNERS = new HashMap<>();
    private static final Map<UUID, UUID> INVITES = new HashMap<>();
    private static final Map<UUID, Long> INVITE_TIME = new HashMap<>();
    private static MinecraftServer server;
    private static Path storage;

    @Override public void onInitialize() {
        DuoAdventure.initialize();
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            storage = s.getSavePath(WorldSavePath.ROOT).resolve("cobblemon-duo.json");
            load();
            DuoAdventure.load(s);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> { save(); DuoAdventure.save(); server = null; });
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
            dispatcher.register(literal("duo")
                .then(literal("invite").then(argument("player", StringArgumentType.word()).executes(ctx -> invite(ctx.getSource(), StringArgumentType.getString(ctx, "player")))))
                .then(literal("accept").executes(ctx -> accept(ctx.getSource())))
                .then(literal("deny").executes(ctx -> deny(ctx.getSource())))
                .then(literal("admin").requires(src -> src.hasPermissionLevel(2))
                    .then(literal("disband").then(argument("player", StringArgumentType.word())
                        .executes(ctx -> adminDisband(ctx.getSource(), StringArgumentType.getString(ctx, "player"))))))
                .then(literal("info").executes(ctx -> info(ctx.getSource())))
                .executes(ctx -> info(ctx.getSource()))
            )
        );
    }

    private static ServerPlayerEntity player(ServerCommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return source.getPlayerOrThrow();
    }
    private static void say(ServerPlayerEntity player, String message) { player.sendMessage(Text.literal("[Duo] " + message), false); }
    private static int invite(ServerCommandSource source, String name) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity sender = player(source);
        ServerPlayerEntity target = server.getPlayerManager().getPlayer(name);
        if (target == null) { say(sender, "That player is not online."); return 0; }
        UUID a = sender.getUuid(), b = target.getUuid();
        if (a.equals(b) || PARTNERS.containsKey(a) || PARTNERS.containsKey(b)) { say(sender, "Both players must be available and different."); return 0; }
        INVITES.put(b, a);
        INVITE_TIME.put(b, System.currentTimeMillis());
        say(sender, "Invitation sent to " + target.getName().getString() + ".");
        say(target, sender.getName().getString() + " invited you to form a duo! Use /duo accept or /duo deny (expires in 60s).");
        return 1;
    }
    private static int accept(ServerCommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity target = player(source);
        UUID b = target.getUuid(), a = INVITES.remove(b);
        Long time = INVITE_TIME.remove(b);
        if (a == null || time == null || System.currentTimeMillis() - time > 60_000) { say(target, "No valid invitation."); return 0; }
        ServerPlayerEntity sender = server.getPlayerManager().getPlayer(a);
        if (sender == null || PARTNERS.containsKey(a) || PARTNERS.containsKey(b)) { say(target, "The invitation is no longer available."); return 0; }
        PARTNERS.put(a, b); PARTNERS.put(b, a); save();
        say(sender, "Your duo with " + target.getName().getString() + " has begun!");
        say(target, "Your duo with " + sender.getName().getString() + " has begun!");
        return 1;
    }
    private static int deny(ServerCommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity p = player(source);
        UUID a = INVITES.remove(p.getUuid());
        INVITE_TIME.remove(p.getUuid());
        if (a == null) { say(p, "No pending invitation."); return 0; }
        ServerPlayerEntity sender = server.getPlayerManager().getPlayer(a);
        if (sender != null) say(sender, p.getName().getString() + " declined your invitation.");
        say(p, "Invitation declined.");
        return 1;
    }
    private static int adminDisband(ServerCommandSource source, String name) {
        ServerPlayerEntity target = server.getPlayerManager().getPlayer(name);
        if (target == null) {
            source.sendError(Text.literal("Player must be online. Offline UUID support is planned."));
            return 0;
        }
        UUID other = PARTNERS.get(target.getUuid());
        if (other == null) {
            source.sendError(Text.literal("That player is not in a duo."));
            return 0;
        }
        PARTNERS.remove(target.getUuid());
        PARTNERS.remove(other);
        DuoAdventure.clear(target.getUuid(), other);
        save();
        say(target, "An administrator disbanded your duo.");
        ServerPlayerEntity partner = server.getPlayerManager().getPlayer(other);
        if (partner != null) say(partner, "An administrator disbanded your duo.");
        source.sendFeedback(() -> Text.literal("Duo disbanded by administrator."), true);
        return 1;
    }
    private static int info(ServerCommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity p = player(source);
        UUID other = PARTNERS.get(p.getUuid());
        if (other == null) { say(p, "No partner yet. Use /duo invite <player>."); return 1; }
        ServerPlayerEntity online = server.getPlayerManager().getPlayer(other);
        say(p, "Partner: " + (online != null ? online.getName().getString() + " (online)" : other + " (offline)"));
        say(p, DuoAdventure.status(p.getUuid(), other));
        return 1;
    }
    public static UUID partnerOf(UUID player) { return PARTNERS.get(player); }
    private static void load() {
        PARTNERS.clear(); INVITES.clear(); INVITE_TIME.clear();
        if (!Files.exists(storage)) return;
        try {
            Data data = GSON.fromJson(Files.readString(storage), Data.class);
            if (data == null || data.partners == null) return;
            for (var e : data.partners.entrySet()) PARTNERS.put(UUID.fromString(e.getKey()), UUID.fromString(e.getValue()));
        } catch (Exception e) { System.err.println("[Cobblemon Duo] Could not read saved duos: " + e.getMessage()); }
    }
    private static void save() {
        if (storage == null) return;
        try {
            Map<String, String> entries = new TreeMap<>();
            PARTNERS.forEach((a,b) -> entries.put(a.toString(), b.toString()));
            Files.createDirectories(storage.getParent());
            Path tmp = storage.resolveSibling(storage.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(new Data(entries)));
            try { Files.move(tmp, storage, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException ex) { Files.move(tmp, storage, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception e) { System.err.println("[Cobblemon Duo] Could not save duos: " + e.getMessage()); }
    }
    private record Data(Map<String, String> partners) {}
}
