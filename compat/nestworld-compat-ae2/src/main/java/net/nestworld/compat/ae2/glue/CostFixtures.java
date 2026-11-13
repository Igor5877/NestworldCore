package net.nestworld.compat.ae2.glue;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import appeng.me.storage.ExternalStorageFacade;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;

/**
 * AE2-COST-001-FAST differential fixture (test copies only, {@code -Dnestworld.compatAe2.costFixtures=true}).
 * Clones real Sophisticated chests of the loaded mining claim (full BE NBT: contents, upgrades, settings) twice into the
 * overworld (A, B), then applies the SAME seeded random operation sequence to both: AE2 facade extract (A: AE2's original
 * slot loop, B: SOPH-EXTRACT-INDEX), facade insert, getAvailableStacks (appliedenhancements slot-index rebuild), direct
 * handler insert/extract/setStackInSlot (pipes/players), and in-place ItemStack shrink/grow/NBT edits of slot stacks
 * (rogue mods). After EVERY operation it compares the returned amounts and all slots of A and B; the item totals of A
 * are checked against the conservation ledger (before + inserted = after + extracted).
 */
public final class CostFixtures {
    public static final boolean ENABLED = Boolean.getBoolean("nestworld.compatAe2.costFixtures");

    private CostFixtures() { }

    public static String diff(MinecraftServer server, int chests, int ops, long seed) {
        ServerLevel mining = server.getLevel(ResourceKey.create(Registries.DIMENSION, new ResourceLocation("jamd", "mining")));
        ServerLevel over = server.overworld();
        if (mining == null) return "no jamd:mining";
        List<BlockEntity> sources = new ArrayList<>();
        java.util.Set<Long> cps = new java.util.LinkedHashSet<>(mining.getForcedChunks());
        try { // test copy: the harness' claim chunk list (cwd = test copy root)
            String js = java.nio.file.Files.readString(java.nio.file.Path.of("mining-chunks.json"));
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*\\]").matcher(js);
            while (m.find()) cps.add(ChunkPos.asLong(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
        } catch (Exception ignored) { }
        int loaded = 0;
        for (long cp : cps) {
            LevelChunk ch = mining.getChunkSource().getChunkNow(ChunkPos.getX(cp), ChunkPos.getZ(cp));
            if (ch == null && mining.hasChunk(ChunkPos.getX(cp), ChunkPos.getZ(cp))) ch = mining.getChunk(ChunkPos.getX(cp), ChunkPos.getZ(cp));
            if (ch == null) continue;
            loaded++;
            for (BlockEntity be : ch.getBlockEntities().values())
                if (be.getClass().getName().startsWith("net.p3pp3rf1y.sophisticatedstorage.block.") && be.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.UP).isPresent())
                    sources.add(be);
        }
        // the real targets: blocks behind every AE2 storage bus of every grid (that is where grid #1's chests are)
        java.util.Set<BlockEntity> seen = new java.util.HashSet<>(sources);
        for (appeng.me.Grid g : appeng.hooks.ticking.TickHandler.instance().getGridList())
            for (appeng.parts.storagebus.StorageBusPart bus : g.getMachines(appeng.parts.storagebus.StorageBusPart.class)) {
                if (bus.getLevel() == null || bus.getBlockEntity() == null) continue;
                BlockEntity be = bus.getLevel().getBlockEntity(bus.getBlockEntity().getBlockPos().relative(bus.getSide()));
                if (be != null && be.getClass().getName().startsWith("net.p3pp3rf1y.sophisticatedstorage.block.") && seen.add(be)) sources.add(be);
            }
        sources.sort((a, b) -> Long.compare(a.getBlockPos().asLong(), b.getBlockPos().asLong()));
        StringBuilder out = new StringBuilder("costfx diff chunks=" + cps.size() + " loaded=" + loaded + " sources=" + sources.size());
        long totalOps = 0, mismatches = 0, indexedBefore = SophExtractIndex.INDEXED.sum(), conservationBad = 0;
        long extractCalls = 0, extractNonZero = 0;
        String firstMismatch = null;
        Random rnd = new Random(seed);
        IActionSource src = IActionSource.empty();
        int done = 0;
        for (BlockEntity source : sources) {
            if (done >= chests) break;
            BlockPos pa = new BlockPos(-200 + 4 * done, 310, -190), pb = pa.offset(0, 0, 3);
            CompoundTag tag = source.saveWithFullMetadata();
            BlockState state = source.getBlockState();
            IItemHandler ha = place(over, pa, state, tag), hb = place(over, pb, state, tag);
            if (ha == null || hb == null) { out.append("\n  place failed at ").append(pa); continue; }
            done++;
            ExternalStorageFacade fa = ExternalStorageFacade.of(ha), fb = ExternalStorageFacade.of(hb);
            List<AEItemKey> pool = new ArrayList<>();
            for (int s = 0; s < ha.getSlots(); s++) { AEItemKey k = AEItemKey.of(ha.getStackInSlot(s)); if (k != null && !pool.contains(k)) pool.add(k); }
            int presentKeys = pool.size();
            for (var item : new net.minecraft.world.item.Item[]{Items.COBBLESTONE, Items.DIAMOND, Items.IRON_INGOT, Items.ENCHANTED_BOOK, Items.STICK})
                pool.add(AEItemKey.of(item));
            if (presentKeys > 0) { // same Item, different NBT: must never be confused with the plain key
                ItemStack named = pool.get(0).toStack(1);
                named.setHoverName(net.minecraft.network.chat.Component.literal("nwfx"));
                pool.add(AEItemKey.of(named));
            }
            long ledgerIn = 0, ledgerOut = 0, before = count(ha);
            for (int i = 0; i < ops; i++, totalOps++) {
                int op = rnd.nextInt(100);
                AEItemKey key = pool.get(rnd.nextInt(pool.size()));
                int amount = new int[]{1, 3, 64, 100, 1000, 100000}[rnd.nextInt(6)];
                Actionable mode = rnd.nextBoolean() ? Actionable.SIMULATE : Actionable.MODULATE;
                String what;
                long ra, rb;
                int slots = Math.max(1, ha.getSlots());
                int slot = rnd.nextInt(slots);
                if (op < 55) {
                    what = "facadeExtract " + key + " x" + amount + " " + mode;
                    SophExtractIndex.fxBypass = true;
                    try { ra = fa.extract(key, amount, mode, src); } finally { SophExtractIndex.fxBypass = false; }
                    rb = fb.extract(key, amount, mode, src);
                    extractCalls++;
                    if (ra > 0) extractNonZero++;
                    if (mode == Actionable.MODULATE) ledgerOut += ra;
                } else if (op < 63) {
                    what = "facadeInsert " + key + " x" + amount + " " + mode;
                    ra = fa.insert(key, amount, mode, src);
                    rb = fb.insert(key, amount, mode, src);
                    if (mode == Actionable.MODULATE) ledgerIn += ra;
                } else if (op < 68) {
                    what = "getAvailableStacks";
                    KeyCounter ka = new KeyCounter(), kb = new KeyCounter();
                    fa.getAvailableStacks(ka); fb.getAvailableStacks(kb);
                    ra = ka.size(); rb = ka.size() == kb.size() ? ra : -1;
                    for (var e : ka) if (kb.get(e.getKey()) != e.getLongValue()) rb = -2;
                } else if (op < 78) {
                    what = "handlerExtract slot " + slot + " x" + amount;
                    ra = ha.extractItem(slot, amount, false).getCount();
                    rb = hb.extractItem(slot, amount, false).getCount();
                    ledgerOut += ra;
                } else if (op < 86) {
                    ItemStack s = key.toStack(Math.min(amount, 64));
                    what = "handlerInsert slot " + slot + " " + s;
                    ra = s.getCount() - ha.insertItem(slot, s.copy(), false).getCount();
                    rb = s.getCount() - hb.insertItem(slot, s.copy(), false).getCount();
                    ledgerIn += ra;
                } else if (op < 90 && ha instanceof IItemHandlerModifiable ma && hb instanceof IItemHandlerModifiable mb) {
                    ItemStack s = rnd.nextBoolean() ? ItemStack.EMPTY : key.toStack(1 + rnd.nextInt(16));
                    what = "setStackInSlot " + slot + " " + s;
                    long old = ha.getStackInSlot(slot).getCount();
                    ma.setStackInSlot(slot, s.copy());
                    mb.setStackInSlot(slot, s.copy());
                    ledgerOut += old; ledgerIn += s.getCount();
                    ra = rb = 0;
                } else if (op < 96) {
                    // rogue mod: in-place shrink / grow of the live slot stack (no listener, no tracker update)
                    ItemStack la = ha.getStackInSlot(slot), lb = hb.getStackInSlot(slot);
                    int d = la.isEmpty() ? 0 : (rnd.nextBoolean() ? -Math.min(la.getCount(), 1 + rnd.nextInt(8)) : 1 + rnd.nextInt(8));
                    what = "inPlace " + d + " slot " + slot;
                    if (d != 0 && !la.isEmpty() && !lb.isEmpty()) { la.grow(d); lb.grow(d); if (d > 0) ledgerIn += d; else ledgerOut -= d; }
                    ra = rb = d;
                } else {
                    // rogue mod: in-place NBT edit of a live slot stack (same Item, new tag)
                    ItemStack la = ha.getStackInSlot(slot), lb = hb.getStackInSlot(slot);
                    what = "inPlaceTag slot " + slot;
                    if (!la.isEmpty() && !lb.isEmpty()) { la.getOrCreateTag().putInt("nwfx", i); lb.getOrCreateTag().putInt("nwfx", i); }
                    ra = rb = 0;
                }
                String diff = ra != rb ? "result A=" + ra + " B=" + rb : compareSlots(ha, hb);
                if (diff != null) {
                    mismatches++;
                    if (firstMismatch == null) firstMismatch = "chest " + done + " op " + i + " " + what + ": " + diff;
                }
            }
            long after = count(ha);
            if (before + ledgerIn != after + ledgerOut) conservationBad++;
            java.util.Set<String> ups = new java.util.TreeSet<>();
            java.util.regex.Matcher um = java.util.regex.Pattern.compile("sophisticated[a-z]*:[a-z_]*upgrade[a-z_]*").matcher(tag.toString());
            while (um.find()) ups.add(um.group());
            out.append(String.format("%n  chest %d %s slots=%d presentKeys=%d before=%d in=%d out=%d after=%d conservation=%s upgrades=%s",
                    done, BuiltInRegistries.BLOCK.getKey(state.getBlock()), ha.getSlots(), presentKeys, before, ledgerIn, ledgerOut, after,
                    before + ledgerIn == after + ledgerOut ? "OK" : "BAD", ups));
            over.removeBlockEntity(pa); over.removeBlockEntity(pb);
            over.setBlock(pa, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2 | 16);
            over.setBlock(pb, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2 | 16);
        }
        out.append("\n  VERDICT ops=").append(totalOps).append(" facadeExtract=").append(extractCalls).append(" nonZero=").append(extractNonZero)
                .append(" indexedCallsOnB=").append(SophExtractIndex.INDEXED.sum() - indexedBefore)
                .append(" mismatches(must be 0)=").append(mismatches).append(" conservationBad(must be 0)=").append(conservationBad)
                .append(firstMismatch == null ? "" : "\n  FIRST " + firstMismatch);
        return out.toString();
    }

    private static IItemHandler place(ServerLevel level, BlockPos pos, BlockState state, CompoundTag tag) {
        level.setBlock(pos, state, 2 | 16);
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return null;
        CompoundTag t = tag.copy();
        t.putInt("x", pos.getX()); t.putInt("y", pos.getY()); t.putInt("z", pos.getZ());
        be.load(t);
        be.setChanged();
        return be.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.UP).orElse(null);
    }

    private static long count(IItemHandler h) {
        long n = 0;
        for (int s = 0; s < h.getSlots(); s++) n += h.getStackInSlot(s).getCount();
        return n;
    }

    private static String compareSlots(IItemHandler a, IItemHandler b) {
        if (a.getSlots() != b.getSlots()) return "slots " + a.getSlots() + " vs " + b.getSlots();
        for (int s = 0; s < a.getSlots(); s++)
            if (!ItemStack.matches(a.getStackInSlot(s), b.getStackInSlot(s)))
                return "slot " + s + " A=" + a.getStackInSlot(s) + a.getStackInSlot(s).getTag() + " B=" + b.getStackInSlot(s) + b.getStackInSlot(s).getTag();
        return null;
    }
}
