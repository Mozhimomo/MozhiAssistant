package com.mozhi.llm.isolation;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * 受限宿主中的私有运行区。跨边界接口只使用 JDK 类型，SDK 类型全部留在私有加载器内。
 * runtimeJars 必须包含本库 all JAR 和调用方实现 JAR；不依赖具体游戏。
 * 调用方应在所有请求结束后 close，不可与正在运行的调用并发关闭。
 */
public final class IsolatedLlmRuntime<T> implements AutoCloseable {
    private final PrivateLoader loader;
    private final T entry;
    private boolean closed;

    private IsolatedLlmRuntime(PrivateLoader loader, T entry) {
        this.loader = loader;
        this.entry = entry;
    }

    public static <T> IsolatedLlmRuntime<T> open(Class<T> bridge, String implementation, URL... runtimeJars)
            throws ReflectiveOperationException, IOException {
        return open(bridge, implementation, new String[0], runtimeJars);
    }

    /** 额外共享的宿主包必须显式提供，如 com.fs.；不可共享本库或 LangChain4j 的实现包。 */
    @SuppressWarnings("deprecation")
    public static <T> IsolatedLlmRuntime<T> open(Class<T> bridge, String implementation,
                                                String[] sharedPackages, URL... runtimeJars)
            throws ReflectiveOperationException, IOException {
        Objects.requireNonNull(bridge, "bridge");
        if (!bridge.isInterface()) throw new IllegalArgumentException("bridge 必须是接口");
        if (bridge.getClassLoader() == null) throw new IllegalArgumentException("bridge 必须由宿主加载");
        if (runtimeJars.length == 0) throw new IllegalArgumentException("runtimeJars 不能为空");
        String[] shared = sharedPackages.clone();
        for (String prefix : shared) {
            if (prefix == null || prefix.isBlank() || !prefix.endsWith(".")
                    || prefix.startsWith("com.mozhi.llm.") || "com.mozhi.llm.".startsWith(prefix)
                    || prefix.startsWith("dev.langchain4j.") || "dev.langchain4j.".startsWith(prefix)) {
                throw new IllegalArgumentException("共享包须以点结尾，且不能包含 LLM 实现包");
            }
        }
        PrivateLoader loader = new PrivateLoader(runtimeJars.clone(), bridge.getClassLoader(),
                bridge.getName(), shared);
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        boolean ready = false;
        try {
            Thread.currentThread().setContextClassLoader(loader);
            // 宿主可能禁止解析 java.lang.reflect.Constructor，入口采用公开无参构造器。
            T entry = bridge.cast(loader.loadClass(implementation).newInstance());
            IsolatedLlmRuntime<T> runtime = new IsolatedLlmRuntime<>(loader, entry);
            ready = true;
            return runtime;
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
            if (!ready) loader.close();
        }
    }

    public <R> R call(Function<? super T, ? extends R> operation) {
        if (closed) throw new IllegalStateException("运行区已关闭");
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(loader);
            return operation.apply(entry);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        loader.close();
    }

    private static final class PrivateLoader extends URLClassLoader {
        static { registerAsParallelCapable(); }
        private final String bridge;
        private final String[] sharedPackages;

        PrivateLoader(URL[] urls, ClassLoader parent, String bridge, String[] sharedPackages) {
            super("Mozhi-LLM-Runtime", urls, parent);
            this.bridge = bridge;
            this.sharedPackages = sharedPackages;

        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name);
                if (type == null) {
                    if (shared(name)) {
                        type = getParent().loadClass(name);
                    } else {
                        try {
                            type = ClassLoader.getPlatformClassLoader().loadClass(name);
                        } catch (ClassNotFoundException notInJdk) {
                            type = findClass(name);
                        }
                    }
                }
                if (resolve) resolveClass(type);
                return type;
            }
        }

        private boolean shared(String name) {
            if (name.equals(bridge) || name.startsWith(bridge + "$")) return true;
            for (String prefix : sharedPackages) if (name.startsWith(prefix)) return true;
            return false;
        }

        @Override
        public URL getResource(String name) {
            URL local = findResource(name);
            if (local != null || name.startsWith("META-INF/services/")) return local;
            return getParent().getResource(name);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            if (name.startsWith("META-INF/services/")) return findResources(name);
            Set<URL> resources = new LinkedHashSet<>(Collections.list(findResources(name)));
            resources.addAll(Collections.list(getParent().getResources(name)));
            return Collections.enumeration(resources);
        }
    }
}
