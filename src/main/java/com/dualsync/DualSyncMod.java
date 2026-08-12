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
    private static boolean needsReset = false; // 死亡等待重置标记，防止复活时间差导致位移错误

    private static UUID p1UUID = null; // 主世界玩家
    private static UUID p2UUID = null; // 下界玩家

    // 设置的起跑坐标
    private static Vec3d overworldSpawn = null;
    private static Vec3d netherSpawn = null;
    private static boolean customSpawnSet = false;

    // 运行过程中的基准位置
    private static Vec3d p1StartPos = null;
    private static Vec3d p2StartPos = null;

    // 起始维度
    private static RegistryKey<World> p1StartDimension = World.OVERWORLD;
    private static RegistryKey<World> p2StartDimension = World.NETHER;

    @Override
    public void onInitialize() {
        // 1. 注册指令
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerCommands(dispatcher);
        });

        // 2. 注册主循环 Tick
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);

        // 3. 注册死亡监听
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (gameActive && entity instanceof ServerPlayerEntity player) {
                if (player.getUuid().equals(p1UUID) || player.getUuid().equals(p2UUID)) {
                    onPlayerDeath(player.getServer());
                }
            }
        });

        // 4. 注册复活监听 (必须等待双方均复活后再同步)
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

        // 传送至起跑点
        p1.teleport(overworld, overworldSpawn.x, overworldSpawn.y, overworldSpawn.z, p1.getYaw(), p1.getPitch());
        p2.teleport(nether, netherSpawn.x, netherSpawn.y, netherSpawn.z, p2.getYaw(), p2.getPitch());

        p1StartPos = overworldSpawn;
        p2StartPos = netherSpawn;

        p1StartDimension = World.OVERWORLD;
        p2StartDimension = World.NETHER;

        needsReset = false;
        gameActive = true;

        sendTitleAndSound(p1, "§a§l双界同步 · 开始", "§7保持同步，跨越维度！", SoundEvents.UI_TOAST_CHALLENGE_COMPLETE);
        sendTitleAndSound(p2, "§a§l双界同步 · 开始", "§7保持同步，跨越维度！", SoundEvents.UI_TOAST_CHALLENGE_COMPLETE);

        broadcast(server, "§a[DualSync] 双界同步解密游戏正式开始！");
    }

    private void onServerTick(MinecraftServer server) {
        // 如果游戏未开启或处于重置等待期间，暂停同步计算
        if (!gameActive || needsReset) return;

        ServerPlayerEntity p1 = server.getPlayerManager().getPlayer(p1UUID);
        ServerPlayerEntity p2 = server.getPlayerManager().getPlayer(p2UUID);

        if (p1 == null || p2 == null || p1.isDead() || p2.isDead()) {
            return;
        }

        // 维度跨越检测（胜利条件）
        boolean p1Crossed = !p1.getWorld().getRegistryKey().equals(p1StartDimension);
        boolean p2Crossed = !p2.getWorld().getRegistryKey().equals(p2StartDimension);

        if (p1Crossed || p2Crossed) {
            triggerVictory(server, p1, p2);
            return;
        }

        // 1. 计算 P1 在 X/Z 水平面上的相对位移
        double deltaX = p1.getX() - p1StartPos.x;
        double deltaZ = p1.getZ() - p1StartPos.z;

        // 2. 下界玩家的目标水平坐标
        double targetX = p2StartPos.x + deltaX;
        double targetZ = p2StartPos.z + deltaZ;

        // 3. 计算与上一帧的偏差
        double currentDx = targetX - p2.getX();
        double currentDz = targetZ - p2.getZ();
        double distSq = currentDx * currentDx + currentDz * currentDz;

        // 解决问题 1：仅在 P1 真正发生 X/Z 移动时才发包更新，防止频繁发包挤掉 UI 和操作
        if (distSq > 0.0001) {
            // 解决问题 2：传参使用 p2.getY()，完全把 Y 轴交给原版重力、跳跃和落体物理
            p2.networkHandler.requestTeleport(
                targetX,
                p2.getY(), 
                targetZ,
                0,
                0,
                EnumSet.of(PositionFlag.X_ROT, PositionFlag.Y_ROT) // 保持 P2 视角可自由旋转
            );
        }
    }

    private void onPlayerDeath(MinecraftServer server) {
        if (!gameActive || needsReset) return;

        // 标记需要重置，此时在两人均复活之前，tick 内的坐标同步会被冻结
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

        // 解决问题 3：只有当两个人均从死亡界面点击复活、且在服内存活时，才精准执行一次统一传送重置
        if (p1 != null && p2 != null && !p1.isDead() && !p2.isDead()) {
            ServerWorld overworld = server.getWorld(World.OVERWORLD);
            ServerWorld nether = server.getWorld(World.NETHER);

            p1.teleport(overworld, overworldSpawn.x, overworldSpawn.y, overworldSpawn.z, p1.getYaw(), p1.getPitch());
            p2.teleport(nether, netherSpawn.x, netherSpawn.y, netherSpawn.z, p2.getYaw(), p2.getPitch());

            p1.setHealth(p1.getMaxHealth());
            p2.setHealth(p2.getMaxHealth());
            p1.getHungerManager().setFoodLevel(20);
            p2.getHungerManager().setFoodLevel(20);

            // 重新刷新准确的基准坐标
            p1StartPos = p1.getPos();
            p2StartPos = p2.getPos();

            // 恢复同步功能
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
