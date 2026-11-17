package dev.gregfana.mixin;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.SlotCrafting;
import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.gregfana.Gregfana;

@Mixin(SlotCrafting.class)
public abstract class CraftingMixin {

    @Shadow
    private EntityPlayer thePlayer;
    @Shadow
    private int amountCrafted;

    @Inject(method = "onCrafting(Lnet/minecraft/item/ItemStack;)V", at = @At("HEAD"), require = 1)
    private void gregfana$output(ItemStack stack, CallbackInfo ci) {
        if (thePlayer instanceof EntityPlayerMP && Gregfana.instance != null && amountCrafted > 0)
            Gregfana.instance.action(
                thePlayer.getUniqueID()
                    .toString(),
                "manual_crafting_items",
                stack,
                amountCrafted);
    }

    @Inject(method = "onPickupFromSlot", at = @At("RETURN"), require = 1)
    private void gregfana$operation(EntityPlayer player, ItemStack stack, CallbackInfo ci) {
        // One pickup commits one recipe's ingredient removal; shift-click invokes this repeatedly.
        if (player instanceof EntityPlayerMP && Gregfana.instance != null) Gregfana.instance.action(
            player.getUniqueID()
                .toString(),
            "crafting_operations",
            stack,
            1);
    }
}
