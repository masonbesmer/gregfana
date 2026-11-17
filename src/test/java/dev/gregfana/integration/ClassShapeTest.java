package dev.gregfana.integration;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import org.junit.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import dev.gregfana.source.ClassShape;

public class ClassShapeTest {

    @Test
    public void doesNotResolveUnrelatedClientOnlySignatures() throws Exception {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
            "fixture/ServerMachine",
            null,
            "java/lang/Object",
            null);
        writer
            .visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "clientOnly",
                "(Lmissing/client/ResourceManager;)V",
                null,
                null)
            .visitEnd();
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "checkRecipe", "()Z", null, null)
            .visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "progress", "I", null, null)
            .visitEnd();
        writer.visitEnd();
        byte[] bytes = writer.toByteArray();
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {

            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (name.equals("fixture.ServerMachine")) return defineClass(name, bytes, 0, bytes.length);
                throw new ClassNotFoundException(name);
            }

            @Override
            public InputStream getResourceAsStream(String name) {
                return name.equals("fixture/ServerMachine.class") ? new ByteArrayInputStream(bytes)
                    : super.getResourceAsStream(name);
            }
        };
        Class<?> type = loader.loadClass("fixture.ServerMachine");
        try {
            type.getDeclaredMethods();
            fail("Reflection should fail on the missing client type");
        } catch (NoClassDefFoundError expected) { /* The production code must avoid this path. */ }
        assertSame(type, ClassShape.declaring(type, "checkRecipe", 0));
        assertNotNull(
            java.lang.invoke.MethodHandles.publicLookup()
                .findVirtual(
                    type,
                    "checkRecipe",
                    java.lang.invoke.MethodType
                        .fromMethodDescriptorString(ClassShape.descriptor(type, "checkRecipe", 0), loader)));
        assertSame(type, ClassShape.declaring(type, "progress", -1));
        assertNull(ClassShape.declaring(type, "absent", 0));
    }
}
