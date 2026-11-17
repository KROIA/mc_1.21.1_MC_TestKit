package net.kroia.testkit.players.fabric;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class FakePlayersImpl {
    private FakePlayersImpl() {}

    public static ServerPlayer create(ServerLevel level, GameProfile profile) {
        return FakePlayer.get(level, profile);
    }
}
