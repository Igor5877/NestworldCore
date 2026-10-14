package net.nestworld.module;

/** Content generations of the named domains ONLY, not a proof of full attach compatibility.
 * Recipe serialization includes serializer identity, recipe id, ingredients and result.
 * No client-specific filtering or arbitrary mod payload contract is asserted here. */
public record SyncDataSnapshot(String registryGeneration, String recipeGeneration, int recipeCount) {}
