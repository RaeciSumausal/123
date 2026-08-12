package com.dualsync.mixin;

import com.dualsync.DualSyncClient; // 改成 com.dualsync.DualSyncClient
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(World.class)
public abstract class EntityCollisionMixin {

    @Inject(method = "getBlockCollisions", at = @At("RETURN"), cancellable = true)
    private void injectVirtualWallCollisions(Entity entity, Box box, CallbackInfoReturnable<Iterable<VoxelShape>> cir) {
        if (!(entity instanceof PlayerEntity)) return;

        // 注意这里：改成了 DualSyncClient
        List<Box> virtualBoxes = DualSyncClient.getVirtualWallBoxes();
        if (virtualBoxes.isEmpty()) return;

        List<VoxelShape> collisionShapes = new ArrayList<>();
        cir.getReturnValue().forEach(collisionShapes::add);

        for (Box virtualBox : virtualBoxes) {
            if (box.intersects(virtualBox)) {
                collisionShapes.add(VoxelShapes.cuboid(virtualBox));
            }
        }

        cir.setReturnValue(collisionShapes);
    }
}
