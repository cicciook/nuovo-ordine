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

    static final Extra[] ACCESSORY_EXTRAS = {
        // Sophisticated Backpacks: tier standard -> gold, rarita decrescente.
        new Extra("sophisticatedbackpacks:backpack", 40, 1, 1, ""),
        new Extra("sophisticatedbackpacks:copper_backpack", 28, 1, 1, ""),
        new Extra("sophisticatedbackpacks:iron_backpack", 16, 1, 1, ""),
        new Extra("sophisticatedbackpacks:gold_backpack", 8, 1, 1, ""),

        // Silenziatori TACZ base: entrano nel pool ACCESSORIES, piu frequente del pool armi.
        new Extra("tacz:attachment", 24, 1, 1, "{AttachmentId:\"tacz:muzzle_silencer_knight_qd\"}"),
        new Extra("tacz:attachment", 24, 1, 1, "{AttachmentId:\"tacz:muzzle_silencer_mirage\"}"),
        new Extra("tacz:attachment", 24, 1, 1, "{AttachmentId:\"tacz:muzzle_silencer_phantom_s1\"}"),
        new Extra("tacz:attachment", 24, 1, 1, "{AttachmentId:\"tacz:muzzle_silencer_ptilopsis\"}"),
        new Extra("tacz:attachment", 24, 1, 1, "{AttachmentId:\"tacz:muzzle_silencer_sg\"}"),
        new Extra("tacz:attachment", 24, 1, 1, "{AttachmentId:\"tacz:muzzle_silencer_ursus\"}"),
        new Extra("tacz:attachment", 24, 1, 1, "{AttachmentId:\"tacz:muzzle_silencer_vulture\"}"),
        new Extra("tacz:attachment", 24, 1, 1, "{AttachmentId:\"tacz:muzzle_silencer_wraith\"}")
    };

    static Object fix(Object value) {
        if (!(value instanceof String s)) return value;
        if (s.startsWith("lootr_more_loot_injected_")) return "lootr_more_loot_injected_1118";
        if (s.startsWith("lootr_more_loot_seen_")) return "lootr_more_loot_seen_1118.txt";
        return value;
    }

    static void array(MethodVisitor mv, String field, Path input) throws IOException {
        List<String> lines = Files.readAllLines(input, StandardCharsets.UTF_8);
        lines.removeIf(String::isBlank);
        mv.visitLdcInsn(lines.size());
        mv.visitTypeInsn(ANEWARRAY, ENTRY);
        for (int i = 0; i < lines.size(); i++) {
            String[] e = lines.get(i).split("\\t", -1);
            if (e.length != 5) throw new IOException("Riga TSV non valida: " + lines.get(i));
            mv.visitInsn(DUP);
            mv.visitLdcInsn(i);
            emitEntry(mv, new Extra(e[0], Integer.parseInt(e[1]), Integer.parseInt(e[2]),
                    Integer.parseInt(e[3]), e[4]));
            mv.visitInsn(AASTORE);
        }
        mv.visitFieldInsn(PUTSTATIC, OWNER, field, "[" + ED);
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

    static void appendAccessories(MethodVisitor mv) {
        mv.visitFieldInsn(GETSTATIC, OWNER, "ACCESSORIES", "[" + ED);
        mv.visitInsn(DUP);
        mv.visitInsn(ARRAYLENGTH);
        mv.visitLdcInsn(ACCESSORY_EXTRAS.length);
        mv.visitInsn(IADD);
        mv.visitMethodInsn(INVOKESTATIC, "java/util/Arrays", "copyOf",
                "([Ljava/lang/Object;I)[Ljava/lang/Object;", false);
        mv.visitTypeInsn(CHECKCAST, "[" + ED);
        mv.visitFieldInsn(PUTSTATIC, OWNER, "ACCESSORIES", "[" + ED);

        for (int i = 0; i < ACCESSORY_EXTRAS.length; i++) {
            mv.visitFieldInsn(GETSTATIC, OWNER, "ACCESSORIES", "[" + ED);
            mv.visitInsn(DUP);
            mv.visitInsn(ARRAYLENGTH);
            mv.visitLdcInsn(ACCESSORY_EXTRAS.length);
            mv.visitInsn(ISUB);
            mv.visitLdcInsn(i);
            mv.visitInsn(IADD);
            emitEntry(mv, ACCESSORY_EXTRAS[i]);
            mv.visitInsn(AASTORE);
        }
    }

    static byte[] patchLootOpenEvents(byte[] bytes, Path work) {
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
                            try {
                                array(this.mv, "WEAPONS", work.resolve("WEAPONS.tsv"));
                                array(this.mv, "AMMO", work.resolve("AMMO.tsv"));
                                appendAccessories(this.mv);
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
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

        Set<String> replacedSources = Set.of(
                "META-INF/loot-update-source/Patch.java",
                "META-INF/loot-update-source/WEAPONS.tsv",
                "META-INF/loot-update-source/AMMO.tsv",
                "META-INF/loot-update-source/build_tables.py",
                "META-INF/loot-update-source/README.md"
        );

        boolean patchedClass = false, patchedToml = false;
        try (ZipFile in = new ZipFile(input.toFile());
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(output))) {
            for (ZipEntry ze : Collections.list(in.entries())) {
                String name = ze.getName();
                if (replacedSources.contains(name)) continue;
                byte[] data = in.getInputStream(ze).readAllBytes();

                if (name.equals(OWNER + ".class")) {
                    data = patchLootOpenEvents(data, work);
                    patchedClass = true;
                } else if (name.equals("META-INF/mods.toml")) {
                    String toml = new String(data, StandardCharsets.UTF_8);
                    String updated = toml.replaceFirst("(?m)^version\\s*=\\s*\"[^\"]+\"", "version=\"1.1.18\"");
                    if (updated.equals(toml)) throw new IOException("Versione mod non trovata");
                    data = updated.getBytes(StandardCharsets.UTF_8);
                    patchedToml = true;
                } else if (name.equals("README.txt")) {
                    data = Files.readAllBytes(work.resolve("README.md"));
                }
                put(out, name, data);
            }

            for (String name : List.of("Patch.java", "WEAPONS.tsv", "AMMO.tsv", "build_tables.py", "README.md")) {
                put(out, "META-INF/loot-update-source/" + name, Files.readAllBytes(work.resolve(name)));
            }
        }

        if (!patchedClass || !patchedToml) {
            Files.deleteIfExists(output);
            throw new IOException("Patch incompleta: class=" + patchedClass + ", toml=" + patchedToml);
        }
    }
}
