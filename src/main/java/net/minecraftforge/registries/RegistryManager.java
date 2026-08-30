/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.minecraftforge.registries;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import com.mojang.serialization.Lifecycle;
import net.minecraft.core.WritableRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.Registry;
import net.minecraftforge.fml.ModLoader;
import net.minecraftforge.network.HandshakeMessages;
import net.minecraftforge.registries.ForgeRegistry.Snapshot;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class RegistryManager
{
    private static final Logger LOGGER = LogManager.getLogger();
    public static final RegistryManager ACTIVE = new RegistryManager("ACTIVE");
    public static final RegistryManager VANILLA = new RegistryManager("VANILLA");
    public static final RegistryManager FROZEN = new RegistryManager("FROZEN");
    private static Set<ResourceLocation> vanillaRegistryKeys = Set.of();

    BiMap<ResourceLocation, ForgeRegistry<?>> registries = HashBiMap.create();
    private Set<ResourceLocation> persisted = Sets.newHashSet();
    private Set<ResourceLocation> synced = Sets.newHashSet();
    private Map<ResourceLocation, ResourceLocation> legacyNames = new HashMap<>();
    private final String name;

    RegistryManager()
    {
        this("STAGING");
    }

    public RegistryManager(String name)
    {
        this.name = name;
    }

    public String getName()
    {
        return this.name;
    }

    /**
     * NestWorld: writes every registry currently in {@code ACTIVE}'s current
     * id -&gt; key -&gt; value mapping to {@code out}, one registry section at a time,
     * sorted by id within each registry -- a ground-truth snapshot for detecting
     * any registry-id-assignment drift across boots (e.g. after a patch touching
     * block/state construction or registration order -- see project memory
     * registry-bake-parallel-init-cache.md for exactly the kind of change this is
     * meant to guard against). Deliberately NOT the same path as
     * {@link ForgeRegistry#dump}, which is gated behind the REGISTRYDUMP log4j
     * marker and only usable at boot time -- this is callable directly, any time,
     * e.g. from an in-game/RCON command, with no logging-config dependency.
     */
    public static void nestworldDumpAll(java.io.PrintWriter out) {
        java.util.List<java.util.Map.Entry<ResourceLocation, ForgeRegistry<?>>> sorted =
                new java.util.ArrayList<>(ACTIVE.registries.entrySet());
        sorted.sort(java.util.Map.Entry.comparingByKey());
        for (java.util.Map.Entry<ResourceLocation, ForgeRegistry<?>> entry : sorted) {
            ResourceLocation name = entry.getKey();
            ForgeRegistry<?> reg = entry.getValue();
            out.println("=== " + name + " (" + reg.getKeys().size() + " entries) ===");
            reg.getKeys().stream()
                    .map(reg::getID)
                    .sorted()
                    .forEach(id -> {
                        Object value = reg.getValue(id);
                        ResourceKey<?> key = reg.getKey(id);
                        out.println(id + "\t" + key + "\t" + value);
                    });
        }
    }

    boolean isStaging()
    {
        return "STAGING".equals(this.name);
    }

    @SuppressWarnings("unchecked")
    public <V> ForgeRegistry<V> getRegistry(ResourceLocation key)
    {
        return (ForgeRegistry<V>)this.registries.get(key);
    }

    public <V> ForgeRegistry<V> getRegistry(ResourceKey<? extends Registry<V>> key)
    {
        return getRegistry(key.location());
    }

    public <V> ResourceLocation getName(IForgeRegistry<V> reg)
    {
        return this.registries.inverse().get(reg);
    }

    public <V> ResourceLocation updateLegacyName(ResourceLocation legacyName)
    {
        ResourceLocation originalName = legacyName;
        while (getRegistry(legacyName) == null)
        {
            legacyName = legacyNames.get(legacyName);
            if (legacyName == null)
            {
                return originalName;
            }
        }
        return legacyName;
    }

    public <V> ForgeRegistry<V> getRegistry(ResourceLocation key, RegistryManager other)
    {
        if (!this.registries.containsKey(key))
        {
            ForgeRegistry<V> ot = other.getRegistry(key);
            if (ot == null)
                return null;
            this.registries.put(key, ot.copy(this));
            if (other.persisted.contains(key))
                this.persisted.add(key);
            if (other.synced.contains(key))
                this.synced.add(key);
            other.legacyNames.entrySet().stream()
                 .filter(e -> e.getValue().equals(key))
                 .forEach(e -> addLegacyName(e.getKey(), e.getValue()));
        }
        return getRegistry(key);
    }

    <V> ForgeRegistry<V> createRegistry(ResourceLocation name, RegistryBuilder<V> builder)
    {
        if (registries.containsKey(name))
            throw new IllegalArgumentException("Attempted to register a registry for " + name + " but it already exists");
        ForgeRegistry<V> reg = new ForgeRegistry<V>(this, name, builder);
        registries.put(name, reg);
        if (builder.getSaveToDisc())
            this.persisted.add(name);
        if (builder.getSync())
            this.synced.add(name);
        for (ResourceLocation legacyName : builder.getLegacyNames())
            addLegacyName(legacyName, name);
        return getRegistry(name);
    }

    static <V> void registerToRootRegistry(ForgeRegistry<V> forgeReg)
    {
        injectForgeRegistry(forgeReg, BuiltInRegistries.REGISTRY);
    }

    @SuppressWarnings("unchecked")
    private static <V> void injectForgeRegistry(ForgeRegistry<V> forgeReg, Registry<? extends Registry<?>> rootRegistry)
    {
        WritableRegistry<Registry<V>> registry = (WritableRegistry<Registry<V>>) rootRegistry;
        Registry<V> wrapper = forgeReg.getWrapper();
        if (wrapper != null)
            registry.register(forgeReg.getRegistryKey(), wrapper, Lifecycle.experimental());
    }

    public static void postNewRegistryEvent()
    {
        NewRegistryEvent event = new NewRegistryEvent();
        DataPackRegistryEvent.NewRegistry dataPackEvent = new DataPackRegistryEvent.NewRegistry();
        vanillaRegistryKeys = Set.copyOf(BuiltInRegistries.REGISTRY.keySet());

        ModLoader.get().postEventWrapContainerInModOrder(event);
        ModLoader.get().postEventWrapContainerInModOrder(dataPackEvent);

        event.fill();
        dataPackEvent.process();
    }

    private void addLegacyName(ResourceLocation legacyName, ResourceLocation name)
    {
        if (this.legacyNames.containsKey(legacyName))
        {
            throw new IllegalArgumentException("Legacy name conflict for registry " + name + ", upgrade path must be linear: " + legacyName);
        }
        this.legacyNames.put(legacyName, name);
    }

    private void findSuperTypes(Class<?> type, Set<Class<?>> types)
    {
        if (type == null || type == Object.class)
        {
            return;
        }
        types.add(type);
        for (Class<?> interfac : type.getInterfaces())
        {
            findSuperTypes(interfac, types);
        }
        findSuperTypes(type.getSuperclass(), types);
    }

    public Map<ResourceLocation, Snapshot> takeSnapshot(boolean savingToDisc)
    {
        Map<ResourceLocation, Snapshot> ret = Maps.newHashMap();
        Set<ResourceLocation> keys = savingToDisc ? this.persisted : this.synced;
        keys.forEach(name -> ret.put(name, getRegistry(name).makeSnapshot()));
        return ret;
    }

    //Public for testing only
    public void clean()
    {
        this.persisted.clear();
        this.synced.clear();
        this.registries.clear();
    }

    public static List<Pair<String, HandshakeMessages.S2CRegistry>> generateRegistryPackets(boolean isLocal)
    {
        return !isLocal ? ACTIVE.takeSnapshot(false).entrySet().stream().
                map(e->Pair.of("Registry " + e.getKey(), new HandshakeMessages.S2CRegistry(e.getKey(), e.getValue()))).
                collect(Collectors.toList()) : Collections.emptyList();
    }

    public static List<ResourceLocation> getRegistryNamesForSyncToClient()
    {
        return ACTIVE.registries.keySet().stream().
                filter(resloc -> ACTIVE.synced.contains(resloc)).
                collect(Collectors.toList());
    }

    public static Set<ResourceLocation> getVanillaRegistryKeys()
    {
        return vanillaRegistryKeys;
    }
}
