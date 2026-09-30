package net.nestworld.module;

import net.minecraft.network.chat.Component;

/**
 * MODULE-1: the failure-injection test module. Registers A (listener) then B (command), then
 * throws before ever attempting C -- proving a module that fails mid-onEnable() does not leave
 * a half-registered state: {@link ModuleRegistry#enableOne} must revoke B, then A (reverse
 * order), leaving the ledger empty and the module FAILED, never RUNNING.
 */
public final class FailingExampleModule implements NestWorldModule {
    @Override
    public ModuleDescriptor descriptor() {
        return new ModuleDescriptor(new ModuleId("failing-example-module"), new ModuleVersion("1.0.0"), "Failing Example Module");
    }

    @Override
    public void onEnable(ModuleContext context) throws Exception {
        context.registerListener(new Object() {
            // deliberately empty -- exists only to be a real, revocable listener registration
        });
        context.registerCommand("failmodcmd", ctx -> {
            ctx.getSource().sendSuccess(() -> Component.literal("should never be reachable"), false);
            return 1;
        });
        throw new IllegalStateException("FailingExampleModule: deliberate failure before registration C, "
                + "per MODULE-1's failure-injection test spec");
    }
}
