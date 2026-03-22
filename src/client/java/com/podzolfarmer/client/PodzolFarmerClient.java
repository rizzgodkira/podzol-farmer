package com.podzolfarmer.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

@Environment(EnvType.CLIENT)
public class PodzolFarmerClient implements ClientModInitializer {

    private boolean enabled = true;

    private enum State {
        IDLE, FACING_TO_PLACE, PLACING_SAPLING, WAITING_FOR_TREE, FACING_TO_BREAK, BREAKING_LOG
    }

    private State state = State.IDLE;
    private BlockPos targetPodzol = null;
    private BlockPos breakingPos = null;
    private int breakingTicks = 0;
    private boolean isMining = false;
    private int idleTicks = 0;
    private int facingTicks = 0;
    private int placeCooldown = 0;
    private boolean hasFacedBreakTarget = false;

    private static final int SCAN_INTERVAL = 20;
    private static final int RANGE = 10;
    private static final int MAX_BREAK_TICKS = 300;
    private static final int FACING_SETTLE_TICKS = 3;
    private static final int PLACE_CONFIRM_TICKS = 15;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("podzolfarmer")
                .then(ClientCommandManager.literal("toggle")
                    .executes(ctx -> {
                        enabled = !enabled;
                        if (!enabled) fullReset();
                        String status = enabled ? "\u00a7aENABLED" : "\u00a7cDISABLED";
                        ctx.getSource().sendFeedback(Text.literal("\u00a76[PodzolFarmer] \u00a7rAuto-farming is now " + status));
                        return 1;
                    }))
                .then(ClientCommandManager.literal("status")
                    .executes(ctx -> {
                        String status = enabled ? "\u00a7aENABLED" : "\u00a7cDISABLED";
                        ctx.getSource().sendFeedback(Text.literal("\u00a76[PodzolFarmer] \u00a7rStatus: " + status + " | State: \u00a7e" + state));
                        return 1;
                    }))
            );
        });
    }

    private void onClientTick(MinecraftClient client) {
        if (!enabled) return;
        if (client.player == null || client.world == null || client.interactionManager == null) return;
        if (client.getNetworkHandler() == null) return;
        if (client.currentScreen != null) return;
        if (client.isPaused()) return;
        if (client.player.isDead()) return;
        if (client.player.isSpectator()) return;

        try {
            tickStateMachine(client, client.player, client.world, client.interactionManager);
        } catch (Exception e) {
            com.podzolfarmer.PodzolFarmerMod.LOGGER.error("[PodzolFarmer] Exception in state {}: {}", state, e.toString());
            fullReset();
        }
    }

    private void tickStateMachine(MinecraftClient client, ClientPlayerEntity player, World world, ClientPlayerInteractionManager im) {
        switch (state) {
            case IDLE -> {
                if (++idleTicks < SCAN_INTERVAL) return;
                idleTicks = 0;
                BlockPos podzol = findActionablePodzol(player, world);
                if (podzol == null) return;
                targetPodzol = podzol;
                BlockState above = world.getBlockState(podzol.up());
                if (isLog(above)) { breakingPos = podzol.up(); facingTicks = 0; hasFacedBreakTarget = false; state = State.FACING_TO_BREAK; }
                else if (above.isOf(Blocks.SPRUCE_SAPLING)) { state = State.WAITING_FOR_TREE; }
                else if (above.isAir()) { facingTicks = 0; state = State.FACING_TO_PLACE; }
            }
            case FACING_TO_PLACE -> {
                if (targetPodzol == null) { fullReset(); return; }
                faceBlock(player, targetPodzol);
                if (++facingTicks >= FACING_SETTLE_TICKS) { facingTicks = 0; state = State.PLACING_SAPLING; }
            }
            case PLACING_SAPLING -> {
                if (targetPodzol == null) { fullReset(); return; }
                if (placeCooldown > 0) { placeCooldown--; return; }
                BlockPos above = targetPodzol.up();
                BlockState aboveState = world.getBlockState(above);
                if (aboveState.isOf(Blocks.SPRUCE_SAPLING)) { state = State.WAITING_FOR_TREE; return; }
                if (isLog(aboveState)) { breakingPos = above; facingTicks = 0; hasFacedBreakTarget = false; state = State.FACING_TO_BREAK; return; }
                if (!aboveState.isAir()) { fullReset(); return; }
                int saplingSlot = findSaplingSlot(player);
                if (saplingSlot == -1) { player.sendMessage(Text.literal("\u00a76[PodzolFarmer] \u00a7cNo spruce saplings in hotbar!"), true); fullReset(); return; }
                switchToSlot(player, client, saplingSlot);
                faceBlock(player, targetPodzol);
                BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(targetPodzol).add(0, 0.5, 0), Direction.UP, targetPodzol, false);
                ActionResult result = im.interactBlock(player, Hand.MAIN_HAND, hit);
                if (result != ActionResult.FAIL && result != ActionResult.PASS) { placeCooldown = PLACE_CONFIRM_TICKS; state = State.WAITING_FOR_TREE; }
                else { fullReset(); }
            }
            case WAITING_FOR_TREE -> {
                if (targetPodzol == null) { fullReset(); return; }
                if (!world.getBlockState(targetPodzol).isOf(Blocks.PODZOL)) { fullReset(); return; }
                BlockState aboveState = world.getBlockState(targetPodzol.up());
                if (isLog(aboveState)) { breakingPos = targetPodzol.up(); facingTicks = 0; hasFacedBreakTarget = false; state = State.FACING_TO_BREAK; }
                else if (aboveState.isAir()) { facingTicks = 0; state = State.FACING_TO_PLACE; }
            }
            case FACING_TO_BREAK -> {
                if (breakingPos == null) { fullReset(); return; }
                if (!hasFacedBreakTarget) faceBlock(player, breakingPos);
                if (++facingTicks >= FACING_SETTLE_TICKS) {
                    hasFacedBreakTarget = true; facingTicks = 0;
                    int axeSlot = findAxeSlot(player);
                    if (axeSlot != -1) switchToSlot(player, client, axeSlot);
                    safeStartMining(im, breakingPos, player);
                    state = State.BREAKING_LOG;
                }
            }
            case BREAKING_LOG -> {
                if (breakingPos == null) { fullReset(); return; }
                if (world.getBlockState(breakingPos).isAir()) {
                    safeStopMining(im);
                    BlockPos next = breakingPos.up();
                    BlockState nextState = world.getBlockState(next);
                    if (isLog(nextState) || nextState.isOf(Blocks.SPRUCE_LEAVES)) { breakingPos = next; facingTicks = 0; hasFacedBreakTarget = false; state = State.FACING_TO_BREAK; }
                    else { targetPodzol = null; breakingPos = null; state = State.IDLE; }
                    return;
                }
                im.attackBlock(breakingPos, getApproachDirection(player, breakingPos));
                if (++breakingTicks > MAX_BREAK_TICKS) { safeStopMining(im); fullReset(); }
            }
        }
    }

    private boolean isLog(BlockState s) { return s.isOf(Blocks.SPRUCE_LOG) || s.isOf(Blocks.SPRUCE_WOOD); }

    private void fullReset() {
        MinecraftClient c = MinecraftClient.getInstance();
        if (c != null && c.interactionManager != null) safeStopMining(c.interactionManager);
        state = State.IDLE; targetPodzol = null; breakingPos = null;
        idleTicks = 0; facingTicks = 0; placeCooldown = 0; hasFacedBreakTarget = false;
    }

    private void safeStartMining(ClientPlayerInteractionManager im, BlockPos pos, ClientPlayerEntity player) {
        try { isMining = true; breakingTicks = 0; im.attackBlock(pos, getApproachDirection(player, pos)); }
        catch (Exception e) { isMining = false; }
    }

    private void safeStopMining(ClientPlayerInteractionManager im) {
        try { if (isMining && im != null) im.cancelBlockBreaking(); }
        catch (Exception ignored) {}
        finally { isMining = false; breakingTicks = 0; }
    }

    private void switchToSlot(ClientPlayerEntity player, MinecraftClient client, int slot) {
        if (slot < 0 || slot > 8) return;
        // Only send the packet — no direct field access, no access widener needed
        // The server will sync the slot back to client automatically
        if (client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        }
    }

    private BlockPos findActionablePodzol(ClientPlayerEntity player, World world) {
        BlockPos p = player.getBlockPos();
        double rsq = (double) RANGE * RANGE;
        List<BlockPos> candidates = new ArrayList<>();
        for (int x = -RANGE; x <= RANGE; x++) for (int y = -RANGE; y <= RANGE; y++) for (int z = -RANGE; z <= RANGE; z++) {
            BlockPos pos = p.add(x, y, z);
            if (p.getSquaredDistance(pos) > rsq) continue;
            if (!world.getBlockState(pos).isOf(Blocks.PODZOL)) continue;
            BlockState above = world.getBlockState(pos.up());
            if (isLog(above)) return pos;
            if (above.isAir() || above.isOf(Blocks.SPRUCE_SAPLING)) candidates.add(pos);
        }
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private int findSaplingSlot(ClientPlayerEntity player) {
        for (int i = 0; i < 9; i++) { ItemStack s = player.getInventory().getStack(i); if (!s.isEmpty() && s.isOf(Items.SPRUCE_SAPLING)) return i; }
        return -1;
    }

    private int findAxeSlot(ClientPlayerEntity player) {
        for (int i = 0; i < 9; i++) { ItemStack s = player.getInventory().getStack(i); if (!s.isEmpty() && s.getItem() instanceof AxeItem) return i; }
        return -1;
    }

    private void faceBlock(ClientPlayerEntity player, BlockPos pos) {
        Vec3d eye = player.getEyePos(), center = Vec3d.ofCenter(pos);
        double dx = center.x - eye.x, dy = center.y - eye.y, dz = center.z - eye.z;
        player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        player.setPitch((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx*dx + dz*dz))));
    }

    private Direction getApproachDirection(ClientPlayerEntity player, BlockPos pos) {
        Vec3d eye = player.getEyePos(), center = Vec3d.ofCenter(pos);
        double dx = eye.x-center.x, dy = eye.y-center.y, dz = eye.z-center.z;
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ay >= ax && ay >= az) return dy > 0 ? Direction.UP : Direction.DOWN;
        if (ax >= az) return dx > 0 ? Direction.EAST : Direction.WEST;
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
