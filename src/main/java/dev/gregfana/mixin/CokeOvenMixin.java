package dev.gregfana.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.gregfana.source.Machines;

/** The coke oven runs its own private recipe loop instead of MTEMultiBlockBase.checkRecipe/runMachine. */
@Pseudo
@Mixin(targets = "gregtech.common.tileentities.machines.multi.MTECokeOven", remap = false)
public abstract class CokeOvenMixin {

    @Inject(method = "checkRecipeProgress", at = @At("HEAD"), require = 1)
    private void gregfana$before(CallbackInfo ci) {
        Machines.cokeBefore(this);
    }

    // Before addOutput: mOutputItems/mOutputFluids still hold the finished recipe's outputs.
    @Inject(
        method = "checkRecipeProgress",
        at = @At(value = "INVOKE", target = "Lgregtech/common/tileentities/machines/multi/MTECokeOven;addOutput()V"),
        require = 1)
    private void gregfana$complete(CallbackInfo ci) {
        Machines.complete(this, false);
    }

    @Inject(method = "checkRecipeProgress", at = @At("RETURN"), require = 1)
    private void gregfana$after(CallbackInfo ci) {
        Machines.cokeAfter(this);
    }
}
