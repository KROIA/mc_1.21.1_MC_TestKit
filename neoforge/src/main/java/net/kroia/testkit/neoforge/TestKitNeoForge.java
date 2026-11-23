package net.kroia.testkit.neoforge;

import net.kroia.testkit.TestKitMod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLConstructModEvent;

@Mod(TestKitMod.MOD_ID)
public final class TestKitNeoForge {
    public TestKitNeoForge(IEventBus modBus) {
        // NeoForge constructs mods in parallel and Architectury's event lists are plain ArrayLists (TK-12):
        // register the listeners from the deferred queue, which runs on one thread after all constructors.
        modBus.addListener((FMLConstructModEvent event) -> event.enqueueWork(TestKitMod::init));
    }
}
