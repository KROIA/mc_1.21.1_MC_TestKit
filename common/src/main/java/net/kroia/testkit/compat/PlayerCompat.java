package net.kroia.testkit.compat;

import net.minecraft.server.level.ServerPlayer;

/** Player and op-list calls whose API changed between Minecraft versions. */
public final class PlayerCompat {
    private PlayerCompat() {}

    /**
     * Ops the player with level 4 (owner). Explicit level: the default op level is 2 on an integrated server, which
     * would even lower a singleplayer owner's level 4 (cheats on) and leave a cheats-off world without commands.
     */
    public static void opOwner(ServerPlayer player) {
        //net.minecraft.server.players.PlayerList players = player.level().getServer().getPlayerList(); // mc<=1.21.8
        //players.getOps().add(new net.minecraft.server.players.ServerOpListEntry(player.getGameProfile(), 4, false)); // mc<=1.21.8
        //players.sendPlayerPermissionLevel(player); // mc<=1.21.8
        //player.level().getServer().getPlayerList().op(player.nameAndId(), java.util.Optional.of(4), java.util.Optional.empty()); // mc>=1.21.9 mc<=1.21.10
        player.level().getServer().getPlayerList().op(player.nameAndId(), java.util.Optional.of(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER), java.util.Optional.empty()); // mc>=1.21.11
    }

    /** Removes the player from the op list. */
    public static void deop(ServerPlayer player) {
        //player.level().getServer().getPlayerList().deop(player.getGameProfile()); // mc<=1.21.8
        player.level().getServer().getPlayerList().deop(player.nameAndId()); // mc>=1.21.9
    }
}
