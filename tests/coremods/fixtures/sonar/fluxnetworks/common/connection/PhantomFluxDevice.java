package sonar.fluxnetworks.common.connection;
public class PhantomFluxDevice implements sonar.fluxnetworks.api.device.IFluxDevice {
 public static PhantomFluxDevice makeUnloaded(sonar.fluxnetworks.common.device.TileFluxDevice device) {return new PhantomFluxDevice();}
}
