import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import jdk.internal.org.objectweb.asm.*;

public class Patch implements Opcodes {
    static final String OWNER = "com/cicciook/lootrmoreloot/LootOpenEvents";
    static final String ENTRY = OWNER + "$Entry";
    static final String ED = "L" + ENTRY + ";";

    record Extra(String item, int weight, int min, int max, String nbt) {}

    // Nuovo Ordine 1.1.22: il pool AMMO non contiene piu munizioni TaCZ.
    // I pesi mantengono prevalenti le munizioni da fucile/pistola e rendono
    // progressivamente piu rare shotgun, sniper e heavy.
    static final Extra[] SUPERB_AMMO = {
        new Extra("superbwarfare:handgun_ammo", 16, 20, 32, ""),
        new Extra("superbwarfare:rifle_ammo",   20, 20, 32, ""),
        new Extra("superbwarfare:shotgun_ammo", 10, 16, 24, ""),
        new Extra("superbwarfare:sniper_ammo",   8, 12, 20, ""),
        new Extra("superbwarfare:heavy_ammo",    4,  8, 16, "")
    };

    static Object fixMarker(Object value) {
        if (!(value instanceof String s)) return value;
        if (s.startsWith("lootr_more_loot_injected_")) return "lootr_more_loot_injected_1122";
        if (s.startsWith("lootr_more_loot_seen_")) return "lootr_more_loot_seen_1122.txt";
        return value;
    }

    static void emitEntry(MethodVisitor mv, Extra e) {
        mv.visitLdcInsn(e.item());
        mv.visitLdcInsn(e.weight());
        mv.visitLdcInsn(e.min());
        mv.visitLdcInsn(e.max());
        if (e.nbt() == null || e.nbt().isEmpty()) {
            mv.visitMethodInsn(INVOKESTATIC, OWNER, "e", "(Ljava/lang/String;III)" + ED, false);
        } else {
            mv.visitLdcInsn(e.nbt());
            mv.visitMethodInsn(INVOKESTATIC, OWNER, "n",
                    "(Ljava/lang/String;IIILjava/lang/String;)" + ED, false);
        }
    }

    static void replaceAmmo(MethodVisitor mv) {
        mv.visitLdcInsn(SUPERB_AMMO.length);
        mv.visitTypeInsn(ANEWARRAY, ENTRY);
        for (int i = 0; i < SUPERB_AMMO.length; i++) {
            mv.visitInsn(DUP);
            mv.visitLdcInsn(i);
            emitEntry(mv, SUPERB_AMMO[i]);
            mv.visitInsn(AASTORE);
        }
        mv.visitFieldInsn(PUTSTATIC, OWNER, "AMMO", "[" + ED);
    }

    static byte[] patchLootOpenEvents(byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        int[] replacements = {0};

        cr.accept(new ClassVisitor(ASM8, cw) {
            @Override
            public FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
                return super.visitField(access, name, desc, sig, fixMarker(value));
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor parent = super.visitMethod(access, name, desc, sig, ex);
                return new MethodVisitor(ASM8, parent) {
                    @Override
                    public void visitLdcInsn(Object value) {
                        super.visitLdcInsn(fixMarker(value));
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String n, String d, Handle h, Object... args) {
                        for (int i = 0; i < args.length; i++) args[i] = fixMarker(args[i]);
                        super.visitInvokeDynamicInsn(n, d, h, args);
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        if (name.equals("<clinit>") && opcode == RETURN) {
                            replaceAmmo(this.mv);
                            replacements[0]++;
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);

        if (replacements[0] != 1) {
            throw new IllegalStateException("Sostituzione AMMO attesa una volta, trovata " + replacements[0]);
        }
        return cw.toByteArray();
    }

    static void put(ZipOutputStream out, String name, byte[] bytes) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(bytes);
        out.closeEntry();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Uso: Patch input.jar output.jar workdir");
        Path input = Path.of(args[0]), output = Path.of(args[1]), work = Path.of(args[2]);

        Set<String> replaced = Set.of(
                "META-INF/loot-update-source/Patch.java",
                "META-INF/loot-update-source/README.md"
        );

        boolean patchedClass = false, patchedToml = false;
        try (ZipFile in = new ZipFile(input.toFile());
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(output))) {
            for (ZipEntry ze : Collections.list(in.entries())) {
                String name = ze.getName();
                if (replaced.contains(name)) continue;
                byte[] data = in.getInputStream(ze).readAllBytes();

                if (name.equals(OWNER + ".class")) {
                    data = patchLootOpenEvents(data);
                    patchedClass = true;
                } else if (name.equals("META-INF/mods.toml")) {
                    String toml = new String(data, StandardCharsets.UTF_8);
                    String updated = toml.replaceFirst(
                            "(?m)^version\\s*=\\s*\"[^\"]+\"",
                            "version=\"1.1.22\"");
                    if (updated.equals(toml)) throw new IOException("Versione mod non trovata");
                    data = updated.getBytes(StandardCharsets.UTF_8);
                    patchedToml = true;
                } else if (name.equals("README.txt")) {
                    data = Files.readAllBytes(work.resolve("README.md"));
                }
                put(out, name, data);
            }

            put(out, "META-INF/loot-update-source/Patch.java", Files.readAllBytes(work.resolve("Patch.java")));
            put(out, "META-INF/loot-update-source/README.md", Files.readAllBytes(work.resolve("README.md")));
        }

        if (!patchedClass || !patchedToml) {
            Files.deleteIfExists(output);
            throw new IOException("Patch incompleta: class=" + patchedClass + ", toml=" + patchedToml);
        }
    }
}
