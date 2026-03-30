package org.allaymc.allaystone;

import org.allaymc.api.plugin.PluginException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

final class PythonEnvironment {
    private static final java.nio.file.PathMatcher WHEEL_MATCHER =
            FileSystems.getDefault().getPathMatcher("glob:**.whl");
    private static final Path INSTALL_PREFIX = WheelPluginSource.PLUGIN_DIRECTORY.resolve(".local");

    private final PythonRuntime runtime;
    private final List<Path> sitePackageDirs;
    private Map<Path, InstalledPlugin> installedPlugins = Map.of();
    private Map<String, InstalledPlugin> pluginsByName = Map.of();

    PythonEnvironment(PythonRuntime runtime) {
        this.runtime = runtime;
        try {
            Files.createDirectories(WheelPluginSource.PLUGIN_DIRECTORY);
            Files.createDirectories(INSTALL_PREFIX);
        } catch (IOException e) {
            throw new PluginException("Unable to initialize the Python plugin environment.", e);
        }

        this.sitePackageDirs = runtime.getSitePackageDirs(INSTALL_PREFIX).stream()
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .distinct()
                .peek(this::ensureDirectory)
                .toList();
    }

    List<Path> sitePackageDirs() {
        return sitePackageDirs;
    }

    synchronized Set<Path> findInstalledPluginMetadata() {
        installWheelPlugins();
        pruneManagedDistributions();
        return refreshInstalledPlugins().keySet();
    }

    synchronized InstalledPlugin loadInstalledPlugin(Path metadataPath) {
        var installedPlugin = installedPlugins.get(normalize(metadataPath));
        if (installedPlugin != null) {
            return installedPlugin;
        }

        var scanned = scanInstalledPlugin(metadataPath);
        if (scanned == null) {
            throw new PluginException("Python package metadata " + metadataPath + " is not an AllayStone plugin.");
        }

        var updatedPlugins = new LinkedHashMap<>(installedPlugins);
        updatedPlugins.put(scanned.metadataPath(), scanned);
        installedPlugins = Map.copyOf(updatedPlugins);
        return scanned;
    }

    synchronized InstalledPlugin reloadPlugin(String pluginName) {
        if (pluginsByName.isEmpty()) {
            refreshInstalledPlugins();
        }

        var installedPlugin = pluginsByName.get(pluginName);
        if (installedPlugin == null) {
            throw new PluginException("Unable to find Python plugin " + pluginName + " in the managed environment.");
        }

        var source = installedPlugin.installationSource();
        if (source != null && !source.editable() && isPluginWheel(source.path())) {
            installWheel(source.path());
        }

        pruneManagedDistributions();
        var refreshed = refreshInstalledPlugins().values().stream()
                .filter(plugin -> plugin.entryPoint().name().equals(pluginName))
                .findFirst()
                .orElse(null);
        if (refreshed == null) {
            throw new PluginException("Unable to refresh Python plugin " + pluginName + ".");
        }
        return refreshed;
    }

    private void installWheelPlugins() {
        List<Path> wheels;
        try (var stream = Files.list(WheelPluginSource.PLUGIN_DIRECTORY)) {
            wheels = stream
                    .filter(Files::isRegularFile)
                    .map(Path::toAbsolutePath)
                    .map(Path::normalize)
                    .filter(WHEEL_MATCHER::matches)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException e) {
            throw new PluginException("Unable to scan the plugins directory for Python wheels.", e);
        }

        for (var wheel : wheels) {
            installWheel(wheel);
        }
    }

    private void installWheel(Path wheelPath) {
        runtime.runPip(List.of(
                "install",
                "--quiet",
                "--no-cache-dir",
                "--no-warn-script-location",
                "--disable-pip-version-check",
                "--use-deprecated=legacy-certs",
                "--prefix",
                INSTALL_PREFIX.toAbsolutePath().toString(),
                "--upgrade",
                "--force-reinstall",
                wheelPath.toAbsolutePath().toString()
        ));
    }

    private void pruneManagedDistributions() {
        var installedDistributions = scanInstalledDistributions();
        var activePlugins = scanActivePlugins(installedDistributions);
        var reachableDistributionNames = collectReachableDistributionNames(activePlugins, installedDistributions);
        installedDistributions.values().stream()
                .filter(distribution -> !reachableDistributionNames.contains(distribution.normalizedName()))
                .forEach(this::removeInstalledDistribution);
    }

    private Map<Path, InstalledPlugin> refreshInstalledPlugins() {
        var plugins = scanInstalledPlugins();
        installedPlugins = Map.copyOf(plugins);
        pluginsByName = plugins.values().stream()
                .collect(Collectors.toMap(plugin -> plugin.entryPoint().name(), plugin -> plugin, (left, right) -> left, LinkedHashMap::new));
        return installedPlugins;
    }

    private Map<String, InstalledDistribution> scanInstalledDistributions() {
        var distributions = new LinkedHashMap<String, InstalledDistribution>();
        for (var sitePackageDir : sitePackageDirs) {
            if (!Files.isDirectory(sitePackageDir)) {
                continue;
            }

            try (var stream = Files.list(sitePackageDir)) {
                for (var candidate : stream.toList()) {
                    var distribution = scanInstalledDistribution(candidate);
                    if (distribution != null) {
                        var existing = distributions.putIfAbsent(distribution.normalizedName(), distribution);
                        if (existing != null && !existing.metadataPath().equals(distribution.metadataPath())) {
                            throw new PluginException(
                                    "Found multiple installed Python distributions named " +
                                    distribution.normalizedName() +
                                    ": " +
                                    existing.metadataPath() +
                                    " and " +
                                    distribution.metadataPath() +
                                    "."
                            );
                        }
                    }
                }
            } catch (IOException e) {
                throw new PluginException("Unable to inspect installed Python packages in " + sitePackageDir + ".", e);
            }
        }
        return distributions;
    }

    private Map<Path, InstalledPlugin> scanInstalledPlugins() {
        var plugins = new LinkedHashMap<Path, InstalledPlugin>();
        for (var plugin : scanActivePlugins(scanInstalledDistributions()).values()) {
            plugins.put(plugin.metadataPath(), plugin);
        }
        return plugins;
    }

    private Map<String, InstalledPlugin> scanActivePlugins(Map<String, InstalledDistribution> installedDistributions) {
        var plugins = new LinkedHashMap<String, InstalledPlugin>();
        for (var distribution : installedDistributions.values()) {
            var plugin = scanInstalledPlugin(distribution);
            if (plugin != null && shouldKeepPlugin(plugin)) {
                plugins.putIfAbsent(plugin.entryPoint().name(), plugin);
            }
        }
        return plugins;
    }

    private Set<String> collectReachableDistributionNames(
            Map<String, InstalledPlugin> activePlugins,
            Map<String, InstalledDistribution> installedDistributions
    ) {
        var pending = new ArrayDeque<String>();
        activePlugins.values().stream()
                .map(plugin -> PythonDistribution.normalizeDistributionName(plugin.distributionMetadata().distributionName()))
                .forEach(pending::addLast);

        var reachable = new java.util.LinkedHashSet<String>();
        while (!pending.isEmpty()) {
            var distributionName = pending.removeFirst();
            if (!reachable.add(distributionName)) {
                continue;
            }

            var distribution = installedDistributions.get(distributionName);
            if (distribution == null) {
                continue;
            }

            distribution.distributionMetadata().dependencyNames().stream()
                    .filter(installedDistributions::containsKey)
                    .forEach(pending::addLast);
        }
        return reachable;
    }

    private InstalledDistribution scanInstalledDistribution(Path metadataPath) {
        var normalizedPath = normalize(metadataPath);
        if (!PythonDistribution.isMetadataPath(normalizedPath)) {
            return null;
        }

        var distributionMetadata = PythonDistribution.readDistributionMetadata(normalizedPath);
        return new InstalledDistribution(
                normalizedPath,
                PythonDistribution.normalizeDistributionName(distributionMetadata.distributionName()),
                distributionMetadata,
                PythonDistribution.readInstallationSource(normalizedPath)
        );
    }

    private InstalledPlugin scanInstalledPlugin(Path metadataPath) {
        return scanInstalledPlugin(scanInstalledDistribution(metadataPath));
    }

    private InstalledPlugin scanInstalledPlugin(InstalledDistribution distribution) {
        if (distribution == null) {
            return null;
        }

        var entryPoint = PythonDistribution.readEntryPoint(
                distribution.metadataPath(),
                distribution.distributionMetadata().distributionName()
        );
        if (entryPoint == null) {
            return null;
        }

        return new InstalledPlugin(
                distribution.metadataPath(),
                distribution.distributionMetadata(),
                entryPoint,
                distribution.installationSource()
        );
    }

    private boolean shouldKeepPlugin(InstalledPlugin plugin) {
        var source = plugin.installationSource();
        return source == null || source.editable() || !isPluginWheel(source.path()) || Files.exists(source.path());
    }

    private void removeInstalledDistribution(InstalledDistribution distribution) {
        var recordPath = distribution.metadataPath().resolve("RECORD");
        if (!Files.exists(recordPath)) {
            PythonRuntime.deleteRecursively(distribution.metadataPath());
            return;
        }

        final List<String> entries;
        try {
            entries = Files.readAllLines(recordPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PluginException("Unable to read RECORD for " + distribution.distributionMetadata().distributionName() + ".", e);
        }

        var sitePackagesDir = distribution.metadataPath().getParent();
        var directories = new ArrayList<Path>();
        for (var entry : entries) {
            if (entry.isBlank()) {
                continue;
            }

            var relativePath = parseRecordPath(entry);
            if (relativePath.isBlank()) {
                continue;
            }

            var target = sitePackagesDir.resolve(relativePath).normalize();
            if (!target.startsWith(sitePackagesDir)) {
                continue;
            }

            try {
                Files.deleteIfExists(target);
            } catch (IOException e) {
                throw new PluginException("Unable to remove installed Python file " + target + ".", e);
            }

            var parent = target.getParent();
            if (parent != null && parent.startsWith(sitePackagesDir)) {
                directories.add(parent);
            }
        }

        directories.stream()
                .distinct()
                .sorted(Comparator.reverseOrder())
                .forEach(directory -> {
                    try {
                        if (Files.isDirectory(directory) && isEmptyDirectory(directory)) {
                            Files.deleteIfExists(directory);
                        }
                    } catch (IOException e) {
                        throw new PluginException("Unable to clean installed Python directory " + directory + ".", e);
                    }
                });
    }

    private boolean isPluginWheel(Path path) {
        return path != null
               && path.startsWith(WheelPluginSource.PLUGIN_DIRECTORY.toAbsolutePath().normalize())
               && WHEEL_MATCHER.matches(path);
    }

    private void ensureDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new PluginException("Unable to initialize Python site-packages directory " + directory + ".", e);
        }
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static boolean isEmptyDirectory(Path path) throws IOException {
        try (var stream = Files.list(path)) {
            return stream.findAny().isEmpty();
        }
    }

    private static String parseRecordPath(String entry) {
        if (entry.isEmpty()) {
            return entry;
        }
        if (entry.charAt(0) != '"') {
            var separator = entry.indexOf(',');
            return separator >= 0 ? entry.substring(0, separator) : entry;
        }

        var path = new StringBuilder();
        for (var i = 1; i < entry.length(); i++) {
            var ch = entry.charAt(i);
            if (ch != '"') {
                path.append(ch);
                continue;
            }

            if (i + 1 < entry.length() && entry.charAt(i + 1) == '"') {
                path.append('"');
                i++;
                continue;
            }

            if (i + 1 == entry.length() || entry.charAt(i + 1) == ',') {
                return path.toString();
            }

            throw new PluginException("Malformed RECORD entry: " + entry);
        }

        throw new PluginException("Malformed RECORD entry: " + entry);
    }

    record InstalledPlugin(
            Path metadataPath,
            PythonDistribution.DistributionMetadata distributionMetadata,
            PythonDistribution.EntryPointSpec entryPoint,
            PythonDistribution.InstallationSource installationSource
    ) {
    }

    record InstalledDistribution(
            Path metadataPath,
            String normalizedName,
            PythonDistribution.DistributionMetadata distributionMetadata,
            PythonDistribution.InstallationSource installationSource
    ) {
    }
}
