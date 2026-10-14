package net.nestworld.module;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.registries.RegistryManager;
import java.util.Comparator;
import java.util.TreeMap;

/** Called only on the owning server thread, after resources are loaded. No reflection or disk IO. */
final class ModuleSyncData {
    static SyncDataSnapshot capture(MinecraftServer server) {
        ContentGeneration registries = new ContentGeneration("forge-registries");
        new TreeMap<>(RegistryManager.ACTIVE.takeSnapshot(false)).forEach((name, snapshot) -> {
            registries.field(name.toString());
            // Match Forge's wire fields, including aliases/overrides/blocked IDs. Snapshot maps
            // are sorted by Forge. Own the temporary buffer rather than touching its cached buffer.
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            try {
                buf.writeMap(snapshot.ids, FriendlyByteBuf::writeResourceLocation, FriendlyByteBuf::writeVarInt);
                buf.writeMap(snapshot.aliases, FriendlyByteBuf::writeResourceLocation, FriendlyByteBuf::writeResourceLocation);
                buf.writeMap(snapshot.overrides, FriendlyByteBuf::writeResourceLocation, (b, v) -> b.writeUtf(v, 0x100));
                buf.writeCollection(snapshot.blocked, FriendlyByteBuf::writeVarInt);
                registries.field(bytes(buf));
            } finally {
                buf.release();
            }
        });
        String registryGeneration = registries.finish();
        ContentGeneration recipes = new ContentGeneration("recipes");
        // Serializer payloads can contain registry numeric IDs. Bind the recipe digest to those IDs.
        recipes.field(registryGeneration);
        var sorted = server.getRecipeManager().getRecipes().stream()
                .sorted(Comparator.comparing(r -> r.getId().toString())).toList();
        for (Recipe<?> recipe : sorted) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            try {
                ClientboundUpdateRecipesPacket.toNetwork(buf, recipe);
                recipes.field(bytes(buf));
            } finally {
                buf.release();
            }
        }
        return new SyncDataSnapshot(registryGeneration, recipes.finish(), sorted.size());
    }

    private static byte[] bytes(FriendlyByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), bytes);
        return bytes;
    }
}
