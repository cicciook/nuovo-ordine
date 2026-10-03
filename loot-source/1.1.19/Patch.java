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

    // Verified against sophisticatedbackpacks-1.20.1-3.26.3.2157.jar.
    // These also go in COMMON so they no longer depend only on the single accessory roll.
    static final Extra[] COMMON_EXTRAS = {
        new Extra("sophisticatedbackpacks:backpack", 10, 1, 1, ""),
        new Extra("sophisticatedbackpacks:copper_backpack", 7, 1, 1, ""),
        new Extra("sophisticatedbackpacks:iron_backpack", 4, 1, 1, ""),
        new Extra("sophisticatedbackpacks:gold_backpack", 2, 1, 1, ""),

        // Utility upgrades only: no magnet, void, feeding, stack, inception,
        // auto-processing, tank/battery or other high-power upgrades.
        new Extra("sophisticatedbackpacks:filter_upgrade", 5, 1, 1, ""),
        new Extra("sophisticatedbackpacks:pickup_upgrade", 4, 1, 1, ""),
        new Extra("sophisticatedbackpacks:crafting_upgrade", 3, 1, 1, ""),
        new Extra("sophisticatedbackpacks:deposit_upgrade", 3, 1, 1, ""),
        new Extra("sophisticatedbackpacks:refill_upgrade", 2, 1, 1, "")
    };

    // Bolt-action rifles previously over-filtered as "heavy sniper".
    // .50 BMG, .338-class heavy rifles, .408/.416, 20/25mm and launchers stay excluded.
    static final Extra[] WEAPON_EXTRAS = {
        new Extra("tacz:modern_kinetic_gun", 2, 1, 1,
                "{GunId:\"maxstuff:ai_awp\",GunFireMode:\"SEMI\",GunCurrentAmmoCount:5,HasBulletInBarrel:1b}"),
        new Extra("tacz:modern_kinetic_gun", 2, 1, 1,
                "{GunId:\"maxstuff:ai_aws\",GunFireMode:\"SEMI\",GunCurrentAmmoCount:5,HasBulletInBarrel:1b}"),
        new Extra("tacz:modern_kinetic_gun", 1, 1, 1,
                "{GunId:\"maxstuff:ar338\",GunFireMode:\"SEMI\",GunCurrentAmmoCount:5,HasBulletInBarrel:1b}")
    };

    static Object fix(Object value) {
        if (!(value instanceof String s)) return value;
        if (s.startsWith("lootr_more_loot_injected_")) return "lootr_more_loot_injected_1119";
        if (s.startsWith("lootr_more_loot_seen_")) return "lootr_more_loot_seen_1119.txt";
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

    static void append(MethodVisitor mv, String field, Extra[] extras) {
        mv.visitFieldInsn(GETSTATIC, OWNER, field, "[" + ED);
        mv.visitInsn(DUP);
        mv.visitInsn(ARRAYLENGTH);
        mv.visitLdcInsn(extras.length);
        mv.visitInsn(IADD);
        mv.visitMethodInsn(INVOKESTATIC, "java/util/Arrays", "copyOf",
                "([Ljava/lang/Object;I)[Ljava/lang/Object;", false);
        mv.visitTypeInsn(CHECKCAST, "[" + ED);
        mv.visitFieldInsn(PUTSTATIC, OWNER, field, "[" + ED);

        for (int i = 0; i < extras.length; i++) {
            mv.visitFieldInsn(GETSTATIC, OWNER, field, "[" + ED);
            mv.visitInsn(DUP);
            mv.visitInsn(ARRAYLENGTH);
            mv.visitLdcInsn(extras.length);
            mv.visitInsn(ISUB);
            mv.visitLdcInsn(i);
            mv.visitInsn(IADD);
            emitEntry(mv, extras[i]);
            mv.visitInsn(AASTORE);
        }
    }

    static byte[] patchLootOpenEvents(byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cr.accept(new ClassVisitor(ASM8, cw) {
            @Override
            public FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
                return super.visitField(access, name, desc, sig, fix(value));
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor parent = super.visitMethod(access, name, desc, sig, ex);
                return new MethodVisitor(ASM8, parent) {
                    @Override
                    public void visitLdcInsn(Object value) {
                        super.visitLdcInsn(fix(value));
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String n, String d, Handle h, Object... args) {
                        for (int i = 0; i < args.length; i++) args[i] = fix(args[i]);
                        super.visitInvokeDynamicInsn(n, d, h, args);
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        if (name.equals("<clinit>") && opcode == RETURN) {
                            append(this.mv, "COMMON", COMMON_EXTRAS);
                            append(this.mv, "WEAPONS", WEAPON_EXTRAS);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);
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
                            "version=\"1.1.19\"");
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
