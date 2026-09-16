package com.mozhi.assistant.bootstrap;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;

/** A private runtime: JDK directly, shared game types from parent, everything else locally. */
public final class AgentClassLoader extends URLClassLoader {
    static { registerAsParallelCapable(); }

    public AgentClassLoader(URL runtimeJar, ClassLoader gameLoader) {
        super("Mozhi-Agent-Runtime", new URL[]{runtimeJar}, gameLoader);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> type = findLoadedClass(name);
            if (type == null) {
                if (isShared(name)) {
                    // One definition of bridge/game types prevents ClassCastException.
                    type = getParent().loadClass(name);
                } else {
                    try {
                        // Bypass the game filter for ALL JDK modules, including org.w3c.dom
                        // and org.xml.sax. Prefix-only java/javax checks miss these packages.
                        type = ClassLoader.getPlatformClassLoader().loadClass(name);
                    } catch (ClassNotFoundException notInJdk) {
                        // No game-parent fallback for implementation or third-party classes.
                        type = findClass(name);
                    }
                }
            }
            if (resolve) resolveClass(type);
            return type;
        }
    }

    private static boolean isShared(String name) {
        return name.startsWith("com.mozhi.assistant.bridge.")
                || name.startsWith("com.fs.") || name.startsWith("org.lwjgl.")
                || name.startsWith("org.apache.log4j.") || name.startsWith("org.json.");
    }

    @Override
    public URL getResource(String name) {
        URL local = findResource(name);
        if (local != null || name.startsWith("META-INF/services/")) return local;
        return getParent().getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        // Do not discover service providers supplied by the game or other mods.
        if (name.startsWith("META-INF/services/")) return findResources(name);
        Set<URL> resources = new LinkedHashSet<>(Collections.list(findResources(name)));
        resources.addAll(Collections.list(getParent().getResources(name)));
        return Collections.enumeration(resources);
    }
}
