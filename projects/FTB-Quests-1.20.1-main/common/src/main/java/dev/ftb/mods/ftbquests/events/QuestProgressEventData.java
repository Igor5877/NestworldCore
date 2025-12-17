package dev.ftb.mods.ftbquests.events;

import dev.ftb.mods.ftbquests.net.DisplayCompletionToastMessage;
import dev.ftb.mods.ftbquests.quest.IslandData;
import dev.ftb.mods.ftbquests.quest.QuestObject;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;

public final class QuestProgressEventData<T extends QuestObject> {
	private final Date time;
	private final IslandData islandData;
	private final T object;
	private final List<ServerPlayer> onlineMembers;
	private final List<ServerPlayer> notifiedPlayers;

	public QuestProgressEventData(Date date, IslandData islandData, T object, Collection<ServerPlayer> online, Collection<ServerPlayer> notified) {
		time = date;
		this.islandData = islandData;
		this.object = object;
		onlineMembers = new ArrayList<>(online);
		notifiedPlayers = new ArrayList<>(notified);
	}

	public void setStarted(long id) {
		islandData.setStarted(id, time);
	}

	public void setCompleted(long id) {
		islandData.setCompleted(id, time);
	}

	public void notifyPlayers(long id) {
		notifiedPlayers.forEach(player -> new DisplayCompletionToastMessage(id).sendTo(player));
	}

	public Date getTime() {
		return time;
	}

	public IslandData getIslandData() {
		return islandData;
	}

	public T getObject() {
		return object;
	}

	public List<ServerPlayer> getOnlineMembers() {
		return onlineMembers;
	}

	public List<ServerPlayer> getNotifiedPlayers() {
		return notifiedPlayers;
	}

	public <N extends QuestObject> QuestProgressEventData<N> withObject(N o) {
		return object == o ? (QuestProgressEventData<N>) this : new QuestProgressEventData<>(time, islandData, o, onlineMembers, notifiedPlayers);
	}
}
