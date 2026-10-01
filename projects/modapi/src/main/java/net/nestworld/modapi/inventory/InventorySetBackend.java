package net.nestworld.modapi.inventory;

/**
 * <p><b>PLATFORM PROVIDER SPI — not part of the public Mod API.</b> This type is a contract
 * between NestWorldCore and the Mod API provider, not a promise to mods; it may change, or move to
 * a separate artifact, without notice (docs/contracts/PKG-002-distribution-model.md, D1a). Mods
 * use the facades ({@code NestWorldEntities}, {@code NestWorldInventory}) and their value types.
 *
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
