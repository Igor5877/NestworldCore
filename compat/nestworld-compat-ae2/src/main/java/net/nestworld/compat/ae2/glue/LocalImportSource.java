package net.nestworld.compat.ae2.glue;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Owner-domain snapshot only. Call immediately before every item strategy/return mutation.
 * False requires the original MAIN path before extracting anything from this source.
 * This proves no supported backing/controller link is present; it is not a lock or a lease.
 */
final class LocalImportSource {
    private static final String PREFIX = "net.p3pp3rf1y.sophisticatedstorage.block.";
    private static final Map<String, String> HASHES = Map.of(
            "BarrelBlockEntity", "69a36449bf4e7863cea2b653c5b95f0abb2e3eb298d47c86ee9068d55c13dfe2",
            "LimitedBarrelBlockEntity", "a5d10c5f529640ec3530093a91c38f56a1cb6c8e3d70a10cff56eadc5fbabe98",
            "WoodStorageBlockEntity", "02e568574c7b9fa9354cc3bdb32f63d6146a4b5e41e7bba98d127835c0a2cb22",
            "StorageBlockEntity", "029e6775fd14e12b52369cc797305300c3b9ded0e369070a60f51f14fd892612",
            "StorageWrapper", "e485e3df60e06d805514c26dcc3168f9b6c2e34b0f05dd858aa27f3727e2c3f4",
            "StorageBlockEntity$1", "628029297b03353c9982eb5c9e98c626e63f4e3851ba3b176154cb4f936530df");
    private static final Proof UNKNOWN = new Proof(null, null, List.of(), List.of(), List.of());
    private static final ClassValue<Proof> PROOFS = new ClassValue<>() {
        @Override
        protected Proof computeValue(Class<?> type) {
            String name = type.getName();
            if (!name.equals(PREFIX + "BarrelBlockEntity") && !name.equals(PREFIX + "LimitedBarrelBlockEntity")) return UNKNOWN;
            try {
                Class<?> base = type;
                while (!base.getName().equals(PREFIX + "StorageBlockEntity")) {
                    if (!matches(base)) return UNKNOWN;
                    base = base.getSuperclass();
                    if (base == null) return UNKNOWN;
                }
                if (!matches(base)) return UNKNOWN;
                ClassLoader loader = type.getClassLoader();
                Class<?> wrapper = Class.forName(PREFIX + "StorageWrapper", false, loader);
                Class<?> concrete = Class.forName(PREFIX + "StorageBlockEntity$1", false, loader);
                if (!matches(wrapper) || !matches(concrete) || concrete.getSuperclass() != wrapper) return UNKNOWN;
                List<MethodHandle> sourceNulls = fields(base,
                        "controllerPos", "linkedStorageEndpoint", "linkedStorageHost");
                List<MethodHandle> sourceFalse = fields(base,
                        "isLinkedToController", "refreshingLinkedStorage", "isBeingUpgraded", "isDroppingContents", "chunkBeingUnloaded");
                List<MethodHandle> wrapperNulls = fields(wrapper,
                        "linkedStorage", "linkedInventory", "linkedUpgrades", "linkedFluidHandler", "linkedEnergyStorage",
                        "linkedStorageEndpoint", "linkedStorageEndpointRole");
                return new Proof(getter(base, "storageWrapper"), concrete, sourceNulls, sourceFalse, wrapperNulls);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                return UNKNOWN;
            }
        }
    };

    private LocalImportSource() {}

    /** MAIN-safe static pre-filter on the block-entity type id only (no source state is read): types that can never
     *  satisfy {@link #local} stay on AE2's native MAIN path instead of paying a handoff per operation. */
    static boolean possibleType(String beTypeId) {
        return beTypeId != null && (POSSIBLE_TYPES.contains(beTypeId)
                || (CoreBeGroups.available() && beTypeId.startsWith("mm:") && beTypeId.contains("_item_port_")));
    }

    private static final java.util.Set<String> POSSIBLE_TYPES = java.util.Set.of(
            "minecraft:barrel", "minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker",
            "sophisticatedstorage:barrel", "sophisticatedstorage:limited_barrel");

    static boolean local(BlockEntity source) {
        if (source == null || source.isRemoved()) return false;
        Class<?> type = source.getClass();
        // Exact identities exclude arbitrary subclasses and all chest aggregation paths.
        if (type == net.minecraft.world.level.block.entity.BarrelBlockEntity.class
                || type == net.minecraft.world.level.block.entity.FurnaceBlockEntity.class
                || type == net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity.class
                || type == net.minecraft.world.level.block.entity.SmokerBlockEntity.class) return true;
        // MM-GROUP-DOMAIN-001: an audited MasterfulMachinery item port is local only while its whole multiblock group
        // (registered in Core) is on this thread's domain — the controller is the only other writer of its handler.
        if (CertifiedItemImportSources.mmItemPort(source)) {
            return source.getLevel() instanceof net.minecraft.server.level.ServerLevel level
                    && mmTogetherHere(level, source);
        }
        Proof proof = PROOFS.get(type);
        if (proof == UNKNOWN) return false;
        try {
            for (MethodHandle field : proof.sourceNulls) if (field.invoke(source) != null) return false;
            for (MethodHandle field : proof.sourceFalse) if ((boolean) field.invoke(source)) return false;
            Object wrapper = proof.wrapper.invoke(source);
            if (wrapper == null || wrapper.getClass() != proof.wrapperClass) return false;
            for (MethodHandle field : proof.wrapperNulls) if (field.invoke(wrapper) != null) return false;
            return true;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    private static boolean matches(Class<?> type) {
        String name = type.getName();
        if (!name.startsWith(PREFIX)) return false;
        String hash = HASHES.get(name.substring(PREFIX.length()));
        return hash != null && AuditedBytecode.matches(type, hash);
    }

    private static List<MethodHandle> fields(Class<?> owner, String... names) throws ReflectiveOperationException {
        List<MethodHandle> fields = new ArrayList<>(names.length);
        for (String name : names) fields.add(getter(owner, name));
        return List.copyOf(fields);
    }

    private static MethodHandle getter(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        if (Modifier.isStatic(field.getModifiers())) throw new NoSuchFieldException(name);
        field.setAccessible(true);
        return MethodHandles.lookup().unreflectGetter(field);
    }

    private record Proof(MethodHandle wrapper, Class<?> wrapperClass, List<MethodHandle> sourceNulls,
            List<MethodHandle> sourceFalse, List<MethodHandle> wrapperNulls) {}

    private static boolean mmTogetherHere(net.minecraft.server.level.ServerLevel level, BlockEntity source) {
        var key = net.minecraft.world.level.block.entity.BlockEntityType.getKey(source.getType());
        String type = key == null ? null : key.toString();
        return type != null && CoreBeGroups.together(level, source.getBlockPos(), type)
                && BeDomainReady.currentThreadIn(level, source.getBlockPos(), type);
    }
}
