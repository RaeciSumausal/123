package net.fabricmc.virtualwall.mixin;

import net.fabricmc.virtualwall.VirtualWallClient;
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
        // 仅处理玩家实体
        if (!(entity instanceof PlayerEntity)) return;

        List<Box> virtualBoxes = VirtualWallClient.getVirtualWallBoxes();
        if (virtualBoxes.isEmpty()) return;

        List<VoxelShape> collisionShapes = new ArrayList<>();
        cir.getReturnValue().forEach(collisionShapes::add);

        // 检查发来的虚拟碰撞框是否与当前玩家的判定框重叠，若重叠则作为“隐形墙”注入
        for (Box virtualBox : virtualBoxes) {
            if (box.intersects(virtualBox)) {
                collisionShapes.add(VoxelShapes.cuboid(virtualBox));
            }
        }

        // 重新设定碰撞计算返回值
        cir.setReturnValue(collisionShapes);
    }
}
