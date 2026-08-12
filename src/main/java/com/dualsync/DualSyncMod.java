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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.EnumSet;
import java.util.UUID;

public class DualSyncMod implements ModInitializer {

    // 游戏运行状态
    private static boolean gameActive = false;
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

        // 2. 注册主循环 Tick (同步坐标与胜利判定)
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);

        // 3. 注册死亡监听 (触发重置音效与视觉)
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (gameActive && entity instanceof ServerPlayerEntity player) {
                if (player.getUuid().equals(p1UUID) || player.getUuid().equals(p2UUID)) {
                    onPlayerDeath(player.getServer());
                }
            }
        });

        // 4. 注册复活监听 (精准回溯下界/主世界起跑点并恢复同步)
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (gameActive && (newPlayer.getUuid().equals(p1UUID) || newPlayer.getUuid().equals(p2UUID))) {
                onPlayerRespawn(newPlayer.getServer());
            }
        });
    }

    private void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("dualsync")
            .requires(source -> source.hasPermissionLevel(2))

            // 指令 1: 手动设置起跑坐标
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

            // 指令 2: 清空坐标
            .then(CommandManager.literal("clearspawn")
                .executes(ctx -> {
                    overworldSpawn = null;
                    netherSpawn = null;
                    customSpawnSet = false;
                    ctx.getSource().sendFeedback(() -> Text.literal("§e[DualSync] 已清空起跑坐标，启动前请重新设置！"), true);
                    return 1;
                }))

            // 指令 3: 开启游戏
            .then(CommandManager.literal("start")
                .then(CommandManager.argument("p1", StringArgumentType.string())
                .then(CommandManager.argument("p2", StringArgumentType.string())
                .executes(ctx -> {
                    // 问题 1 修复：未设置坐标时不再自动寻找，提示必须手动设置
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

            // 指令 4: 终止游戏
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

        // 问题 3 修复：直接锚定设置的坐标，确保 100% 精确
        p1StartPos = overworldSpawn;
        p2StartPos = netherSpawn;

        p1StartDimension = World.OVERWORLD;
        p2StartDimension = World.NETHER;

        gameActive = true;

        sendTitleAndSound(p1, "§a§l双界同步 · 开始", "§7保持同步，跨越维度！", SoundEvents.UI_TOAST_CHALLENGE_COMPLETE);
        sendTitleAndSound(p2, "§a§l双界同步 · 开始", "§7保持同步，跨越维度！", SoundEvents.UI_TOAST_CHALLENGE_COMPLETE);

        broadcast(server, "§a[DualSync] 双界同步解密游戏正式开始！");
    }

    private void onServerTick(MinecraftServer server) {
        if (!gameActive) return;

        ServerPlayerEntity p1 = server.getPlayerManager().getPlayer(p1UUID);
        ServerPlayerEntity p2 = server.getPlayerManager().getPlayer(p2UUID);

        if (p1 == null || p2 == null || p1.isDead() || p2.isDead()) {
            return;
        }

        // 维度跨越检测
        boolean p1Crossed = !p1.getWorld().getRegistryKey().equals(p1StartDimension);
        boolean p2Crossed = !p2.getWorld().getRegistryKey().equals(p2StartDimension);

        if (p1Crossed || p2Crossed) {
            triggerVictory(server, p1, p2);
            return;
        }

        // 计算 P1 位移并应用给 P2
        Vec3d delta1 = p1.getPos().subtract(p1StartPos);
        double targetX = p2StartPos.x + delta1.x;
        double targetY = p2StartPos.y + delta1.y;
        double targetZ = p2StartPos.z + delta1.z;

        // 问题 4 修复：平滑移动、防穿墙、不卡视角、空中下落
        syncP2Position(p2, targetX, targetY, targetZ);
    }

    private void syncP2Position(ServerPlayerEntity p2, double targetX, double targetY, double targetZ) {
        ServerWorld world = p2.getServerWorld();

        // 1. 防穿墙检测
        BlockPos feetPos = BlockPos.ofFloored(targetX, targetY + 0.1, targetZ);
        BlockPos headPos = BlockPos.ofFloored(targetX, targetY + 1.5, targetZ);

        boolean feetInWall = world.getBlockState(feetPos).isOpaqueFullCube(world, feetPos);
        boolean headInWall = world.getBlockState(headPos).isOpaqueFullCube(world, headPos);

        double finalX = targetX;
        double finalY = targetY;
        double finalZ = targetZ;

        if (feetInWall || headInWall) {
            // 如果前方是墙，限制 X/Z 轴盲目穿墙
            finalX = p2.getX();
            finalZ = p2.getZ();
        }

        // 2. 空中下落检测（解决空中悬浮问题）
        BlockPos groundCheck = BlockPos.ofFloored(finalX, finalY - 0.1, finalZ);
        boolean isAirBelow = world.getBlockState(groundCheck).isAir();

        if (isAirBelow && !p2.isOnGround()) {
            // 如果脚下是空气，保留自由下落 Y 轴，不强制锁定高度
            if (p2.getY() < finalY) {
                finalY = p2.getY();
            }
        }

        // 3. 使用 Relative Rotation Flags 传送，彻底解放视角控制
        p2.networkHandler.requestTeleport(
            finalX, 
            finalY, 
            finalZ, 
            0, 
            0, 
            EnumSet.of(PositionFlag.X_ROT, PositionFlag.Y_ROT)
        );
    }

    private void onPlayerDeath(MinecraftServer server) {
        if (!gameActive) return;

        ServerPlayerEntity p1 = server.getPlayerManager().getPlayer(p1UUID);
        ServerPlayerEntity p2 = server.getPlayerManager().getPlayer(p2UUID);

        // 问题 2 修复：不再提示“挑战失败”，改为“触发重置”
        if (p1 != null) sendTitleAndSound(p1, "§c§l触发重置", "§7检测到玩家阵亡，正在重新开始...", SoundEvents.ENTITY_WITHER_DEATH);
        if (p2 != null) sendTitleAndSound(p2, "§c§l触发重置", "§7检测到玩家阵亡，正在重新开始...", SoundEvents.ENTITY_WITHER_DEATH);
    }

    private void onPlayerRespawn(MinecraftServer server) {
        if (!gameActive) return;

        ServerPlayerEntity p1 = server.getPlayerManager().getPlayer(p1UUID);
        ServerPlayerEntity p2 = server.getPlayerManager().getPlayer(p2UUID);

        if (p1 == null || p2 == null) return;

        ServerWorld overworld = server.getWorld(World.OVERWORLD);
        ServerWorld nether = server.getWorld(World.NETHER);

        // 问题 3 修复：复活时强行送回精确起跑点
        p1.teleport(overworld, overworldSpawn.x, overworldSpawn.y, overworldSpawn.z, p1.getYaw(), p1.getPitch());
        p2.teleport(nether, netherSpawn.x, netherSpawn.y, netherSpawn.z, p2.getYaw(), p2.getPitch());

        p1.setHealth(p1.getMaxHealth());
        p2.setHealth(p2.getMaxHealth());
        p1.getHungerManager().setFoodLevel(20);
        p2.getHungerManager().setFoodLevel(20);

        // 重新锚定起点基准
        p1StartPos = overworldSpawn;
        p2StartPos = netherSpawn;

        sendTitleAndSound(p1, "§e§l重新开始！", "§7已重置到起点，保持步调一致！", SoundEvents.ENTITY_PLAYER_LEVELUP);
        sendTitleAndSound(p2, "§e§l重新开始！", "§7已重置到起点，保持步调一致！", SoundEvents.ENTITY_PLAYER_LEVELUP);
    }

    private void triggerVictory(MinecraftServer server, ServerPlayerEntity p1, ServerPlayerEntity p2) {
        gameActive = false;

        sendTitleAndSound(p1, "§6§l🎉 挑战成功！", "§a你们成功打破空间藩篱，完成了双界同步！", SoundEvents.UI_TOAST_CHALLENGE_COMPLETE);
        sendTitleAndSound(p2, "§6§l🎉 挑战成功！", "§a你们成功打破空间藩篱，完成了双界同步！", SoundEvents.UI_TOAST_CHALLENGE_COMPLETE);

        broadcast(server, "§6[DualSync] 恭喜玩家 " + p1.getName().getString() + " 与 " + p2.getName().getString() + " 成功通关！");
    }

    private void stopGame(MinecraftServer server, String reason) {
        gameActive = false;
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
