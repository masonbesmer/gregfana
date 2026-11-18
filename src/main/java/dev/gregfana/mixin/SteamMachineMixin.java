package dev.gregfana.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.gregfana.source.Machines;

/**
 * Steam single-block machines replace {@code checkRecipe()} without reaching {@code checkRecipe(boolean)}, so the
 * {@link BasicMachineMixin} input hooks never run for them. Completion still goes through the base onPostTick hook.
 * Keep the targets in sync with {@code Machines.STEAM_CHECKS}.
 */
@Pseudo
@Mixin(
    targets = { "gregtech.api.metatileentity.implementations.MTEBasicMachineBronze",
        "gregtech.common.tileentities.machines.steam.MTESteamFurnaceBronze",
        "gregtech.common.tileentities.machines.steam.MTESteamFurnaceSteel",
        "gregtech.common.tileentities.machines.steam.MTESteamMaceratorBronze",
        "gregtech.common.tileentities.machines.steam.MTESteamMaceratorSteel" },
    remap = false)
public abstract class SteamMachineMixin {

    @Inject(method = "checkRecipe()I", at = @At("HEAD"), require = 1)
    private void gregfana$before(CallbackInfoReturnable<Integer> ci) {
        Machines.before(this, true);
    }

    @Inject(method = "checkRecipe()I", at = @At("RETURN"), require = 1)
    private void gregfana$after(CallbackInfoReturnable<Integer> ci) {
        Machines.after(this, ci.getReturnValue() == 2);
    }
}
