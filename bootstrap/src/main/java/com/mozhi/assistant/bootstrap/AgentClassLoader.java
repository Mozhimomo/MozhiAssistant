package com.mozhi.assistant.bootstrap;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;

/** 私有运行环境：直接加载 JDK，共享游戏类型由父加载器提供，其余类型均在本地加载。 */
public final class AgentClassLoader extends URLClassLoader {
    static { registerAsParallelCapable(); }

    public AgentClassLoader(URL runtimeJar, URL llmJar, ClassLoader gameLoader) {
        this(gameLoader, runtimeJar, llmJar);
    }

    public AgentClassLoader(ClassLoader gameLoader, URL... runtimeJars) {
        super("Mozhi-Private-Runtime", runtimeJars.clone(), gameLoader);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> type = findLoadedClass(name);
            if (type == null) {
                if (isShared(name)) {
                    // 桥接与游戏类型只保留一个定义，避免 ClassCastException。
                    type = getParent().loadClass(name);
                } else {
                    try {
                        // 所有 JDK 模块均绕过游戏过滤器，包括 org.w3c.dom
                        // 和 org.xml.sax；仅检查 java/javax 前缀会遗漏这些包。
                        type = ClassLoader.getPlatformClassLoader().loadClass(name);
                    } catch (ClassNotFoundException notInJdk) {
                        // 实现类和第三方类不回退到游戏父加载器。
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
        // 不发现游戏或其他模组提供的服务实现。
        if (name.startsWith("META-INF/services/")) return findResources(name);
        Set<URL> resources = new LinkedHashSet<>(Collections.list(findResources(name)));
        resources.addAll(Collections.list(getParent().getResources(name)));
        return Collections.enumeration(resources);
    }
}
