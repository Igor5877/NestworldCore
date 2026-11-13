package net.nestworld.inspector;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * One server→client inspection snapshot around a player. Plain data; encoded by hand, size bounded by
 * {@link ServerInspector#MAX_RADIUS} (≤ 289 chunks) and {@link #MAX_BLOCK_ENTITIES}.
 */
public final class Snapshot {
    public static final int MAX_BLOCK_ENTITIES = 4096;
    public static final int MAX_TYPES = 1024;
    public static final int NONE = Integer.MIN_VALUE;

    /** Block-entity reason codes (why the domain is what it is). */
    public static final byte R_INTERIOR = 0, R_BORDER_BAND = 1, R_NO_REGION = 2, R_TYPE_PIN = 3, R_MOD_PIN = 4,
            R_POSITION_PIN = 5, R_CASCADE_SAFE = 6, R_UNEXPLAINED = 7, R_UNMANAGED = 8, R_ERROR = 9;

    public static String reasonName(byte r) {
        return switch (r) {
            case R_INTERIOR -> "region interior";
            case R_BORDER_BAND -> "border band";
            case R_NO_REGION -> "no region";
            case R_TYPE_PIN -> "type-pinned";
            case R_MOD_PIN -> "mod-pinned";
            case R_POSITION_PIN -> "position-pinned";
            case R_CASCADE_SAFE -> "cascade-safe (grid region)";
            case R_UNEXPLAINED -> "unexplained (Core differs)";
            case R_UNMANAGED -> "dimension not managed";
            case R_ERROR -> "Core query failed";
            default -> "?";
        };
    }

    public record Region(int id, float avgTickMs, int minCX, int minCZ, int maxCX, int maxCZ) {}

    /** {@code regionId} is the grid region (NONE if none); flags bit0 = border band, bit1 = loaded. */
    public record Chunk(int regionId, byte flags) {
        public boolean band() { return (flags & 1) != 0; }
        public boolean loaded() { return (flags & 2) != 0; }
    }

    /** {@code domain} = region id executing it, NONE = MAIN. */
    public record Be(long pos, String type, int domain, byte reason, int groupSize) {}

    public boolean managed;
    public String dimension = "";
    public int centerCX, centerCZ, radius;
    public float mspt;
    public boolean groupsSupported;
    public boolean truncated;
    public final List<Region> regions = new ArrayList<>();
    /** Row-major, (2r+1)^2 entries, x outer: index = (dx+r)*(2r+1) + (dz+r). */
    public final List<Chunk> chunks = new ArrayList<>();
    public final List<Be> blockEntities = new ArrayList<>();

    public Chunk chunkAt(int cx, int cz) {
        int dx = cx - centerCX, dz = cz - centerCZ;
        if (Math.abs(dx) > radius || Math.abs(dz) > radius) return null;
        int idx = (dx + radius) * (2 * radius + 1) + (dz + radius);
        return idx < chunks.size() ? chunks.get(idx) : null;
    }

    public Region region(int id) {
        for (Region r : regions) if (r.id == id) return r;
        return null;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(managed);
        buf.writeUtf(dimension, 256);
        buf.writeInt(centerCX);
        buf.writeInt(centerCZ);
        buf.writeVarInt(radius);
        buf.writeFloat(mspt);
        buf.writeBoolean(groupsSupported);
        buf.writeBoolean(truncated);
        buf.writeVarInt(regions.size());
        for (Region r : regions) {
            buf.writeInt(r.id);
            buf.writeFloat(r.avgTickMs);
            buf.writeInt(r.minCX);
            buf.writeInt(r.minCZ);
            buf.writeInt(r.maxCX);
            buf.writeInt(r.maxCZ);
        }
        buf.writeVarInt(chunks.size());
        for (Chunk c : chunks) {
            buf.writeInt(c.regionId);
            buf.writeByte(c.flags);
        }
        List<String> types = new ArrayList<>();
        java.util.Map<String, Integer> idx = new java.util.HashMap<>();
        for (Be b : blockEntities) idx.computeIfAbsent(b.type, t -> { types.add(t); return types.size() - 1; });
        buf.writeVarInt(types.size());
        for (String t : types) buf.writeUtf(t, 256);
        buf.writeVarInt(blockEntities.size());
        for (Be b : blockEntities) {
            buf.writeLong(b.pos);
            buf.writeVarInt(idx.get(b.type));
            buf.writeInt(b.domain);
            buf.writeByte(b.reason);
            buf.writeVarInt(b.groupSize);
        }
    }

    public static Snapshot decode(FriendlyByteBuf buf) {
        Snapshot s = new Snapshot();
        s.managed = buf.readBoolean();
        s.dimension = buf.readUtf(256);
        s.centerCX = buf.readInt();
        s.centerCZ = buf.readInt();
        s.radius = Math.min(buf.readVarInt(), ServerInspector.MAX_RADIUS);
        s.mspt = buf.readFloat();
        s.groupsSupported = buf.readBoolean();
        s.truncated = buf.readBoolean();
        int nr = Math.min(buf.readVarInt(), 4096);
        for (int i = 0; i < nr; i++) {
            s.regions.add(new Region(buf.readInt(), buf.readFloat(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt()));
        }
        int nc = buf.readVarInt();
        int maxC = (2 * ServerInspector.MAX_RADIUS + 1) * (2 * ServerInspector.MAX_RADIUS + 1);
        if (nc > maxC) throw new IllegalArgumentException("too many chunks " + nc);
        for (int i = 0; i < nc; i++) s.chunks.add(new Chunk(buf.readInt(), buf.readByte()));
        int nt = buf.readVarInt();
        if (nt > MAX_TYPES) throw new IllegalArgumentException("too many types " + nt);
        String[] types = new String[nt];
        for (int i = 0; i < nt; i++) types[i] = buf.readUtf(256);
        int nb = buf.readVarInt();
        if (nb > MAX_BLOCK_ENTITIES) throw new IllegalArgumentException("too many block entities " + nb);
        for (int i = 0; i < nb; i++) {
            long pos = buf.readLong();
            int ti = buf.readVarInt();
            s.blockEntities.add(new Be(pos, ti < nt ? types[ti] : "?", buf.readInt(), buf.readByte(), buf.readVarInt()));
        }
        return s;
    }
}
