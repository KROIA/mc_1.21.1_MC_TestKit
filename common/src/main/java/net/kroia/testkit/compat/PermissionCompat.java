package net.kroia.testkit.compat;

import net.minecraft.commands.CommandSourceStack;

/** Command permission levels; copied from ModUtilities' PermissionCompat with its version toggles. */
public final class PermissionCompat {
    private PermissionCompat() {}

    public static boolean hasPermission(CommandSourceStack source, int level) {
        //return source.hasPermission(level); // mc<=1.21.10
        return source.permissions().hasPermission(new net.minecraft.server.permissions.Permission.HasCommandLevel(net.minecraft.server.permissions.PermissionLevel.byId(level))); // mc>=1.21.11
    }

    /** @return the source with exactly this permission level (0 = normal player, 2 = op commands, 4 = owner). */
    public static CommandSourceStack withLevel(CommandSourceStack source, int level) {
        //return source.withPermission(level); // mc<=1.21.10
        return source.withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.forLevel(net.minecraft.server.permissions.PermissionLevel.byId(level))); // mc>=1.21.11
    }
}
