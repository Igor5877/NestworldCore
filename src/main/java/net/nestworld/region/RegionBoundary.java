/*
 * NestworldCore - Cross-region boundary abstraction (future open-world support)
 */
package net.nestworld.region;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for synchronising state across region boundaries in an open-world setup.
 *
 * In SkyBlock mode this is never invoked — islands are fully isolated processes.
 *
 * In open-world mode, two adjacent region-processes need to exchange:
 *   - Entity positions near the border (so players see mobs from the next region)
 *   - Block Entity state for multiblock structures that straddle a boundary
 *     (e.g. AE2 ME Network, Create contraptions, ReactorCraft cores)
 *   - Redstone signal levels at boundary blocks
 *
 * Implementation path (not part of Phase 1):
 *   1. RegionBoundaryServer — embedded Netty server in each region-process
 *      accepting border-state packets from neighbours.
 *   2. RegionBoundaryClient — sends border-state to neighbours every N ticks.
 *   3. BorderChunkProxy — presents "ghost" chunks from the neighbouring process
 *      to the local ServerLevel so Minecraft's own systems (chunk loading,
 *      entity tracking) work without modification.
 *   4. BlockEntityBridgeHandler — for specific well-known multiblock mods,
 *      serialise BE capability state and sync across the boundary.
 *
 * This design lets vanilla Minecraft and modded code remain unmodified.
 * The proxy chunks make the neighbouring region look local to each process.
 */
public interface RegionBoundary {

    /**
     * The two region-process UUIDs that share this boundary.
     */
    UUID regionA();
    UUID regionB();

    /**
     * Push border-chunk state from this region to the neighbour.
     * Called every 2 ticks for chunks within 2 chunks of the border.
     *
     * @param borderChunkData Serialised chunk data (NBT or custom binary)
     * @return Future completing when the neighbour acknowledges receipt
     */
    CompletableFuture<Void> pushBorderState(byte[] borderChunkData);

    /**
     * Receive and apply border-chunk state from the neighbouring region.
     * Applied to ghost chunks in the local ServerLevel.
     *
     * @param borderChunkData Serialised chunk data from neighbour
     */
    void applyNeighbourState(byte[] borderChunkData);

    /**
     * Notifies this boundary that a Block Entity at the given position needs
     * its capability state synchronised across the boundary.
     *
     * Used for multiblock structures (AE2, Create, etc.) whose controller block
     * sits in one region but member blocks sit in the neighbouring region.
     *
     * @param blockPos  Encoded block position (x << 38 | y << 12 | z) within region-local coords
     * @param capabilityId  Forge capability resource location string
     * @param nbtPayload    Serialised capability NBT
     */
    CompletableFuture<Void> syncBlockEntityCapability(long blockPos, String capabilityId, byte[] nbtPayload);
}
