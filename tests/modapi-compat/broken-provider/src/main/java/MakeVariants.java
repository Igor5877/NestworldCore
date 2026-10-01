import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** args: <published provider jar> <output dir>. See build.gradle for the variant list. */
public class MakeVariants {
    static final String IFACE = "net/nestworld/modapi/inventory/InventorySetBackend.class";
    static final String REGISTRY = "net/nestworld/modapi/inventory/InventorySetBackendRegistry.class";

    public static void main(String[] a) throws Exception {
        Path in = Path.of(a[0]), out = Path.of(a[1]);
        String verified = "1.0.0-SNAPSHOT";
        make(in, out.resolve("provider-unverified-1.0.1.jar"), "1.0.1", "none");
        make(in, out.resolve("provider-abi-remove-setSlot.jar"), verified, "remove-setSlot");
        make(in, out.resolve("provider-abi-add-abstract.jar"), verified, "add-abstract");
        make(in, out.resolve("provider-abi-delete-registry.jar"), verified, "delete-registry");
        make(in, out.resolve("provider-9.0.0-remove-setSlot.jar"), "9.0.0", "remove-setSlot");
    }

    static void make(Path inJar, Path outJar, String version, String mode) throws Exception {
        boolean touched = mode.equals("none");
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(inJar));
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(outJar))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                byte[] b = in.readAllBytes();
                String n = e.getName();
                if (n.equals("META-INF/mods.toml")) {
                    b = new String(b, "UTF-8").replaceAll("version=\"[^\"]+\"", "version=\"" + version + "\"").getBytes("UTF-8");
                } else if (mode.equals("delete-registry") && n.equals(REGISTRY)) {
                    touched = true;
                    continue;
                } else if (n.equals(IFACE) && (mode.equals("remove-setSlot") || mode.equals("add-abstract"))) {
                    ClassWriter cw = new ClassWriter(0);
                    boolean[] changed = {false};
                    new ClassReader(b).accept(new ClassVisitor(Opcodes.ASM9, cw) {
                        @Override public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
                            if (mode.equals("remove-setSlot") && name.equals("setSlot")) { changed[0] = true; return null; }
                            return super.visitMethod(acc, name, desc, sig, ex);
                        }
                        @Override public void visitEnd() {
                            if (mode.equals("add-abstract")) {
                                super.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "flush", "()V", null, null).visitEnd();
                                changed[0] = true;
                            }
                            super.visitEnd();
                        }
                    }, 0);
                    b = cw.toByteArray();
                    touched |= changed[0];
                }
                out.putNextEntry(new ZipEntry(n));
                out.write(b);
                out.closeEntry();
            }
        }
        if (!touched) throw new IllegalStateException(mode + ": nothing changed -- the provider layout differs from what this generator expects");
        System.out.println("wrote " + outJar.getFileName() + " (version " + version + ", " + mode + ")");
    }
}
