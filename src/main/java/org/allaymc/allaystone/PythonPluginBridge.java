package org.allaymc.allaystone;

import org.allaymc.api.plugin.Plugin;
import org.allaymc.api.plugin.PluginContainer;
import org.allaymc.api.plugin.PluginException;
import org.allaymc.api.server.Server;

final class PythonPluginBridge extends Plugin {
    private final WheelPluginLoader loader;
    private final Object lifecycleLock = new Object();
    private WheelPluginLoader.LoadedPythonPlugin loadedPlugin;

    PythonPluginBridge(WheelPluginLoader loader, WheelPluginLoader.LoadedPythonPlugin loadedPlugin) {
        this.loader = loader;
        this.loadedPlugin = loadedPlugin;
    }

    @Override
    public void onLoad() {
        synchronized (lifecycleLock) {
            invokeLifecycle(requireLoadedPlugin(), "on_load", false);
        }
    }

    @Override
    public void onEnable() {
        synchronized (lifecycleLock) {
            invokeLifecycle(requireLoadedPlugin(), "on_enable", false);
        }
    }

    @Override
    public void onDisable() {
        synchronized (lifecycleLock) {
            var loadedPlugin = this.loadedPlugin;
            if (loadedPlugin == null) {
                return;
            }

            try {
                invokeLifecycle(loadedPlugin, "on_disable", true);
            } finally {
                closePlugin(loadedPlugin);
                this.loadedPlugin = null;
            }
        }
    }

    @Override
    public boolean isReloadable() {
        return true;
    }

    @Override
    public void reload() {
        synchronized (lifecycleLock) {
            var previous = requireLoadedPlugin();
            invokeLifecycle(previous, "on_disable", true);

            WheelPluginLoader.LoadedPythonPlugin reloaded = null;
            try {
                reloaded = loader.reloadPlugin();
                injectRuntimeBindings(reloaded);
                invokeLifecycle(reloaded, "on_load", false);
                invokeLifecycle(reloaded, "on_enable", false);
                loadedPlugin = reloaded;
                closePlugin(previous);
            } catch (RuntimeException e) {
                closePlugin(reloaded);
                try {
                    invokeLifecycle(previous, "on_enable", false);
                    loadedPlugin = previous;
                } catch (RuntimeException resumeError) {
                    closePlugin(previous);
                    loadedPlugin = null;
                    e.addSuppressed(resumeError);
                }
                throw e;
            }
        }
    }

    @Override
    public void setPluginContainer(PluginContainer pluginContainer) {
        super.setPluginContainer(pluginContainer);
        synchronized (lifecycleLock) {
            var loadedPlugin = this.loadedPlugin;
            if (loadedPlugin != null) {
                injectRuntimeBindings(loadedPlugin);
            }
        }
    }

    private WheelPluginLoader.LoadedPythonPlugin requireLoadedPlugin() {
        if (loadedPlugin == null) {
            throw new IllegalStateException("The Python plugin context is not available.");
        }
        return loadedPlugin;
    }

    private void injectRuntimeBindings(WheelPluginLoader.LoadedPythonPlugin loadedPlugin) {
        var pluginContainer = getPluginContainer();
        if (pluginContainer == null) {
            return;
        }

        loadedPlugin.context().run(ignored -> {
            loadedPlugin.pythonPlugin().putMember("server", Server.getInstance());
            loadedPlugin.pythonPlugin().putMember("logger", getPluginLogger());
            loadedPlugin.pythonPlugin().putMember("data_folder", pluginContainer.dataFolder().toAbsolutePath().toString());
            loadedPlugin.pythonPlugin().putMember("name", pluginContainer.descriptor().getName());
            loadedPlugin.pythonPlugin().putMember("java_plugin", this);
        });
    }

    private void invokeLifecycle(
            WheelPluginLoader.LoadedPythonPlugin loadedPlugin,
            String methodName,
            boolean keepOpenOnFailure
    ) {
        try {
            loadedPlugin.context().run(ignored -> {
                var method = loadedPlugin.pythonPlugin().getMember(methodName);
                if (method == null || !method.canExecute()) {
                    throw new PluginException("Python plugin is missing callable lifecycle method " + methodName + ".");
                }
                method.execute();
            });
        } catch (RuntimeException e) {
            if (!keepOpenOnFailure) {
                closePlugin(loadedPlugin);
            }
            throw e;
        }
    }

    private static void closePlugin(WheelPluginLoader.LoadedPythonPlugin loadedPlugin) {
        if (loadedPlugin != null) {
            loadedPlugin.close();
        }
    }
}
