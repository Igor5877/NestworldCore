package net.nestworld.region;

import it.unimi.dsi.fastutil.longs.Long2ObjectFunction;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.Visibility;
import net.minecraft.world.phys.AABB;

/**
 * Thread-safe replacement for the vanilla {@link EntitySectionStorage}.
 *
 * <p>Vanilla backs the section index with a {@code LongAVLTreeSet} and a
 * {@code Long2ObjectOpenHashMap} — neither is safe for concurrent access. With
 * several region threads ticking entities in parallel, a structural write
 * (entity crossing a section border on thread A) racing an iteration
 * ({@code getEntities} during mob AI on thread B) corrupts the AVL tree and
 * sends readers into an infinite loop; the watchdog then kills the server.
 *
 * <p>All structural reads take a shared read lock, writes take the exclusive
 * write lock. Consumers passed to {@link #forEachAccessibleNonEmptySection}
 * run while holding the read lock; they may re-enter read paths (the lock is
 * reentrant) but must never create or remove sections — vanilla consumers
 * only collect entities, so this holds.
 *
 * <p>The lazy {@code LongStream}/{@code Stream} accessors are materialised
 * under the lock so the iterators cannot escape it.
 */
public class ConcurrentEntitySectionStorage<T extends EntityAccess> extends EntitySectionStorage<T> {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public ConcurrentEntitySectionStorage(Class<T> entityClass, Long2ObjectFunction<Visibility> initialSectionVisibility) {
        super(entityClass, initialSectionVisibility);
    }

    @Override
    public void forEachAccessibleNonEmptySection(AABB bounds, AbortableIterationConsumer<EntitySection<T>> consumer) {
        lock.readLock().lock();
        try {
            super.forEachAccessibleNonEmptySection(bounds, consumer);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public LongStream getExistingSectionPositionsInChunk(long chunkKey) {
        lock.readLock().lock();
        try {
            return LongStream.of(super.getExistingSectionPositionsInChunk(chunkKey).toArray());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Stream<EntitySection<T>> getExistingSectionsInChunk(long chunkKey) {
        lock.readLock().lock();
        try {
            List<EntitySection<T>> sections = new ArrayList<>();
            super.getExistingSectionsInChunk(chunkKey).forEach(sections::add);
            return sections.stream();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public EntitySection<T> getOrCreateSection(long sectionKey) {
        // Fast path: the section almost always exists already.
        EntitySection<T> existing = getSection(sectionKey);
        if (existing != null) {
            return existing;
        }
        lock.writeLock().lock();
        try {
            return super.getOrCreateSection(sectionKey);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Nullable
    @Override
    public EntitySection<T> getSection(long sectionKey) {
        lock.readLock().lock();
        try {
            return super.getSection(sectionKey);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public LongSet getAllChunksWithExistingSections() {
        lock.readLock().lock();
        try {
            return super.getAllChunksWithExistingSections();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void remove(long sectionKey) {
        lock.writeLock().lock();
        try {
            super.remove(sectionKey);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public int count() {
        lock.readLock().lock();
        try {
            return super.count();
        } finally {
            lock.readLock().unlock();
        }
    }
}
