package org.allaymc.allaystone;

import org.allaymc.api.plugin.PluginException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
                .peek(this::ensureDirectory)
                .toList();
    }

    List<Path> sitePackageDirs() {
        return sitePackageDirs;
    }

    synchronized Set<Path> findInstalledPluginMetadata() {
        cleanupRemovedWheelInstalls();
        installWheelPlugins();
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

        var refreshed = refreshInstalledPlugins().values().stream()
                .filter(plugin -> plugin.entryPoint().name().equals(pluginName))
                .findFirst()
                .orElse(null);
        if (refreshed == null) {
            throw new PluginException("Unable to refresh Python plugin " + pluginName + ".");
        }
        return refreshed;
    }

    private void cleanupRemovedWheelInstalls() {
        for (var plugin : scanInstalledPlugins().values()) {
            var source = plugin.installationSource();
            if (source == null || source.editable() || !isPluginWheel(source.path()) || Files.exists(source.path())) {
                continue;
            }
            removeInstalledPlugin(plugin);
        }
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

    private Map<Path, InstalledPlugin> refreshInstalledPlugins() {
        var plugins = scanInstalledPlugins();
        installedPlugins = Map.copyOf(plugins);
        pluginsByName = plugins.values().stream()
                .collect(Collectors.toMap(plugin -> plugin.entryPoint().name(), plugin -> plugin, (left, right) -> left, LinkedHashMap::new));
        return installedPlugins;
    }

    private Map<Path, InstalledPlugin> scanInstalledPlugins() {
        var plugins = new LinkedHashMap<Path, InstalledPlugin>();
        for (var sitePackageDir : sitePackageDirs) {
            if (!Files.isDirectory(sitePackageDir)) {
                continue;
            }

            try (var stream = Files.list(sitePackageDir)) {
                for (var candidate : stream.toList()) {
                    var plugin = scanInstalledPlugin(candidate);
                    if (plugin != null) {
                        plugins.put(plugin.metadataPath(), plugin);
                    }
                }
            } catch (IOException e) {
                throw new PluginException("Unable to inspect installed Python packages in " + sitePackageDir + ".", e);
            }
        }
        return plugins;
    }

    private InstalledPlugin scanInstalledPlugin(Path metadataPath) {
        var normalizedPath = normalize(metadataPath);
        if (!PythonDistribution.isMetadataPath(normalizedPath)) {
            return null;
        }

        var distributionMetadata = PythonDistribution.readDistributionMetadata(normalizedPath);
        var entryPoint = PythonDistribution.readEntryPoint(normalizedPath, distributionMetadata.distributionName());
        if (entryPoint == null) {
            return null;
        }

        return new InstalledPlugin(
                normalizedPath,
                distributionMetadata,
                entryPoint,
                PythonDistribution.readInstallationSource(normalizedPath)
        );
    }

    private void removeInstalledPlugin(InstalledPlugin plugin) {
        var recordPath = plugin.metadataPath().resolve("RECORD");
        if (!Files.exists(recordPath)) {
            PythonRuntime.deleteRecursively(plugin.metadataPath());
            return;
        }

        final List<String> entries;
        try {
            entries = Files.readAllLines(recordPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PluginException("Unable to read RECORD for " + plugin.distributionMetadata().distributionName() + ".", e);
        }

        var sitePackagesDir = plugin.metadataPath().getParent();
        var directories = new ArrayList<Path>();
        for (var entry : entries) {
            if (entry.isBlank()) {
                continue;
            }

            var separator = entry.indexOf(',');
            var relativePath = separator >= 0 ? entry.substring(0, separator) : entry;
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

    record InstalledPlugin(
            Path metadataPath,
            PythonDistribution.DistributionMetadata distributionMetadata,
            PythonDistribution.EntryPointSpec entryPoint,
            PythonDistribution.InstallationSource installationSource
    ) {
    }
}
