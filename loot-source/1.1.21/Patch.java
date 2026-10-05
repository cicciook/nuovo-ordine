import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import jdk.internal.org.objectweb.asm.*;

public class Patch implements Opcodes {
    static final String OWNER = "com/cicciook/lootrmoreloot/LootOpenEvents";
    static final double OLD_WEAPON_CHANCE = 0.12d;
    static final double NEW_WEAPON_CHANCE = 0.08d;

    static Object fixMarker(Object value) {
        if (!(value instanceof String s)) return value;
        if (s.startsWith("lootr_more_loot_injected_")) return "lootr_more_loot_injected_1121";
        if (s.startsWith("lootr_more_loot_seen_")) return "lootr_more_loot_seen_1121.txt";
        return value;
    }

    static byte[] patchLootOpenEvents(byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        int[] weaponChanceReplacements = {0};

        cr.accept(new ClassVisitor(ASM8, cw) {
            @Override
            public FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
                return super.visitField(access, name, desc, sig, fixMarker(value));
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor parent = super.visitMethod(access, name, desc, sig, ex);
                boolean lootMethod = name.equals("injectBalancedLoot") && desc.equals("(Ljava/lang/Object;)Z");

                return new MethodVisitor(ASM8, parent) {
                    @Override
                    public void visitLdcInsn(Object value) {
                        Object updated = fixMarker(value);

                        // In injectBalancedLoot the first 0.12 is the WEAPONS roll.
                        // The second 0.12 controls high-tier Survival Instinct armor
                        // and must remain unchanged.
                        if (lootMethod
                                && weaponChanceReplacements[0] == 0
                                && value instanceof Double d
                                && Double.compare(d, OLD_WEAPON_CHANCE) == 0) {
                            updated = NEW_WEAPON_CHANCE;
                            weaponChanceReplacements[0]++;
                        }

                        super.visitLdcInsn(updated);
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String n, String d, Handle h, Object... args) {
                        for (int i = 0; i < args.length; i++) args[i] = fixMarker(args[i]);
                        super.visitInvokeDynamicInsn(n, d, h, args);
                    }
                };
            }
        }, 0);

        if (weaponChanceReplacements[0] != 1) {
            throw new IllegalStateException(
                    "Roll armi 0.12 non trovato una sola volta in injectBalancedLoot");
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
                            "version=\"1.1.21\"");
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
