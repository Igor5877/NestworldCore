package net.nestworld.compat.ae2.glue;

/** Public counter access for the grid-worker mixins (package-private state in {@link MainServiced}). */
public final class MainServicedCounters {
    private MainServicedCounters() {}
    public static void crafting() { MainServiced.crafting.increment(); }
    public static void refused() { MainServiced.refused.increment(); }
}
