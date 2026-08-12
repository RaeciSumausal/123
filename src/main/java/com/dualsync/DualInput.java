package net.fabricmc.virtualwall;

import net.minecraft.network.PacketByteBuf;

public class DualInput {
    public float forward;   // -1.0 (后) 到 1.0 (前)
    public float sideways;  // -1.0 (右) 到 1.0 (左)
    public boolean jumping;
    public boolean sneaking;

    public DualInput() {
        this(0, 0, false, false);
    }

    public DualInput(float forward, float sideways, boolean jumping, boolean sneaking) {
        this.forward = forward;
        this.sideways = sideways;
        this.jumping = jumping;
        this.sneaking = sneaking;
    }

    public void writeToBuf(PacketByteBuf buf) {
        buf.writeFloat(forward);
        buf.writeFloat(sideways);
        buf.writeBoolean(jumping);
        buf.writeBoolean(sneaking);
    }

    public static DualInput readFromBuf(PacketByteBuf buf) {
        return new DualInput(
            buf.readFloat(),
            buf.readFloat(),
            buf.readBoolean(),
            buf.readBoolean()
        );
    }
}
