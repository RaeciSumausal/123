package com.dualsync;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;

import java.util.*;

public class DualSyncMod implements ModInitializer {

    public static final String MOD_ID = "virtualwall";
    public static final Identifier INPUT_C2S_PACKET = new Identifier(MOD_ID, "input_c2s");
    public static final Identifier INPUT_S2C_PACKET = new Identifier(MOD_ID, "input_s2c");
    public static final Identifier WALL_S2C_PACKET = new Identifier(MOD_ID, "wall_s2c");

    private static boolean active = false;
    private static UUID p1UUID = null; // 主界玩家
    private static UUID p2UUID = null; // 下界玩家

    private static Vec3d p1Spawn = Vec3d.ZERO;
    private static Vec3d p2Spawn = Vec3d.ZERO;

    private static final Map<UUID, DualInput> playerInputs = new HashMap<>();

    @Override
    public void onInitialize() {
        // 注册客户端发来的输入包
        ServerPlayNetworking.registerGlobalReceiver(INPUT_C2S_PACKET, (server, player, handler, buf, responseSender) -> {
            DualInput input = DualInput.readFromBuf(buf);
            server.execute(() -> playerInputs.put(player.getUuid(), input));
        });

        // 指令注册
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("vwall")
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
                        p1Spawn = new Vec3d(x, owY, z);
                        p2Spawn = new Vec3d(x, netherY, z);
                        ctx.getSource().sendFeedback(() -> Text.literal("§a[VirtualWall] 起始坐标设置成功！"), true);
                        return 1;
                    }))))))
                .then(CommandManager.literal("start")
                    .then(CommandManager.argument("p1", StringArgumentType.string())
                    .then(CommandManager.argument("p2", StringArgumentType.string())
                    .executes(ctx -> {
                        MinecraftServer server = ctx.getSource().getServer();
                        ServerPlayerEntity p1 = server.getPlayerManager().getPlayer(StringArgumentType.getString(ctx, "p1"));
                        ServerPlayerEntity p2 = server.getPlayerManager().getPlayer(StringArgumentType.getString(ctx, "p2"));
                        if (p1 == null || p2 == null) return 0;

                        p1UUID = p1.getUuid();
                        p2UUID = p2.getUuid();
                        active = true;

                        // 传送至各自起点
                        p1.teleport(server.getWorld(World.OVERWORLD), p1Spawn.x, p1Spawn.y, p1Spawn.z, p1.getYaw(), p1.getPitch());
                        p2.teleport(server.getWorld(World.NETHER), p2Spawn.x, p2Spawn.y, p2Spawn.z, p2.getYaw(), p2.getPitch());
                        return 1;
                    })))));
        });

        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
    }

    private void onServerTick(MinecraftServer server) {
        if (!active) return;

        ServerPlayerEntity p1 = server.getPlayerManager().getPlayer(p1UUID);
        ServerPlayerEntity p2 = server.getPlayerManager().getPlayer(p2UUID);
        if (p1 == null || p2 == null) return;

        // 1. 互发输入数据包
        DualInput i1 = playerInputs.getOrDefault(p1UUID, new DualInput());
        DualInput i2 = playerInputs.getOrDefault(p2UUID, new DualInput());

        sendInputToClient(p1, i2); // 把 P2 的按键发给 P1
        sendInputToClient(p2, i1); // 把 P1 的按键发给 P2

        // 2. 扫描对方维度的实体周边方块，打包为虚拟碰撞体发给客户端
        syncVirtualWalls(p1, server.getWorld(World.NETHER), p1Spawn, p2Spawn);
        syncVirtualWalls(p2, server.getWorld(World.OVERWORLD), p2Spawn, p1Spawn);
    }

    private void sendInputToClient(ServerPlayerEntity target, DualInput remoteInput) {
        PacketByteBuf buf = PacketByteBufs.create();
        remoteInput.writeToBuf(buf);
        ServerPlayNetworking.send(target, INPUT_S2C_PACKET, buf);
    }

    private void syncVirtualWalls(ServerPlayerEntity player, ServerWorld otherWorld, Vec3d selfSpawn, Vec3d otherSpawn) {
        // 计算玩家在当前维度的相对偏移，映射到另一个维度
        Vec3d selfPos = player.getPos();
        Vec3d offset = selfPos.subtract(selfSpawn);
        Vec3d mappedPos = otherSpawn.add(offset);

        BlockPos centerPos = BlockPos.ofFloored(mappedPos);
        List<Box> virtualBoxes = new ArrayList<>();

        // 扫描对方维度 3x3x3 范围内的实体碰撞框
        int radius = 2;
        for (int x = -radius; x <= radius; x++) {
            for (int y = -1; y <= 2; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos checkPos = centerPos.add(x, y, z);
                    BlockState state = otherWorld.getBlockState(checkPos);

                    if (!state.isAir()) {
                        VoxelShape shape = state.getCollisionShape(otherWorld, checkPos);
                        for (Box box : shape.getBoundingBoxes()) {
                            // 将对方维度的方块 Box，偏移映射回当前玩家的本地世界坐标系中
                            Box localBox = box.offset(checkPos).offset(selfSpawn.subtract(otherSpawn));
                            virtualBoxes.add(localBox);
                        }
                    }
                }
            }
        }

        // 发送虚拟墙 Collision Boxes 给客户端
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeInt(virtualBoxes.size());
        for (Box box : virtualBoxes) {
            buf.writeDouble(box.minX);
            buf.writeDouble(box.minY);
            buf.writeDouble(box.minZ);
            buf.writeDouble(box.maxX);
            buf.writeDouble(box.maxY);
            buf.writeDouble(box.maxZ);
        }
        ServerPlayNetworking.send(player, WALL_S2C_PACKET, buf);
    }
}
