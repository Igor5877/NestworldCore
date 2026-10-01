package net.nestworld.modapi.inventory;

/**
 * Core-internal SPI, mirrors {@code net.nestworld.modapi.entity.VelocityExecutionBackend}'s
 * shape for a different primitive -- deliberately NOT unified into one generic backend
 * interface across primitives yet (two primitives show the shape; a third would justify
 * extraction, not before).
 *
 * <p>Mods call {@link NestWorldInventory} only. Implementations receive an already-captured,
 * already-detached {@link SetSlotRequest} (the live {@code ItemStack} never reaches this
 * interface) and are responsible for their OWN correct fresh-reacquisition/incarnation/version
 * checking sequence before the actual mutation -- see
 * {@code docs/contracts/InventorySetSlotPrimitive.md}'s 9-step sequence.
 */
public interface InventorySetBackend {
    void setSlot(SetSlotRequest request);
}
