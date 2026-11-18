package dev.gregfana.source;

import static org.junit.Assert.*;

import org.junit.Test;

public class ItemsTest {

    private static String[] stat(String id) {
        return Items.stat(id, n -> n == 17 ? "minecraft:log" : null, n -> n == 397 ? "minecraft:skull" : null);
    }

    @Test
    public void resolvesNumericVanillaIdsByStatKind() {
        assertArrayEquals(new String[] { "mined", "minecraft:log" }, stat("stat.mineBlock.17"));
        assertArrayEquals(new String[] { "used", "minecraft:skull" }, stat("stat.useItem.397"));
        // Mined stats resolve via the block registry; 397 is only known as an item here.
        assertArrayEquals(new String[] { "mined", "397" }, stat("stat.mineBlock.397"));
    }

    @Test
    public void stripsAutogenPrefixForModdedIds() {
        assertArrayEquals(
            new String[] { "crafted", "Thaumcraft:blockCustomPlant" },
            stat("stat.craftItem.autogen.Thaumcraft:blockCustomPlant"));
        assertArrayEquals(
            new String[] { "broken", "gregtech:gt.metatool.01" },
            stat("stat.breakItem.gregtech:gt.metatool.01"));
    }

    @Test
    public void ignoresNonItemStats() {
        assertNull(stat("stat.playOneMinute"));
        assertNull(stat("stat.walkOneCm"));
    }
}
