package dev.gregfana.source;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

public final class Items {

    private Items() {}

    public static String id(ItemStack stack) {
        return Item.itemRegistry.getNameForObject(stack.getItem()) + ":"
            + (stack.getItem()
                .getHasSubtypes() ? stack.getItemDamage() : 0);
    }
}
