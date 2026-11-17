package net.kroia.testkit.players.neoforge;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

public final class FakePlayersImpl {
    private FakePlayersImpl() {}

    public static ServerPlayer create(ServerLevel level, GameProfile profile) {
        return FakePlayerFactory.get(level, profile);
    }
}
