package com.dualsync.mixin;

import com.dualsync.DualInput;       // 改成 com.dualsync.DualInput
import com.dualsync.DualSyncClient; // 改成 com.dualsync.DualSyncClient
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
        // 注意这里：改成了 DualSyncClient
        DualInput remote = DualSyncClient.getRemoteInput();

        self.movementForward = MathHelper.clamp(self.movementForward + remote.forward, -1.0f, 1.0f);
        self.movementSideways = MathHelper.clamp(self.movementSideways + remote.sideways, -1.0f, 1.0f);

        self.jumping = self.jumping || remote.jumping;
        self.sneaking = self.sneaking || remote.sneaking;
    }
}
