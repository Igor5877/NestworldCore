package net.nestworld.inspector;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkConstants;

/**
 * NestWorld Inspector: read-only admin visualisation of NestWorld Core's region / execution-domain layout.
 * Optional on both sides. Client classes live in {@code net.nestworld.inspector.client} and are reached only under
 * {@link Dist#CLIENT}.
 */
@Mod(NestworldInspector.MODID)
public final class NestworldInspector {
    public static final String MODID = "nestworld_inspector";

    public NestworldInspector() {
        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(() -> NetworkConstants.IGNORESERVERONLY, (remote, isServer) -> true));
        InspectorNetwork.init();
        MinecraftForge.EVENT_BUS.register(ServerInspector.class);
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> net.nestworld.inspector.client.ClientInspector.init(modBus));
    }
}
