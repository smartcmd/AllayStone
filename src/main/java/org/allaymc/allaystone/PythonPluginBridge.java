package org.allaymc.allaystone;

import org.allaymc.api.plugin.Plugin;
import org.allaymc.api.plugin.PluginContainer;
import org.allaymc.api.plugin.PluginException;
import org.allaymc.api.server.Server;
import org.graalvm.polyglot.Value;

import java.util.concurrent.atomic.AtomicBoolean;

final class PythonPluginBridge extends Plugin {
    private final PythonRuntime.PythonContextHandle context;
    private final Value pythonPlugin;
    private final AtomicBoolean closed = new AtomicBoolean();

    PythonPluginBridge(PythonRuntime.PythonContextHandle context, Value pythonPlugin) {
        this.context = context;
        this.pythonPlugin = pythonPlugin;
    }

    @Override
    public void onLoad() {
        invokeLifecycle("on_load", false);
    }

    @Override
    public void onEnable() {
        invokeLifecycle("on_enable", false);
    }

    @Override
    public void onDisable() {
        try {
            invokeLifecycle("on_disable", true);
        } finally {
            closeContext();
        }
    }

    @Override
    public void setPluginContainer(PluginContainer pluginContainer) {
        super.setPluginContainer(pluginContainer);
        context.run(ignored -> {
            pythonPlugin.putMember("server", Server.getInstance());
            pythonPlugin.putMember("logger", getPluginLogger());
            pythonPlugin.putMember("data_folder", pluginContainer.dataFolder().toAbsolutePath().toString());
            pythonPlugin.putMember("name", pluginContainer.descriptor().getName());
            pythonPlugin.putMember("java_plugin", this);
        });
    }

    private void invokeLifecycle(String methodName, boolean keepOpenOnFailure) {
        try {
            context.run(ignored -> {
                var method = pythonPlugin.getMember(methodName);
                if (method == null || !method.canExecute()) {
                    throw new PluginException("Python plugin is missing callable lifecycle method " + methodName + ".");
                }
                method.execute();
            });
        } catch (RuntimeException e) {
            if (!keepOpenOnFailure) {
                closeContext();
            }
            throw e;
        }
    }

    private void closeContext() {
        if (closed.compareAndSet(false, true)) {
            context.close();
        }
    }
}
