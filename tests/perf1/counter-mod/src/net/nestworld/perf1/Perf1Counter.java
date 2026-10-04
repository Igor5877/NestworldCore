package net.nestworld.perf1;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.LongAdder;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.common.Mod;

/**
 * PERF-1 harness probe, not part of Core: counts executed living-entity ticks.
 *
 * Every LivingEntity.tick() posts one LivingTickEvent, on whichever thread runs the tick
 * (a region thread under NestWorld), so the cumulative count is the primary throughput
 * measure: executed entity ticks per second. A daemon thread appends
 * "epochMillis,cumulativeTicks" to perf1-counter.csv once per second; the harness takes
 * the difference over the measurement window.
 */
@Mod("perf1counter")
public final class Perf1Counter {
    private static final LongAdder LIVING_TICKS = new LongAdder();

    public Perf1Counter() {
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, true,
                LivingEvent.LivingTickEvent.class, e -> LIVING_TICKS.increment());
        Thread writer = new Thread(Perf1Counter::writeLoop, "perf1-counter-writer");
        writer.setDaemon(true);
        writer.start();
    }

    private static void writeLoop() {
        Path out = Path.of("perf1-counter.csv");
        while (true) {
            try {
                Thread.sleep(1000L);
                String line = System.currentTimeMillis() + "," + LIVING_TICKS.sum() + "\n";
                Files.writeString(out, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (InterruptedException e) {
                return;
            } catch (IOException ignored) {
                // the harness detects gaps in the series; never disturb the server
            }
        }
    }
}
