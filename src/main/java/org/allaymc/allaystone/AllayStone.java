package org.allaymc.allaystone;

import org.allaymc.api.plugin.Plugin;
import org.allaymc.api.server.Server;

public final class AllayStone extends Plugin {
    private PythonRuntime runtime;

    @Override
    public void onLoad() {
        runtime = new PythonRuntime(getPluginContainer().dataFolder());
        var pluginManager = Server.getInstance().getPluginManager();
        pluginManager.registerCustomSource(new WheelPluginSource());
        pluginManager.registerCustomLoaderFactory(new WheelPluginLoader.Factory(runtime));
        pluginLogger.info("Registered GraalPy wheel plugin loader.");
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
        pluginLogger.info("AllayStone is disabled.");
    }
}
