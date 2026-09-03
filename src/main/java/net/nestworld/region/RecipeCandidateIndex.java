package net.nestworld.region;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

/**
 * Diagnostic-only in its current (shadow-mode) wiring -- see RecipeCandidateIndexShadowStats
 * and the patched bcl_sort in the deployed BCLib jar. Builds a conservative, over-approximating
 * "which recipes could possibly match this container" candidate set, so a future production
 * mode can check matches() against a small candidate set instead of every recipe in the
 * RecipeType.CRAFTING bucket (~9880 recipes per the 2026-09-03 profiling data).
 *
 * Hard correctness contract (never relaxed): index candidates are a SUPERSET of the recipes
 * Recipe.matches() would actually accept. Recipe.matches() itself, and BCLib's namespace-
 * priority comparator, remain the sole authority for what actually matches and which one wins
 * -- this index only ever narrows the set of recipes that get checked, never decides a match
 * itself. Any recipe this index cannot safely reason about is added to an "unsupported" bucket
 * that is unconditionally included in every candidate set (equivalent to "always full-scan
 * this one"), so false negatives are structurally impossible by construction, not just by
 * empirical validation -- though shadow-mode differential checking (RecipeCandidateIndexShadowStats)
 * still verifies this empirically before any production-mode use is considered.
 *
 * Only ShapedRecipe/ShapelessRecipe instances whose ingredients are all plain vanilla
 * Ingredient instances (not a mod's custom Ingredient subclass, whose test() semantics this
 * index cannot safely reason about from getItems() alone) are indexed by item. Every other
 * recipe (custom Recipe<CraftingContainer> implementations like the ones profiling found --
 * SecureRecipe, BinInsertRecipe, DyeableRecipe, FramedDrawerRecipe, BarrelMaterialRecipe,
 * CoverRecipe, etc. -- and any Shaped/Shapeless recipe with a non-vanilla Ingredient) goes into
 * the unsupported bucket.
 */
public final class RecipeCandidateIndex {

    private RecipeCandidateIndex() {}

    /** Production gate (2026-09-03, Candidate Recipe Index ТЗ): when true, the patched bcl_sort
     *  scans ONLY the recipes this index returns as candidates, instead of every recipe in the
     *  type's bucket. Validated 2026-09-03 (RecipeCandidateIndexShadowStats: 0 false negatives,
     *  0 comparator mismatches over a real live production window, ~26.6x mean candidate
     *  reduction; adopted as the standing architecture, see memory). Defaults ON -- opt back OUT
     *  with -Dnestworld.recipeCandidateIndexProduction=false in user_jvm_args.txt if a rollback
     *  is ever needed without a jar swap. This is a JVM-property default read once at class-init
     *  (survives restarts, unlike the RCON toggle alone); the RCON command still overrides it
     *  live for the running session. Independent of shadow mode; running both at once defeats
     *  production mode's performance purpose (it would still do a full scan to compare against),
     *  so shadow mode is meant for validation runs, production mode for standing use. */
    private static volatile boolean productionEnabled =
            !"false".equalsIgnoreCase(System.getProperty("nestworld.recipeCandidateIndexProduction"));

    public static boolean isProductionEnabled() {
        return productionEnabled;
    }

    public static void setProductionEnabled(boolean enabled) {
        productionEnabled = enabled;
    }

    public static final class IndexEntry {
        final Map<Item, Set<Object>> itemToRecipes = new HashMap<>();
        final Set<Object> unsupported = new HashSet<>();
        final int totalRecipes;

        IndexEntry(int totalRecipes) {
            this.totalRecipes = totalRecipes;
        }
    }

    // Keyed by the identity of the `inter` map RecipeManager.byType() returns for a given
    // RecipeType -- that map instance is replaced wholesale on every recipe reload (apply()
    // rebuilds `this.recipes` from scratch), so identity alone is a correct invalidation key.
    // Only ever holds the single most-recently-built entry (cleared on every rebuild) since we
    // don't need historical indexes for old, now-unreachable recipe maps.
    private static final Map<Map<?, ?>, IndexEntry> cache = Collections.synchronizedMap(new IdentityHashMap<>());

    public static IndexEntry getOrBuild(Map<?, ?> inter) {
        IndexEntry existing = cache.get(inter);
        if (existing != null) return existing;
        synchronized (cache) {
            existing = cache.get(inter);
            if (existing != null) return existing;
            IndexEntry built = build(inter);
            cache.clear();
            cache.put(inter, built);
            return built;
        }
    }

    private static IndexEntry build(Map<?, ?> inter) {
        IndexEntry entry = new IndexEntry(inter.size());
        for (Object recipeObj : inter.values()) {
            if (!(recipeObj instanceof Recipe<?> recipe)) continue;
            boolean safeClass = (recipe instanceof ShapedRecipe) || (recipe instanceof ShapelessRecipe);
            if (!safeClass) {
                entry.unsupported.add(recipeObj);
                continue;
            }

            List<Ingredient> ingredients = recipe.getIngredients();
            Set<Item> itemsForRecipe = new HashSet<>();
            boolean unsafeIngredient = false;
            for (Ingredient ing : ingredients) {
                if (ing.isEmpty()) continue;
                if (ing.getClass() != Ingredient.class) {
                    // A mod's custom Ingredient subclass -- its test() may accept items outside
                    // getItems()'s resolved set (e.g. NBT/durability-sensitive predicates), so
                    // we cannot safely narrow by item id for this recipe at all.
                    unsafeIngredient = true;
                    break;
                }
                for (ItemStack stack : ing.getItems()) {
                    itemsForRecipe.add(stack.getItem());
                }
            }
            if (unsafeIngredient || itemsForRecipe.isEmpty()) {
                entry.unsupported.add(recipeObj);
                continue;
            }
            for (Item item : itemsForRecipe) {
                entry.itemToRecipes.computeIfAbsent(item, k -> new HashSet<>()).add(recipeObj);
            }
        }
        return entry;
    }

    /** Conservative candidate set for the given container's CURRENT contents: unsupported
     *  recipes (always included) unioned with the intersection, over every distinct item type
     *  present, of "recipes with at least one ingredient accepting that item". This is a
     *  necessary-but-not-sufficient condition for both ShapedRecipe and ShapelessRecipe
     *  matching (a recipe cannot match if some present item type isn't accepted by ANY of its
     *  ingredients, regardless of shape/position), so it never excludes a true match. */
    public static Set<Object> computeCandidates(IndexEntry entry, Container container) {
        Set<Item> present = new HashSet<>();
        int size = container.getContainerSize();
        for (int i = 0; i < size; i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty()) present.add(stack.getItem());
        }

        Set<Object> result = new HashSet<>(entry.unsupported);
        if (present.isEmpty()) {
            return result; // no vanilla-safe (indexed) recipe can match an empty container
        }

        Set<Object> intersection = null;
        for (Item item : present) {
            Set<Object> forItem = entry.itemToRecipes.getOrDefault(item, Collections.emptySet());
            if (intersection == null) {
                intersection = new HashSet<>(forItem);
            } else {
                intersection.retainAll(forItem);
            }
        }
        if (intersection != null) {
            result.addAll(intersection);
        }
        return result;
    }
}
