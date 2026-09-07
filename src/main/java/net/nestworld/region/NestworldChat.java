package net.nestworld.region;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Presentation-only helpers for {@code /nestworld *} chat output, styled after the
 * {@code spark} profiler's UX (visual hierarchy, health-colored values, compact
 * multi-value rows). Every method here is a pure formatter: it takes already-computed
 * values and returns/sends {@link Component}s, never touches diagnostic counters,
 * thresholds that drive server behavior, or command logic. Centralizing the palette
 * here means a future style tweak is a change to this one file, not to every handler
 * in {@code NestworldCommand}.
 */
public final class NestworldChat {

    private NestworldChat() {}

    /** Presentation-only health classification -- distinct from any engine decision
     *  threshold. Where the engine already has an equivalent enum (RegionScheduler.State,
     *  CpuCapacityPlanner.WarningLevel), use the {@code from*} bridges below instead of
     *  re-deriving a band, so the color never disagrees with the value the engine itself
     *  acted on. */
    public enum Health { OK, WARNING, CRITICAL }

    // ---- classification (see docs/../plan for the exact bands + why) ----

    /** TPS has no engine-side threshold today (server behavior doesn't branch on it) --
     *  this band is new, presentation-only, using the conventional 18/15 split most
     *  perf tools (incl. spark) use. */
    public static Health classifyTps(double tps) {
        if (tps >= 18.0) return Health.OK;
        if (tps >= 15.0) return Health.WARNING;
        return Health.CRITICAL;
    }

    /** Server ms/tick counterpart to {@link #classifyTps}, band chosen so the ms and
     *  TPS numbers for the same instant never show disagreeing colors (66.7ms ~= 15 TPS). */
    public static Health classifyMsPerTick(double ms) {
        if (ms <= 50.0) return Health.OK;
        if (ms <= 66.7) return Health.WARNING;
        return Health.CRITICAL;
    }

    /** Reuses RegionSplitManager's own MERGE_MS_THRESHOLD/SPLIT_MS_THRESHOLD (10.0/25.0)
     *  -- the same numbers the engine already uses to decide whether a region should
     *  split, now also used to color the displayed cost. */
    public static Health classifyRegionCost(double ms) {
        if (ms < RegionSplitManager.MERGE_MS_THRESHOLD) return Health.OK;
        if (ms <= RegionSplitManager.SPLIT_MS_THRESHOLD) return Health.WARNING;
        return Health.CRITICAL;
    }

    /** For {@code /nestworld entityfairness}'s "how long between two real ticks of the
     *  SAME entity" measurements (p50/p95/p99/max interval, max age) -- no engine
     *  threshold exists for this (EntityFairnessMetrics is measurement-only by explicit
     *  design, see its own javadoc), so this is a new presentation-only band, in
     *  multiples of one vanilla tick (50ms @ 20 TPS): within ~2 ticks is normal
     *  round-robin jitter, within ~6 ticks is worth watching, beyond that is the
     *  "starved entity" pattern the whole metric exists to catch (see the class javadoc's
     *  chicken-farm point-hotspot story). */
    public static Health classifyEntityIntervalMs(double ms) {
        if (ms <= 100.0) return Health.OK;
        if (ms <= 300.0) return Health.WARNING;
        return Health.CRITICAL;
    }

    public static Health fromSchedulerState(RegionScheduler.State state) {
        return switch (state) {
            case IDLE, RUNNING -> Health.OK;
            case DELAYED -> Health.WARNING;
            case OVERLOADED -> Health.CRITICAL;
        };
    }

    public static Health fromCpuWarningLevel(CpuCapacityPlanner.WarningLevel level) {
        return switch (level) {
            case INFO -> Health.OK;
            case WARNING -> Health.WARNING;
            case CRITICAL -> Health.CRITICAL;
        };
    }

    /** Localized label for a {@link RegionScheduler.State} -- used by {@code /nestworld
     *  scheduler} for both the summary counts and per-region rows. */
    public static String schedulerStateLabel(Locale loc, RegionScheduler.State state) {
        return switch (state) {
            case IDLE -> tr(loc, "Idle", "Вільний");
            case RUNNING -> tr(loc, "Running", "Виконується");
            case DELAYED -> tr(loc, "Delayed", "Затримка");
            case OVERLOADED -> tr(loc, "Overloaded", "Перевантажений");
        };
    }

    /** {@link #schedulerStateLabel}, colored via {@link #fromSchedulerState} -- the
     *  no-checkmark state indicator for {@code /nestworld scheduler} rows. */
    public static Component schedulerStateText(Locale loc, RegionScheduler.State state) {
        return Component.literal(schedulerStateLabel(loc, state)).withStyle(colorOf(fromSchedulerState(state)));
    }

    // ---- locale ----

    /** Which language a command's chat output should use. RCON/console has no reported
     *  client language, so it always gets EN. */
    public enum Locale { EN, UK }

    /** {@link net.minecraft.server.level.ServerPlayer#getLanguage()} reports the
     *  client's last-known locale (e.g. "uk_ua", default "en_us" per vanilla javadoc) --
     *  read directly, nothing NestworldCore tracks itself. Non-player sources (RCON,
     *  console) have no client language, so they get EN. */
    public static Locale localeOf(CommandSourceStack src) {
        var player = src.getPlayer();
        if (player != null && player.getLanguage().toLowerCase(java.util.Locale.ROOT).startsWith("uk")) {
            return Locale.UK;
        }
        return Locale.EN;
    }

    /** Pick the EN or UK string for this source's locale. Kept as an inline pair at the
     *  call site (not a lookup table) so a translation is always visible next to the
     *  English original it corresponds to -- easier to audit for correctness. */
    public static String tr(Locale loc, String en, String uk) {
        return loc == Locale.UK ? uk : en;
    }

    // ---- colors ----

    private static ChatFormatting colorOf(Health h) {
        return switch (h) {
            case OK -> ChatFormatting.GREEN;
            case WARNING -> ChatFormatting.GOLD;
            case CRITICAL -> ChatFormatting.RED;
        };
    }

    /** Plain neutral bullet for list rows with no health dimension. */
    public static Component bullet() {
        return Component.literal("•").withStyle(ChatFormatting.GRAY); // •
    }

    /** "1875000" -> "1.9M", "6" -> "6" -- large chunk coordinates are long enough to
     *  break the compact single-line layout otherwise (rule: don't let long technical
     *  values break readability). */
    private static String compactNum(int n) {
        if (Math.abs(n) >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        return String.valueOf(n);
    }

    /** "[minX..maxX, minZ..maxZ]", grouped by axis (not by corner) -- matches the
     *  user-provided mockup, e.g. "[-1.8M..1, -1.8M..6]". */
    public static String compactBounds(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        return "[" + compactNum(minChunkX) + ".." + compactNum(maxChunkX) + ", "
                + compactNum(minChunkZ) + ".." + compactNum(maxChunkZ) + "]";
    }

    /** One "├─ "/"└─ " tree-connector sub-line under a region's main line -- {@code last}
     *  picks the closing "└─" connector for whichever sub-line is actually last among
     *  the ones a given region has (heat/queue-depth/stats are each conditional). */
    public static Component treeLine(boolean last, Component content) {
        return Component.literal(last ? "└─ " : "├─ ").withStyle(ChatFormatting.DARK_GRAY).append(content);
    }

    /** "[FreeRun -3]" -- the free-running badge, replacing the old verbose "independent
     *  tick(auto) lead +N" wording (user-provided mockup). Kept as the literal English
     *  word "FreeRun" in both locales, same treatment as "TPS" -- a short technical
     *  badge, not a translated sentence.
     *
     * <p>Deliberately NOT {@code localTickCount - server.getTickCount()} (tried that
     * first): a brand-new {@code WorldRegion} object's {@code localTickCount} always
     * starts at 0 while the server's own counter keeps counting from boot, so right
     * after a split/merge that comparison shows a huge, meaningless delta that's really
     * just "how old is this region object", not "is it behind schedule" -- needed an
     * "age" caveat to not be misleading, which the user correctly pushed back on
     * ("чого зразу вірний тіки відставання показати?").
     *
     * <p>This version is epoch-independent and always correct: it compares ticks
     * ACTUALLY processed in the last real 10 seconds ({@code s10.sampleCount()}, the
     * same {@link TickDurationHistory} window already sampled for the Tick sub-lines)
     * against ticks EXPECTED in 10 real seconds at the free-running pace target
     * ({@link NestworldTuning#FREE_RUNNING_TARGET_TICK_NANOS}, 20/s) -- a freshly
     * created region has fewer historical samples but the RATE it's keeping right now
     * is still measured correctly, no region-age artifact possible. Colored via a new
     * presentation-only band (not an engine threshold): |delta|<=5 ticks is normal
     * sampling-window jitter (OK/green), <=30 is worth watching (WARNING/gold), beyond
     * that is a real, sustained drift (CRITICAL/red). {@code null} if there's no 10s
     * sample yet (region just started). */
    public static Component freeRunTag(TickDurationHistory.Stats s10) {
        if (s10 == null) return null;
        double expectedPerSec = 1_000_000_000.0 / NestworldTuning.FREE_RUNNING_TARGET_TICK_NANOS;
        long delta = s10.sampleCount() - Math.round(10.0 * expectedPerSec);
        long abs = Math.abs(delta);
        Health h = abs <= 5 ? Health.OK : (abs <= 30 ? Health.WARNING : Health.CRITICAL);
        return Component.literal(String.format("[FreeRun %+d]", delta)).withStyle(colorOf(h));
    }

    /** A region's main list line: "#id [bounds] | Tick: X.Xms | Entities: N" (user-provided
     *  mockup) -- id is a fixed yellow badge (the health signal now lives in the colored
     *  Tick value, not the id itself, per the same feedback that dropped the ✓/⚠/✖ symbols
     *  earlier). Free-running status is its own tree sub-line (see {@link #freeRunTag}),
     *  not appended here -- inline it made this already-long line wrap in the default
     *  (narrow) chat box. */
    public static Component regionMainLine(Locale loc, int id, int minChunkX, int minChunkZ,
            int maxChunkX, int maxChunkZ, double avgMs, int entities) {
        Health h = classifyRegionCost(avgMs);
        return Component.literal("#" + id).withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(" " + compactBounds(minChunkX, minChunkZ, maxChunkX, maxChunkZ) + " ")
                        .withStyle(ChatFormatting.GRAY))
                .append(Component.literal("| ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(tr(loc, "Tick", "Тік") + ": ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(String.format("%.1f" + tr(loc, "ms", "мс") + " ", avgMs)).withStyle(colorOf(h)))
                .append(Component.literal("| ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(tr(loc, "Entities", "Сутності") + ": ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(String.valueOf(entities))
                        .withStyle(entities > 0 ? ChatFormatting.AQUA : ChatFormatting.GRAY));
    }

    /** "Heat: 28 [(-1,7)=28.2 ...]" sub-line content (caller wraps with {@link
     *  #treeLine}). {@code hotspotSummary} is the pre-formatted opaque string {@link
     *  BlockTickHeat#hotspotSummary} already produces -- rendered as-is in gold inside
     *  brackets, not parsed apart (it's a list of coord=value pairs, not worth a bespoke
     *  parser just to recolor each piece). */
    public static Component heatLine(Locale loc, double heatValue, String hotspotSummary) {
        return Component.literal(tr(loc, "Heat", "Нагрів") + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.format("%.0f ", heatValue)).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("[").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(hotspotSummary).withStyle(ChatFormatting.GOLD))
                .append(Component.literal("]").withStyle(ChatFormatting.DARK_GRAY));
    }

    /** A single tick over this many ms (default threshold in {@link #hasPeriodicSpike})
     *  can hide inside a 100-tick rolling average without ever pushing
     *  {@link #classifyMsPerTick} out of OK -- e.g. one 500ms freeze among 100 ticks
     *  only adds ~5ms to the average. Surfaced as its own label, not folded into
     *  WARNING/CRITICAL, since "occasional freeze, otherwise fine" and "consistently
     *  slow" are different problems with different causes. */
    public static final double PERIODIC_SPIKE_MS_DEFAULT = 500.0;

    /** Reads the SAME rolling tick-time window {@code /nestworld mspt} already reads
     *  ({@link net.minecraft.server.MinecraftServer#tickTimes}, last 100 ticks) -- no
     *  new metric collection, just a second way of looking at data that already exists. */
    public static boolean hasPeriodicSpike(net.minecraft.server.MinecraftServer server, double thresholdMs) {
        return maxTickMs(server) > thresholdMs;
    }

    /** The actual worst single tick in the same rolling window, for showing alongside
     *  the "Periodically unstable" label (a bare boolean doesn't tell the admin how bad
     *  the spike was). */
    public static double maxTickMs(net.minecraft.server.MinecraftServer server) {
        long[] ticks = server.tickTimes;
        if (ticks == null) return 0.0;
        long max = 0;
        for (long nanos : ticks) max = Math.max(max, nanos);
        return max / 1_000_000.0;
    }

    /** Generic one-word health label for the "is this healthy?" line every command
     *  should lead with (rule: most important info first). No glyph/checkmark -- bold
     *  colored text carries the status on its own, per explicit user preference.
     *  {@code periodicSpike}: true when {@link #hasPeriodicSpike} fired even though
     *  {@code h} itself is OK (average looks fine, but there's a hidden freeze) --
     *  shows its own distinct label instead of silently saying "Stable". */
    public static String healthLabel(Locale loc, Health h, boolean periodicSpike) {
        if (h == Health.OK && periodicSpike) {
            return tr(loc, "Periodically unstable", "Періодично нестабільний");
        }
        return switch (h) {
            case OK -> tr(loc, "Stable", "Стабільний");
            case WARNING -> tr(loc, "Moderately stable", "Помірно стабільний");
            case CRITICAL -> tr(loc, "Unstable", "Нестабільний");
        };
    }

    /** Health-colored text -- the no-checkmark status indicator used everywhere a command
     *  used to show a ✓/⚠/✖ symbol. Color alone carries the signal, no bold (bold combined
     *  with Cyrillic/symbol glyphs was rendering oddly for some clients). A periodic spike
     *  on an otherwise-OK average is shown in the WARNING color, not green -- it's real,
     *  just intermittent. */
    public static Component healthText(Locale loc, Health h, boolean periodicSpike) {
        Health displayColor = (h == Health.OK && periodicSpike) ? Health.WARNING : h;
        return Component.literal(healthLabel(loc, h, periodicSpike)).withStyle(colorOf(displayColor));
    }

    // ---- structure ----

    /** Section header, spark-style "⚡ Title". Gold, matches the symbol palette's WARNING
     *  color deliberately (spark's own lightning-bolt convention) without implying the
     *  section itself is in a warning state -- it's a title marker, not a status. */
    public static Component header(String title) {
        return Component.literal("⚡ " + title).withStyle(ChatFormatting.GOLD);
    }

    /** Plain section title without the ⚡ marker, for groups that aren't a spark-style
     *  multi-value metric block (e.g. "Regions", "NestWorld"). */
    public static Component title(String text) {
        return Component.literal(text).withStyle(ChatFormatting.WHITE);
    }

    /** "Label: value", label in gray, value in white (or the given health color). */
    public static Component kv(String label, String value) {
        return kv(label, value, null);
    }

    public static Component kv(String label, String value, Health valueHealth) {
        MutableComponent c = Component.literal(label + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(value).withStyle(
                        valueHealth != null ? colorOf(valueHealth) : ChatFormatting.WHITE));
        return c;
    }

    /** A bare value colored by health, no label -- for embedding a colored number inline
     *  in a larger hand-built line (e.g. a ranked list row) instead of the "Label: value"
     *  shape {@link #kv} produces. Avoids ad-hoc {@code Health == OK ? GREEN : ...}
     *  ternary chains at call sites. */
    public static Component coloredValue(String text, Health h) {
        return Component.literal(text).withStyle(colorOf(h));
    }

    /** 2-space indent per level, prefixed onto an existing component. */
    public static Component indent(int level, Component c) {
        return Component.literal("  ".repeat(Math.max(0, level))).append(c);
    }

    /** Wraps a plain string in an indented, unstyled literal -- for opaque pre-formatted
     *  lines (e.g. a metrics class's own .format() output) that don't need re-coloring,
     *  just re-indenting to match the surrounding block. */
    public static Component indentLiteral(int level, String text) {
        return indent(level, Component.literal(text));
    }

    /** Compact aligned multi-value row, e.g. labels={"min","med","95%","max"},
     *  values={"3.3","4.1","6.3","13.5"} -> "min/med/95%/max (ms): 3.3 / 4.1 / 6.3 / 13.5". */
    public static Component compactRow(String header, String[] labels, String[] values) {
        StringBuilder sb = new StringBuilder();
        if (header != null && !header.isEmpty()) {
            sb.append(header).append(": ");
        }
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) sb.append(" / ");
            sb.append(values[i]);
        }
        return Component.literal(sb.toString()).withStyle(ChatFormatting.WHITE);
    }

    /** Sends each line as its own sendSuccess call (preserves the existing "1 line = 1
     *  chat message" convention) with the SAME broadcast flag every line in this call
     *  already used -- doesn't change any command's chat/console channel. No per-line
     *  prefix tag -- tried one, dropped it: it ate width and pushed already-long lines
     *  (e.g. the Stats row) into wrapping in the narrow default chat box. */
    public static void send(CommandSourceStack src, boolean broadcast, Component... lines) {
        for (Component line : lines) {
            src.sendSuccess(() -> line, broadcast);
        }
    }

    /** Blank-line separator between logical blocks. */
    public static Component blank() {
        return Component.literal("");
    }

    /** "Tick (min/med/95%/max ms)" header + one compact row per available window
     *  (10s, 1m), reading the given {@link TickDurationHistory} -- {@link
     *  TickDurationHistory#SERVER} for the main tick, or a region's own {@code
     *  tickHistory} field for a free-running region's independent tick. Each of the 4
     *  numbers is colored individually via {@link #classifyMsPerTick} (same bands
     *  already used for "Server tick"/region cost -- no new thresholds). Returns an
     *  empty list if there aren't enough samples yet (e.g. just booted/just started
     *  free-running) -- callers should just skip adding these lines in that case. A
     *  leading blank line separates this block from whatever came before it. Shared by
     *  status/lag/per-region so all three stay in the exact same format (rule: one
     *  formatter, not a bespoke block per caller). */
    public static java.util.List<Component> tickDurationRows(Locale loc, TickDurationHistory history) {
        java.util.List<Component> out = new java.util.ArrayList<>();
        TickDurationHistory.Stats s10 = history.compute(10_000);
        TickDurationHistory.Stats s60 = history.compute(60_000);
        if (s10 == null && s60 == null) return out;
        out.add(blank());
        out.add(indent(1, Component.literal(
                tr(loc, "Tick (min/med/95%/max)", "Тік (мін/мед/95%/макс)") + ":")
                .withStyle(ChatFormatting.GRAY)));
        if (s10 != null) out.add(indent(2, statsValuesCompact(loc, s10, tr(loc, "10s", "10с"))));
        if (s60 != null) out.add(indent(2, statsValuesCompact(loc, s60, tr(loc, "1m", "1хв"))));
        return out;
    }

    /** Single-line, no-header/no-blank variant of {@link #tickDurationRows} for the
     *  compact per-region detail line (a region's own independent tick, free-running or
     *  barrier-synced alike) -- the full header+blank block would break up the tight
     *  region-list layout. Returns {@code null} if there aren't enough samples yet. */
    public static java.util.List<Component> compactTickLine(Locale loc, TickDurationHistory history) {
        java.util.List<Component> out = new java.util.ArrayList<>();
        TickDurationHistory.Stats s10 = history.compute(10_000);
        TickDurationHistory.Stats s60 = history.compute(60_000);
        // One line per window, not both joined on one line -- joined, the row was long
        // enough to wrap in the default (narrow) chat box, which broke the tree-connector
        // indentation on the wrapped continuation.
        if (s10 != null) {
            out.add(Component.literal(tr(loc, "Tick", "Тік") + ": ").withStyle(ChatFormatting.GRAY)
                    .append(statsValuesCompact(loc, s10, tr(loc, "10s", "10с"))));
        }
        if (s60 != null) {
            out.add(Component.literal(tr(loc, "Tick", "Тік") + ": ").withStyle(ChatFormatting.GRAY)
                    .append(statsValuesCompact(loc, s60, tr(loc, "1m", "1хв"))));
        }
        return out;
    }

    /** "0.0/0.1/1.7мс (10s)" -- min/med/95%/max separated by dark-gray slashes, each
     *  number colored individually via {@link #classifyMsPerTick} (same bands already
     *  used for "Server tick"/region cost, no new thresholds), unit suffixed onto the
     *  last value, window label trailing in parens (user-provided mockup). */
    private static Component statsValuesCompact(Locale loc, TickDurationHistory.Stats s, String windowLabel) {
        double[] values = {s.minMs(), s.medianMs(), s.p95Ms(), s.maxMs()};
        MutableComponent c = Component.literal("");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) c.append(Component.literal("/").withStyle(ChatFormatting.DARK_GRAY));
            boolean isLast = i == values.length - 1;
            String text = isLast
                    ? String.format("%.1f" + tr(loc, "ms", "мс"), values[i])
                    : String.format("%.1f", values[i]);
            c.append(Component.literal(text).withStyle(colorOf(classifyMsPerTick(values[i]))));
        }
        c.append(Component.literal(" (" + windowLabel + ")").withStyle(ChatFormatting.DARK_GRAY));
        return c;
    }
}
