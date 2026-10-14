package ftprobe;

import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.*;
import net.nestworld.module.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;

/** Isolated-server test mod; never packaged with Core. Stops its scratch server after the test. */
@Mod("fasttransitionprobe")
public final class ServerProbe {
    public ServerProbe() {
        if (!Boolean.getBoolean("nestworld.fastTransition.probe"))
            throw new IllegalStateException("ServerProbe requires explicit scratch-server opt-in");
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent public void started(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        try {
            ModuleRegistry registry = ModuleRegistry.forServer(server);
            registry.discover(new Probe(server, registry));
            registry.enableAll();
        } catch (Throwable e) {
            e.printStackTrace();
            System.out.println("FT_SERVER_PROBE FAIL startup");
            server.halt(false);
        }
    }

    public static final class Probe implements NestWorldModule {
        private final MinecraftServer server;
        private final ModuleRegistry registry;
        private ModuleContext context;
        private ChunkPreparation first, second, expires;
        private int ticks, checks;
        private boolean cancelled;
        Probe(MinecraftServer server, ModuleRegistry registry) { this.server = server; this.registry = registry; }
        @Override public ModuleDescriptor descriptor() {
            return new ModuleDescriptor(new ModuleId("ft-probe"), new ModuleVersion("1"), "Scratch test");
        }
        @Override public void onEnable(ModuleContext context) {
            this.context = context;
            try {
                check(registry.loginBudget().packets() == 32, "built-in module registered its budget");
                context.registerLoginBudget(new LoginBudget(2, 128));
                context.registerListener(this);
                SyncDataSnapshot original = context.snapshotSyncData();
                var recipes = new ArrayList<Recipe<?>>(server.getRecipeManager().getRecipes());
                try {
                    var reversed = new ArrayList<Recipe<?>>(recipes);
                    Collections.reverse(reversed);
                    server.getRecipeManager().replaceRecipes(reversed);
                    check(original.equals(context.snapshotSyncData()), "recipe ordering does not change generations");
                    ResourceLocation id = new ResourceLocation("minecraft:oak_planks");
                    check(recipes.stream().anyMatch(r -> r.getId().equals(id)), "recipe fixture exists");
                    reversed.removeIf(r -> r.getId().equals(id));
                    reversed.add(new ShapelessRecipe(id, "planks", CraftingBookCategory.BUILDING,
                            new ItemStack(Items.OAK_PLANKS, 7), NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.OAK_LOG))));
                    server.getRecipeManager().replaceRecipes(reversed);
                    SyncDataSnapshot changed = context.snapshotSyncData();
                    check(!original.recipeGeneration().equals(changed.recipeGeneration()), "same-ID recipe body invalidates");
                    check(original.registryGeneration().equals(changed.registryGeneration()), "recipe edit preserves registry generation");
                } finally {
                    server.getRecipeManager().replaceRecipes(recipes);
                }
                check(original.equals(context.snapshotSyncData()), "restoring recipes restores generation");
                ChunkPreparationRequest request = request(600);
                first = context.prepareChunks(request);
                check(first == context.prepareChunks(request), "live request idempotency");
                boolean rejected = false;
                try {
                    context.prepareChunks(new ChunkPreparationRequest(request.id(), request.dimension(), 33, 32, 0, 600));
                } catch (IllegalArgumentException expected) { rejected = true; }
                check(rejected, "conflicting reuse of request id rejected");
                second = context.prepareChunks(request(600));
                expires = context.prepareChunks(request(2));
            } catch (Throwable e) {
                fail(e);
                throw new IllegalStateException("probe setup failed", e);
            }
        }
        private ChunkPreparationRequest request(int ticks) {
            return new ChunkPreparationRequest(UUID.randomUUID(), new ResourceLocation("minecraft:overworld"), 32, 32, 0, ticks);
        }
        @SubscribeEvent public void tick(TickEvent.ServerTickEvent event) {
            if (event.getServer() != server || event.phase != TickEvent.Phase.END) return;
            try {
                ticks++;
                if (ticks > 580) throw new AssertionError("preparation did not become READY in 580 ticks");
                if (ticks < 4 || first == null) return;
                check(expires.state() == ChunkPreparation.State.EXPIRED, "short lease expired");
                check(registry.loginBudget().equals(new LoginBudget(2, 128)), "multiple module policies intersect");
                if (!cancelled) {
                    if (first.state() != ChunkPreparation.State.READY || second.state() != ChunkPreparation.State.READY) return;
                    check(first.ready().toCompletableFuture().isDone(), "ready completion delivered");
                    first.revoke();
                    first.revoke();
                    check(first.state() == ChunkPreparation.State.CANCELLED, "cancellation idempotent");
                    check(second.state() == ChunkPreparation.State.READY, "overlapping lease survives cancellation");
                    cancelled = true;
                    return;
                }
                registry.quiesce(new ModuleId("ft-probe"));
                check(second.state() == ChunkPreparation.State.CANCELLED, "quiesce revokes READY lease");
                check(context.prepareChunks(request(20)) == null, "old context rejects preparation");
                check(context.registerLoginBudget(new LoginBudget(8, 1024)) == null, "old context rejects policy");
                check(registry.loginBudget().packets() == 32, "other module policy survives");
                registry.quiesce(new ModuleId("fast-transition"));
                check(registry.loginBudget().equals(LoginBudget.VANILLA), "quiesce restores vanilla pacing");
                System.out.println("FT_SERVER_PROBE PASS checks=" + checks + " ticks=" + ticks);
                server.halt(false);
            } catch (Throwable e) { fail(e); }
        }
        private void check(boolean condition, String name) {
            if (!condition) throw new AssertionError(name);
            checks++;
        }
        private void fail(Throwable e) {
            System.out.println("FT_SERVER_PROBE FAIL " + e);
            e.printStackTrace();
            server.halt(false);
        }
    }
}
