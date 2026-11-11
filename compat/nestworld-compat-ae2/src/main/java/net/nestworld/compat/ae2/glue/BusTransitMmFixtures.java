package net.nestworld.compat.ae2.glue;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.parts.PartHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.util.AEColor;
import appeng.blockentity.storage.DriveBlockEntity;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEItems;
import appeng.core.definitions.AEParts;
import appeng.me.Grid;
import appeng.parts.automation.IOBusPart;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandlerModifiable;

/** Targeted fixture for the pack's real 2x2x2 Fire Attuned machine (4 crystals -> 4 infused crystals).
 * Clone that machine first; no MM implementation, controller routing or recipe is replaced.
 */
public final class BusTransitMmFixtures {
    private static final int SEED = 128;
    private static Fixture fixture;

    private static final class Fixture {
        final ServerLevel level;
        final BlockPos input, output, energy, fluid, controller, exportHost, importHost, inputDrive, outputDrive;
        final AEItemKey raw, cooked;
        final AtomicInteger pending = new AtomicInteger(), errors = new AtomicInteger();
        final AtomicLong portUnits = new AtomicLong();
        long seeded, networkUnits, cookedNetwork, held;
        volatile int machineActive = -1;
        volatile boolean formed;
        boolean paused;

        Fixture(ServerLevel level, BlockPos min) {
            this.level = level;
            input = min.above(); output = min.offset(1, 1, 1); energy = min.offset(1, 1, 0);
            fluid = min; controller = min.offset(0, 1, 1);
            exportHost = input.west(); importHost = output.east();
            inputDrive = exportHost.west(); outputDrive = importHost.east();
            raw = key("armorplus:lava_crystal"); cooked = key("armorplus:infused_lava_crystal");
        }
    }

    private BusTransitMmFixtures() {}

    private static AEItemKey key(String id) {
        var item = BuiltInRegistries.ITEM.get(new ResourceLocation(id));
        if (item == net.minecraft.world.item.Items.AIR) throw new IllegalArgumentException("Missing MM recipe item " + id);
        return AEItemKey.of(item);
    }

    public static String setup(ServerLevel level, BlockPos min) {
        Fixture f = new Fixture(level, min);
        if (fixture != null && fixture.pending.get() != 0) return "MM fixture busy";
        fixture = f;
        for (BlockPos p : new BlockPos[]{f.input, f.output}) {
            if (!String.valueOf(BusTransit.expectedBeType(level, p)).startsWith("mm:"))
                throw new IllegalArgumentException("Clone the real MM machine first: missing port at " + p);
        }
        // Recreate the controller so cloned absolute port references cannot survive from the source machine.
        var state = level.getBlockState(f.controller);
        level.setBlockAndUpdate(f.controller, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(f.controller, state);
        buildGrid(f, true); buildGrid(f, false);
        f.pending.set(2);
        for (BlockPos p : new BlockPos[]{f.input, f.output}) dispatch(f, p, () -> {
            var be = level.getBlockEntity(p);
            var handler = be.getCapability(ForgeCapabilities.ITEM_HANDLER).orElseThrow(IllegalStateException::new);
            if (!(handler instanceof IItemHandlerModifiable mod)) throw new IllegalStateException("MM port not modifiable");
            for (int slot = 0; slot < mod.getSlots(); slot++) mod.setStackInSlot(slot, ItemStack.EMPTY);
        });
        return "MM fixture built; seed=" + SEED + " input=" + BeDomainReady.domainName(level, f.input, BusTransit.expectedBeType(level, f.input))
                + " output=" + BeDomainReady.domainName(level, f.output, BusTransit.expectedBeType(level, f.output))
                + " controller=" + BeDomainReady.domainName(level, f.controller, BusTransit.expectedBeType(level, f.controller));
    }

    private static void buildGrid(Fixture f, boolean export) {
        BlockPos host = export ? f.exportHost : f.importHost, drive = export ? f.inputDrive : f.outputDrive;
        f.level.setBlockAndUpdate(export ? drive.west() : drive.east(), AEBlocks.CREATIVE_ENERGY_CELL.block().defaultBlockState());
        f.level.setBlockAndUpdate(drive, AEBlocks.DRIVE.block().defaultBlockState());
        var d = (DriveBlockEntity) f.level.getBlockEntity(drive);
        d.getInternalInventory().setItemDirect(0, AEItems.ITEM_CELL_256K.stack());
        PartHelper.setPart(f.level, host, null, null, AEParts.GLASS_CABLE.item(AEColor.TRANSPARENT));
        IOBusPart bus = export ? PartHelper.setPart(f.level, host, Direction.EAST, null, AEParts.EXPORT_BUS.asItem())
                : PartHelper.setPart(f.level, host, Direction.WEST, null, AEParts.IMPORT_BUS.asItem());
        if (bus == null) throw new IllegalStateException("MM fixture bus missing");
        if (export) bus.getConfig().setStack(0, new GenericStack(f.raw, 1));
        else bus.getConfig().setStack(0, new GenericStack(f.cooked, 1));
        for (int n = 0; n < 2; n++) bus.getUpgrades().addItems(AEItems.SPEED_CARD.stack());
    }

    private static Grid grid(Fixture f, BlockPos p) {
        if (!(f.level.getBlockEntity(p) instanceof DriveBlockEntity drive)) return null;
        var node = drive.getMainNode().getNode();
        return node != null && BusTransit.liveGrid(node) instanceof Grid g ? g : null;
    }

    private static void dispatch(Fixture f, BlockPos p, Runnable action) {
        String type = BusTransit.expectedBeType(f.level, p);
        Runnable wrapped = () -> {
            try {
                if (!BeDomainReady.currentThreadIn(f.level, p, type)) throw new IllegalStateException("MM fixture wrong domain");
                action.run();
            } catch (Throwable t) {
                f.errors.incrementAndGet();
                Ae2Compat.LOG.error("MM targeted fixture failure at {}", p, t);
            } finally { f.pending.decrementAndGet(); }
        };
        try {
            if (BusTransit.domainIsMain(f.level, p, type)) wrapped.run();
            else BeDomainReady.enqueueTo(f.level, p, type, wrapped, kind -> { f.errors.incrementAndGet(); f.pending.decrementAndGet(); });
        } catch (Throwable t) { f.errors.incrementAndGet(); f.pending.decrementAndGet(); }
    }

    public static void tick(int tick) {
        Fixture f = fixture;
        if (f == null || f.paused || f.pending.get() != 0 || f.errors.get() != 0) return;
        Grid g = grid(f, f.inputDrive);
        if (f.seeded == 0 && g != null) f.seeded = g.getStorageService().getInventory().insert(f.raw, SEED, Actionable.MODULATE, IActionSource.empty());
        if (tick % 20 != 0) return;
        f.pending.set(2);
        dispatch(f, f.energy, () -> f.level.getBlockEntity(f.energy).getCapability(ForgeCapabilities.ENERGY)
                .orElseThrow(IllegalStateException::new).receiveEnergy(2_000_000_000, false));
        dispatch(f, f.fluid, () -> f.level.getBlockEntity(f.fluid).getCapability(ForgeCapabilities.FLUID_HANDLER)
                .orElseThrow(IllegalStateException::new).fill(new FluidStack(Fluids.LAVA, 1_000_000), IFluidHandler.FluidAction.EXECUTE));
    }

    public static String snapshot() {
        Fixture f = fixture;
        if (f == null) return "MM fixture absent";
        if (f.pending.get() != 0) return "MM snapshot busy";
        f.networkUnits = 0; f.cookedNetwork = 0; f.held = 0; f.portUnits.set(0);
        for (BlockPos drive : new BlockPos[]{f.inputDrive, f.outputDrive}) {
            Grid g = grid(f, drive);
            if (g != null) {
                var stacks = g.getStorageService().getInventory().getAvailableStacks();
                f.networkUnits += stacks.get(f.raw) + stacks.get(f.cooked);
                f.cookedNetwork += stacks.get(f.cooked);
            }
        }
        for (BusTransit.Rec record : BusTransit.LIVE_INDEX()) {
            if (!record.hostPos.equals(f.exportHost) && !record.hostPos.equals(f.importHost)) continue;
            synchronized (record) { f.held += record.heldTotal() + record.inflightTotal(); }
        }
        f.pending.set(3);
        for (BlockPos p : new BlockPos[]{f.input, f.output}) dispatch(f, p, () -> {
            var handler = f.level.getBlockEntity(p).getCapability(ForgeCapabilities.ITEM_HANDLER).orElseThrow(IllegalStateException::new);
            long amount = 0;
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                var stack = handler.getStackInSlot(slot);
                if (stack.is(f.raw.getItem()) || stack.is(f.cooked.getItem())) amount += stack.getCount();
            }
            f.portUnits.addAndGet(amount);
        });
        dispatch(f, f.controller, () -> {
            Object controller = f.level.getBlockEntity(f.controller);
            try {
                f.machineActive = (int) controller.getClass().getMethod("getActiveRecipeCount").invoke(controller);
                f.formed = (boolean) controller.getClass().getMethod("isFormed").invoke(controller);
            } catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
        });
        return "MM snapshot requested";
    }

    public static String pause(boolean value) {
        if (fixture == null) return "MM fixture absent";
        fixture.paused = value;
        return "MM fixture paused=" + value;
    }

    public static String status() {
        Fixture f = fixture;
        if (f == null) return "MM fixture absent";
        return "MM fixture pending=" + f.pending.get() + " errors=" + f.errors.get() + " seeded=" + f.seeded
                + " formed=" + f.formed + " activeRecipes=" + f.machineActive + " network=" + f.networkUnits
                + " cookedNetwork=" + f.cookedNetwork
                + " ports=" + f.portUnits.get() + " held=" + f.held + " balance=" + (f.networkUnits + f.portUnits.get() + f.held - f.seeded)
                + " (require activeRecipes=0 and quiescent snapshot)";
    }
}
