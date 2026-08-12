package com.dualsync;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.MovementType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public class DualSyncMod implements ModInitializer {

    private static boolean gameRunning = false;
    private static ServerPlayerEntity playerOverworld = null;
    private static ServerPlayerEntity playerNether = null;

    private static double spawnX = 0, spawnYOverworld = 64, spawnYNether = 64, spawnZ = 0;
    private static boolean isSyncing = false; // 防递归标记

    // 记录上一 Tick 的位置，用作比较
    private static Vec3d lastP1Pos = null;
    private static Vec3d lastP2Pos = null;

    @Override
    public void onInitialize() {
        // 注册指令
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("dualsync")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.literal("setspawn")
                    .then(CommandManager.argument("x", DoubleArgumentType.doubleArg())
                    .then(CommandManager.argument("y1", DoubleArgumentType.doubleArg())
                    .then(CommandManager.argument("y2", DoubleArgumentType.doubleArg())
                    .then(CommandManager.argument("z", DoubleArgumentType.doubleArg())
                        .executes(ctx -> {
                            spawnX = DoubleArgumentType.getDouble(ctx, "x");
                            spawnYOverworld = DoubleArgumentType.getDouble(ctx, "y1");
                            spawnYNether = DoubleArgumentType.getDouble(ctx, "y2");
                            spawnZ = DoubleArgumentType.getDouble(ctx, "z");
                            ctx.getSource().sendFeedback(() -> Text.literal("§a[双维同步] 复活点设置成功！"), false);
                            return 1;
                        }))))))
                .then(CommandManager.literal("start")
                    .then(CommandManager.argument("p1", EntityArgumentType.player())
                    .then(CommandManager.argument("p2", EntityArgumentType.player())
                        .executes(ctx -> {
                            playerOverworld = EntityArgumentType.getPlayer(ctx, "p1");
                            playerNether = EntityArgumentType.getPlayer(ctx, "p2");

                            ServerWorld overworld = ctx.getSource().getServer().getWorld(World.OVERWORLD);
                            ServerWorld nether = ctx.getSource().getServer().getWorld(World.NETHER);

                            if (overworld != null && nether != null) {
                                playerOverworld.teleport(overworld, spawnX, spawnYOverworld, spawnZ, playerOverworld.getYaw(), playerOverworld.getPitch());
                                playerNether.teleport(nether, spawnX, spawnYNether, spawnZ, playerNether.getYaw(), playerNether.getPitch());
                            }

                            lastP1Pos = playerOverworld.getPos();
                            lastP2Pos = playerNether.getPos();
                            gameRunning = true;

                            ctx.getSource().getServer().getPlayerManager().broadcast(Text.literal("§a[双维同步] 游戏开始！跨越传送门者胜！").formatted(Formatting.GREEN), false);
                            return 1;
                        }))))
                .then(CommandManager.literal("stop")
                    .executes(ctx -> {
                        stopGame();
                        ctx.getSource().sendFeedback(() -> Text.literal("§c[双维同步] 游戏已终止。"), false);
                        return 1;
                    }))
            );
        });

        // 绑定 Tick 每帧的检测与位移同步逻辑 (20Hz)
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!gameRunning || isSyncing) return;
            if (playerOverworld == null || playerNether == null || !playerOverworld.isAlive() || !playerNether.isAlive()) return;

            Vec3d currentP1Pos = playerOverworld.getPos();
            Vec3d currentP2Pos = playerNether.getPos();

            if (lastP1Pos == null || lastP2Pos == null) {
                lastP1Pos = currentP1Pos;
                lastP2Pos = currentP2Pos;
                return;
            }

            // 检查谁在移动，计算增量
            double dx1 = currentP1Pos.x - lastP1Pos.x;
            double dz1 = currentP1Pos.z - lastP1Pos.z;

            double dx2 = currentP2Pos.x - lastP2Pos.x;
            double dz2 = currentP2Pos.z - lastP2Pos.z;

            double dx = 0, dz = 0;
            ServerPlayerEntity mover = null;
            ServerPlayerEntity partner = null;

            if (Math.abs(dx1) > 0.001 || Math.abs(dz1) > 0.001) {
                dx = dx1;
                dz = dz1;
                mover = playerOverworld;
                partner = playerNether;
            } else if (Math.abs(dx2) > 0.001 || Math.abs(dz2) > 0.001) {
                dx = dx2;
                dz = dz2;
                mover = playerNether;
                partner = playerOverworld;
            }

            if (mover != null && partner != null) {
                // 计算目标位置碰撞箱
                Box moverBoxTarget = mover.getBoundingBox().offset(dx, 0, dz);
                Box partnerBoxTarget = partner.getBoundingBox().offset(dx, 0, dz);

                // 判断双方在目标点是否会卡墙
                boolean moverCollided = hasCollision(mover.getServerWorld(), moverBoxTarget);
                boolean partnerCollided = hasCollision(partner.getServerWorld(), partnerBoxTarget);

                isSyncing = true;
                if (moverCollided || partnerCollided) {
                    // 撞墙拦截：退回移动前位置 (保留 Y 轴高度)
                    Vec3d safePosMover = new Vec3d(lastP1Pos.x, currentP1Pos.y, lastP1Pos.z);
                    if (mover == playerNether) safePosMover = new Vec3d(lastP2Pos.x, currentP2Pos.y, lastP2Pos.z);
                    
                    mover.teleport(mover.getServerWorld(), safePosMover.x, safePosMover.y, safePosMover.z, mover.getYaw(), mover.getPitch());
                } else {
                    // 同步传送 partner
                    Vec3d partnerPos = partner.getPos();
                    partner.teleport(partner.getServerWorld(), partnerPos.x + dx, partnerPos.y, partnerPos.z + dz, partner.getYaw(), partner.getPitch());
                }
                isSyncing = false;
            }

            // 检查跨维度胜利判定（检查是否在地狱传送门方块中）
            checkPortalWin(playerOverworld);
            checkPortalWin(playerNether);

            lastP1Pos = playerOverworld.getPos();
            lastP2Pos = playerNether.getPos();
        });
    }

    private boolean hasCollision(ServerWorld world, Box box) {
        return world.getBlockCollisions(null, box).iterator().hasNext();
    }

    private void checkPortalWin(ServerPlayerEntity player) {
        if (!gameRunning) return;
        BlockPos pos = player.getBlockPos();
        BlockState state = player.getServerWorld().getBlockState(pos);
        if (state.isOf(netherPortalBlock())) {
            gameRunning = false;
            player.getServer().getPlayerManager().broadcast(
                Text.literal("§e★ [双维同步] 玩家 " + player.getName().getString() + " 跨越维度，赢得游戏！ ★").formatted(Formatting.GOLD),
                false
            );
            stopGame();
        }
    }

    private net.minecraft.block.Block netherPortalBlock() {
        return net.minecraft.block.Blocks.NETHER_PORTAL;
    }

    private void stopGame() {
        gameRunning = false;
        playerOverworld = null;
        playerNether = null;
        lastP1Pos = null;
        lastP2Pos = null;
    }
}