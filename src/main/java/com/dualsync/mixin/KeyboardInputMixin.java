package net.fabricmc.virtualwall.mixin;

import net.fabricmc.virtualwall.DualInput;
import net.fabricmc.virtualwall.VirtualWallClient;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public class KeyboardInputMixin {

    @Inject(method = "tick", at = @At("RETURN"))
    private void onTick(boolean slowDown, float f, CallbackInfo ci) {
        KeyboardInput self = (KeyboardInput) (Object) this;
        DualInput remote = VirtualWallClient.getRemoteInput();

        // 叠加前后与左右输入向量（限幅在 -1.0 到 1.0 之间）
        self.movementForward = MathHelper.clamp(self.movementForward + remote.forward, -1.0f, 1.0f);
        self.movementSideways = MathHelper.clamp(self.movementSideways + remote.sideways, -1.0f, 1.0f);

        // 逻辑“或”触发跳跃与潜行
        self.jumping = self.jumping || remote.jumping;
        self.sneaking = self.sneaking || remote.sneaking;
    }
}
