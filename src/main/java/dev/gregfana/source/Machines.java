package dev.gregfana.source;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.fluids.FluidStack;

import dev.gregfana.Gregfana;
import dev.gregfana.core.Metrics;
import dev.gregfana.core.ResourceSnapshot;

/** GT names are stable, non-obfuscated API names. Cache reflective access once per concrete class. */
public final class Machines {

    private static final String BASIC = "gregtech.api.metatileentity.implementations.MTEBasicMachine";
    private static final String MULTI = "gregtech.api.metatileentity.implementations.MTEMultiBlockBase";
    private static final Map<Class<?>, Map<String, MethodHandle>> METHODS = new HashMap<>();
    private static final Map<Class<?>, Map<String, Field>> FIELDS = new HashMap<>();
    /** Coverage levels: no counters / completions and outputs only / also physical input consumption. */
    public static final int NONE = 0, OUTPUTS = 1, FULL = 2;
    private static final Map<Class<?>, Boolean> INPUTS_STANDARD = new HashMap<>();
    private static final Map<Object, InputSnapshot> INPUTS = new IdentityHashMap<>();
    // Runtime bypass detection: machines whose ticks reached a hook since the last collection.
    private static final Set<Object> SEEN = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<Class<?>> HOOKED = new HashSet<>();
    private static final Map<Class<?>, Integer> STRIKES = new HashMap<>();
    private static final int BYPASS_STRIKES = 2;

    private Machines() {}

    public static Object call(Object o, String name) {
        try {
            Map<String, MethodHandle> map = METHODS.computeIfAbsent(o.getClass(), k -> new HashMap<>());
            MethodHandle m = map.get(name);
            if (m == null) {
                Class<?> c = ClassShape.declaring(o.getClass(), name, 0);
                if (c == null) throw new NoSuchMethodException(name);
                MethodType signature = MethodType
                    .fromMethodDescriptorString(ClassShape.descriptor(c, name, 0), c.getClassLoader());
                try {
                    m = MethodHandles.publicLookup()
                        .findVirtual(c, name, signature);
                } catch (IllegalAccessException protectedMethod) {
                    // Protected GT base-class helpers have no client-only signatures.
                    Method reflected = c.getDeclaredMethod(name);
                    reflected.setAccessible(true);
                    m = MethodHandles.lookup()
                        .unreflect(reflected);
                }
                map.put(name, m);
            }
            return m.invokeWithArguments(o);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(name, e);
        }
    }

    public static Object field(Object o, String name) {
        try {
            Map<String, Field> map = FIELDS.computeIfAbsent(o.getClass(), k -> new HashMap<>());
            Field f = map.get(name);
            if (f == null) {
                Class<?> c = ClassShape.declaring(o.getClass(), name, -1);
                if (c == null) throw new NoSuchFieldException(name);
                f = c.getDeclaredField(name);
                f.setAccessible(true);
                map.put(name, f);
            }
            return f.get(o);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(name, e);
        }
    }

    private static boolean inherits(Object o, String base) {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) if (c.getName()
            .equals(base)) return true;
        return false;
    }

    private static String declaring(Class<?> type, String name, int args) {
        Class<?> owner = ClassShape.declaring(type, name, args);
        return owner == null ? "" : owner.getName();
    }

    /**
     * Static checks only decide whether input snapshots are trustworthy. Whether the base processing loop runs at
     * all is decided at runtime in {@link #collect}: a class that is active but never reaches a hook is a bypass.
     */
    public static int level(Object machine) {
        if (STRIKES.getOrDefault(machine.getClass(), 0) >= BYPASS_STRIKES) return NONE;
        boolean multi = inherits(machine, MULTI);
        if (!multi && !inherits(machine, BASIC)) return NONE;
        boolean standard = INPUTS_STANDARD.computeIfAbsent(machine.getClass(), c -> {
            if (!multi)
                return declaring(c, "checkRecipe", 0).equals(BASIC) && declaring(c, "checkRecipe", 1).equals(BASIC);
            // Custom recipe selection is fine: consumption is measured as physical stack decreases.
            return declaring(c, "getStoredInputs", 0).equals(MULTI) && declaring(c, "getStoredFluids", 0).equals(MULTI);
        });
        if (!standard) return OUTPUTS;
        if (multi) {
            // Virtual inventories commit separately; count completions and outputs, not inferred consumption.
            if (!((List<?>) field(machine, "mDualInputHatches")).isEmpty()) return OUTPUTS;
            for (String group : new String[] { "mInputBusses", "mInputHatches" }) {
                for (Object hatch : (List<?>) field(machine, group)) {
                    String name = hatch.getClass()
                        .getName();
                    if (name.contains("ME") || name.contains("Debug")) return OUTPUTS;
                }
            }
        }
        return FULL;
    }

    /** Called from per-tick hook points; must stay cheap. */
    public static void seen(Object machine) {
        Gregfana mod = Gregfana.instance;
        if (mod != null && mod.ready) SEEN.add(machine);
    }

    private static int enabled(Object machine) {
        Gregfana mod = Gregfana.instance;
        if (mod == null || !mod.ready
            || !mod.config.machines.matcher(
                machine.getClass()
                    .getName())
                .matches())
            return NONE;
        return level(machine);
    }

    private static Map<String, String> labels(Object machine) {
        Object recipeMap = call(machine, "getRecipeMap");
        return Metrics.labels(
            "machine",
            String.valueOf(call(machine, "getMetaName")),
            "recipe_map",
            recipeMap == null ? "none" : String.valueOf(field(recipeMap, "unlocalizedName")));
    }

    private static void failure(Throwable e) {
        Gregfana mod = Gregfana.instance;
        if (mod != null && mod.ready) mod.metrics.add("gregfana_machine_hook_errors_total", Metrics.labels(), 1, false);
        Gregfana.LOG.debug("Machine instrumentation unavailable", e);
    }

    public static void before(Object machine, boolean basic) {
        seen(machine);
        try {
            int level = enabled(machine);
            if (level == NONE) return;
            InputSnapshot snapshot = new InputSnapshot();
            if (level < FULL) {
                // Outputs only: the empty snapshot still lets after() count the run start.
            } else if (basic) {
                for (ItemStack item : (ItemStack[]) call(machine, "getAllInputs")) snapshot.item(item);
                snapshot.fluid((FluidStack) call(machine, "getFillableStack"));
            } else {
                for (Object item : (List<?>) call(machine, "getStoredInputs")) snapshot.item((ItemStack) item);
                for (Object fluid : (List<?>) call(machine, "getStoredFluids")) snapshot.fluid((FluidStack) fluid);
                ItemStack[] inventory = (ItemStack[]) field(machine, "mInventory");
                if (inventory.length > 1) snapshot.item(inventory[1]);
            }
            INPUTS.put(machine, snapshot);
        } catch (Exception | LinkageError e) {
            failure(e);
        }
    }

    public static void after(Object machine, boolean success) {
        InputSnapshot snapshot = INPUTS.remove(machine);
        if (snapshot == null) return;
        try {
            Map<String, String> labels = labels(machine);
            if (success) Gregfana.instance.metrics.add("gregfana_machine_runs_started_total", labels, 1, false);
            snapshot.items.consumed((id, n) -> amount(labels, "items_consumed", "item", id, n));
            snapshot.fluids.consumed((id, n) -> amount(labels, "fluid_consumed_millibuckets", "fluid", id, n));
        } catch (Exception | LinkageError e) {
            failure(e);
        }
    }

    /** Coke oven: input is mInventory[0]; mInventory[1] is its output slot, so the multi snapshot does not apply. */
    public static void cokeBefore(Object machine) {
        seen(machine);
        try {
            if (enabled(machine) < FULL) return;
            InputSnapshot snapshot = new InputSnapshot();
            snapshot.item(((ItemStack[]) field(machine, "mInventory"))[0]);
            INPUTS.put(machine, snapshot);
        } catch (Exception | LinkageError e) {
            failure(e);
        }
    }

    /** A run started this tick iff a recipe is now set and has not progressed yet. */
    public static void cokeAfter(Object machine) {
        try {
            if (!INPUTS.containsKey(machine)) return;
            after(
                machine,
                ((Number) field(machine, "mMaxProgresstime")).intValue() > 0
                    && ((Number) field(machine, "mProgresstime")).intValue() == 0);
        } catch (Exception | LinkageError e) {
            INPUTS.remove(machine);
            failure(e);
        }
    }

    /** Called inside the completion branch, before output buffers are transferred/cleared. */
    public static void complete(Object machine, boolean basic) {
        try {
            if (enabled(machine) == NONE) return;
            if (((Number) field(machine, "mProgresstime")).intValue()
                < ((Number) field(machine, "mMaxProgresstime")).intValue()) return;
            Map<String, String> labels = labels(machine);
            Metrics metrics = Gregfana.instance.metrics;
            metrics.add("gregfana_machine_runs_completed_total", labels, 1, false);
            int parallel = 1;
            if (!basic) {
                Object logic = field(machine, "processingLogic");
                if (logic != null) parallel = Math.max(
                    ((Number) call(logic, "getCurrentParallels")).intValue(),
                    ((Number) field(machine, "lastParallel")).intValue());
            }
            metrics.add("gregfana_machine_recipes_completed_total", labels, Math.max(1, parallel), false);
            ItemStack[] items = (ItemStack[]) field(machine, "mOutputItems");
            if (items != null) for (ItemStack item : items)
                if (item != null) amount(labels, "items_produced", "item", Items.id(item), item.stackSize);
            FluidStack[] fluids = basic ? new FluidStack[] { (FluidStack) field(machine, "mOutputFluid") }
                : (FluidStack[]) field(machine, "mOutputFluids");
            if (fluids != null) for (FluidStack fluid : fluids) if (fluid != null) amount(
                labels,
                "fluid_produced_millibuckets",
                "fluid",
                fluid.getFluid()
                    .getName(),
                fluid.amount);
        } catch (Exception | LinkageError e) {
            failure(e);
        }
    }

    private static void amount(Map<String, String> labels, String metric, String key, String id, long amount) {
        if (amount <= 0) return;
        Metrics metrics = Gregfana.instance.metrics;
        metrics.add("gregfana_machine_" + metric + "_total", labels, amount, false);
        Map<String, String> detail = new HashMap<>(labels);
        detail.put(key, id);
        metrics.add("gregfana_machine_" + metric + "_by_resource_total", detail, amount, true);
    }

    public static void collect(Gregfana mod) {
        Map<String, long[]> counts = new HashMap<>();
        Set<Class<?>> unseenActive = new HashSet<>();
        for (WorldServer world : DimensionManager.getWorlds()) {
            for (Object obj : world.loadedTileEntityList) {
                TileEntity tile = (TileEntity) obj;
                if (tile.isInvalid() || !tile.getClass()
                    .getName()
                    .equals("gregtech.api.metatileentity.BaseMetaTileEntity")) continue;
                try {
                    Object machine = call(tile, "getMetaTileEntity");
                    if (machine == null || !mod.config.machines.matcher(
                        machine.getClass()
                            .getName())
                        .matches()) continue;
                    if (!inherits(machine, BASIC) && !inherits(machine, MULTI)) continue;
                    String type = String.valueOf(call(machine, "getMetaName"));
                    long[] count = counts.computeIfAbsent(type, k -> new long[4]);
                    count[0]++;
                    boolean active = (Boolean) call(tile, "isActive");
                    if (active) count[1]++;
                    Class<?> c = machine.getClass();
                    if (SEEN.contains(machine)) {
                        HOOKED.add(c);
                        STRIKES.remove(c);
                    } else if (active) unseenActive.add(c);
                    int level = level(machine);
                    if (level != NONE) count[2]++;
                    if (level == OUTPUTS) count[3]++;
                } catch (Exception | LinkageError e) {
                    failure(e);
                }
            }
        }
        // Active in consecutive collections without ever reaching a hook: the class runs its own loop.
        // A class seen hooking even once is never flagged (an idle or stalled instance is not a bypass).
        unseenActive.removeAll(HOOKED);
        for (Class<?> c : unseenActive) if (STRIKES.merge(c, 1, Integer::sum) == BYPASS_STRIKES)
            Gregfana.LOG.info("{} bypasses the standard processing loop; reporting it unsupported", c.getName());
        SEEN.clear();
        for (String metric : new String[] { "gregfana_machines_loaded", "gregfana_machines_active",
            "gregfana_machines_supported", "gregfana_machines_inputs_untracked" }) mod.metrics.clearGauges(metric);
        counts.forEach((name, count) -> {
            Map<String, String> labels = Metrics.labels("machine", name);
            mod.metrics.gauge("gregfana_machines_loaded", labels, count[0], false);
            mod.metrics.gauge("gregfana_machines_active", labels, count[1], false);
            mod.metrics.gauge("gregfana_machines_supported", labels, count[2], false);
            mod.metrics.gauge("gregfana_machines_inputs_untracked", labels, count[3], false);
        });
        // Drop snapshots left by exceptional recipe checks; never retain unloaded tile entities.
        INPUTS.clear();
    }

    public static void clear() {
        INPUTS.clear();
        METHODS.clear();
        FIELDS.clear();
        INPUTS_STANDARD.clear();
        SEEN.clear();
        HOOKED.clear();
        STRIKES.clear();
        ClassShape.clear();
    }

    private static final class InputSnapshot {

        final ResourceSnapshot<ItemStack> items = new ResourceSnapshot<>(Items::id, s -> s.stackSize);
        final ResourceSnapshot<FluidStack> fluids = new ResourceSnapshot<>(
            s -> s.getFluid()
                .getName(),
            s -> s.amount);

        void item(ItemStack s) {
            items.capture(s);
        }

        void fluid(FluidStack s) {
            fluids.capture(s);
        }
    }
}
