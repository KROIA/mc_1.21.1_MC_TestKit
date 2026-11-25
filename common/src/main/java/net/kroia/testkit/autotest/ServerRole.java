package net.kroia.testkit.autotest;

import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * The role of the server a test runs against, from {@link AutoTest#serverRole()}. On a client it is the role of the
 * server the client plays on (its integrated server, or the dedicated server it joined).
 * <p>
 * Trust is the trust the test cell declares (run_matrix's {@code trustedSlave} property), not the master's live
 * trust list: a slave outside an autotest run counts as untrusted.
 */
public enum ServerRole {
    /** Singleplayer, or a dedicated server without the multi-server link. */
    STANDALONE,
    MASTER,
    SLAVE_TRUSTED,
    SLAVE_UNTRUSTED;

    public boolean isSlave() {
        return this == SLAVE_TRUSTED || this == SLAVE_UNTRUSTED;
    }

    /**
     * @param passed          the {@code serverRole} property (run_matrix sets it on clients that join a dedicated
     *                        server), or null
     * @param joinedDedicated the client joins a dedicated server ({@code connect} property): then only
     *                        {@code passed} knows the role, the local provider sees no server
     * @param provider        the mods' role provider for this JVM's own server ({@link AutoTest#setRoleProvider})
     * @param trustedCell     the cell trusts its slaves ({@code trustedSlave} property set)
     */
    static ServerRole resolve(@Nullable String passed, boolean joinedDedicated, Supplier<String> provider, boolean trustedCell) {
        if (passed == null && joinedDedicated)
            throw new IllegalStateException("this client joined a dedicated server, but run_matrix passed no -D"
                    + AutoTest.P + ".serverRole");
        String role = passed != null ? passed : provider.get();
        // ponytail: one flag for the whole cell; true for every run_matrix config (a -trusted cell has one slave,
        // slave2 trusts both). Match the slave's own id against the trustedSlave list if a cell ever mixes them.
        return switch (role) {
            case "standalone" -> STANDALONE;
            case "master" -> MASTER;
            case "slave" -> trustedCell ? SLAVE_TRUSTED : SLAVE_UNTRUSTED;
            default -> throw new IllegalStateException("unknown server role '" + role + "'");
        };
    }
}
