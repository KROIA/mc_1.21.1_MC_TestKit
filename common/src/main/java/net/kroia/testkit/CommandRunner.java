package net.kroia.testkit;

import net.kroia.testkit.compat.PermissionCompat;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Runs a command through the server's dispatcher, as a player would type it, and captures the reply.
 * The replies go to the test, not to the player's chat. Call it on the server thread (where suites run).
 * <pre>{@code
 * Reply r = CommandRunner.asPlayer(player, 0, "bank balance");
 * return assertTrue(r.text(), r.success() && r.contains("Balance"));
 * }</pre>
 */
public final class CommandRunner {
    private CommandRunner() {}

    /**
     * @param success  the command ran and reported success (false on unknown command, syntax error, missing
     *                 permission or a command that failed)
     * @param result   the command's return value (0 if it did not run)
     * @param messages every reply sent to the source, success and failure messages alike, in order
     */
    public record Reply(String command, boolean success, int result, List<Component> messages) {
        /** @return the replies as plain text, one per line. */
        public String text() {
            return messages.stream().map(Component::getString).collect(Collectors.joining("\n"));
        }

        public boolean contains(String text) {
            return text().contains(text);
        }

        @Override
        public String toString() {
            return "/" + command + " -> " + (success ? "ok " + result : "failed") + ": " + text();
        }
    }

    /** As the server console (permission level 4). */
    public static Reply asConsole(MinecraftServer server, String command) {
        return run(server.createCommandSourceStack(), command);
    }

    /** As the player, with the player's own permission level. */
    public static Reply asPlayer(ServerPlayer player, String command) {
        return run(player.createCommandSourceStack(), command);
    }

    /**
     * As the player, but with exactly this permission level for this command (0 = normal player, 2 = op commands,
     * 4 = owner). The op list is not changed. Note: mod checks that look up the player itself (not the command
     * source), e.g. through a permissions mod, still see the player's real level.
     */
    public static Reply asPlayer(ServerPlayer player, int permissionLevel, String command) {
        return run(PermissionCompat.withLevel(player.createCommandSourceStack(), permissionLevel), command);
    }

    /** With any prepared source; its output is redirected to the reply. A leading '/' is optional. */
    public static Reply run(CommandSourceStack source, String command) {
        MinecraftServer server = source.getServer();
        if (!server.isSameThread())
            throw new IllegalStateException("CommandRunner must run on the server thread: /" + command);
        Capture capture = new Capture();
        boolean[] success = {false};
        int[] result = {0};
        CommandSourceStack stack = source.withSource(capture).withCallback((ok, value) -> {
            success[0] = ok;
            result[0] = value;
        });
        String line = command.startsWith("/") ? command.substring(1) : command;
        server.getCommands().performPrefixedCommand(stack, line);
        return new Reply(line, success[0], result[0], List.copyOf(capture.messages));
    }

    private static final class Capture implements CommandSource {
        final List<Component> messages = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component message) {
            messages.add(message);
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    }
}
