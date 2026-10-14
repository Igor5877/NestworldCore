package net.nestworld.module;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraftforge.common.MinecraftForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Real implementation of {@link ModuleContext}. Package-private -- modules only ever see the
 * interface, never this class, matching `docs/contracts/SetVelocityPrimitive.md`'s "public
 * facade vs. internal router boundary" precedent applied one layer up.
 *
 * <p><b>Honest limitation, stated once here rather than scattered</b>: command revocation is a
 * SOFT revoke, not a real Brigadier tree removal. Confirmed via {@code javap} against this
 * project's actual bundled Brigadier (1.1.8) before writing this class:
 * {@code CommandNode.addChild} exists, {@code removeChild}/{@code removeCommand} do not. A
 * revoked command's Brigadier node therefore remains visible in tab-complete/{@code /help}
 * after revoke, but its {@link Command#run} is wrapped to refuse execution once revoked --
 * {@code ASSERTABLE}/{@code TESTABLE} per MODULE-0.5 §G3's honesty framework ("this command no
 * longer takes effect"), NOT {@code ASSERTABLE} for "this command no longer appears to exist"
 * (that would require a real tree-removal API this Brigadier version doesn't expose). Do not
 * silently upgrade this claim without re-verifying against whatever Brigadier version is in use
 * at the time.
 */
final class ModuleContextImpl implements ModuleContext {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModuleContextImpl.class);

    /**
     * MODULE-LIFECYCLE-001 (I3): a command is a MODULE-OWNED DECLARATION that lives with this module
     * generation. Revoking it is a soft revoke (Brigadier cannot remove nodes): the executor stops
     * running wherever the declaration is bound. A BINDING (a node in one dispatcher) is created per
     * dispatcher generation by {@link ModuleRegistry#bindCommands} and dies with that dispatcher.
     */
    static final class CommandDeclaration {
        final String name;
        final Command<CommandSourceStack> executor;
        final AtomicBoolean live = new AtomicBoolean(true);

        CommandDeclaration(String name, Command<CommandSourceStack> executor) {
            this.name = name;
            this.executor = executor;
        }

        void bindInto(CommandDispatcher<CommandSourceStack> dispatcher) {
            LiteralArgumentBuilder<CommandSourceStack> builder = Commands.literal(name).executes(ctx -> {
                if (!live.get()) {
                    ctx.getSource().sendFailure(net.minecraft.network.chat.Component.literal(
                            "'" + name + "' was revoked (module resource no longer live)"));
                    return 0;
                }
                return executor.run(ctx);
            });
            dispatcher.getRoot().addChild(builder.build());
        }
    }

    private final ModuleIdentity identity;
    private final ResourceLedger ledger;
    /** Binds a fresh declaration into the server's CURRENT dispatcher (I4); supplied by the registry. */
    private final java.util.function.Consumer<CommandDeclaration> currentDispatcherBinder;
    private final java.util.List<CommandDeclaration> declarations = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final net.minecraft.server.MinecraftServer server;
    private LoginBudget loginBudget;
    private ModuleChunkPreparations preparations;

    ModuleContextImpl(net.minecraft.server.MinecraftServer server, ModuleIdentity identity, ResourceLedger ledger,
                      java.util.function.Consumer<CommandDeclaration> currentDispatcherBinder) {
        this.server = server;
        this.identity = identity;
        this.ledger = ledger;
        this.currentDispatcherBinder = currentDispatcherBinder;
    }

    void stopAccepting() {
        accepting.set(false);
    }

    /** Declarations that must be bound into each new dispatcher generation while RUNNING. */
    java.util.List<CommandDeclaration> liveDeclarations() {
        java.util.List<CommandDeclaration> out = new java.util.ArrayList<>();
        for (CommandDeclaration d : declarations) if (d.live.get()) out.add(d);
        return out;
    }

    @Override
    public ModuleIdentity identity() {
        return identity;
    }

    @Override public boolean ownsServer(net.minecraft.server.MinecraftServer candidate) { return server == candidate; }

    private void requireServerThread() {
        if (!server.isSameThread()) throw new IllegalStateException("module capability requires owning server thread");
    }

    LoginBudget loginBudget() { return accepting.get() ? loginBudget : null; }

    @Override
    public ResourceHandle registerLoginBudget(LoginBudget budget) {
        requireServerThread();
        if (!accepting.get()) return null;
        java.util.Objects.requireNonNull(budget);
        if (loginBudget != null) throw new IllegalStateException("module already registered a login budget");
        loginBudget = budget;
        ResourceHandle handle = new ResourceHandle() {
            private boolean revoked;
            @Override public String kind() { return "login-budget"; }
            @Override public void revoke() {
                requireServerThread();
                if (!revoked) { revoked = true; loginBudget = null; }
            }
        };
        ledger.record(handle);
        return handle;
    }

    @Override
    public ChunkPreparation prepareChunks(ChunkPreparationRequest request) {
        requireServerThread();
        if (!accepting.get()) return null;
        if (preparations == null) {
            preparations = new ModuleChunkPreparations(server, identity);
            ledger.record(preparations);
        }
        return preparations.prepare(java.util.Objects.requireNonNull(request));
    }

    @Override
    public SyncDataSnapshot snapshotSyncData() {
        requireServerThread();
        if (!accepting.get()) throw new IllegalStateException("module context revoked");
        return ModuleSyncData.capture(server);
    }

    @Override
    public ResourceHandle registerListener(Object listener) {
        requireServerThread();
        if (!accepting.get()) {
            LOGGER.info("NestWorld module {}: listener registration REJECTED (no longer accepting)", identity);
            return null;
        }
        MinecraftForge.EVENT_BUS.register(listener);
        ResourceHandle handle = new ResourceHandle() {
            private final AtomicBoolean revoked = new AtomicBoolean(false);

            @Override
            public String kind() {
                return "listener";
            }

            @Override
            public void revoke() {
                if (revoked.compareAndSet(false, true)) {
                    MinecraftForge.EVENT_BUS.unregister(listener);
                }
            }
        };
        ledger.record(handle);
        return handle;
    }

    @Override
    public ResourceHandle registerCommand(String name, Command<CommandSourceStack> executor) {
        requireServerThread();
        if (!accepting.get()) {
            LOGGER.info("NestWorld module {}: command '{}' registration REJECTED (no longer accepting)", identity, name);
            return null;
        }
        CommandDeclaration declaration = new CommandDeclaration(name, executor);
        declarations.add(declaration);
        currentDispatcherBinder.accept(declaration);
        ResourceHandle handle = new ResourceHandle() {
            private final AtomicBoolean revoked = new AtomicBoolean(false);

            @Override
            public String kind() {
                return "command:" + name;
            }

            @Override
            public void revoke() {
                if (revoked.compareAndSet(false, true)) {
                    declaration.live.set(false); // soft revoke: every binding of this declaration stops executing
                }
            }
        };
        ledger.record(handle);
        return handle;
    }
}
