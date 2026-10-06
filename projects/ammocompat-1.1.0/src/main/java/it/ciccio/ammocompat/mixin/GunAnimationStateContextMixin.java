package it.ciccio.ammocompat.mixin;

import com.tacz.guns.client.animation.statemachine.GunAnimationStateContext;
import it.ciccio.ammocompat.AmmoCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = GunAnimationStateContext.class, remap = false)
public abstract class GunAnimationStateContextMixin {
    @Shadow private ItemStack currentGunItem;

    @Inject(method = "hasAmmoToConsume", at = @At("RETURN"), cancellable = true, remap = false)
    private void ammocompat$seeSuperbReserveClient(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) {
            return;
        }
        var player = Minecraft.getInstance().player;
        if (player != null && AmmoCompat.hasCompatibleSuperbAmmo(player, currentGunItem)) {
            cir.setReturnValue(true);
        }
    }
}
