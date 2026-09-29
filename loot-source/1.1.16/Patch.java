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

    static void array(MethodVisitor mv, String field, Path input) throws IOException {
        List<String> lines = Files.readAllLines(input, StandardCharsets.UTF_8);
        lines.removeIf(String::isBlank);
        mv.visitLdcInsn(lines.size());
        mv.visitTypeInsn(ANEWARRAY, ENTRY);
        for (int i = 0; i < lines.size(); i++) {
            String[] e = lines.get(i).split("\\t", -1);
            if (e.length != 5) {
                throw new IOException("Riga TSV non valida in " + input + ": " + lines.get(i));
            }
            mv.visitInsn(DUP);
            mv.visitLdcInsn(i);
            mv.visitLdcInsn(e[0]);
            mv.visitLdcInsn(Integer.parseInt(e[1]));
            mv.visitLdcInsn(Integer.parseInt(e[2]));
            mv.visitLdcInsn(Integer.parseInt(e[3]));
            if (e[4].isEmpty()) {
                mv.visitMethodInsn(INVOKESTATIC, OWNER, "e", "(Ljava/lang/String;III)" + ED, false);
            } else {
                mv.visitLdcInsn(e[4]);
                mv.visitMethodInsn(INVOKESTATIC, OWNER, "n", "(Ljava/lang/String;IIILjava/lang/String;)" + ED, false);
            }
            mv.visitInsn(AASTORE);
        }
        mv.visitFieldInsn(PUTSTATIC, OWNER, field, "[" + ED);
    }

    static byte[] patchLootOpenEvents(byte[] bytes, Path work) {
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cr.accept(new ClassVisitor(ASM8, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor parent = super.visitMethod(access, name, desc, sig, ex);
                return new MethodVisitor(ASM8, parent) {
                    @Override
                    public void visitInsn(int opcode) {
                        if (name.equals("<clinit>") && opcode == RETURN) {
                            try {
                                array(this.mv, "WEAPONS", work.resolve("WEAPONS.tsv"));
                                array(this.mv, "AMMO", work.resolve("AMMO.tsv"));
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
        if (args.length != 3) {
            throw new IllegalArgumentException("Uso: Patch input.jar output.jar workdir");
        }
        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);
        Path work = Path.of(args[2]);
        Set<String> replacedSources = Set.of(
            "META-INF/loot-update-source/Patch.java",
            "META-INF/loot-update-source/WEAPONS.tsv",
            "META-INF/loot-update-source/AMMO.tsv",
            "META-INF/loot-update-source/build_addon_catalog.py",
            "META-INF/loot-update-source/ADDON_REPORT.json"
        );

        boolean patchedClass = false;
        boolean patchedToml = false;
        try (ZipFile in = new ZipFile(input.toFile());
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(output))) {
            for (ZipEntry ze : Collections.list(in.entries())) {
                String name = ze.getName();
                if (replacedSources.contains(name)) {
                    continue;
                }
                byte[] bytes = in.getInputStream(ze).readAllBytes();
                if (name.equals(OWNER + ".class")) {
                    bytes = patchLootOpenEvents(bytes, work);
                    patchedClass = true;
                } else if (name.equals("META-INF/mods.toml")) {
                    String toml = new String(bytes, StandardCharsets.UTF_8);
                    String updated = toml.replaceFirst(
                        "(?m)^version\\s*=\\s*\"[^\"]+\"",
                        "version=\"1.1.16\""
                    );
                    if (updated.equals(toml)) {
                        throw new IOException("Versione mod non trovata in META-INF/mods.toml");
                    }
                    bytes = updated.getBytes(StandardCharsets.UTF_8);
                    patchedToml = true;
                }
                put(out, name, bytes);
            }

            put(out, "META-INF/loot-update-source/Patch.java", Files.readAllBytes(work.resolve("Patch.java")));
            put(out, "META-INF/loot-update-source/WEAPONS.tsv", Files.readAllBytes(work.resolve("WEAPONS.tsv")));
            put(out, "META-INF/loot-update-source/AMMO.tsv", Files.readAllBytes(work.resolve("AMMO.tsv")));
            put(out, "META-INF/loot-update-source/build_addon_catalog.py", Files.readAllBytes(work.resolve("build_addon_catalog.py")));
            put(out, "META-INF/loot-update-source/ADDON_REPORT.json", Files.readAllBytes(work.resolve("ADDON_REPORT.json")));
        }

        if (!patchedClass || !patchedToml) {
            Files.deleteIfExists(output);
            throw new IOException("Patch incompleta: class=" + patchedClass + ", toml=" + patchedToml);
        }
    }
}
