package net.nestworld.region;

import net.minecraft.world.entity.Entity;

import java.util.function.Predicate;

/**
 * Self-excluding wrapper around the vanilla "pushable" entity selector, used by
 * the bounded collision cap in {@code LivingEntity.pushEntities}.
 *
 * <p>Why a named class instead of a lambda: the cap is applied via a Forge
 * <em>source patch</em> to LivingEntity, so the whole class is recompiled. A
 * lambda written inside LivingEntity compiles to an extra {@code lambda$...}
 * synthetic method in LivingEntity.class, which shifts the class's synthetic
 * layout. Forgified Fabric API's {@code fabric-entity-events} mixin targets a
 * synthetic lambda in {@code wakeUp} via {@code @Dynamic}, and that shift makes
 * its injection fail (critical) — breaking every mod that bundles FFAPI. Moving
 * the predicate to its own class keeps the synthetic in <em>this</em> class, so
 * LivingEntity's bytecode (and FFAPI's injection point) stays intact.
 */
public final class NestworldPushPredicate implements Predicate<Entity> {

    private final Predicate<? super Entity> base;
    private final Entity self;

    public NestworldPushPredicate(Predicate<? super Entity> base, Entity self) {
        this.base = base;
        this.self = self;
    }

    @Override
    public boolean test(Entity other) {
        return other != self && base.test(other);
    }
}
