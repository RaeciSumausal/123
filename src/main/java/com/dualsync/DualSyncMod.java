package net.fabricmc.dualsync;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
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

        // 4. 注册复活监听 (强制回溯下界/主世界起跑点并恢复同步)
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

                    ctx.getSource().sendFeedback(() -> Text.literal("§a[DualSync] 成功设置自定义坐标！\n" +
                            "主世界: (" + x + ", " + owY + ", " + z + ")\n" +
                            "下界: (" + x + ", " + netherY + ", " + z + ")"), true);
                    return 1;
                }))))))

            // 指令 2: 清空坐标 (需求 2)
            .then(CommandManager.literal("clearspawn")
                .executes(ctx -> {
                    overworldSpawn = null;
                    netherSpawn = null;
                    customSpawnSet = false;
                    ctx.getSource().sendFeedback(() -> Text.literal("§e[DualSync] 已清空预设起跑坐标！开启游戏时将自动选取安全区域。"), true);
                    return 1;
                }))

            // 指令 3: 开启游戏
            .then(CommandManager.literal("start")
                .then(CommandManager.argument("p1", StringArgumentType.string())
                .then(CommandManager.argument("p2", StringArgumentType.string())
                .executes(ctx -> {
                    String p1Name = StringArgumentType.getString(ctx, "p1");
                    String p2Name = StringArgumentType.getString(ctx, "p2");

                    MinecraftServer server = ctx.getSource().getServer();
                    ServerPlayerEntity p1 = server.getPlayerManager().getPlayer(p1Name);
                    ServerPlayerEntity p2 = server.getPlayerManager().getPlayer(p2Name);

                    if (p1 == null || p2 == null) {
                        ctx.getSource().sendError(Text.literal("[DualSync] 错误：找不到指定玩家，请确认两人均在服内！"));
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

        // 若未手动设置坐标，自动寻找安全位置 (需求 2)
        if (!customSpawnSet || overworldSpawn == null || netherSpawn == null) {
            overworldSpawn = findSafeSpawn(overworld, p1.getBlockPos());
            netherSpawn = findSafeSpawn(nether, p2.getBlockPos());
            source.sendFeedback(() -> Text.literal("§e[DualSync] 未设置坐标，已自动选择安全起跑点！"), false);
        }

        // 传送至起跑点
        p1.teleport(overworld, overworldSpawn.x, overworldSpawn.y, overworldSpawn.z, p1.getYaw(), p1.getPitch());
        p2.teleport(nether, netherSpawn.x, netherSpawn.y, netherSpawn.z, p2.getYaw(), p2.getPitch());

        p1StartPos = p1.getPos();
        p2StartPos = p2.getPos();
        p1StartDimension = p1.getWorld().getRegistryKey();
        p2StartDimension = p2.getWorld().getRegistryKey();

        gameActive = true;

        // 音效与文字标题效果 (需求 1)
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

        // 问题 3 修复：检测跨越维度触发胜利
        boolean p1Crossed = !p1.getWorld().getRegistryKey().equals(p1StartDimension);
        boolean p2Crossed = !p2.getWorld().getRegistryKey().equals(p2StartDimension);

        if (p1Crossed || p2Crossed) {
            triggerVictory(server, p1, p2);
            return;
        }

        // 问题 4 修复：位置同步与重力下落处理
        Vec3d delta1 = p1.getPos().subtract(p1StartPos);
        Vec3d targetP2Pos = p2StartPos.add(delta1);

        updatePlayerPositionWithGravity(p2, targetP2Pos);
    }

    private void updatePlayerPositionWithGravity(ServerPlayerEntity player, Vec3d targetPos) {
        ServerWorld world = player.getServerWorld();
        BlockPos targetBlock = BlockPos.ofFloored(targetPos);
        
        // 检查目标位置脚下是否有方块
        boolean hasGroundBelow = !world.getBlockState(targetBlock.down()).isAir();

        // 传送基本坐标
        player.teleport(world, targetPos.x, targetPos.y, targetPos.z, player.getYaw(), player.getPitch());

        // 如果玩家悬空且没有飞行能力，则保留下落速度，避免在空中平移浮空
        if (!hasGroundBelow && !player.isOnGround() && !player.isFallFlying() && !player.getAbilities().flying) {
            double fallVel = player.getVelocity().y < 0 ? player.getVelocity().y : -0.08;
            player.setVelocity(player.getVelocity().x, fallVel, player.getVelocity().z);
            player.velocityModified = true;
        }
    }

    private void onPlayerDeath(MinecraftServer server) {
        if (!gameActive) return;

        ServerPlayerEntity p1 = server.getPlayerManager().getPlayer(p1UUID);
        ServerPlayerEntity p2 = server.getPlayerManager().getPlayer(p2UUID);

        // 音效与标题：死亡提示 (需求 1)
        if (p1 != null) sendTitleAndSound(p1, "§c§l挑战失败", "§7一名玩家已阵亡，即将重置...", SoundEvents.ENTITY_WITHER_DEATH);
        if (p2 != null) sendTitleAndSound(p2, "§c§l挑战失败", "§7一名玩家已阵亡，即将重置...", SoundEvents.ENTITY_WITHER_DEATH);
    }

    private void onPlayerRespawn(MinecraftServer server) {
        if (!gameActive) return;

        ServerPlayerEntity p1 = server.getPlayerManager().getPlayer(p1UUID);
        ServerPlayerEntity p2 = server.getPlayerManager().getPlayer(p2UUID);

        if (p1 == null || p2 == null) return;

        ServerWorld overworld = server.getWorld(World.OVERWORLD);
        ServerWorld nether = server.getWorld(World.NETHER);

        // 问题 1 & 2 修复：分别送回主世界与下界，重置状态与锚点
        p1.teleport(overworld, overworldSpawn.x, overworldSpawn.y, overworldSpawn.z, p1.getYaw(), p1.getPitch());
        p2.teleport(nether, netherSpawn.x, netherSpawn.y, netherSpawn.z, p2.getYaw(), p2.getPitch());

        p1.setHealth(p1.getMaxHealth());
        p2.setHealth(p2.getMaxHealth());
        p1.getHungerManager().setFoodLevel(20);
        p2.getHungerManager().setFoodLevel(20);

        // 重新设定起点基准
        p1StartPos = p1.getPos();
        p2StartPos = p2.getPos();

        // 恢复后音效与标题 (需求 1)
        sendTitleAndSound(p1, "§e§l重新开始！", "§7已重置到起点，保持步调一致！", SoundEvents.ENTITY_PLAYER_LEVELUP);
        sendTitleAndSound(p2, "§e§l重新开始！", "§7已重置到起点，保持步调一致！", SoundEvents.ENTITY_PLAYER_LEVELUP);
    }

    private void triggerVictory(MinecraftServer server, ServerPlayerEntity p1, ServerPlayerEntity p2) {
        gameActive = false;

        // 通关音效与全屏标题 (需求 1 & 问题 3)
        sendTitleAndSound(p1, "§6§l🎉 挑战成功！", "§a你们成功打破空间藩篱，完成了双界同步！", SoundEvents.UI_GOAT_HORN_PLAY_0);
        sendTitleAndSound(p2, "§6§l🎉 挑战成功！", "§a你们成功打破空间藩篱，完成了双界同步！", SoundEvents.UI_GOAT_HORN_PLAY_0);

        broadcast(server, "§6[DualSync] 恭喜玩家 " + p1.getName().getString() + " 与 " + p2.getName().getString() + " 成功通关！");
    }

    private void stopGame(MinecraftServer server, String reason) {
        gameActive = false;
        broadcast(server, "§c[DualSync] 游戏已终止：" + reason);
    }

    // 自动寻找安全地面算法 (需求 2)
    private Vec3d findSafeSpawn(ServerWorld world, BlockPos origin) {
        BlockPos.Mutable mutable = origin.mutableCopy();
        for (int y = origin.getY(); y > world.getBottomY() + 5; y--) {
            mutable.setY(y);
            BlockState block = world.getBlockState(mutable);
            BlockState above1 = world.getBlockState(mutable.up());
            BlockState above2 = world.getBlockState(mutable.up(2));

            if (!block.isAir() && block.isOpaque() && above1.isAir() && above2.isAir()) {
                return new Vec3d(mutable.getX() + 0.5, mutable.getY() + 1.0, mutable.getZ() + 0.5);
            }
        }
        return new Vec3d(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5);
    }

    // 发送大标题 + 音效工具类 (需求 1)
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
