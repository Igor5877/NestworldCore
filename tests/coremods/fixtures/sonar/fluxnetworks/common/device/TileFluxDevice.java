package sonar.fluxnetworks.common.device;
public class TileFluxDevice implements sonar.fluxnetworks.api.device.IFluxDevice {
 private final net.minecraft.core.GlobalPos pos;
 public Runnable onDisconnect = () -> {};
 public TileFluxDevice(int id) {pos = new net.minecraft.core.GlobalPos(id);}
 public net.minecraft.core.GlobalPos getGlobalPos() {return pos;}
 public sonar.fluxnetworks.api.device.FluxDeviceType getDeviceType() {return new sonar.fluxnetworks.api.device.FluxDeviceType();}
 public sonar.fluxnetworks.common.connection.TransferHandler getTransferHandler() {return new sonar.fluxnetworks.common.connection.TransferHandler();}
 public void disconnect() {onDisconnect.run();}
}
