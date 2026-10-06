package it.ciccio.ammocompat.mixin;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.gun.AbstractGunItem;
import com.tacz.guns.util.AttachmentDataUtils;
import it.ciccio.ammocompat.AmmoCompat;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = AbstractGunItem.class, remap = false)
public abstract class AbstractGunItemMixin {
    @Inject(method = "canReload", at = @At("RETURN"), cancellable = true, remap = false)
    private void ammocompat$allowSuperbReload(LivingEntity shooter, ItemStack gun,
                                              CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) {
            return;
        }
        AbstractGunItem self = (AbstractGunItem) (Object) this;
        if (self.useInventoryAmmo(gun)) {
            return;
        }
        var index = TimelessAPI.getCommonGunIndex(self.getGunId(gun)).orElse(null);
        if (index == null) {
            return;
        }
        int max = AttachmentDataUtils.getAmmoCountWithAttachment(gun, index.getGunData());
        if (self.getCurrentAmmoCount(gun) >= max) {
            return;
        }
        if (AmmoCompat.hasCompatibleSuperbAmmo(shooter, gun)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "hasInventoryAmmo", at = @At("RETURN"), cancellable = true, remap = false)
    private void ammocompat$allowSuperbInventoryAmmo(LivingEntity shooter, ItemStack gun,
                                                     boolean needCheckAmmo,
                                                     CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue() || !needCheckAmmo) {
            return;
        }
        AbstractGunItem self = (AbstractGunItem) (Object) this;
        if (self.useInventoryAmmo(gun) && AmmoCompat.hasCompatibleSuperbAmmo(shooter, gun)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "findAndExtractInventoryAmmo", at = @At("RETURN"), cancellable = true, remap = false)
    private void ammocompat$consumeSuperbDirectly(IItemHandler itemHandler, ItemStack gun,
                                                  int needAmmoCount,
                                                  CallbackInfoReturnable<Integer> cir) {
        int vanilla = cir.getReturnValue();
        if (vanilla >= needAmmoCount) {
            return;
        }
        int extra = AmmoCompat.extractCompatibleSuperbAmmo(itemHandler, gun, needAmmoCount - vanilla);
        if (extra > 0) {
            cir.setReturnValue(vanilla + extra);
        }
    }
}
