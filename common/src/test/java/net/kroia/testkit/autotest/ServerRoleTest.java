package net.kroia.testkit.autotest;

import org.junit.jupiter.api.Test;

import static net.kroia.testkit.autotest.ServerRole.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerRoleTest {
    @Test
    void resolve() {
        // own server (dedicated, or a client's integrated server): the provider decides
        assertEquals(STANDALONE, ServerRole.resolve(null, false, () -> "standalone", false));
        assertEquals(MASTER, ServerRole.resolve(null, false, () -> "master", true));
        assertEquals(SLAVE_UNTRUSTED, ServerRole.resolve(null, false, () -> "slave", false));
        assertEquals(SLAVE_TRUSTED, ServerRole.resolve(null, false, () -> "slave", true));
        // a client on a dedicated server: the property decides, its own provider sees no server
        assertEquals(SLAVE_TRUSTED, ServerRole.resolve("slave", true, () -> "standalone", true));
        assertEquals(MASTER, ServerRole.resolve("master", true, () -> "standalone", false));
        assertThrows(IllegalStateException.class, () -> ServerRole.resolve(null, true, () -> "standalone", false));
        assertThrows(IllegalStateException.class, () -> ServerRole.resolve("slav", true, () -> "standalone", false));
    }
}
