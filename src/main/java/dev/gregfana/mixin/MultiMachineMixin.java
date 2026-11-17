package dev.gregfana.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.gregfana.source.Machines;

@Pseudo
@Mixin(targets = "gregtech.api.metatileentity.implementations.MTEMultiBlockBase", remap = false)
public abstract class MultiMachineMixin {

    @Shadow
    public int mProgresstime;
    @Shadow
    public int mMaxProgresstime;

    @Inject(method = "checkRecipe()Z", at = @At("HEAD"), require = 1)
    private void gregfana$before(CallbackInfoReturnable<Boolean> ci) {
        Machines.before(this, false);
    }

    @Inject(method = "checkRecipe()Z", at = @At("RETURN"), require = 1)
    private void gregfana$after(CallbackInfoReturnable<Boolean> ci) {
        Machines.after(this, ci.getReturnValue());
    }

    @Inject(
        method = "runMachine",
        at = @At(
            value = "INVOKE",
            target = "Lgregtech/api/metatileentity/implementations/MTEMultiBlockBase;incrementProgressTime()V",
            shift = At.Shift.AFTER),
        require = 1)
    private void gregfana$complete(CallbackInfo ci) {
        if (mMaxProgresstime > 0 && mProgresstime >= mMaxProgresstime) Machines.complete(this, false);
    }
}
