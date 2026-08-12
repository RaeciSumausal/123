package com.dualsync;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.List;

public class DualSyncClient implements ClientModInitializer {

    private static DualInput remoteInput = new DualInput();
    private static final List<Box> virtualWallBoxes = new ArrayList<>();

    @Override
    public void onInitializeClient() {
        // 接收对方按键
        ClientPlayNetworking.registerGlobalReceiver(VirtualWallMod.INPUT_S2C_PACKET, (client, handler, buf, responseSender) -> {
            DualInput input = DualInput.readFromBuf(buf);
            client.execute(() -> remoteInput = input);
        });

        // 接收对方维度的虚拟墙 Collision Boxes
        ClientPlayNetworking.registerGlobalReceiver(VirtualWallMod.WALL_S2C_PACKET, (client, handler, buf, responseSender) -> {
            int count = buf.readInt();
            List<Box> boxes = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                boxes.add(new Box(
                    buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble()
                ));
            }
            client.execute(() -> {
                virtualWallBoxes.clear();
                virtualWallBoxes.addAll(boxes);
            });
        });

        // 每 Tick 将本地按键发给服务端
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ClientPlayerEntity player = client.player;
            if (player != null && player.input != null) {
                PacketByteBuf buf = PacketByteBufs.create();
                DualInput localInput = new DualInput(
                    player.input.movementForward,
                    player.input.movementSideways,
                    player.input.jumping,
                    player.input.sneaking
                );
                localInput.writeToBuf(buf);
                ClientPlayNetworking.send(VirtualWallMod.INPUT_C2S_PACKET, buf);
            }
        });
    }

    public static DualInput getRemoteInput() {
        return remoteInput;
    }

    public static List<Box> getVirtualWallBoxes() {
        return virtualWallBoxes;
    }
}
