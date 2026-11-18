package dev.gregfana.source;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

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
    private static final Map<Class<?>, Boolean> SUPPORTED = new HashMap<>();
    private static final Map<Object, InputSnapshot> INPUTS = new IdentityHashMap<>();

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

    public static boolean supported(Object machine) {
        boolean standard = SUPPORTED.computeIfAbsent(machine.getClass(), c -> {
            if (inherits(machine, BASIC))
                return declaring(c, "onPostTick", 2).equals(BASIC) && declaring(c, "checkRecipe", 0).equals(BASIC)
                    && declaring(c, "checkRecipe", 1).equals(BASIC);
            if (inherits(machine, MULTI))
                return declaring(c, "runMachine", 2).equals(MULTI) && declaring(c, "checkProcessing", 0).equals(MULTI)
                    && declaring(c, "doCheckRecipe", 0).equals(MULTI)
                    && declaring(c, "getStoredInputs", 0).equals(MULTI)
                    && declaring(c, "getStoredFluids", 0).equals(MULTI)
                    && declaring(c, "getStoredInputsForColor", 1).equals(MULTI)
                    && declaring(c, "getStoredFluidsForColor", 1).equals(MULTI)
                    && declaring(c, "checkRecipeForCustomHatches", 1).equals(MULTI);
            return false;
        });
        if (!standard) return false;
        if (inherits(machine, MULTI)) {
            // Virtual inventories commit separately; report unsupported rather than inferred consumption.
            if (!((List<?>) field(machine, "mDualInputHatches")).isEmpty()) return false;
            for (String group : new String[] { "mInputBusses", "mInputHatches" }) {
                for (Object hatch : (List<?>) field(machine, group)) if (hatch.getClass()
                    .getName()
                    .contains("ME")
                    || hatch.getClass()
                        .getName()
                        .contains("Debug"))
                    return false;
            }
        }
        return true;
    }

    private static boolean enabled(Object machine) {
        Gregfana mod = Gregfana.instance;
        return mod != null && mod.ready
            && mod.config.machines.matcher(
                machine.getClass()
                    .getName())
                .matches()
            && supported(machine);
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
        try {
            if (!enabled(machine)) return;
            InputSnapshot snapshot = new InputSnapshot();
            if (basic) {
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
        try {
            if (!enabled(machine)) return;
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
            if (!enabled(machine)) return;
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
                    long[] count = counts.computeIfAbsent(type, k -> new long[3]);
                    count[0]++;
                    if ((Boolean) call(tile, "isActive")) count[1]++;
                    if (supported(machine)) count[2]++;
                } catch (Exception | LinkageError e) {
                    failure(e);
                }
            }
        }
        for (String metric : new String[] { "gregfana_machines_loaded", "gregfana_machines_active",
            "gregfana_machines_supported" }) mod.metrics.clearGauges(metric);
        counts.forEach((name, count) -> {
            Map<String, String> labels = Metrics.labels("machine", name);
            mod.metrics.gauge("gregfana_machines_loaded", labels, count[0], false);
            mod.metrics.gauge("gregfana_machines_active", labels, count[1], false);
            mod.metrics.gauge("gregfana_machines_supported", labels, count[2], false);
        });
        // Drop snapshots left by exceptional recipe checks; never retain unloaded tile entities.
        INPUTS.clear();
    }

    public static void clear() {
        INPUTS.clear();
        METHODS.clear();
        FIELDS.clear();
        SUPPORTED.clear();
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
