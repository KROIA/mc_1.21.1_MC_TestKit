package net.kroia.testkit.world;

import net.kroia.testkit.scenario.Scenario;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * A cleared, force-loaded 16x16x16 box of the overworld for world tests: place blocks, let a (fake) player use them,
 * fill containers, wait ticks, check contents. Boxes sit far from spawn at y 100 (in the air, so the terrain does not
 * matter) and never overlap. Call it from a test thread ({@link net.kroia.testkit.TestSuite.Side#SCENARIO} suite):
 * every method runs its world access on the server thread itself, and the waits let the server tick.
 * <pre>{@code
 * try (TestArea area = TestArea.open(getServer())) {
 *     BlockPos chest = area.place(0, 0, 0, Blocks.CHEST.defaultBlockState());
 *     area.place(0, 1, 0, Blocks.HOPPER.defaultBlockState());   // hopper above, pointing down into the chest
 *     area.insert(area.pos(0, 1, 0), new ItemStack(Items.DIAMOND, 3));
 *     area.waitUntil(() -> area.countNow(chest, Items.DIAMOND) == 3, 200);
 * }
 * }</pre>
 */
public final class TestArea implements AutoCloseable {
    public static final int SIZE = 16;
    private static final AtomicInteger NEXT = new AtomicInteger();

    private final MinecraftServer server;
    private final ServerLevel level;
    private final BlockPos origin;

    private TestArea(MinecraftServer server, BlockPos origin) {
        this.server = server;
        this.level = server.overworld();
        this.origin = origin;
    }

    /** Opens the next free box: force-loads its chunk and clears it. */
    public static TestArea open(MinecraftServer server) {
        TestArea area = new TestArea(server, new BlockPos(10_000 + NEXT.getAndIncrement() * 32, 100, 10_000));
        Scenario.onServer(server, () -> {
            area.level.setChunkForced(area.origin.getX() >> 4, area.origin.getZ() >> 4, true);
            area.fill(Blocks.AIR.defaultBlockState());
            return null;
        });
        return area;
    }

    /** Clears the box and releases the chunk. */
    @Override
    public void close() {
        Scenario.onServer(server, () -> {
            fill(Blocks.AIR.defaultBlockState());
            level.setChunkForced(origin.getX() >> 4, origin.getZ() >> 4, false);
            return null;
        });
    }

    public ServerLevel level() {
        return level;
    }

    /** @return the position at this offset inside the box (0..15 on each axis) */
    public BlockPos pos(int dx, int dy, int dz) {
        if (dx < 0 || dy < 0 || dz < 0 || dx >= SIZE || dy >= SIZE || dz >= SIZE)
            throw new IllegalArgumentException("offset " + dx + "," + dy + "," + dz + " is outside the " + SIZE + " box");
        return origin.offset(dx, dy, dz);
    }

    public BlockPos place(int dx, int dy, int dz, BlockState state) {
        BlockPos pos = pos(dx, dy, dz);
        Scenario.onServer(server, () -> level.setBlockAndUpdate(pos, state));
        return pos;
    }

    public BlockState state(BlockPos pos) {
        return Scenario.onServer(server, () -> level.getBlockState(pos));
    }

    /**
     * Right-clicks the top face of the block with {@code inHand} in the main hand, as the player would. The player is
     * moved next to the block first (a fake player from {@link net.kroia.testkit.players.FakePlayers} is fine).
     */
    public InteractionResult use(ServerPlayer player, BlockPos pos, ItemStack inHand) {
        return Scenario.onServer(server, () -> {
            player.setPos(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
            player.setItemInHand(InteractionHand.MAIN_HAND, inHand);
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
            return player.gameMode.useItemOn(player, level, inHand, InteractionHand.MAIN_HAND, hit);
        });
    }

    /** Puts {@code stack} into the container at {@code pos}, slot by slot. @return what did not fit */
    public ItemStack insert(BlockPos pos, ItemStack stack) {
        return Scenario.onServer(server, () -> {
            Container c = container(pos);
            ItemStack rest = stack.copy();
            for (int i = 0; i < c.getContainerSize() && !rest.isEmpty(); i++) {
                ItemStack slot = c.getItem(i);
                if (slot.isEmpty()) {
                    int n = Math.min(rest.getCount(), c.getMaxStackSize(rest));
                    c.setItem(i, rest.split(n));
                } else if (ItemStack.isSameItemSameComponents(slot, rest)) {
                    int n = Math.min(rest.getCount(), Math.min(c.getMaxStackSize(slot), slot.getMaxStackSize()) - slot.getCount());
                    if (n > 0) {
                        slot.grow(n);
                        rest.shrink(n);
                    }
                }
            }
            c.setChanged();
            return rest;
        });
    }

    /** @return how many of {@code item} the container at {@code pos} holds */
    public int count(BlockPos pos, Item item) {
        return Scenario.onServer(server, () -> countNow(pos, item));
    }

    /** {@link #count} for use inside {@link #waitUntil} (already on the server thread). */
    public int countNow(BlockPos pos, Item item) {
        Container c = container(pos);
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++)
            if (c.getItem(i).is(item)) n += c.getItem(i).getCount();
        return n;
    }

    /** Waits {@code ticks} server ticks (the server keeps running). */
    public void waitTicks(int ticks) {
        waitUntil(() -> false, ticks);
    }

    /**
     * Checks {@code condition} on the server thread once per tick. @return true once it holds, false after
     * {@code maxTicks} ticks
     */
    public boolean waitUntil(BooleanSupplier condition, int maxTicks) {
        long end = server.getTickCount() + (long) maxTicks;
        while (true) {
            if (Scenario.onServer(server, condition::getAsBoolean)) return true;
            if (server.getTickCount() >= end) return false;
            long tick = server.getTickCount();
            while (server.getTickCount() == tick) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted");
                }
            }
        }
    }

    private Container container(BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof Container c) return c;
        throw new IllegalStateException("no container at " + pos + " (" + level.getBlockState(pos) + ")");
    }

    private void fill(BlockState state) {
        for (BlockPos p : BlockPos.betweenClosed(origin, origin.offset(SIZE - 1, SIZE - 1, SIZE - 1))) {
            if (level.getBlockEntity(p) instanceof Container c) c.clearContent(); // removed containers would drop it
            level.setBlock(p, state, 2 | 16); // client update, no neighbour shape updates
        }
    }
}
