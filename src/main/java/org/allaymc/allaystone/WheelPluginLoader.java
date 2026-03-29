package org.allaymc.allaystone;

import org.allaymc.api.plugin.PluginContainer;
import org.allaymc.api.plugin.PluginDependency;
import org.allaymc.api.plugin.PluginDescriptor;
import org.allaymc.api.plugin.PluginException;
import org.allaymc.api.plugin.PluginLoader;
import org.graalvm.polyglot.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static org.allaymc.api.plugin.PluginContainer.createPluginContainer;

final class WheelPluginLoader implements PluginLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(WheelPluginLoader.class);
    private static final String RUNTIME_PLUGIN_NAME = "AllayStone";

    private static final String IMPORT_PLUGIN_CLASS = """
            import importlib
            from allaystone.plugin import Plugin as _AllayStonePlugin

            module = importlib.import_module(__allaystone_module_name)
            value = module
            for segment in __allaystone_attr_path.split("."):
                value = getattr(value, segment)

            if not isinstance(value, type):
                raise TypeError(f"{__allaystone_entry_point} does not resolve to a class")
            if not issubclass(value, _AllayStonePlugin):
                raise TypeError(
                    f"{__allaystone_entry_point} must inherit from allaystone.plugin.Plugin"
                )

            value
            """;

    private final PythonRuntime runtime;
    private final PythonEnvironment environment;
    private final Path pluginPath;
    private PythonEnvironment.InstalledPlugin installedPlugin;
    private PythonPluginDescriptor descriptor;

    WheelPluginLoader(PythonRuntime runtime, PythonEnvironment environment, Path pluginPath) {
        this.runtime = runtime;
        this.environment = environment;
        this.pluginPath = pluginPath;
    }

    @Override
    public Path getPluginPath() {
        return pluginPath;
    }

    @Override
    public synchronized PluginDescriptor loadDescriptor() {
        if (descriptor != null) {
            return descriptor;
        }

        var installedPlugin = environment.loadInstalledPlugin(pluginPath);
        try (var context = runtime.createContext(List.of(), environment.sitePackageDirs())) {
            var pluginClass = loadPluginClass(context, installedPlugin.entryPoint());
            descriptor = buildDescriptor(pluginClass, installedPlugin.distributionMetadata(), installedPlugin.entryPoint());
            if (descriptor.getAPIVersion().isBlank()) {
                LOGGER.warn("Python plugin {} does not declare api_version.", descriptor.getName());
            }
            this.installedPlugin = installedPlugin;
            return descriptor;
        }
    }

    @Override
    public PluginContainer loadPlugin() {
        var descriptor = (PythonPluginDescriptor) loadDescriptor();
        var bridge = new PythonPluginBridge(this, createLoadedPlugin(requireInstalledPlugin()));
        return createPluginContainer(
                bridge,
                descriptor,
                this,
                WheelPluginSource.getOrCreateDataFolder(descriptor.getName())
        );
    }

    synchronized LoadedPythonPlugin reloadPlugin() {
        var descriptor = (PythonPluginDescriptor) loadDescriptor();
        var reloadedPlugin = environment.reloadPlugin(descriptor.getName());
        if (!reloadedPlugin.entryPoint().name().equals(descriptor.getName())) {
            throw new PluginException("Reloaded Python plugin name does not match loaded plugin " + descriptor.getName() + ".");
        }
        installedPlugin = reloadedPlugin;
        return createLoadedPlugin(reloadedPlugin);
    }

    private LoadedPythonPlugin createLoadedPlugin(PythonEnvironment.InstalledPlugin installedPlugin) {
        var context = runtime.createContext(List.of(), environment.sitePackageDirs());
        try {
            var pluginClass = loadPluginClass(context, installedPlugin.entryPoint());
            if (!pluginClass.canInstantiate()) {
                throw new PluginException("Python plugin class " + installedPlugin.entryPoint().rawValue() + " is not instantiable.");
            }

            var pluginInstance = context.call(ignored -> pluginClass.newInstance());
            return new LoadedPythonPlugin(context, pluginInstance);
        } catch (RuntimeException e) {
            context.close();
            throw e;
        }
    }

    private static Value loadPluginClass(
            PythonRuntime.PythonContextHandle context,
            PythonDistribution.EntryPointSpec entryPoint
    ) {
        return context.call(polyglot -> {
            var bindings = polyglot.getBindings("python");
            bindings.putMember("__allaystone_module_name", entryPoint.moduleName());
            bindings.putMember("__allaystone_attr_path", entryPoint.attributePath());
            bindings.putMember("__allaystone_entry_point", entryPoint.rawValue());
            return polyglot.eval("python", IMPORT_PLUGIN_CLASS);
        });
    }

    private PythonPluginDescriptor buildDescriptor(
            Value pluginClass,
            PythonDistribution.DistributionMetadata distributionMetadata,
            PythonDistribution.EntryPointSpec entryPoint
    ) {
        var pluginName = normalizeName(entryPoint.name());
        var version = readString(pluginClass.getMember("version"), distributionMetadata.version());
        if (version.isBlank()) {
            throw new PluginException("Python plugin " + pluginName + " does not declare a version.");
        }

        var apiVersion = readString(pluginClass.getMember("api_version"), "");
        var description = readString(pluginClass.getMember("description"), distributionMetadata.summary());
        var authors = readStringList(pluginClass.getMember("authors"), distributionMetadata.authors());
        var website = readString(pluginClass.getMember("website"), distributionMetadata.website());
        var dependencies = new ArrayList<PluginDependency>();
        readDependencyNames(pluginClass.getMember("depend"))
                .forEach(name -> dependencies.add(new PluginDependency(normalizeDependencyName(name), null, false)));
        readDependencyNames(pluginClass.getMember("soft_depend"))
                .forEach(name -> dependencies.add(new PluginDependency(normalizeDependencyName(name), null, true)));
        if (dependencies.stream().noneMatch(dependency -> dependency.name().equalsIgnoreCase(RUNTIME_PLUGIN_NAME))) {
            dependencies.add(new PluginDependency(RUNTIME_PLUGIN_NAME, null, false));
        }

        return new PythonPluginDescriptor(
                pluginName,
                entryPoint.rawValue(),
                version,
                authors,
                description,
                apiVersion,
                dependencies,
                website
        );
    }

    private static String readString(Value value, String defaultValue) {
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.isString()) {
            throw new PluginException("Expected a string value but got " + value + ".");
        }
        return value.asString();
    }

    private static List<String> readStringList(Value value, List<String> defaultValue) {
        if (value == null || value.isNull()) {
            return List.copyOf(defaultValue);
        }
        if (value.isString()) {
            return List.of(value.asString());
        }
        if (!value.hasArrayElements()) {
            throw new PluginException("Expected a list of strings but got " + value + ".");
        }

        var result = new ArrayList<String>();
        for (long i = 0; i < value.getArraySize(); i++) {
            var item = value.getArrayElement(i);
            if (!item.isString()) {
                throw new PluginException("Expected dependency and author entries to be strings.");
            }
            result.add(item.asString());
        }
        return List.copyOf(result);
    }

    private static List<String> readDependencyNames(Value value) {
        return readStringList(value, List.of());
    }

    private static String normalizeName(String value) {
        var normalized = value.strip().toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-");
        if (normalized.isBlank()) {
            throw new PluginException("Plugin names must not be blank.");
        }
        return normalized;
    }

    private static String normalizeDependencyName(String value) {
        var normalized = normalizeName(value);
        return normalized.equals(normalizeName(RUNTIME_PLUGIN_NAME)) ? RUNTIME_PLUGIN_NAME : normalized;
    }

    private PythonEnvironment.InstalledPlugin requireInstalledPlugin() {
        return Objects.requireNonNull(installedPlugin);
    }

    static final class Factory implements PluginLoader.Factory {
        private final PythonRuntime runtime;
        private final PythonEnvironment environment;

        Factory(PythonRuntime runtime, PythonEnvironment environment) {
            this.runtime = runtime;
            this.environment = environment;
        }

        @Override
        public boolean canLoad(Path pluginPath) {
            return PythonDistribution.isMetadataPath(pluginPath);
        }

        @Override
        public PluginLoader create(Path pluginPath) {
            return new WheelPluginLoader(runtime, environment, pluginPath);
        }
    }

    static final class LoadedPythonPlugin implements AutoCloseable {
        private final PythonRuntime.PythonContextHandle context;
        private final Value pythonPlugin;

        private LoadedPythonPlugin(PythonRuntime.PythonContextHandle context, Value pythonPlugin) {
            this.context = context;
            this.pythonPlugin = pythonPlugin;
        }

        PythonRuntime.PythonContextHandle context() {
            return context;
        }

        Value pythonPlugin() {
            return pythonPlugin;
        }

        @Override
        public void close() {
            context.close();
        }
    }

    private static final class PythonPluginDescriptor implements PluginDescriptor {
        private final String name;
        private final String entrance;
        private final String version;
        private final List<String> authors;
        private final String description;
        private final String apiVersion;
        private final List<PluginDependency> dependencies;
        private final String website;

        private PythonPluginDescriptor(
                String name,
                String entrance,
                String version,
                List<String> authors,
                String description,
                String apiVersion,
                List<PluginDependency> dependencies,
                String website
        ) {
            this.name = name;
            this.entrance = entrance;
            this.version = version;
            this.authors = List.copyOf(authors);
            this.description = description;
            this.apiVersion = apiVersion;
            this.dependencies = List.copyOf(dependencies);
            this.website = website;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getEntrance() {
            return entrance;
        }

        @Override
        public String getVersion() {
            return version;
        }

        @Override
        public List<String> getAuthors() {
            return authors;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public String getAPIVersion() {
            return apiVersion;
        }

        @Override
        public List<PluginDependency> getDependencies() {
            return dependencies;
        }

        @Override
        public String getWebsite() {
            return website;
        }
    }
}
