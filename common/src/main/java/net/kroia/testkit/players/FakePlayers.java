package net.kroia.testkit.players;

import com.mojang.authlib.GameProfile;
import dev.architectury.injectables.annotations.ExpectPlatform;
import net.kroia.testkit.compat.PlayerCompat;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fake players for server tests: server-side players without a game client (Fabric API {@code FakePlayer},
 * NeoForge {@code FakePlayerFactory}). Each has the stable offline UUID of its name (the same one an offline client
 * with that name gets), its own inventory and, if wanted, op rights.
 * <p>
 * Limits: a fake player is not in the server's player list (not "online"), and packets sent to it are dropped.
 * Mod code that needs an online player, or that crashes when it sends a packet to one, is a bug or a limit of that
 * mod: log it for the owning project.
 */
public final class FakePlayers {
    private FakePlayers() {}

    /** @return the fake player with this name in the overworld (the same instance on repeated calls). */
    public static ServerPlayer get(MinecraftServer server, String name) {
        return get(server.overworld(), name);
    }

    public static ServerPlayer get(ServerLevel level, String name) {
        return create(level, UUIDUtil.createOfflineProfile(name));
    }

    /** Gives the player op level 4 (true) or removes it from the op list (false). Undo it in teardown. */
    public static void setOp(ServerPlayer player, boolean op) {
        if (op) PlayerCompat.opOwner(player);
        else PlayerCompat.deop(player);
    }

    @ExpectPlatform
    static ServerPlayer create(ServerLevel level, GameProfile profile) {
        throw new AssertionError("platform implementation missing");
    }
}
