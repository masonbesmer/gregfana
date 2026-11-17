package dev.gregfana.source;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Reads signatures without resolving client-only method/field types on a dedicated server. */
public final class ClassShape {

    private static final Map<Class<?>, ClassShape> CACHE = new HashMap<>();
    private final Set<String> methods = new HashSet<>();
    private final Map<String, String> descriptors = new HashMap<>();
    private final Set<String> fields = new HashSet<>();

    private ClassShape(Class<?> type) {
        String resource = type.getName()
            .replace('.', '/') + ".class";
        try (InputStream in = type.getClassLoader()
            .getResourceAsStream(resource)) {
            if (in == null) throw new IOException("Missing class resource " + resource);
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM5) {

                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String signature,
                    String[] exceptions) {
                    methods.add(name + "/" + Type.getArgumentTypes(desc).length);
                    descriptors.put(name + "/" + Type.getArgumentTypes(desc).length, desc);
                    return null;
                }

                @Override
                public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                    fields.add(name);
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (IOException e) {
            throw new IllegalStateException(resource, e);
        }
    }

    public static Class<?> declaring(Class<?> type, String name, int args) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            ClassShape shape = CACHE.computeIfAbsent(c, ClassShape::new);
            if (args < 0 ? shape.fields.contains(name) : shape.methods.contains(name + "/" + args)) return c;
        }
        return null;
    }

    public static void clear() {
        CACHE.clear();
    }

    public static String descriptor(Class<?> owner, String name, int args) {
        return CACHE.computeIfAbsent(owner, ClassShape::new).descriptors.get(name + "/" + args);
    }
}
