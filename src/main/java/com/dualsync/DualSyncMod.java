package net.fabricmc.dualsync;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.EnumSet;
import java.util.UUID;

public class DualSyncMod implements ModInitializer {

    private static boolean gameActive = false;
    private static boolean needsReset = false;

    private static UUID p1UUID = null; // 主世界玩家
    private static UUID p2UUID = null; // 下界玩家

    // 起跑坐标
    private static Vec3d overworldSpawn = null;
    private static Vec3d netherSpawn = null;
    private static boolean customSpawnSet = false;

    // 上一帧基准坐标
    private static Vec3d p1LastPos = null;
    private static Vec3d p2LastPos = null;

    // 起始维度
    private static RegistryKey<World> p1StartDimension = World.OVERWORLD;
    private static RegistryKey<World> p2StartDimension = World.NETHER;

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerCommands(dispatcher);
        });

        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (gameActive && entity instanceof ServerPlayerEntity player) {
                if (player.getUuid().equals(p1UUID) || player.getUuid().equals(p2UUID)) {
                    onPlayerDeath(player.getServer());
                }
            }
        });
    }

    private void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("dualsync")
            .requires(source -> source.hasPermissionLevel(2))

            .then(CommandManager.literal("setspawn")
                .then(CommandManager.argument("x", DoubleArgumentType.doubleArg())
                .then(CommandManager.argument("owY", DoubleArgumentType.doubleArg())
                .then(CommandManager.argument("netherY", DoubleArgumentType.doubleArg())
                .then(CommandManager.argument("z", DoubleArgumentType.doubleArg())
                .executes(ctx -> {
                    double x = DoubleArgumentType.getDouble(ctx, "x");
                    double owY = DoubleArgumentType.getDouble(ctx, "owY");
                    double netherY = DoubleArgumentType.getDouble(ctx, "netherY");
                    double z = DoubleArgumentType.getDouble(ctx, "z");

                    overworldSpawn = new Vec3d(x, owY, z);
                    netherSpawn = new Vec3d(x, netherY, z);
                    customSpawnSet = true;

                    ctx.getSource().sendFeedback(() -> Text.literal("§a[DualSync] 成功设置起跑坐标！\n" +
                            "主世界: (" + x + ", " + owY + ", " + z + ")\n" +
                            "下界: (" + x + ", " + netherY + ", " + z + ")"), true);
                    return 1;
                }))))))

            .then(CommandManager.literal("clearspawn")
                .executes(ctx -> {
                    overworldSpawn = null;
                    netherSpawn = null;
                    customSpawnSet = false;
                    ctx.getSource().sendFeedback(() -> Text.literal("§e[DualSync] 已清空起跑坐标！"), true);
                    return 1;
                }))

            .then(CommandManager.literal("start")
                .then(CommandManager.argument("p1", StringArgumentType.string())
                .then(CommandManager.argument("p2", StringArgumentType.string())
                .executes(ctx -> {
                    if (!customSpawnSet || overworldSpawn == null || netherSpawn == null) {
                        ctx.getSource().sendError(Text.literal("§c[DualSync] 错误：尚未设置坐标！请先使用 /dualsync setspawn 设置。"));
                        return 0;
                    }

                    String p1Name = StringArgumentType.getString(ctx, "p1");
                    String p2Name = StringArgumentType.getString(ctx, "p2");

                    MinecraftServer server = ctx.getSource().getServer();
                    ServerPlayerEntity p1 = server.getPlayerManager().getPlayer(p1Name);
                    ServerPlayerEntity p2 = server.getPlayerManager().getPlayer(p2Name);

                    if (p1 == null || p2 == null) {
                        ctx.getSource().sendError(Text.literal("§c[DualSync] 错误：找不到指定玩家，请确认两人均在线！"));
                        return 0;
                    }

                    startGame(server, p1, p2, ctx.getSource());
                    return 1;
                }))))

            .then(CommandManager.literal("stop")
                .executes(ctx -> {
                    if (!gameActive) {
                        ctx.getSource().sendError(Text.literal("[DualSync] 游戏未在运行中。"));
                        return 0;
                    }
                    stopGame(ctx.getSource().getServer(), "管理员终止了游戏。");
                    return 1;
                }))
        );
    }

    private void startGame(MinecraftServer server, ServerPlayerEntity p1, ServerPlayerEntity p2, ServerCommandSource source) {
        p1UUID = p1.getUuid();
        p2UUID = p2.getUuid();

        ServerWorld overworld = server.getWorld(World.OVERWORLD);
        ServerWorld nether = server.getWorld(World.NETHER);

        p1.teleport(overworld, overworldSpawn.x, overworldSpawn.y, overworldSpawn.z, p1.getYaw(), p1.getPitch());
        p2.teleport(nether, netherSpawn.x, netherSpawn.y, netherSpawn.z, p2.getYaw(), p2.getPitch());

        p1.setVelocity(Vec3d.ZERO);
        p2.setVelocity(Vec3d.ZERO);
        p1.fallDistance = 0;
        p2.fallDistance = 0;

        p1LastPos = overworldSpawn;
        p2LastPos = netherSpawn;

        p1StartDimension = World.OVERWORLD;
        p2StartDimension = World.NETHER;

        needsReset = false;
        gameActive = true;

        sendTitleAndSound(p1, "§a§l双界同步 · 开始", "§7保持同步，跨越维度！", SoundEvents.UI_TOAST_CHALLENGE_COMPLETE);
        sendTitleAndSound(p2, "§a§l双界同步 · 开始", "§7保持同步，跨越维度！", SoundEvents.UI_TOAST_CHALLENGE_COMPLETE);

        broadcast(server, "§a[DualSync] 双界同步解密游戏正式开始！");
    }

    private void onServerTick(MinecraftServer server) {
        if (!gameActive) return;

        ServerPlayerEntity p1 = server.getPlayerManager().getPlayer(p1UUID);
        ServerPlayerEntity p2 = server.getPlayerManager().getPlayer(p2UUID);

        if (p1 == null || p2 == null) return;

        // 安全复活重置检测
        if (needsReset) {
            if (p1.isAlive() && !p1.isDead() && p2.isAlive() && !p2.isDead()) {
                executeReset(server, p1, p2);
            }
            return;
        }

        if (p1.isDead() || p2.isDead()) return;

        // 维度跨越检测（胜利条件）
        boolean p1Crossed = !p1.getWorld().getRegistryKey().equals(p1StartDimension);
        boolean p2Crossed = !p2.getWorld().getRegistryKey().equals(p2StartDimension);

        if (p1Crossed || p2Crossed) {
            triggerVictory(server, p1, p2);
            return;
        }

        Vec3d p1Cur = p1.getPos();
        Vec3d p2Cur = p2.getPos();

        // 1. 计算双方本 Tick 的水平增量位移
        double d1X = p1Cur.x - p1LastPos.x;
        double d1Z = p1Cur.z - p1LastPos.z;

        double d2X = p2Cur.x - p2LastPos.x;
        double d2Z = p2Cur.z - p2LastPos.z;

        double targetDX = d1X + d2X;
        double targetDZ = d1Z + d2Z;

        // 2. 轴向墙壁碰撞检测 (只检测 0.6 格以上的实体墙壁，避开半砖和台阶)
        double finalDX = 0;
        if (Math.abs(targetDX) > 0.0001) {
            if (canPlayerMoveTo(p1, p1LastPos.x + targetDX, p1LastPos.z) &&
                canPlayerMoveTo(p2, p2LastPos.x + targetDX, p2LastPos.z)) {
                finalDX = targetDX;
            }
        }

        double finalDZ = 0;
        if (Math.abs(targetDZ) > 0.0001) {
            if (canPlayerMoveTo(p1, p1LastPos.x + finalDX, p1LastPos.z + targetDZ) &&
                canPlayerMoveTo(p2, p2LastPos.x + finalDX, p2LastPos.z + targetDZ)) {
                finalDZ = targetDZ;
            }
        }

        double targetP1X = p1LastPos.x + finalDX;
        double targetP1Z = p1LastPos.z + finalDZ;

        double targetP2X = p2LastPos.x + finalDX;
        double targetP2Z = p2LastPos.z + finalDZ;

        // 3. 修正水平同步：传入玩家真正的实际 Y 坐标，绝不上锁或重置高度
        syncHorizontalPosition(p1, targetP1X, targetP1Z, p1Cur);
        syncHorizontalPosition(p2, targetP2X, targetP2Z, p2Cur);

        p1LastPos = p1.getPos();
        p2LastPos = p2.getPos();
    }

    // 精准实体墙壁碰撞检测
    private boolean canPlayerMoveTo(ServerPlayerEntity player, double targetX, double targetZ) {
        ServerWorld world = player.getServerWorld();
        double currentY = player.getY();

        double halfWidth = 0.28;
        double minY = currentY + 0.60;
        double maxY = currentY + player.getHeight() - 0.1;

        if (maxY <= minY) maxY = minY + 0.1;

        Box testBox = new Box(
            targetX - halfWidth, minY, targetZ - halfWidth,
            targetX + halfWidth, maxY, targetZ + halfWidth
        );

        return world.isSpaceEmpty(player, testBox);
    }

    // 修复后的同步逻辑：使用 player.getY() 维持绝对高度，仅旋转属性使用相对增量
    private void syncHorizontalPosition(ServerPlayerEntity player, double targetX, double targetZ, Vec3d currentPos) {
        double distSq = (targetX - currentPos.x) * (targetX - currentPos.x) + (targetZ - currentPos.z) * (targetZ - currentPos.z);

        // 仅在被墙体限制或增量偏差时同步
        if (distSq > 0.000001) {
            player.networkHandler.requestTeleport(
                targetX,
                player.getY(), // 关键修复：使用玩家当前真实的 Y 坐标！
                targetZ,
                0.0f,          // 视角增量 0
                0.0f,          // 视角增量 0
                EnumSet.of(PositionFlag.X_ROT, PositionFlag.Y_ROT) // 仅旋转使用相对模式
            );
        }
    }

    private void onPlayerDeath(MinecraftServer server) {
        if (!gameActive || needsReset) return;

        needsReset = true;

        ServerPlayerEntity p1 = server.getPlayerManager().getPlayer(p1UUID);
        ServerPlayerEntity p2 = server.getPlayerManager().getPlayer(p2UUID);

        if (p1 != null) sendTitleAndSound(p1, "§c§l触发重置", "§7检测到玩家阵亡，正在重新开始...", SoundEvents.ENTITY_WITHER_DEATH);
        if (p2 != null) sendTitleAndSound(p2, "§c§l触发重置", "§7检测到玩家阵亡，正在重新开始...", SoundEvents.ENTITY_WITHER_DEATH);
    }

    private void executeReset(MinecraftServer server, ServerPlayerEntity p1, ServerPlayerEntity p2) {
        ServerWorld overworld = server.getWorld(World.OVERWORLD);
        ServerWorld nether = server.getWorld(World.NETHER);

        p1.teleport(overworld, overworldSpawn.x, overworldSpawn.y, overworldSpawn.z, p1.getYaw(), p1.getPitch());
        p2.teleport(nether, netherSpawn.x, netherSpawn.y, netherSpawn.z, p2.getYaw(), p2.getPitch());

        p1.setVelocity(Vec3d.ZERO);
        p2.setVelocity(Vec3d.ZERO);
        p1.fallDistance = 0;
        p2.fallDistance = 0;

        p1.setHealth(p1.getMaxHealth());
        p2.setHealth(p2.getMaxHealth());
        p1.getHungerManager().setFoodLevel(20);
        p2.getHungerManager().setFoodLevel(20);

        p1LastPos = overworldSpawn;
        p2LastPos = netherSpawn;

        needsReset = false;

        sendTitleAndSound(p1, "§e§l重新开始！", "§7已重置到起点，保持步调一致！", SoundEvents.ENTITY_PLAYER_LEVELUP);
        sendTitleAndSound(p2, "§e§l重新开始！", "§7已重置到起点，保持步调一致！", SoundEvents.ENTITY_PLAYER_LEVELUP);
    }

    private void triggerVictory(MinecraftServer server, ServerPlayerEntity p1, ServerPlayerEntity p2) {
        gameActive = false;
        needsReset = false;

        sendTitleAndSound(p1, "§6§l🎉 挑战成功！", "§a你们成功打破空间藩篱，完成了双界同步！", SoundEvents.UI_TOAST_CHALLENGE_COMPLETE);
        sendTitleAndSound(p2, "§6§l🎉 挑战成功！", "§a你们成功打破空间藩篱，完成了双界同步！", SoundEvents.UI_TOAST_CHALLENGE_COMPLETE);

        broadcast(server, "§6[DualSync] 恭喜玩家 " + p1.getName().getString() + " 与 " + p2.getName().getString() + " 成功通关！");
    }

    private void stopGame(MinecraftServer server, String reason) {
        gameActive = false;
        needsReset = false;
        broadcast(server, "§c[DualSync] 游戏已终止：" + reason);
    }

    private void sendTitleAndSound(ServerPlayerEntity player, String title, String subtitle, SoundEvent sound) {
        player.networkHandler.sendPacket(new TitleFadeS2CPacket(10, 70, 20));
        if (title != null) {
            player.networkHandler.sendPacket(new TitleS2CPacket(Text.literal(title)));
        }
        if (subtitle != null) {
            player.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal(subtitle)));
        }
        if (sound != null) {
            player.playSound(sound, SoundCategory.MASTER, 1.0f, 1.0f);
        }
    }

    private void broadcast(MinecraftServer server, String message) {
        server.getPlayerManager().broadcast(Text.literal(message), false);
    }
}
