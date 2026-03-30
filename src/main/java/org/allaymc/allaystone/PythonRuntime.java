package org.allaymc.allaystone;

import org.graalvm.polyglot.*;
import org.graalvm.polyglot.io.IOAccess;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

final class PythonRuntime implements AutoCloseable {
    private static final String RESOURCE_LIST_PATH = "python/resource-list.txt";
    private static final String[] HELPER_RESOURCES = {
            "python/src/allaystone/__init__.py",
            "python/src/allaystone/plugin.py"
    };

    private static final String PREPARE_PATHS = """
            import site
            import sys
            
            helper = __allaystone_helper_src
            if helper not in sys.path:
                sys.path.insert(0, helper)
            
            for site_dir in filter(None, __allaystone_site_dirs.splitlines()):
                site.addsitedir(site_dir)
            
            for module_path in filter(None, __allaystone_module_paths.splitlines()):
                if module_path not in sys.path:
                    sys.path.insert(0, module_path)
            """;

    private static final String SITE_PACKAGE_DIRS = """
            import site
            
            site.getsitepackages(prefixes=[__allaystone_prefix])
            """;

    private static final String RUN_PIP = """
            import ensurepip
            import sys
            from importlib import resources
            
            wheel = resources.files("ensurepip") / "_bundled" / f"pip-{ensurepip.version()}-py3-none-any.whl"
            if str(wheel) not in sys.path:
                sys.path.insert(0, str(wheel))
            
            from pip._internal.cli.main import main as pip_main
            
            exit_code = pip_main(list(filter(None, __allaystone_pip_args.splitlines())))
            if exit_code:
                raise SystemExit(exit_code)
            """;

    private final Path helperSourceRoot;
    private final Engine engine;
    private volatile boolean closed;

    PythonRuntime(Path dataFolder) {
        try {
            var runtimeRoot = Files.createDirectories(dataFolder.resolve(".runtime"));
            helperSourceRoot = runtimeRoot.resolve("src");
            deleteRecursively(helperSourceRoot);
            Files.createDirectories(helperSourceRoot);
            copyHelperPackage();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to initialize GraalPy runtime directories.", e);
        }

        engine = Engine.newBuilder("python")
                .option("engine.WarnInterpreterOnly", "false")
                .build();
    }

    List<Path> getSitePackageDirs(Path prefix) {
        ensureOpen();
        try (var context = new PythonContextHandle(createPolyglotContext())) {
            var result = context.call(polyglot -> {
                polyglot.getBindings("python").putMember("__allaystone_prefix", prefix.toAbsolutePath().toString());
                return polyglot.eval("python", SITE_PACKAGE_DIRS);
            });
            return toPathList(result);
        }
    }

    void runPip(List<String> args) {
        ensureOpen();
        try (var context = new PythonContextHandle(createPolyglotContext())) {
            context.run(polyglot -> {
                polyglot.getBindings("python").putMember(
                        "__allaystone_pip_args",
                        args.stream().collect(Collectors.joining("\n"))
                );
                polyglot.eval("python", RUN_PIP);
            });
        }
    }

    PythonContextHandle createContext(List<Path> modulePaths, List<Path> sitePackageDirs) {
        ensureOpen();
        var handle = new PythonContextHandle(createPolyglotContext());
        handle.run(polyglot -> {
            var bindings = polyglot.getBindings("python");
            bindings.putMember("__allaystone_helper_src", helperSourceRoot.toAbsolutePath().toString());
            bindings.putMember("__allaystone_module_paths", joinPaths(modulePaths));
            bindings.putMember("__allaystone_site_dirs", joinPaths(sitePackageDirs));
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

    private Context createPolyglotContext() {
        return Context.newBuilder("python")
                .engine(engine)
                .hostClassLoader(PythonRuntime.class.getClassLoader())
                .allowExperimentalOptions(true)
                .allowAllAccess(true)
                .allowHostAccess(HostAccess.ALL)
                .allowHostClassLookup(className -> true)
                .allowHostClassLoading(true)
                .allowInnerContextOptions(true)
                .allowNativeAccess(true)
                .allowCreateThread(true)
                .allowCreateProcess(true)
                .allowEnvironmentAccess(EnvironmentAccess.INHERIT)
                .allowPolyglotAccess(PolyglotAccess.ALL)
                .allowIO(IOAccess.ALL)
                .allowValueSharing(true)
                .build();
    }

    private static String joinPaths(List<Path> paths) {
        return paths.stream()
                .map(path -> path.toAbsolutePath().toString())
                .collect(Collectors.joining("\n"));
    }

    private static List<Path> toPathList(Value value) {
        if (value == null || !value.hasArrayElements()) {
            throw new IllegalStateException("Expected GraalPy to return a list of site-packages directories.");
        }

        var result = new java.util.ArrayList<Path>();
        for (long i = 0; i < value.getArraySize(); i++) {
            result.add(Path.of(value.getArrayElement(i).asString()));
        }
        return List.copyOf(result);
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
