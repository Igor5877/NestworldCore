package net.nestworld.module;

/** MODULE-0.5 §G1's identity model: {@code ModuleId + ModuleVersion + ModuleGeneration}. Carried
 *  through lifecycle, {@link ResourceLedger}, and {@link ModuleFailure} attribution -- per
 *  explicit instruction, generation is not a decorative field even though MODULE-1 has no
 *  async/hot-reload work that would exercise cross-generation staleness yet. */
public record ModuleIdentity(ModuleId id, ModuleVersion version, ModuleGeneration generation) {
    @Override
    public String toString() {
        return id.value() + "@" + version.value() + "#gen" + generation.value();
    }
}
