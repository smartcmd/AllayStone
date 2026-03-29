package org.allaymc.allaystone;

import org.allaymc.api.plugin.PluginContainer;
import org.allaymc.api.plugin.PluginDependency;
import org.allaymc.api.plugin.PluginDescriptor;
import org.allaymc.api.plugin.PluginException;
import org.allaymc.api.plugin.PluginLoader;
import org.graalvm.polyglot.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.allaymc.api.plugin.PluginContainer.createPluginContainer;

final class WheelPluginLoader implements PluginLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(WheelPluginLoader.class);
    private static final java.nio.file.PathMatcher WHEEL_MATCHER =
            FileSystems.getDefault().getPathMatcher("glob:**.whl");
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
    private final Path pluginPath;
    private DistributionMetadata distributionMetadata;
    private EntryPointSpec entryPoint;
    private Path installRoot;
    private PythonPluginDescriptor descriptor;

    WheelPluginLoader(PythonRuntime runtime, Path pluginPath) {
        this.runtime = runtime;
        this.pluginPath = pluginPath;
    }

    @Override
    public Path getPluginPath() {
        return pluginPath;
    }

    @Override
    public PluginDescriptor loadDescriptor() {
        if (descriptor != null) {
            return descriptor;
        }

        try (var zipFile = new ZipFile(pluginPath.toFile())) {
            distributionMetadata = readDistributionMetadata(zipFile);
            entryPoint = readEntryPoint(zipFile, distributionMetadata.distributionName());
            installRoot = extractWheel(zipFile, distributionMetadata);
        } catch (IOException e) {
            throw new PluginException("Unable to inspect Python wheel " + pluginPath.getFileName() + ".", e);
        }

        try (var context = runtime.createContext(installRoot)) {
            var pluginClass = loadPluginClass(context);
            descriptor = buildDescriptor(pluginClass);
            if (descriptor.getAPIVersion().isBlank()) {
                LOGGER.warn("Python plugin {} does not declare api_version.", descriptor.getName());
            }
            return descriptor;
        }
    }

    @Override
    public PluginContainer loadPlugin() {
        var descriptor = (PythonPluginDescriptor) loadDescriptor();
        var context = runtime.createContext(Objects.requireNonNull(installRoot));
        try {
            var pluginClass = loadPluginClass(context);
            if (!pluginClass.canInstantiate()) {
                throw new PluginException("Python plugin class " + entryPoint.rawValue() + " is not instantiable.");
            }

            var pluginInstance = context.call(ignored -> pluginClass.newInstance());
            var bridge = new PythonPluginBridge(context, pluginInstance);
            return createPluginContainer(
                    bridge,
                    descriptor,
                    this,
                    WheelPluginSource.getOrCreateDataFolder(descriptor.getName())
            );
        } catch (RuntimeException e) {
            context.close();
            throw e;
        }
    }

    private Value loadPluginClass(PythonRuntime.PythonContextHandle context) {
        return context.call(polyglot -> {
            var bindings = polyglot.getBindings("python");
            bindings.putMember("__allaystone_module_name", entryPoint.moduleName());
            bindings.putMember("__allaystone_attr_path", entryPoint.attributePath());
            bindings.putMember("__allaystone_entry_point", entryPoint.rawValue());
            return polyglot.eval("python", IMPORT_PLUGIN_CLASS);
        });
    }

    private PythonPluginDescriptor buildDescriptor(Value pluginClass) {
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

    private Path extractWheel(ZipFile zipFile, DistributionMetadata distributionMetadata) throws IOException {
        var installRoot = runtime.prepareInstallRoot(normalizeName(distributionMetadata.distributionName()));
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            var entry = entries.nextElement();
            if (entry.isDirectory()) {
                continue;
            }

            var mappedPath = mapWheelEntry(entry.getName());
            if (mappedPath == null || mappedPath.isBlank()) {
                continue;
            }

            var destination = installRoot.resolve(mappedPath).normalize();
            if (!destination.startsWith(installRoot)) {
                throw new PluginException("Python wheel " + pluginPath.getFileName() + " contains invalid entry " + entry.getName() + ".");
            }

            Files.createDirectories(Objects.requireNonNull(destination.getParent()));
            try (var in = zipFile.getInputStream(entry)) {
                Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return installRoot;
    }

    private static String mapWheelEntry(String entryName) {
        var marker = ".data/purelib/";
        var markerIndex = entryName.indexOf(marker);
        if (markerIndex >= 0) {
            return entryName.substring(markerIndex + marker.length());
        }
        if (entryName.contains(".data/")) {
            return null;
        }
        return entryName;
    }

    private static DistributionMetadata readDistributionMetadata(ZipFile zipFile) throws IOException {
        var metadataEntry = findDistInfoEntry(zipFile, "/METADATA");
        if (metadataEntry == null) {
            throw new PluginException("Python wheel " + zipFile.getName() + " does not contain a METADATA file.");
        }

        var fields = parseMetadataFields(readTextEntry(zipFile, metadataEntry));
        var distributionName = firstField(fields, "Name");
        var version = firstField(fields, "Version");
        if (distributionName == null || distributionName.isBlank()) {
            throw new PluginException("Python wheel " + zipFile.getName() + " does not declare a distribution name.");
        }
        if (version == null || version.isBlank()) {
            throw new PluginException("Python wheel " + zipFile.getName() + " does not declare a version.");
        }

        var authors = new ArrayList<String>();
        var author = firstField(fields, "Author");
        if (author != null && !author.isBlank()) {
            authors.add(author);
        }
        if (authors.isEmpty()) {
            var authorEmail = firstField(fields, "Author-email");
            if (authorEmail != null && !authorEmail.isBlank()) {
                authors.add(authorEmail);
            }
        }

        return new DistributionMetadata(
                distributionName,
                version,
                defaultString(firstField(fields, "Summary")),
                authors,
                resolveWebsite(fields)
        );
    }

    private static EntryPointSpec readEntryPoint(ZipFile zipFile, String distributionName) throws IOException {
        var entryPointEntry = findDistInfoEntry(zipFile, "/entry_points.txt");
        if (entryPointEntry == null) {
            throw new PluginException("Python wheel " + zipFile.getName() + " does not define allaystone entry points.");
        }

        var sections = parseIniSections(readTextEntry(zipFile, entryPointEntry));
        var groupEntries = sections.getOrDefault("allaystone", Collections.emptyMap());
        if (groupEntries.size() != 1) {
            throw new PluginException("Python wheel " + zipFile.getName() + " must define exactly one [allaystone] entry point.");
        }

        var entry = groupEntries.entrySet().iterator().next();
        var entryName = normalizeName(entry.getKey());
        var expectedDistributionName = "allaystone-" + entryName;
        if (!normalizeName(distributionName).equals(expectedDistributionName)) {
            throw new PluginException(
                    "Wheel distribution " + distributionName + " must match the allaystone entry point name " + entry.getKey() + "."
            );
        }

        var value = entry.getValue().trim();
        var separator = value.indexOf(':');
        if (separator <= 0 || separator == value.length() - 1) {
            throw new PluginException("Invalid allaystone entry point value: " + value + ".");
        }

        return new EntryPointSpec(entryName, value, value.substring(0, separator), value.substring(separator + 1));
    }

    private static ZipEntry findDistInfoEntry(ZipFile zipFile, String suffix) {
        return zipFile.stream()
                .filter(entry -> entry.getName().endsWith(".dist-info" + suffix))
                .findFirst()
                .orElse(null);
    }

    private static String readTextEntry(ZipFile zipFile, ZipEntry entry) throws IOException {
        try (var in = zipFile.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, List<String>> parseMetadataFields(String content) throws IOException {
        var values = new LinkedHashMap<String, List<String>>();
        try (var reader = new BufferedReader(new StringReader(content))) {
            String currentKey = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    currentKey = null;
                    continue;
                }
                if ((line.startsWith(" ") || line.startsWith("\t")) && currentKey != null) {
                    var currentValues = values.get(currentKey);
                    var lastIndex = currentValues.size() - 1;
                    currentValues.set(lastIndex, currentValues.get(lastIndex) + "\n" + line.strip());
                    continue;
                }

                var separator = line.indexOf(':');
                if (separator <= 0) {
                    continue;
                }

                currentKey = line.substring(0, separator);
                values.computeIfAbsent(currentKey, ignored -> new ArrayList<>())
                        .add(line.substring(separator + 1).trim());
            }
        }
        return values;
    }

    private static Map<String, Map<String, String>> parseIniSections(String content) throws IOException {
        var sections = new LinkedHashMap<String, Map<String, String>>();
        try (var reader = new BufferedReader(new StringReader(content))) {
            String currentSection = null;
            String line;
            while ((line = reader.readLine()) != null) {
                var stripped = line.trim();
                if (stripped.isEmpty() || stripped.startsWith("#") || stripped.startsWith(";")) {
                    continue;
                }
                if (stripped.startsWith("[") && stripped.endsWith("]")) {
                    currentSection = stripped.substring(1, stripped.length() - 1).trim();
                    sections.computeIfAbsent(currentSection, ignored -> new LinkedHashMap<>());
                    continue;
                }
                if (currentSection == null) {
                    continue;
                }

                var separator = stripped.indexOf('=');
                if (separator <= 0) {
                    continue;
                }

                sections.get(currentSection).put(
                        stripped.substring(0, separator).trim(),
                        stripped.substring(separator + 1).trim()
                );
            }
        }
        return sections;
    }

    private static String firstField(Map<String, List<String>> fields, String name) {
        var values = fields.get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.getFirst();
    }

    private static String resolveWebsite(Map<String, List<String>> fields) {
        var homePage = firstField(fields, "Home-page");
        if (homePage != null && !homePage.isBlank()) {
            return homePage;
        }

        var projectUrls = fields.get("Project-URL");
        if (projectUrls == null) {
            return "";
        }
        for (var projectUrl : projectUrls) {
            var separator = projectUrl.indexOf(',');
            if (separator >= 0 && separator < projectUrl.length() - 1) {
                return projectUrl.substring(separator + 1).trim();
            }
            if (!projectUrl.isBlank()) {
                return projectUrl;
            }
        }
        return "";
    }

    private static String defaultString(String value) {
        return value == null ? "" : value;
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

    static final class Factory implements PluginLoader.Factory {
        private final PythonRuntime runtime;

        Factory(PythonRuntime runtime) {
            this.runtime = runtime;
        }

        @Override
        public boolean canLoad(Path pluginPath) {
            return Files.isRegularFile(pluginPath) && WHEEL_MATCHER.matches(pluginPath);
        }

        @Override
        public PluginLoader create(Path pluginPath) {
            return new WheelPluginLoader(runtime, pluginPath);
        }
    }

    private record DistributionMetadata(
            String distributionName,
            String version,
            String summary,
            List<String> authors,
            String website
    ) {
    }

    private record EntryPointSpec(
            String name,
            String rawValue,
            String moduleName,
            String attributePath
    ) {
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
