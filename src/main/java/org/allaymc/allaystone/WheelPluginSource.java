package org.allaymc.allaystone;

import org.allaymc.api.plugin.PluginException;
import org.allaymc.api.plugin.PluginSource;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

final class WheelPluginSource implements PluginSource {
    static final Path PLUGIN_DIRECTORY = Path.of("plugins");
    private static final java.nio.file.PathMatcher WHEEL_MATCHER =
            FileSystems.getDefault().getPathMatcher("glob:**.whl");

    WheelPluginSource() {
        try {
            Files.createDirectories(PLUGIN_DIRECTORY);
        } catch (IOException e) {
            throw new PluginException("Unable to initialize the plugins directory.", e);
        }
    }

    static Path getOrCreateDataFolder(String pluginName) {
        try {
            return Files.createDirectories(PLUGIN_DIRECTORY.resolve(pluginName));
        } catch (IOException e) {
            throw new PluginException("Unable to create the data folder for plugin " + pluginName + ".", e);
        }
    }

    @Override
    public Set<Path> find() {
        try (var stream = Files.list(PLUGIN_DIRECTORY)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(WHEEL_MATCHER::matches)
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            throw new PluginException("Unable to scan the plugins directory for Python wheels.", e);
        }
    }
}
