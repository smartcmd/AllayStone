package org.allaymc.allaystone;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

final class PythonRuntime implements AutoCloseable {
    private static final String RESOURCE_LIST_PATH = "python/resource-list.txt";
    private static final String[] HELPER_RESOURCES = {
            "python/src/allaystone/__init__.py",
            "python/src/allaystone/plugin.py"
    };

    private static final String PREPARE_PATHS = """
            import sys

            helper = __allaystone_helper_src
            plugin = __allaystone_plugin_src

            if helper not in sys.path:
                sys.path.insert(0, helper)
            if plugin not in sys.path:
                sys.path.insert(0, plugin)
            """;

    private final Path helperSourceRoot;
    private final Path wheelRoot;
    private final Engine engine;
    private volatile boolean closed;

    PythonRuntime(Path dataFolder) {
        try {
            var runtimeRoot = Files.createDirectories(dataFolder.resolve(".runtime"));
            helperSourceRoot = Files.createDirectories(runtimeRoot.resolve("src"));
            wheelRoot = Files.createDirectories(runtimeRoot.resolve("wheels"));
            copyHelperPackage();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to initialize GraalPy runtime directories.", e);
        }

        engine = Engine.newBuilder("python")
                .option("engine.WarnInterpreterOnly", "false")
                .build();
    }

    Path prepareInstallRoot(String pluginName) {
        ensureOpen();
        var installRoot = wheelRoot.resolve(pluginName);
        deleteRecursively(installRoot);
        try {
            return Files.createDirectories(installRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create Python wheel install root for " + pluginName + ".", e);
        }
    }

    PythonContextHandle createContext(Path pluginSourceRoot) {
        ensureOpen();
        var context = Context.newBuilder("python")
                .engine(engine)
                .allowExperimentalOptions(true)
                .allowAllAccess(true)
                .build();
        var handle = new PythonContextHandle(context);
        handle.run(polyglot -> {
            var bindings = polyglot.getBindings("python");
            bindings.putMember("__allaystone_helper_src", helperSourceRoot.toAbsolutePath().toString());
            bindings.putMember("__allaystone_plugin_src", pluginSourceRoot.toAbsolutePath().toString());
            polyglot.eval("python", PREPARE_PATHS);
        });
        return handle;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            engine.close();
        } catch (RuntimeException ignored) {
        }
    }

    private void copyHelperPackage() throws IOException {
        for (var resourcePath : listHelperResources()) {
            var relativePath = Path.of(resourcePath.replaceFirst("^python/src/", ""));
            var destination = helperSourceRoot.resolve(relativePath);
            Files.createDirectories(Objects.requireNonNull(destination.getParent()));
            try (InputStream in = PythonRuntime.class.getClassLoader().getResourceAsStream(resourcePath)) {
                if (in == null) {
                    throw new IOException("Bundled resource not found: " + resourcePath);
                }
                Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static List<String> listHelperResources() throws IOException {
        try (InputStream in = PythonRuntime.class.getClassLoader().getResourceAsStream(RESOURCE_LIST_PATH)) {
            if (in == null) {
                return List.of(HELPER_RESOURCES);
            }

            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .toList();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("The GraalPy runtime is already closed.");
        }
    }

    static void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }

        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (exc != null) {
                        throw exc;
                    }
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("Unable to clean runtime directory " + path + ".", e);
        }
    }

    static final class PythonContextHandle implements AutoCloseable {
        private final Context context;
        private final ReentrantLock lock = new ReentrantLock(true);
        private volatile boolean closed;

        PythonContextHandle(Context context) {
            this.context = context;
        }

        <T> T call(Function<Context, T> action) {
            lock.lock();
            var outermostCall = lock.getHoldCount() == 1;
            try {
                ensureOpen();
                if (outermostCall) {
                    context.enter();
                }
                try {
                    return action.apply(context);
                } finally {
                    if (outermostCall) {
                        context.leave();
                    }
                }
            } finally {
                lock.unlock();
            }
        }

        void run(Consumer<Context> action) {
            call(context -> {
                action.accept(context);
                return null;
            });
        }

        @Override
        public void close() {
            lock.lock();
            try {
                if (closed) {
                    return;
                }
                closed = true;
                context.close();
            } finally {
                lock.unlock();
            }
        }

        private void ensureOpen() {
            if (closed) {
                throw new IllegalStateException("The Python context is already closed.");
            }
        }
    }
}
