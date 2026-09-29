package net.nestworld.region;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Fires Forgified Fabric API's {@code ServerChunkEvents.CHUNK_UNLOAD} directly from
 * {@code ChunkMap.scheduleUnload()} (a source-level call, NOT a Mixin) -- see project
 * memory for the full trace: this exact event used to be fired by
 * {@code fabric-lifecycle-events-v1}'s {@code ThreadedAnvilChunkStorageMixin}, an
 * {@code @Inject} targeting {@code ChunkMap}'s scheduleUnload lambda by its VANILLA
 * compiler-assigned ordinal ({@code lambda$scheduleUnload$14}, SRG {@code m_202998_}).
 * NestWorld's own patches add lambda expressions earlier in {@code ChunkMap.java} than
 * vanilla has, which shifts that ordinal at compile time -- confirmed via javap on this
 * project's own build output: the SAME lambda (identical 4-arg signature) now compiles
 * to {@code lambda$scheduleUnload$16}. The Mixin's injection target, baked into its
 * refmap against vanilla's unshifted ordinal, can never match again -- this is a
 * structural fragility of ordinal-targeted lambda Mixins against ANY Forge project that
 * patches the same class, not a version-specific bug in that mod (a newer build of it
 * would still be baked against vanilla's own unmodified lambda count).
 *
 * <p>Firing the identical event here, once, directly from source at the exact point the
 * original Mixin injected (confirmed via its {@code @At(value="INVOKE",
 * target="LevelChunk;setLoaded(Z)V", shift=AFTER)} -- i.e. immediately after {@code
 * ((LevelChunk) chunk).setLoaded(false)}, same statement position as the existing Forge
 * {@code ChunkEvent.Unload} post right below it) sidesteps the ordinal problem entirely
 * and keeps working regardless of how many more lambdas future NestWorld patches add.
 *
 * <p>Soft dependency: Forgified Fabric API is frequently not installed at all (most of
 * this project's own test servers don't have it) -- everything here is reflection-gated
 * behind one static {@code Class.forName} probe, so this is a true zero-cost no-op when
 * that mod is absent.
 */
public final class NestworldFabricChunkEventsCompat {
    private static final Logger LOGGER = LogManager.getLogger("NestWorld/FabricChunkEventsCompat");

    private static final boolean PRESENT;
    private static Field chunkUnloadField;
    private static Method invokerMethod;
    private static Method onChunkUnloadMethod;

    static {
        boolean present = false;
        try {
            Class<?> eventsClass = Class.forName("net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents");
            Class<?> unloadIface = Class.forName("net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents$Unload");
            Class<?> eventClass = Class.forName("net.fabricmc.fabric.api.event.Event");
            chunkUnloadField = eventsClass.getField("CHUNK_UNLOAD");
            invokerMethod = eventClass.getMethod("invoker");
            onChunkUnloadMethod = unloadIface.getMethod("onChunkUnload", ServerLevel.class, LevelChunk.class);
            present = true;
        } catch (Throwable t) {
            // Forgified Fabric API not installed, or a version whose class/method shape
            // has itself since changed -- expected on most servers; stays a clean no-op.
        }
        PRESENT = present;
    }

    private NestworldFabricChunkEventsCompat() {}

    public static void fireChunkUnload(ServerLevel level, LevelChunk chunk) {
        if (!PRESENT) return;
        try {
            Object event = chunkUnloadField.get(null);
            Object invoker = invokerMethod.invoke(event);
            onChunkUnloadMethod.invoke(invoker, level, chunk);
        } catch (Throwable t) {
            LOGGER.warn("Failed to fire ServerChunkEvents.CHUNK_UNLOAD compat event: {}", t.toString());
        }
    }
}
