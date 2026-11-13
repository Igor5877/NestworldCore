package net.nestworld.inspector;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/** One optional play channel: absent on either side is accepted, so the mod never blocks a connection. */
public final class InspectorNetwork {
    public static final String VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(NestworldInspector.MODID, "main"), () -> VERSION,
            NetworkRegistry.acceptMissingOr(VERSION), NetworkRegistry.acceptMissingOr(VERSION));

    private InspectorNetwork() {}

    static void init() {
        CHANNEL.messageBuilder(SnapshotMsg.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SnapshotMsg::encode).decoder(SnapshotMsg::decode).consumerMainThread(SnapshotMsg::handle).add();
    }

    public static boolean hasMod(ServerPlayer p) {
        try {
            return CHANNEL.isRemotePresent(p.connection.connection);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Tells a client with the mod to drop its snapshot (sent on /nwinspect off). */
    static void sendClear(ServerPlayer p) {
        if (hasMod(p)) CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new SnapshotMsg(null));
    }

    /** {@code snapshot == null} = clear. */
    public record SnapshotMsg(Snapshot snapshot) {
        void encode(FriendlyByteBuf buf) {
            buf.writeBoolean(snapshot != null);
            if (snapshot != null) snapshot.encode(buf);
        }

        static SnapshotMsg decode(FriendlyByteBuf buf) {
            return new SnapshotMsg(buf.readBoolean() ? Snapshot.decode(buf) : null);
        }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> net.nestworld.inspector.client.ClientInspector.accept(snapshot));
            ctx.get().setPacketHandled(true);
        }
    }
}
