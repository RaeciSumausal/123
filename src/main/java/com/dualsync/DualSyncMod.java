package net.fabricmc.dualsync;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
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
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.EnumSet;
import java.util.UUID;

public class DualSyncMod implements ModInitializer {

    private static boolean gameActive = false;
    private static boolean needsReset = false;

    private static UUID p1UUID = null; // 主世界玩家
    private static UUID p2UUID = null; // 下界玩家

    // 设置的起跑坐标
    private static Vec3d overworldSpawn = null;
    private static Vec3d netherSpawn = null;
    private static boolean customSpawnSet = false;

    // 上一帧记录的位置 (增量位移计算)
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

        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (gameActive && (newPlayer.getUuid().equals(p1UUID) || newPlayer.getUuid().equals(p2UUID))) {
                checkAndPerformReset(newPlayer.getServer());
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
                    ctx.getSource().sendFeedback(() -> Text.literal("§e[DualSync] 已清空起跑坐标，启动前请重新设置！"), true);
                    return 1;
                }))

            .then(CommandManager.literal("start")
                .then(CommandManager.argument("p1", StringArgumentType.string())
                .then(CommandManager.argument("p2", StringArgumentType.string())
                .executes(ctx -> {
                    if (!customSpawnSet || overworldSpawn == null || netherSpawn == null) {
                        ctx.getSource().sendError(Text.literal("§c[DualSync] 错误：尚未设置坐标！请先使用 /dualsync setspawn <x> <owY> <netherY> <z> 设置。"));
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

        // 强行绑定准确坐标，消除初始帧位置获取不准问题
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
        if (!gameActive || needsReset) return;

        ServerPlayerEntity p1 = server.getPlayerManager().getPlayer(p1UUID);
        ServerPlayerEntity p2 = server.getPlayerManager().getPlayer(p2UUID);

        if (p1 == null || p2 == null || p1.isDead() || p2.isDead()) {
            return;
        }

        // 维度跨越检测（胜利）
        boolean p1Crossed = !p1.getWorld().getRegistryKey().equals(p1StartDimension);
        boolean p2Crossed = !p2.getWorld().getRegistryKey().equals(p2StartDimension);

        if (p1Crossed || p2Crossed) {
            triggerVictory(server, p1, p2);
            return;
        }

        Vec3d p1Cur = p1.getPos();
        Vec3d p2Cur = p2.getPos();

        // 1. 计算本 Tick 双方的 X/Z 位移增量
        double d1X = p1Cur.x - p1LastPos.x;
        double d1Z = p1Cur.z - p1LastPos.z;

        double d2X = p2Cur.x - p2LastPos.x;
        double d2Z = p2Cur.z - p2LastPos.z;

        // 2. 双向位移同步算法（允许下界玩家使用 WASD 自主移动）
        if (Math.abs(d1X) > 0.0001 || Math.abs(d1Z) > 0.0001) {
            double targetP2X = p2Cur.x + d1X;
            double targetP2Z = p2Cur.z + d1Z;
            p2.networkHandler.requestTeleport(
                targetP2X,
                p2Cur.y, // Y 轴完全留给原版重力/跳跃
                targetP2Z,
                0,
                0,
                EnumSet.of(PositionFlag.X_ROT, PositionFlag.Y_ROT)
            );
            p2Cur = new Vec3d(targetP2X, p2Cur.y, targetP2Z);
        }

        if (Math.abs(d2X) > 0.0001 || Math.abs(d2Z) > 0.0001) {
            double targetP1X = p1Cur.x + d2X;
            double targetP1Z = p1Cur.z + d2Z;
            p1.networkHandler.requestTeleport(
                targetP1X,
                p1Cur.y, // Y 轴完全留给原版重力/跳跃
                targetP1Z,
                0,
                0,
                EnumSet.of(PositionFlag.X_ROT, PositionFlag.Y_ROT)
            );
            p1Cur = new Vec3d(targetP1X, p1Cur.y, targetP1Z);
        }

        // 3. 更新上一帧位置基准
        p1LastPos = p1Cur;
        p2LastPos = p2Cur;
    }

    private void onPlayerDeath(MinecraftServer server) {
        if (!gameActive || needsReset) return;

        needsReset = true;

        ServerPlayerEntity p1 = server.getPlayerManager().getPlayer(p1UUID);
        ServerPlayerEntity p2 = server.getPlayerManager().getPlayer(p2UUID);

        if (p1 != null) sendTitleAndSound(p1, "§c§l触发重置", "§7检测到玩家阵亡，正在重新开始...", SoundEvents.ENTITY_WITHER_DEATH);
        if (p2 != null) sendTitleAndSound(p2, "§c§l触发重置", "§7检测到玩家阵亡，正在重新开始...", SoundEvents.ENTITY_WITHER_DEATH);
    }

    private void checkAndPerformReset(MinecraftServer server) {
        if (!gameActive || !needsReset) return;

        ServerPlayerEntity p1 = server.getPlayerManager().getPlayer(p1UUID);
        ServerPlayerEntity p2 = server.getPlayerManager().getPlayer(p2UUID);

        if (p1 != null && p2 != null && !p1.isDead() && !p2.isDead()) {
            ServerWorld overworld = server.getWorld(World.OVERWORLD);
            ServerWorld nether = server.getWorld(World.NETHER);

            p1.teleport(overworld, overworldSpawn.x, overworldSpawn.y, overworldSpawn.z, p1.getYaw(), p1.getPitch());
            p2.teleport(nether, netherSpawn.x, netherSpawn.y, netherSpawn.z, p2.getYaw(), p2.getPitch());

            p1.setVelocity(Vec3d.ZERO);
            p2.setVelocity(Vec3d.ZERO);

            p1.setHealth(p1.getMaxHealth());
            p2.setHealth(p2.getMaxHealth());
            p1.getHungerManager().setFoodLevel(20);
            p2.getHungerManager().setFoodLevel(20);

            // 彻底解决主世界玩家死亡时下界玩家重置点错位的核心修复：直接赋值为初始起跑坐标常量！
            p1LastPos = overworldSpawn;
            p2LastPos = netherSpawn;

            needsReset = false;

            sendTitleAndSound(p1, "§e§l重新开始！", "§7已重置到起点，保持步调一致！", SoundEvents.ENTITY_PLAYER_LEVELUP);
            sendTitleAndSound(p2, "§e§l重新开始！", "§7已重置到起点，保持步调一致！", SoundEvents.ENTITY_PLAYER_LEVELUP);
        }
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
