package net.nestworld.module;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

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
    private final java.util.function.Predicate<CommandDeclaration> currentDispatcherBinder;
    private final java.util.function.Consumer<CommandDeclaration> commandReleaser;
    private final java.util.List<CommandDeclaration> declarations = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicBoolean callbacksLive = new AtomicBoolean(true);
    private final net.minecraft.server.MinecraftServer server;
    private final java.util.Set<String> capabilities;
    private final java.util.function.BiConsumer<String, Throwable> callbackFailure;
    private LoginBudget loginBudget;
    private ModuleChunkPreparations preparations;

    ModuleContextImpl(net.minecraft.server.MinecraftServer server, ModuleIdentity identity, ResourceLedger ledger,
                      java.util.Set<String> capabilities,
                      java.util.function.Predicate<CommandDeclaration> currentDispatcherBinder,
                      java.util.function.Consumer<CommandDeclaration> commandReleaser,
                      java.util.function.BiConsumer<String, Throwable> callbackFailure) {
        this.server = server;
        this.identity = identity;
        this.ledger = ledger;
        this.capabilities = java.util.Set.copyOf(capabilities);
        this.currentDispatcherBinder = currentDispatcherBinder;
        this.commandReleaser = commandReleaser;
        this.callbackFailure = callbackFailure;
    }

    void stopAccepting() {
        accepting.set(false);
        callbacksLive.set(false);
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
        if (!accepting.get() || !capabilities.contains("loginBudget")) return null;
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
        if (!accepting.get() || !capabilities.contains("chunkPreparation")) return null;
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
        if (!capabilities.contains("syncData")) throw new IllegalStateException("syncData capability not declared");
        return ModuleSyncData.capture(server);
    }

    @Override
    public <E extends Event> ResourceHandle registerListener(Class<E> type, EventPriority priority,
                                                              boolean receiveCancelled, Consumer<E> handler) {
        requireServerThread();
        if (!accepting.get() || !capabilities.contains("listeners")) {
            LOGGER.info("NestWorld module {}: listener registration REJECTED (no longer accepting)", identity);
            return null;
        }
        java.util.Objects.requireNonNull(type);
        java.util.Objects.requireNonNull(priority);
        java.util.Objects.requireNonNull(handler);
        AtomicBoolean live = new AtomicBoolean(true);
        Consumer<E> wrapper = event -> {
            if (!live.get() || !callbacksLive.get()) return;
            try {
                handler.accept(event);
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable failure) {
                callbacksLive.set(false);
                callbackFailure.accept("listener:" + type.getName(), failure);
                sneakyThrow(failure);
            }
        };
        MinecraftForge.EVENT_BUS.addListener(priority, receiveCancelled, type, wrapper);
        ResourceHandle handle = new ResourceHandle() {
            private final AtomicBoolean revoked = new AtomicBoolean(false);

            @Override
            public String kind() {
                return "listener";
            }

            @Override
            public void revoke() {
                if (revoked.compareAndSet(false, true)) {
                    live.set(false);
                    MinecraftForge.EVENT_BUS.unregister(wrapper);
                }
            }
        };
        ledger.record(handle);
        return handle;
    }

    @Override
    public ResourceHandle registerCommand(String name, Command<CommandSourceStack> executor) {
        requireServerThread();
        if (!accepting.get() || !capabilities.contains("commands")) {
            LOGGER.info("NestWorld module {}: command '{}' registration REJECTED (no longer accepting)", identity, name);
            return null;
        }
        Command<CommandSourceStack> guarded = command -> {
            if (!callbacksLive.get()) {
                command.getSource().sendFailure(net.minecraft.network.chat.Component.literal(
                        "module " + identity.id().value() + " FAILED"));
                return 0;
            }
            try {
                return executor.run(command);
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable failure) {
                callbacksLive.set(false);
                callbackFailure.accept("command:" + name, failure);
                return sneakyThrow(failure);
            }
        };
        CommandDeclaration declaration = new CommandDeclaration(name, guarded);
        if (!currentDispatcherBinder.test(declaration)) {
            declaration.live.set(false);
            LOGGER.warn("NestWorld module {}: command '{}' registration REJECTED (name already owned)", identity, name);
            return null;
        }
        declarations.add(declaration);
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
                    commandReleaser.accept(declaration);
                }
            }
        };
        ledger.record(handle);
        return handle;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable, R> R sneakyThrow(Throwable failure) throws T {
        throw (T) failure;
    }
}
