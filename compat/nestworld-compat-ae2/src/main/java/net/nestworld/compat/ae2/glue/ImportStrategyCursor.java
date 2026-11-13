package net.nestworld.compat.ae2.glue;

import appeng.api.behaviors.StackImportStrategy;
import appeng.api.behaviors.StackTransferContext;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.AEKey;
import appeng.parts.automation.HandlerStrategy;
import appeng.util.BlockApiCache;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import net.minecraft.core.Direction;

/** Narrow capability guard at the original foreign import-strategy cursor.
 * The caller must already own the target's execution domain and validate its lifecycle.
 * ABSENT consumes this strategy's original no-op prefix: never call transfer again for it.
 * MAIN_REQUIRED resumes the same strategy (and its suffix) on MAIN, with fresh validation.
 * UNKNOWN must not authorize region execution of the strategy.
 */
final class ImportStrategyCursor {
    enum Probe { ABSENT, MAIN_REQUIRED, UNKNOWN }

    private static final Access UNKNOWN = new Access(null, null, null, null, null, null, null);
    private static final ClassValue<ResourceAccess> RESOURCES = new ClassValue<>() {
        @Override
        protected ResourceAccess computeValue(Class<?> type) {
            try {
                ClassLoader loader = type.getClassLoader();
                MethodHandle side = getter(type, "fromSide", Direction.class, false);
                if (type.getName().equals("me.ramidzkh.mekae2.ae2.stack.MekanismStackImportStrategy")
                        && AuditedBytecode.matches(type, "19e91efbfa67f4dafc83ee2711974f2de60c479248f70ced459c6ceccf8fe795")) {
                    Class<?> keyClass = Class.forName("me.ramidzkh.mekae2.ae2.MekanismKeyType", false, loader);
                    return new ResourceAccess(getter(type, "lookups", BlockApiCache[].class, false), side,
                            getter(keyClass, "TYPE", AEKeyType.class, true), null, null,
                            Class.forName("mekanism.api.chemical.IChemicalHandler", false, loader));
                }
                if (type.getName().equals("appbot.ae2.ManaStorageImportStrategy")
                        && AuditedBytecode.matches(type, "9c75230bdc0cc5c6e1f0d4b90def13c0e62f6aab49b83c439b33531b1870cb8f")) {
                    Class<?> lookup = Class.forName("appbot.Lookup", false, loader);
                    Class<?> keyType = Class.forName("appbot.ae2.ManaKeyType", false, loader);
                    Class<?> key = Class.forName("appbot.ae2.ManaKey", false, loader);
                    Class<?> safe = Class.forName("appbot.ae2.SafeMana", false, loader);
                    if (!AuditedBytecode.matches(safe, "5cd36c1c540a9e1e0d6fd3e40c1377761f73fd6dd61c2e3736d504cfb65c0eba")) return null;
                    return new ResourceAccess(getter(type, "apiCache", lookup, false), side,
                            getter(keyType, "TYPE", AEKeyType.class, true),
                            MethodHandles.lookup().unreflect(lookup.getMethod("find", Object.class)),
                            getter(key, "KEY", AEKey.class, true),
                            Class.forName("vazkii.botania.api.mana.ManaReceiver", false, loader));
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                // No unverified resource callback may execute in REGION.
            }
            return null;
        }
    };
    private static final ClassValue<Access> ACCESS = new ClassValue<>() {
        @Override
        protected Access computeValue(Class<?> type) {
            String keyType;
            String capability;
            String cacheField;
            boolean flux;
            switch (type.getName()) {
                case "appeng.parts.automation.StorageImportStrategy" -> {
                    if (!AuditedBytecode.matches(type, "dcf6c7d170e2d9cc8c1c7906acda66d4ffd49b891c8bd314760206710c6e6bb3")) return UNKNOWN;
                    try {
                        return new Access(getter(type, "apiCache", BlockApiCache.class, false),
                                getter(type, "fromSide", Direction.class, false), null, Object.class,
                                null, null, getter(type, "conversion", HandlerStrategy.class, false));
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                        return UNKNOWN;
                    }
                }
                case "com.glodblock.github.appflux.common.me.strategy.FEStackImportStrategy" -> {
                    if (!AuditedBytecode.matches(type, "5fae5a8d1489006bc3215d46b67c9afaeced330e9e4f50da0b5e4a1dd37317a6")) return UNKNOWN;
                    keyType = "com.glodblock.github.appflux.common.me.key.type.FluxKeyType";
                    capability = "net.minecraftforge.energy.IEnergyStorage";
                    cacheField = "apiCache";
                    flux = true;
                }
                case "com.rerurate.ifsoulsdisks.applied.stack.SoulsStackImportStrategy" -> {
                    if (!AuditedBytecode.matches(type, "3195fc3a42a2ea282f5e9ca08e7017dcc72aceaabdc898bf538a6bf6baaed3b4")) return UNKNOWN;
                    keyType = "com.buuz135.industrialforegoingsouls.soulplied_energistics.applied.SoulAEKeyType";
                    capability = "com.buuz135.industrialforegoingsouls.capabilities.ISoulHandler";
                    cacheField = "cache";
                    flux = false;
                }
                default -> { return UNKNOWN; }
            }
            try {
                ClassLoader loader = type.getClassLoader();
                MethodHandle cache = getter(type, cacheField, BlockApiCache.class, false);
                MethodHandle side = getter(type, "fromSide", Direction.class, false);
                Class<?> keyClass = Class.forName(keyType, false, loader);
                MethodHandle key = getter(keyClass, "TYPE", keyClass, true);
                Class<?> capClass = Class.forName(capability, false, loader);
                MethodHandle energy = null;
                MethodHandle fluxKey = null;
                if (flux) {
                    Class<?> energyClass = Class.forName("com.glodblock.github.appflux.common.me.key.type.EnergyType", false, loader);
                    Class<?> fluxClass = Class.forName("com.glodblock.github.appflux.common.me.key.FluxKey", false, loader);
                    // The strategy hash alone cannot establish that this called factory is pure.
                    if (!AuditedBytecode.matches(fluxClass, "f0ab0008b9856f65958907e5e7d13bfcd7da0136aeaabfdbf2f02f05f497ac27")
                            || !AuditedBytecode.matches(energyClass, "eb0055b52f764ba4d96ca143c23c520b521cab12d7f8af493f773e1b7603952c")) return UNKNOWN;
                    energy = getter(energyClass, "FE", energyClass, true);
                    fluxKey = MethodHandles.lookup().unreflect(fluxClass.getMethod("of", energyClass));
                }
                return new Access(cache, side, key, capClass, energy, fluxKey, null);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                return UNKNOWN;
            }
        }
    };

    private ImportStrategyCursor() {}

    /** Checks exact audited identity and metadata only; does not query the target capability. */
    static boolean audited(StackImportStrategy strategy) {
        return strategy != null && (ACCESS.get(strategy.getClass()) != UNKNOWN || RESOURCES.get(strategy.getClass()) != null);
    }

    /** Only AE2's actual item conversion may use the optimistic item ledger. */
    static boolean isItem(StackImportStrategy strategy) {
        if (strategy == null) return false;
        Access access = ACCESS.get(strategy.getClass());
        if (access == UNKNOWN || access.conversion == null) return false;
        try {
            return access.conversion.invoke(strategy) == HandlerStrategy.ITEMS;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    static Probe probe(StackImportStrategy strategy, StackTransferContext context) {
        if (strategy == null || context == null) return Probe.UNKNOWN;
        Access access = ACCESS.get(strategy.getClass());
        if (access == UNKNOWN) {
            ResourceAccess resource = RESOURCES.get(strategy.getClass());
            if (resource != null) return probeResource(strategy, context, resource);
            // Missing identity/metadata preserves the pre-existing builtin path on MAIN only.
            return switch (strategy.getClass().getName()) {
                case "me.ramidzkh.mekae2.ae2.stack.MekanismStackImportStrategy",
                        "appbot.ae2.ManaStorageImportStrategy" -> Probe.MAIN_REQUIRED;
                default -> Probe.UNKNOWN;
            };
        }
        try {
            // Preserve the original prefix order, including the disabled-key short circuit.
            AEKeyType keyType;
            if (access.conversion != null) {
                Object conversion = access.conversion.invoke(strategy);
                // Unknown custom conversion callbacks belong to MAIN, even with the standard wrapper.
                if (conversion != HandlerStrategy.ITEMS && conversion != HandlerStrategy.FLUIDS) return Probe.MAIN_REQUIRED;
                keyType = ((HandlerStrategy<?, ?>) conversion).getKeyType();
            } else {
                keyType = (AEKeyType) access.key.invoke();
            }
            if (!context.isKeyTypeEnabled(keyType)) return Probe.ABSENT;
            BlockApiCache<?> cache = (BlockApiCache<?>) access.cache.invoke(strategy);
            Direction side = (Direction) access.side.invoke(strategy);
            Object capability = access.capability.cast(cache.find(side));
            // Audited FE bytecode allocates this key even when find returned null.
            // FluxKey.of only constructs a key; no energy/storage operation occurs here.
            if (access.fluxKey != null) access.fluxKey.invoke(access.energy.invoke());
            return capability == null ? Probe.ABSENT : Probe.MAIN_REQUIRED;
        } catch (Throwable unavailable) {
            return Probe.UNKNOWN;
        }
    }

    private static Probe probeResource(StackImportStrategy strategy, StackTransferContext context, ResourceAccess access) {
        try {
            if (!context.isKeyTypeEnabled((AEKeyType) access.keyType.invoke())) return Probe.ABSENT;
            if (access.filterKey != null
                    && context.isInFilter((AEKey) access.filterKey.invoke()) == context.isInverted()) return Probe.ABSENT;
            Object cache = access.cache.invoke(strategy);
            if (access.find == null) {
                // Native Mek order: stop probing at the first capability that needs real MAIN work.
                for (BlockApiCache<?> lookup : (BlockApiCache<?>[]) cache) {
                    Direction side = (Direction) access.side.invoke(strategy);
                    if (access.capability.cast(lookup.find(side)) != null) return Probe.MAIN_REQUIRED;
                }
                return Probe.ABSENT;
            }
            Object capability = access.capability.cast(access.find.invoke(cache, access.side.invoke(strategy)));
            // Audited SafeMana.conv(null) returns null immediately; nonnull conversion stays on MAIN.
            return capability == null ? Probe.ABSENT : Probe.MAIN_REQUIRED;
        } catch (Throwable unavailable) {
            return Probe.UNKNOWN;
        }
    }

    private static MethodHandle getter(Class<?> owner, String name, Class<?> fieldType, boolean isStatic)
            throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        if (field.getType() != fieldType || Modifier.isStatic(field.getModifiers()) != isStatic) {
            throw new NoSuchFieldException(owner.getName() + "." + name);
        }
        field.setAccessible(true);
        return MethodHandles.lookup().unreflectGetter(field);
    }

    private record Access(MethodHandle cache, MethodHandle side, MethodHandle key,
            Class<?> capability, MethodHandle energy, MethodHandle fluxKey, MethodHandle conversion) {}

    private record ResourceAccess(MethodHandle cache, MethodHandle side, MethodHandle keyType,
            MethodHandle find, MethodHandle filterKey, Class<?> capability) {}
}
