package net.kroia.testkit.neoforge;

import net.kroia.testkit.TestKitMod;
import net.neoforged.fml.common.Mod;

@Mod(TestKitMod.MOD_ID)
public final class TestKitNeoForge {
    public TestKitNeoForge() {
        TestKitMod.init();
    }
}
