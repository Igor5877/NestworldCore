package dev.ftb.mods.ftbquests.net;

import dev.architectury.networking.NetworkManager;
import dev.architectury.networking.simple.BaseC2SMessage;
import dev.architectury.networking.simple.MessageType;
import dev.ftb.mods.ftbquests.quest.IslandData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public class RequestIslandDataMessage extends BaseC2SMessage {
    public RequestIslandDataMessage(FriendlyByteBuf buf) {
    }

    public RequestIslandDataMessage() {
    }

    @Override
    public MessageType getType() {
        return FTBQuestsNetHandler.REQUEST_ISLAND_DATA;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
    }

    @Override
    public void handle(NetworkManager.PacketContext context) {
        if (context.getPlayer() instanceof ServerPlayer serverPlayer) {
            IslandData data = IslandData.get(serverPlayer);
            if (data != null) {
                new SyncIslandDataMessage(data, true).sendTo(serverPlayer);
            }
        }
    }
}
