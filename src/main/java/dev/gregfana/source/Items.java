package dev.gregfana.source;

import java.util.function.IntFunction;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

public final class Items {

    private Items() {}

    private static final String[][] STAT_ACTIONS = { { "stat.mineBlock.", "mined" }, { "stat.craftItem.", "crafted" },
        { "stat.useItem.", "used" }, { "stat.breakItem.", "broken" } };

    public static String id(ItemStack stack) {
        return Item.itemRegistry.getNameForObject(stack.getItem()) + ":"
            + (stack.getItem()
                .getHasSubtypes() ? stack.getItemDamage() : 0);
    }

    /**
     * Splits a per-item statistic ID into {action, item}, or returns null for other statistics.
     * Vanilla IDs carry numeric block/item IDs (world-specific); those are resolved to registry names.
     * Mod IDs carry "autogen.&lt;registry name&gt;". Unresolvable numbers are kept as-is.
     */
    public static String[] stat(String id, IntFunction<String> blocks, IntFunction<String> items) {
        for (String[] entry : STAT_ACTIONS) {
            if (!id.startsWith(entry[0])) continue;
            String rest = id.substring(entry[0].length());
            if (rest.startsWith("autogen.")) rest = rest.substring("autogen.".length());
            if (!rest.isEmpty() && rest.chars()
                .allMatch(Character::isDigit)) {
                String name = (entry[1].equals("mined") ? blocks : items).apply(Integer.parseInt(rest));
                if (name != null) rest = name;
            }
            return new String[] { entry[1], rest };
        }
        return null;
    }
}
