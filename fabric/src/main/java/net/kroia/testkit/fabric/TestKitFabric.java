package net.kroia.testkit.fabric;

import net.fabricmc.api.ModInitializer;
import net.kroia.testkit.TestKitMod;

public final class TestKitFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        TestKitMod.init();
    }
}
