package net.minecraftforge.event;
public class TickEvent {
 public enum Phase { START, END }
 public static class ServerTickEvent { public final Phase phase; public ServerTickEvent(Phase phase) {this.phase = phase;} }
}
