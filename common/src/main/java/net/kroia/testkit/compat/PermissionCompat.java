package net.kroia.testkit.compat;

import net.minecraft.commands.CommandSourceStack;

/** Command permission levels; copied from ModUtilities' PermissionCompat with its version toggles. */
public final class PermissionCompat {
    private PermissionCompat() {}

    public static boolean hasPermission(CommandSourceStack source, int level) {
        return source.hasPermission(level); // mc<=1.21.10
        //return source.permissions().hasPermission(new net.minecraft.server.permissions.Permission.HasCommandLevel(net.minecraft.server.permissions.PermissionLevel.byId(level))); // mc>=1.21.11
    }
}
