package net.nestworld.compat.mekanism.glue;

/** Implemented by TransmitterNetworkRegistry via mixin. */
public interface ChangedNetworks {
    /** networksToChange.add under the registry's own lock. */
    boolean nestworld$addChanged(Object network);

    /** TransmitterNetworkRegistry.reset() body, with networksToChange cleared under the lock. */
    void nestworld$reset();
}
