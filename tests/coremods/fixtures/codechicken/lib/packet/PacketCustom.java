package codechicken.lib.packet;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
public class PacketCustom implements codechicken.lib.data.MCDataOutput {
 public static final Set<Integer> RECEIVED = ConcurrentHashMap.newKeySet();
 public static final List<Integer> SENT_ORDER = new CopyOnWriteArrayList<>();
 public static volatile Runnable beforeSend = () -> {};
 public static final AtomicBoolean FAIL_NEXT = new AtomicBoolean();
 private int count;
 private final List<Integer> ids = new ArrayList<>();
 public PacketCustom(net.minecraft.resources.ResourceLocation channel, int type) {}
 public PacketCustom writeVarInt(int count) { this.count = count; return this; }
 public void record(int id) { ids.add(id); }
 public void sendToPlayer(net.minecraft.server.level.ServerPlayer player) {
  beforeSend.run();
  if (FAIL_NEXT.getAndSet(false)) throw new IllegalStateException("injected send failure");
  if (count != ids.size()) throw new AssertionError("Packet count/content mismatch");
  for (int id : ids) {
   if (!RECEIVED.add(id)) throw new AssertionError("Duplicate update " + id);
   SENT_ORDER.add(id);
  }
 }
}
