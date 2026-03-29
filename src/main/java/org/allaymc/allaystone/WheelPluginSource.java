package org.allaymc.allaystone;

import org.allaymc.api.plugin.PluginException;
import org.allaymc.api.plugin.PluginSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

final class WheelPluginSource implements PluginSource {
    static final Path PLUGIN_DIRECTORY = Path.of("plugins");
    private final PythonEnvironment environment;

    WheelPluginSource(PythonEnvironment environment) {
        this.environment = environment;
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
        return environment.findInstalledPluginMetadata();
    }
}
