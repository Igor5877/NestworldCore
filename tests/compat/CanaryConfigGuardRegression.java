import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraftforge.fml.loading.NestworldCanaryConfigGuard;

final class CanaryConfigGuardRegression {
    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Path mods = Files.createDirectories(root.resolve("mods"));
        Path config = root.resolve("config/canary.properties");
        require(!NestworldCanaryConfigGuard.containsCanaryJar(mods), "absent Canary must be ignored");
        Files.createFile(mods.resolve("canary-mc1.20.1.jar"));
        require(NestworldCanaryConfigGuard.containsCanaryJar(mods), "Canary JAR must be detected");

        Files.createDirectories(config.getParent());
        Files.writeString(config, "mixin.other=true\n"
                + "mixin.collections.entity_by_type=true\n"
                + "mixin.collections.entity_by_type=false\n"
                + "mixin.ai.poi=true\n");
        require(!NestworldCanaryConfigGuard.allDisabled(config), "partial config must be unsafe");
        require(NestworldCanaryConfigGuard.ensureDisabled(config), "conflicting options must change");
        require(NestworldCanaryConfigGuard.allDisabled(config), "all four options must be false");
        String after = Files.readString(config);
        require(after.contains("mixin.other=true"), "unrelated option must be preserved");
        require(!after.contains("entity_by_type=true") && !after.contains("mixin.ai.poi=true"),
                "old conflicting true values must be removed");
        require(!NestworldCanaryConfigGuard.ensureDisabled(config), "second run must be idempotent");
        require(Files.readString(config).equals(after), "idempotent run must not rewrite config");
        System.out.println("Canary pre-load configuration regression passed");
    }
}
