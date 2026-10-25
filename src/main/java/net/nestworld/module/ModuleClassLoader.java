package net.nestworld.module;

import net.minecraftforge.eventbus.api.Event;

import java.net.URL;
import java.net.URLClassLoader;

/** JVM-lifetime, parent-first loader for one validated external module jar. */
final class ModuleClassLoader extends URLClassLoader {
    static { registerAsParallelCapable(); }

    ModuleClassLoader(String id, URL jar, ClassLoader parent) {
        super("nestworld-module-" + id, new URL[]{jar}, parent);
    }

    @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
        Class<?> type = super.findClass(name);
        if (type != Event.class && Event.class.isAssignableFrom(type)) {
            throw new ClassNotFoundException("module-defined Forge Event subclass is unsupported: " + name);
        }
        return type;
    }
}
