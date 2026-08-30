package net.nestworld.region;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Deployment-integrity check, added after a real incident this session: several hours were
 * spent investigating what looked like a mineflayer/protocol bug (RELEASE_USE_ITEM packets
 * "vanishing"), when the actual cause was that edits to vanilla-named classes (anything under
 * {@code net.minecraft.*}) were never reaching the deployed server at all -- {@code
 * :forge:universalJar} + manual copy (the pattern used successfully all session for pure {@code
 * net.nestworld.*} additions) silently ships NOTHING for vanilla-named files, because {@code
 * FilterNewJar}'s {@code isVanilla()} check excludes them from that jar entirely. Compiling
 * cleanly and "BUILD SUCCESSFUL" gave zero signal that the deploy had silently no-opped.
 *
 * <p>This logs, at server boot, a SHA-256 of the ACTUALLY-LOADED bytecode (via {@code
 * Class.getResourceAsStream} on the live classloader -- not a file on disk, the real running
 * class) for every vanilla-named class this project patches with a NestworldCore dispatcher
 * call. Compare this against a hash of the extracted class from the DEPLOYED artifact --
 * {@code unzip -p libraries/net/minecraftforge/forge/<ver>/forge-<ver>-server.jar
 * net/minecraft/.../Foo.class | sha256sum} -- after any deploy meant to ship a vanilla-class
 * edit; a mismatch means the deploy silently failed, exactly as it did repeatedly this session
 * before the real fix ({@code :forge:genPatches} -> {@code :forge:installerJar} ->
 * {@code --installServer}) was found. Do NOT compare against the raw {@code
 * build/classes/java/main/<path>.class} output -- that is PRE-reobfuscation and will always
 * differ from the runtime hash even on a fully successful deploy (reobf remaps the constant
 * pool), giving a false-alarm mismatch; the deployed {@code -server.jar} (post-reobf) is the
 * only valid comparison target. Also logs each per-mechanism master flag's live ON/OFF state
 * (opt-in sets still separately gate each one -- a flag being ON here does not mean any player
 * is currently opted in).
 */
public final class NestworldDeploymentFingerprint {
    private static final Logger LOGGER = LogManager.getLogger("NestWorld/RegionSystem");

    private NestworldDeploymentFingerprint() {}

    /** Classes under net.minecraft.* known to carry a NestworldCore dispatcher call as of this
     *  writing -- add to this list whenever a new vanilla-named file gets a new dispatcher hook,
     *  so the fingerprint log stays a complete cross-check, not a partial one. */
    private static final String[] PATCHED_VANILLA_CLASSES = {
            "net.minecraft.server.network.ServerGamePacketListenerImpl",
            "net.minecraft.server.players.PlayerList",
    };

    public static void logAtStartup() {
        LOGGER.info("NestWorldCore patch flags (master switch only -- each still needs its own "
                + "per-player opt-in set to actually route anything):");
        LOGGER.info("  PlayerInput        = {}", NestworldTuning.PLAYER_INPUT_REGION_EXECUTION);
        LOGGER.info("  PlayerTick         = {}", NestworldTuning.PLAYER_TICK_REGION_EXECUTION);
        LOGGER.info("  Attack             = {}", NestworldTuning.PLAYER_ATTACK_REGION_EXECUTION);
        LOGGER.info("  BlockInteraction   = {}", NestworldTuning.PLAYER_INTERACTION_REGION_EXECUTION);
        LOGGER.info("  UseItem            = {}", NestworldTuning.PLAYER_USE_ITEM_REGION_EXECUTION);
        LOGGER.info("  UseItemOnBlock     = {}", NestworldTuning.PLAYER_USE_ITEM_ON_BLOCK_REGION_EXECUTION);
        LOGGER.info("  EntityInteract     = {}", NestworldTuning.PLAYER_ENTITY_INTERACT_REGION_EXECUTION);
        LOGGER.info("NestWorldCore deployment fingerprint (SHA-256 of the ACTUALLY-LOADED bytecode "
                + "-- after any deploy touching a vanilla-named file, compare against: unzip -p "
                + "<deployed forge-<ver>-server.jar> <path>.class | sha256sum -- NOT build/classes, "
                + "which is pre-reobf and will mismatch even on a correct deploy):");
        for (String className : PATCHED_VANILLA_CLASSES) {
            logClassFingerprint(className);
        }
    }

    private static void logClassFingerprint(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            String resourcePath = "/" + className.replace('.', '/') + ".class";
            try (InputStream in = clazz.getResourceAsStream(resourcePath)) {
                if (in == null) {
                    LOGGER.warn("  {} = <could not locate loaded bytecode resource>", className);
                    return;
                }
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    md.update(buf, 0, n);
                }
                LOGGER.info("  {} = {}", className, HexFormat.of().formatHex(md.digest()));
            }
        } catch (Exception e) {
            LOGGER.warn("  {} = <fingerprint failed: {}>", className, e.getMessage());
        }
    }
}
