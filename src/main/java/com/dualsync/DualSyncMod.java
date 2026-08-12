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

    // 起跑绝对坐标
    private static Vec3d overworldSpawn = null;
    private static Vec3d netherSpawn = null;
    private static boolean customSpawnSet = false;

    // 全局唯一共享偏移量（绝对防漂移）
    private static double sharedOffsetX = 0.0;
    private static double sharedOffsetZ = 0.0;

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

        sharedOffsetX = 0.0;
        sharedOffsetZ = 0.0;

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

        if (needsReset) {
            if (p1.isAlive() && !p1.isDead() && p2.isAlive() && !p2.isDead()) {
                executeReset(server, p1, p2);
            }
            return;
        }

        if (p1.isDead() || p2.isDead()) return;

        // 维度跨越胜利判定
        boolean p1Crossed = !p1.getWorld().getRegistryKey().equals(p1StartDimension);
        boolean p2Crossed = !p2.getWorld().getRegistryKey().equals(p2StartDimension);

        if (p1Crossed || p2Crossed) {
            triggerVictory(server, p1, p2);
            return;
        }

        // 1. 计算两名玩家当前相对于起点的【实际偏移】
        double p1RealOffsetX = p1.getX() - overworldSpawn.x;
        double p1RealOffsetZ = p1.getZ() - overworldSpawn.z;

        double p2RealOffsetX = p2.getX() - netherSpawn.x;
        double p2RealOffsetZ = p2.getZ() - netherSpawn.z;

        // 2. 计算两名玩家在本 Tick 产生的“新贡献位移”
        double d1X = p1RealOffsetX - sharedOffsetX;
        double d1Z = p1RealOffsetZ - sharedOffsetZ;

        double d2X = p2RealOffsetX - sharedOffsetX;
        double d2Z = p2RealOffsetZ - sharedOffsetZ;

        double targetDX = d1X + d2X;
        double targetDZ = d1Z + d2Z;

        // 3. 碰撞检测，尝试推进 sharedOffset
        double tryNextOffsetX = sharedOffsetX + targetDX;
        double tryNextOffsetZ = sharedOffsetZ + targetDZ;

        double finalOffsetX = sharedOffsetX;
        if (Math.abs(targetDX) > 0.00001) {
            if (canPlayerMoveTo(p1, overworldSpawn.x + tryNextOffsetX, p1.getZ()) &&
                canPlayerMoveTo(p2, netherSpawn.x + tryNextOffsetX, p2.getZ())) {
                finalOffsetX = tryNextOffsetX;
            }
        }

        double finalOffsetZ = sharedOffsetZ;
        if (Math.abs(targetDZ) > 0.00001) {
            if (canPlayerMoveTo(p1, overworldSpawn.x + finalOffsetX, overworldSpawn.z + tryNextOffsetZ) &&
                canPlayerMoveTo(p2, netherSpawn.x + finalOffsetX, netherSpawn.z + tryNextOffsetZ)) {
                finalOffsetZ = tryNextOffsetZ;
            }
        }

        // 4. 更新绝对唯一的基准偏移量
        sharedOffsetX = finalOffsetX;
        sharedOffsetZ = finalOffsetZ;

        // 5. 计算目标 X/Z 坐标并精准矫正
        double targetP1X = overworldSpawn.x + sharedOffsetX;
        double targetP1Z = overworldSpawn.z + sharedOffsetZ;

        double targetP2X = netherSpawn.x + sharedOffsetX;
        double targetP2Z = netherSpawn.z + sharedOffsetZ;

        syncHorizontalPosition(p1, targetP1X, targetP1Z);
        syncHorizontalPosition(p2, targetP2X, targetP2Z);
    }

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

    // 终极同步算法：结合 PositionFlag.Y 标志位，实现“无拉扯 + 正常重力下落”
    private void syncHorizontalPosition(ServerPlayerEntity player, double targetX, double targetZ) {
        double dx = targetX - player.getX();
        double dz = targetZ - player.getZ();
        double distSq = dx * dx + dz * dz;

        // 只有当水平偏差大于 0.0001 格 (0.1 毫米) 时才进行同步。
        // 自己在主动按 WASD 移动的玩家，其位置已经踩在 target 上，distSq 几乎为 0，
        // 服务端绝不会对他发任何封包，保证 100% 原生平滑手感！
        if (distSq > 0.0001) {
            // 核心技巧：指定 PositionFlag.Y, X_ROT, Y_ROT 为相对模式
            // Y 设为 0.0 (相对)：客户端绝对不会重置/清空 Vy 下落速度，下落手感 100% 原生！
            // 视角 设为 0.0 (相对)：视角完全不受影响，不会强制扭转镜头！
            player.networkHandler.requestTeleport(
                targetX,
                0.0,
                targetZ,
                0.0f,
                0.0f,
                EnumSet.of(PositionFlag.Y, PositionFlag.X_ROT, PositionFlag.Y_ROT)
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

        sharedOffsetX = 0.0;
        sharedOffsetZ = 0.0;

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
