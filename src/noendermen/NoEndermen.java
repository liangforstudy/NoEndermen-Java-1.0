package noendermen;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/**
 * NoEndermen for Minecraft Java 1.0.0 (the MineCon 2011 "lever pull" release).
 *
 * Patches a vanilla minecraft.jar so Endermen never exist:
 *   1. Removes every Enderman SpawnListEntry from biome spawn lists
 *      (overworld BiomeGenBase weight 1, The End BiomeGenEnd weight 10).
 *   2. Removes the "Enderman" (id 58) mapping from EntityList, so any Endermen
 *      already saved in existing worlds are skipped on chunk load.
 *
 * No obfuscated names are hard-coded: the Enderman class is found by its unique
 * sound string "mob.endermen.idle", and the patch sites are found by bytecode shape.
 *
 * Usage: java -jar NoEndermen-patcher.jar <minecraft-1.0.jar> [outputDir]
 * Outputs:
 *   minecraft-1.0-noendermen.jar  full patched jar (META-INF signature removed)
 *   NoEndermen-jarmod.zip         only the changed classes, for Prism "Add to Minecraft.jar"
 */
public class NoEndermen {
    static final String CLIENT_1_0_SHA1 = "b679fea27f2284836202e9365e13a82552092e5d";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("NoEndermen patcher for Minecraft Java 1.0.0");
            System.out.println("Usage: java -jar NoEndermen-patcher.jar <minecraft-1.0.jar> [outputDir]");
            System.exit(1);
        }
        File in = new File(args[0]);
        File outDir = new File(args.length > 1 ? args[1] : ".");
        outDir.mkdirs();

        byte[] jarBytes = readAll(new FileInputStream(in));
        String sha1 = sha1(jarBytes);
        if (CLIENT_1_0_SHA1.equals(sha1)) {
            System.out.println("Input: vanilla Minecraft 1.0 client jar (SHA-1 verified).");
        } else {
            System.out.println("WARNING: input SHA-1 " + sha1 + " is not the vanilla 1.0 client jar.");
            System.out.println("         Trying anyway; the patcher will refuse if it can't find what it needs.");
        }

        // Read every entry.
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (!e.isDirectory()) entries.put(e.getName(), readAll(zin));
            }
        }

        // 1) Find the Enderman class by its unique sound string.
        String enderman = null;
        for (Map.Entry<String, byte[]> en : entries.entrySet()) {
            if (!en.getKey().endsWith(".class")) continue;
            if (containsLdcString(en.getValue(), "mob.endermen.idle")) {
                if (enderman != null) fail("Found more than one Enderman candidate class.");
                enderman = en.getKey().substring(0, en.getKey().length() - 6);
            }
        }
        if (enderman == null) fail("Could not find the Enderman class (string \"mob.endermen.idle\").");
        System.out.println("Enderman class: " + enderman);

        // 2) Patch every class that references it.
        Map<String, byte[]> changed = new LinkedHashMap<>();
        int spawnRemoved = 0, registryRemoved = 0;
        for (Map.Entry<String, byte[]> en : entries.entrySet()) {
            String name = en.getKey();
            if (!name.endsWith(".class")) continue;
            ClassNode cn = new ClassNode();
            new ClassReader(en.getValue()).accept(cn, 0);
            int s = 0, r = 0;
            for (MethodNode mn : cn.methods) {
                s += removeSpawnEntries(mn, enderman);
                r += removeEntityListMapping(mn, enderman);
            }
            if (s + r > 0) {
                ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                cn.accept(cw);
                changed.put(name, cw.toByteArray());
                System.out.println("Patched " + name + ": " + (s > 0 ? s + " spawn entr" + (s == 1 ? "y" : "ies") + " removed " : "")
                        + (r > 0 ? "EntityList mapping removed" : ""));
                spawnRemoved += s;
                registryRemoved += r;
            }
        }
        if (spawnRemoved == 0) fail("No Enderman spawn entries found - is this really 1.0?");
        if (registryRemoved != 1) fail("Expected exactly 1 EntityList mapping, found " + registryRemoved + ".");

        // 3) Write the full patched jar (drop signature files, or the JVM rejects modified classes).
        File outJar = new File(outDir, "minecraft-1.0-noendermen.jar");
        try (ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(outJar))) {
            for (Map.Entry<String, byte[]> en : entries.entrySet()) {
                String n = en.getKey();
                if (n.startsWith("META-INF/")) continue;
                zout.putNextEntry(new ZipEntry(n));
                zout.write(changed.containsKey(n) ? changed.get(n) : en.getValue());
                zout.closeEntry();
            }
        }
        // 4) Write a jar-mod zip with just the changed classes.
        File outMod = new File(outDir, "NoEndermen-jarmod.zip");
        try (ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(outMod))) {
            for (Map.Entry<String, byte[]> en : changed.entrySet()) {
                zout.putNextEntry(new ZipEntry(en.getKey()));
                zout.write(en.getValue());
                zout.closeEntry();
            }
        }
        System.out.println();
        System.out.println("Done. Removed " + spawnRemoved + " spawn entries and the EntityList mapping.");
        System.out.println("  Full jar : " + outJar.getPath());
        System.out.println("  Jar mod  : " + outMod.getPath() + "  (Prism: Edit instance > Version > Add to Minecraft.jar)");
    }

    /**
     * Removes:  aload_0; getfield List; new SLE; dup; ldc Enderman; int; int; int;
     *           invokespecial SLE.<init>(Class,int,int,int); invokeinterface List.add; pop
     */
    static int removeSpawnEntries(MethodNode mn, String enderman) {
        int removed = 0;
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (!isLdcClass(insn, enderman)) continue;
            AbstractInsnNode dup = prev(insn), nw = prev(dup), gf = prev(nw), al = prev(gf);
            if (dup == null || dup.getOpcode() != Opcodes.DUP) continue;
            if (nw == null || nw.getOpcode() != Opcodes.NEW) continue;
            if (gf == null || gf.getOpcode() != Opcodes.GETFIELD || !((FieldInsnNode) gf).desc.equals("Ljava/util/List;")) continue;
            if (al == null || al.getOpcode() != Opcodes.ALOAD || ((VarInsnNode) al).var != 0) continue;
            AbstractInsnNode i1 = next(insn), i2 = next(i1), i3 = next(i2), init = next(i3), add = next(init), pop = next(add);
            if (!isIntPush(i1) || !isIntPush(i2) || !isIntPush(i3)) continue;
            if (init == null || init.getOpcode() != Opcodes.INVOKESPECIAL) continue;
            MethodInsnNode m = (MethodInsnNode) init;
            if (!m.name.equals("<init>") || !m.desc.equals("(Ljava/lang/Class;III)V") || !m.owner.equals(((TypeInsnNode) nw).desc)) continue;
            if (add == null || add.getOpcode() != Opcodes.INVOKEINTERFACE || !((MethodInsnNode) add).name.equals("add")) continue;
            if (pop == null || pop.getOpcode() != Opcodes.POP) continue;
            AbstractInsnNode cur = al;
            while (true) {
                AbstractInsnNode nx = cur.getNext();
                mn.instructions.remove(cur);
                if (cur == pop) break;
                cur = nx;
            }
            removed++;
        }
        return removed;
    }

    /** Removes:  ldc Enderman; ldc "..."; int; invokestatic X.(Class,String,int)V */
    static int removeEntityListMapping(MethodNode mn, String enderman) {
        int removed = 0;
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (!isLdcClass(insn, enderman)) continue;
            AbstractInsnNode str = next(insn), id = next(str), call = next(id);
            if (!(str instanceof LdcInsnNode) || !(((LdcInsnNode) str).cst instanceof String)) continue;
            if (!isIntPush(id)) continue;
            if (call == null || call.getOpcode() != Opcodes.INVOKESTATIC
                    || !((MethodInsnNode) call).desc.equals("(Ljava/lang/Class;Ljava/lang/String;I)V")) continue;
            mn.instructions.remove(insn);
            mn.instructions.remove(str);
            mn.instructions.remove(id);
            mn.instructions.remove(call);
            removed++;
        }
        return removed;
    }

    // ---- helpers ----
    static boolean isLdcClass(AbstractInsnNode n, String cls) {
        return n instanceof LdcInsnNode && ((LdcInsnNode) n).cst instanceof Type
                && ((Type) ((LdcInsnNode) n).cst).getInternalName().equals(cls);
    }
    static boolean isIntPush(AbstractInsnNode n) {
        if (n == null) return false;
        int op = n.getOpcode();
        return (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) || op == Opcodes.BIPUSH || op == Opcodes.SIPUSH
                || (n instanceof LdcInsnNode && ((LdcInsnNode) n).cst instanceof Integer);
    }
    static AbstractInsnNode prev(AbstractInsnNode n) {
        if (n == null) return null;
        n = n.getPrevious();
        while (n != null && n.getOpcode() < 0) n = n.getPrevious(); // skip labels/line numbers
        return n;
    }
    static AbstractInsnNode next(AbstractInsnNode n) {
        if (n == null) return null;
        n = n.getNext();
        while (n != null && n.getOpcode() < 0) n = n.getNext();
        return n;
    }
    static boolean containsLdcString(byte[] cls, String s) {
        ClassNode cn = new ClassNode();
        new ClassReader(cls).accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        for (MethodNode mn : cn.methods)
            for (AbstractInsnNode n : mn.instructions.toArray())
                if (n instanceof LdcInsnNode && s.equals(((LdcInsnNode) n).cst)) return true;
        return false;
    }
    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int r;
        while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
        return bo.toByteArray();
    }
    static String sha1(byte[] b) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte x : MessageDigest.getInstance("SHA-1").digest(b)) sb.append(String.format("%02x", x));
        return sb.toString();
    }
    static void fail(String msg) {
        System.err.println("ERROR: " + msg);
        System.exit(2);
    }
}
