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
@Mixin(targets = "gregtech.api.metatileentity.implementations.MTEBasicMachine", remap = false)
public abstract class BasicMachineMixin {

    @Shadow
    public int mProgresstime;
    @Shadow
    public int mMaxProgresstime;

    @Inject(method = "checkRecipe(Z)I", at = @At("HEAD"), require = 1)
    private void gregfana$before(CallbackInfoReturnable<Integer> ci) {
        Machines.before(this, true);
    }

    @Inject(method = "checkRecipe(Z)I", at = @At("RETURN"), require = 1)
    private void gregfana$after(CallbackInfoReturnable<Integer> ci) {
        Machines.after(this, ci.getReturnValue() == 2);
    }

    @Inject(
        method = "onPostTick",
        at = @At(
            value = "FIELD",
            target = "Lgregtech/api/metatileentity/implementations/MTEBasicMachine;mProgresstime:I",
            opcode = 181,
            ordinal = 0,
            shift = At.Shift.AFTER),
        require = 1)
    private void gregfana$complete(CallbackInfo ci) {
        Machines.seen(this);
        if (mMaxProgresstime > 0 && mProgresstime >= mMaxProgresstime) Machines.complete(this, true);
    }
}
