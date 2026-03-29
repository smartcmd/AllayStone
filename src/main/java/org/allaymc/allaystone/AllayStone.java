package org.allaymc.allaystone;

import org.allaymc.api.plugin.Plugin;
import org.allaymc.api.server.Server;

public final class AllayStone extends Plugin {
    private PythonRuntime runtime;
    private PythonEnvironment environment;

    @Override
    public void onLoad() {
        runtime = new PythonRuntime(getPluginContainer().dataFolder());
        environment = new PythonEnvironment(runtime);
        var pluginManager = Server.getInstance().getPluginManager();
        pluginManager.registerCustomSource(new WheelPluginSource(environment));
        pluginManager.registerCustomLoaderFactory(new WheelPluginLoader.Factory(runtime, environment));
        pluginLogger.info("Registered GraalPy plugin loader with managed prefix plugins/.local.");
    }

    @Override
    public void onEnable() {
        pluginLogger.info("AllayStone is enabled.");
    }

    @Override
    public void onDisable() {
        if (runtime != null) {
            runtime.close();
        }
        environment = null;
        pluginLogger.info("AllayStone is disabled.");
    }
}
