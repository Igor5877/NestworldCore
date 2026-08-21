package net.nestworld.region;

import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * "Хто винен / скільки часу" телеметрія (2026-08-21, продовження
 * docs/CHUNK_GEN_PIPELINE_OBSERVABILITY_SPEC.md Phase 1, окремий запит користувача):
 * точна (не семпльована) інструментація КОЖНОГО виклику entity.tick() /
 * blockEntity.tick() -- скільки наносекунд і в якому чанку -- згруповано по
 * моду (namespace реєстрового ключа типу) та по чанку.
 *
 * <p>Навмисно JVM-wide + {@link ConcurrentHashMap}/{@link LongAdder}, а НЕ
 * per-region-thread-confined поле: інструментовані точки викликаються з
 * region-потоків (RegionThread.tickEntities, NestworldDimensionRegion's
 * per-region block-entity батчі) І з головного потоку (pinned-entity tick,
 * mainBes блок-entity батч, Nether/End guardEntityTick) ОДНОЧАСНО -- дивись
 * дослідження call site'ів перед цим класом: tickBlockEntityList() зокрема
 * викликається з N різних RegionThread водночас. LongAdder обраний саме тому,
 * що розрахований на високо-конкурентні інкременти з мінімальною contention
 * (набагато краще за synchronized чи навіть AtomicLong під навантаженням) --
 * той самий клас задач, що вже вирішує {@code NestworldPins.errorCounts}.
 *
 * <p>НЕ використовує Forge {@code TimeTracker} (ENTITY_UPDATE/BLOCK_ENTITY_UPDATE)
 * -- той клас має один спільний несинхронізований {@code currentlyTracking}/
 * {@code timing} на трекер, розрахований на серійні vanilla start/end пари;
 * під конкурентним тіком регіонів це вже зламано (гонка), тож цифри звідти
 * довіряти не можна.
 *
 * <p>Per-chunk частина навмисно НЕ необмежена мапа "весь світ, назавжди" --
 * тисячі завантажених чанків означали б вибух кардинальності при експорті в
 * Prometheus. Замість цього: {@link #topChunks(int)} повертає снепшот top-N
 * найважчих чанків за поточне вікно, і {@link #decayChunks()} періодично
 * зменшує вагу (та прибирає дрібні записи), той самий підхід що вже
 * використовує {@code BlockTickHeat} для block-tick heat-мапи.
 */
public final class TickAttribution {
    private TickAttribution() {}

    /** kind -&gt; modId -&gt; нс/лічильник. kind: "entity" або "blockentity". */
    private static final ConcurrentHashMap<String, ConcurrentHashMap<String, LongAdder>> modNanos = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ConcurrentHashMap<String, LongAdder>> modCounts = new ConcurrentHashMap<>();

    /** dimensionKey -&gt; ChunkPos.asLong() -&gt; нс. Одна мапа на вимір, щоб не
     *  плутати однакові (x,z) координати з різних вимірів. */
    private static final ConcurrentHashMap<String, ConcurrentHashMap<Long, LongAdder>> chunkNanos = new ConcurrentHashMap<>();

    private static final double CHUNK_DECAY = 0.5;
    private static final long CHUNK_DROP_BELOW_NANOS = 50_000L; // 0.05ms -- прибрати шум

    private static ConcurrentHashMap<String, LongAdder> nanosMap(String kind) {
        return modNanos.computeIfAbsent(kind, k -> new ConcurrentHashMap<>());
    }
    private static ConcurrentHashMap<String, LongAdder> countsMap(String kind) {
        return modCounts.computeIfAbsent(kind, k -> new ConcurrentHashMap<>());
    }

    /** Записати один тік ентіті. modId -- namespace з {@code EntityType.getKey(...)}. */
    public static void recordEntityTick(String modId, long elapsedNanos) {
        nanosMap("entity").computeIfAbsent(modId, k -> new LongAdder()).add(elapsedNanos);
        countsMap("entity").computeIfAbsent(modId, k -> new LongAdder()).increment();
    }

    /** Записати один тік block-entity. modId -- namespace з {@code BlockEntityType.getKey(...)}. */
    public static void recordBlockEntityTick(String modId, long elapsedNanos) {
        nanosMap("blockentity").computeIfAbsent(modId, k -> new LongAdder()).add(elapsedNanos);
        countsMap("blockentity").computeIfAbsent(modId, k -> new LongAdder()).increment();
    }

    /** Додати вагу чанку (будь-який kind тіку -- entity чи block-entity, обидва
     *  однаково "цей чанк коштував часу"). dimensionKey -- напр. "minecraft:overworld". */
    public static void recordChunkCost(String dimensionKey, ChunkPos pos, long elapsedNanos) {
        chunkNanos.computeIfAbsent(dimensionKey, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(pos.toLong(), k -> new LongAdder())
                .add(elapsedNanos);
    }

    /** Періодичний decay (той самий підхід що BlockTickHeat) -- викликати раз на
     *  ~1с з головного потоку (напр. з RegionSplitManager's 20-tick оцінки), щоб
     *  мапа відображала "недавнє" навантаження, а не весь uptime, і щоб дрібні
     *  записи не накопичувались вічно. */
    public static void decayChunks() {
        for (ConcurrentHashMap<Long, LongAdder> perDim : chunkNanos.values()) {
            var it = perDim.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                LongAdder adder = e.getValue();
                long cur = adder.sum();
                long next = (long) (cur * CHUNK_DECAY);
                if (next < CHUNK_DROP_BELOW_NANOS) {
                    it.remove();
                } else {
                    adder.reset();
                    adder.add(next);
                }
            }
        }
    }

    public record ModCost(String modId, long nanos, long count) {
        public double ms() { return nanos / 1e6; }
    }

    /** Топ-N модів по сумарному часу тіку для одного kind ("entity"/"blockentity"). */
    public static List<ModCost> topMods(String kind, int limit) {
        ConcurrentHashMap<String, LongAdder> nanosByMod = modNanos.get(kind);
        if (nanosByMod == null || nanosByMod.isEmpty()) return List.of();
        ConcurrentHashMap<String, LongAdder> countsByMod = countsMap(kind);
        List<ModCost> out = new ArrayList<>(nanosByMod.size());
        for (Map.Entry<String, LongAdder> e : nanosByMod.entrySet()) {
            LongAdder cnt = countsByMod.get(e.getKey());
            out.add(new ModCost(e.getKey(), e.getValue().sum(), cnt == null ? 0 : cnt.sum()));
        }
        out.sort(Comparator.comparingLong(ModCost::nanos).reversed());
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    public record ChunkCost(String dimensionKey, int chunkX, int chunkZ, long nanos) {
        public double ms() { return nanos / 1e6; }
    }

    /** Топ-N найважчих чанків по всіх вимірах разом, за поточне (decayed) вікно. */
    public static List<ChunkCost> topChunks(int limit) {
        List<ChunkCost> out = new ArrayList<>();
        for (Map.Entry<String, ConcurrentHashMap<Long, LongAdder>> dimEntry : chunkNanos.entrySet()) {
            String dim = dimEntry.getKey();
            for (Map.Entry<Long, LongAdder> e : dimEntry.getValue().entrySet()) {
                long packed = e.getKey();
                ChunkPos pos = new ChunkPos(packed);
                out.add(new ChunkCost(dim, pos.x, pos.z, e.getValue().sum()));
            }
        }
        out.sort(Comparator.comparingLong(ChunkCost::nanos).reversed());
        return out.size() > limit ? out.subList(0, limit) : out;
    }
}
