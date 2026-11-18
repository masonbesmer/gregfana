package dev.gregfana.integration;

import static org.junit.Assert.*;

import java.io.InputStream;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Verify injection anchors against the pinned GT bytecode without loading its optional integrations. */
public class HookContractTest {

    private static final String BASE = "gregtech/api/metatileentity/implementations/";

    private ClassNode read(String name) throws Exception {
        return readPath(BASE + name);
    }

    private ClassNode readPath(String path) throws Exception {
        try (InputStream in = getClass().getClassLoader()
            .getResourceAsStream(path + ".class")) {
            assertNotNull("Pinned GT dev jar must be on test runtime", in);
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }

    private MethodNode method(ClassNode c, String name, String desc) {
        for (Object object : c.methods) {
            MethodNode method = (MethodNode) object;
            if (method.name.equals(name) && (desc == null || method.desc.equals(desc))) return method;
        }
        throw new AssertionError("Missing hook target " + name + desc);
    }

    @Test
    public void basicCompletionAnchorPrecedesOutputTransferAndClear() throws Exception {
        ClassNode basic = read("MTEBasicMachine");
        method(basic, "checkRecipe", "(Z)I");
        MethodNode tick = method(basic, "onPostTick", null);
        int firstOutputRead = -1;
        int progressWrite = -1;
        int transfer = -1;
        int clear = -1;
        for (int i = 0; i < tick.instructions.size(); i++) {
            AbstractInsnNode instruction = tick.instructions.get(i);
            if (instruction instanceof FieldInsnNode) {
                FieldInsnNode f = (FieldInsnNode) instruction;
                if (f.name.equals("mOutputItems") && f.getOpcode() == 180 && firstOutputRead == -1) firstOutputRead = i;
                if (f.name.equals("mProgresstime") && f.getOpcode() == 181 && progressWrite == -1) progressWrite = i;
            }
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode m = (MethodInsnNode) instruction;
                if (m.name.equals("addStackToSlot") && transfer == -1) transfer = i;
                if (m.owner.equals("java/util/Arrays") && m.name.equals("fill") && clear == -1) clear = i;
            }
        }
        assertTrue(
            progressWrite >= 0 && progressWrite < firstOutputRead && firstOutputRead < transfer && transfer < clear);
        // The completion hook must not be inside an output loop (which also evaluates its exit condition).
        for (int i = progressWrite + 1; i < tick.instructions.size(); i++) {
            if (tick.instructions.get(i) instanceof JumpInsnNode) {
                int target = tick.instructions.indexOf(((JumpInsnNode) tick.instructions.get(i)).label);
                assertFalse("Completion anchor is inside a loop", target <= progressWrite);
            }
        }
    }

    @Test
    public void multiCompletionAnchorIsUniqueAndBeforeOutputs() throws Exception {
        ClassNode multi = read("MTEMultiBlockBase");
        method(multi, "checkRecipe", "()Z");
        MethodNode run = method(multi, "runMachine", null);
        int increments = 0;
        int index = -1;
        int output = -1;
        for (int i = 0; i < run.instructions.size(); i++) {
            AbstractInsnNode instruction = run.instructions.get(i);
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode m = (MethodInsnNode) instruction;
                if (m.name.equals("incrementProgressTime")) {
                    increments++;
                    index = i;
                    assertEquals(BASE + "MTEMultiBlockBase", m.owner);
                }
                if (m.name.equals("addItemOutputs")) output = i;
            }
        }
        assertEquals(1, increments);
        assertTrue(index >= 0 && output > index);
    }

    @Test
    public void cokeOvenCompletionAnchorIsUniqueAndBeforeOutputClear() throws Exception {
        ClassNode coke = readPath("gregtech/common/tileentities/machines/multi/MTECokeOven");
        MethodNode check = method(coke, "checkRecipeProgress", null);
        int calls = 0;
        int anchor = -1;
        int clear = -1;
        for (int i = 0; i < check.instructions.size(); i++) {
            AbstractInsnNode instruction = check.instructions.get(i);
            if (instruction instanceof MethodInsnNode && ((MethodInsnNode) instruction).name.equals("addOutput")) {
                calls++;
                anchor = i;
            }
            if (instruction instanceof FieldInsnNode && ((FieldInsnNode) instruction).name.equals("mOutputItems")
                && instruction.getOpcode() == 181
                && clear == -1) clear = i;
        }
        assertEquals(1, calls);
        assertTrue(anchor >= 0 && anchor < clear);
        // The coke oven must still bypass the base-class hooks, or runs would be counted twice.
        for (Object object : coke.methods) assertNotEquals("runMachine", ((MethodNode) object).name);
    }

    private static final String STEAM = "gregtech/common/tileentities/machines/steam/MTESteam";

    @Test
    public void steamCheckRecipeOverridesAreAllHookedExactlyOnce() throws Exception {
        java.util.Set<String> declaring = new java.util.HashSet<>();
        java.util.List<String> classes = new java.util.ArrayList<>(
            java.util.Arrays.asList(BASE + "MTEBasicMachineBronze", BASE + "MTEBasicMachineSteel"));
        for (String machine : new String[] { "AlloySmelter", "Compressor", "Extractor", "ForgeHammer", "Furnace",
            "Macerator" }) for (String tier : new String[] { "Bronze", "Steel" }) classes.add(STEAM + machine + tier);
        for (String path : classes) {
            ClassNode c = readPath(path);
            for (Object object : c.methods) {
                MethodNode m = (MethodNode) object;
                if (!m.name.equals("checkRecipe") || !m.desc.equals("()I")) continue;
                declaring.add(path.replace('/', '.'));
                // A super/overload call would run the hook twice (or the BasicMachineMixin hook as well).
                for (int i = 0; i < m.instructions.size(); i++) if (m.instructions.get(i) instanceof MethodInsnNode)
                    assertNotEquals(path, "checkRecipe", ((MethodInsnNode) m.instructions.get(i)).name);
            }
        }
        assertEquals(dev.gregfana.source.Machines.STEAM_CHECKS, declaring);

        ClassNode mixin = readPath("dev/gregfana/mixin/SteamMachineMixin");
        java.util.Set<Object> targets = new java.util.HashSet<>();
        for (Object a : mixin.invisibleAnnotations) {
            java.util.List<?> values = ((org.objectweb.asm.tree.AnnotationNode) a).values;
            for (int i = 0; values != null && i < values.size(); i += 2) if (values.get(i)
                .equals("targets")) targets.addAll((java.util.List<?>) values.get(i + 1));
        }
        assertEquals(dev.gregfana.source.Machines.STEAM_CHECKS, targets);
    }
}
