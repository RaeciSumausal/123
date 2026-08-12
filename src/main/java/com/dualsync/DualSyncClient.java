package com.dualsync;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.List;

public class DualSyncClient implements ClientModInitializer {
    private static DualInput remoteInput = new DualInput();
    private static List<Box> virtualWallBoxes = new ArrayList<>();

    public static DualInput getRemoteInput() {
        return remoteInput;
    }

    public static List<Box> getVirtualWallBoxes() {
        return virtualWallBoxes;
    }

    @Override
    public void onInitializeClient() {
        // 接收远程输入包
        ClientPlayNetworking.registerGlobalReceiver(DualSyncMod.INPUT_S2C_PACKET, (client, handler, buf, responseSender) -> {
            float forward = buf.readFloat();
            float sideways = buf.readFloat();
            boolean jumping = buf.readBoolean();
            boolean sneaking = buf.readBoolean();

            client.execute(() -> {
                remoteInput.forward = forward;
                remoteInput.sideways = sideways;
                remoteInput.jumping = jumping;
                remoteInput.sneaking = sneaking;
            });
        });

        // 接收隐形墙数据包
        ClientPlayNetworking.registerGlobalReceiver(DualSyncMod.WALL_S2C_PACKET, (client, handler, buf, responseSender) -> {
            int count = buf.readInt();
            List<Box> boxes = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                double minX = buf.readDouble();
                double minY = buf.readDouble();
                double minZ = buf.readDouble();
                double maxX = buf.readDouble();
                double maxY = buf.readDouble();
                double maxZ = buf.readDouble();
                boxes.add(new Box(minX, minY, minZ, maxX, maxY, maxZ));
            }

            client.execute(() -> {
                virtualWallBoxes = boxes;
            });
        });

        // 定时发送本地输入状态给服务端
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player != null && client.options != null) {
                PacketByteBuf buf = PacketByteBufs.create();
                buf.writeFloat(client.player.input.movementForward);
                buf.writeFloat(client.player.input.movementSideways);
                buf.writeBoolean(client.player.input.jumping);
                buf.writeBoolean(client.player.input.sneaking);

                ClientPlayNetworking.send(DualSyncMod.INPUT_C2S_PACKET, buf);
            }
        });
    }
}
