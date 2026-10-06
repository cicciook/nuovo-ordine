package it.ciccio.ammocompat.mixin;

import com.tacz.guns.item.ModernKineticGunScriptAPI;
import it.ciccio.ammocompat.AmmoCompat;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ModernKineticGunScriptAPI.class, remap = false)
public abstract class ModernKineticGunScriptAPIMixin {
    @Shadow private LivingEntity shooter;
    @Shadow private ItemStack itemStack;

    @Inject(method = "consumeAmmoFromPlayer", at = @At("RETURN"), cancellable = true, remap = false)
    private void ammocompat$consumeSuperbReserve(int neededAmount,
                                                 CallbackInfoReturnable<Integer> cir) {
        int alreadyConsumed = cir.getReturnValue();
        int missing = Math.max(0, neededAmount - alreadyConsumed);
        if (missing <= 0) {
            return;
        }
        int extra = AmmoCompat.consumeCompatibleSuperbReserve(shooter, itemStack, missing);
        if (extra > 0) {
            cir.setReturnValue(alreadyConsumed + extra);
        }
    }

    @Inject(method = "hasAmmoToConsume", at = @At("RETURN"), cancellable = true, remap = false)
    private void ammocompat$seeSuperbReserve(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue() && AmmoCompat.hasCompatibleSuperbAmmo(shooter, itemStack)) {
            cir.setReturnValue(true);
        }
    }
}
