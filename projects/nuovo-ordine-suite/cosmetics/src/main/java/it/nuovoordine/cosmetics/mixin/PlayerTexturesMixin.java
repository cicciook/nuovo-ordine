package it.nuovoordine.cosmetics.mixin;

import it.nuovoordine.cosmetics.CosmeticsClient;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Both official dev and Forge 1.20.1 SRG names are explicit; no refmap required. */
@Mixin(value = AbstractClientPlayer.class, remap = false)
public abstract class PlayerTexturesMixin {
    private AbstractClientPlayer no$self() {
        return (AbstractClientPlayer) (Object) this;
    }

    private CosmeticsClient.Textures no$textures() {
        return CosmeticsClient.TEXTURES.get(no$self().getUUID());
    }

    @Inject(
        method = {"getSkinTextureLocation", "m_108560_"},
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private void no$skin(CallbackInfoReturnable<ResourceLocation> c) {
        var t = no$textures();
        if (t != null && t.skin() != null) c.setReturnValue(t.skin());
    }

    @Inject(
        method = {"getCloakTextureLocation", "m_108561_"},
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private void no$cape(CallbackInfoReturnable<ResourceLocation> c) {
        var cape = CosmeticsClient.currentCape(no$self().getUUID());
        if (cape != null) c.setReturnValue(cape);
    }

    @Inject(
        method = {"isCapeLoaded", "m_108555_"},
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private void no$capeLoaded(CallbackInfoReturnable<Boolean> c) {
        if (CosmeticsClient.currentCape(no$self().getUUID()) != null)
            c.setReturnValue(true);
    }

    @Inject(
        method = {"getModelName", "m_108564_"},
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private void no$model(CallbackInfoReturnable<String> c) {
        var t = no$textures();
        if (t != null && t.skin() != null)
            c.setReturnValue(t.slim() ? "slim" : "default");
    }
}
