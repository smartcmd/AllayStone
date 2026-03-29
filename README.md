# AllayStone

A python plugin loader and runtime for AllayMC using GraalPython, inspired by Endstone.

## Build

```powershell
./gradlew shadowJar
```

This produces `build/libs/AllayStone-0.1.0-shaded.jar`.

## Install

1. Copy the shaded jar into the Allay server `plugins/` directory.
2. Start the server.

AllayStone manages a Python prefix at `plugins/.local` and loads Python plugins from the distributions installed there.

Wheel files copied into `plugins/*.whl` are installed into `plugins/.local` automatically during startup.

Editable installs are also supported if they target the same prefix:

```powershell
python -m pip install -e <plugin-project> --prefix <server>/plugins/.local
```

For `./gradlew runServer`, the managed prefix is `build/run/plugins/.local`.

## Removing Plugins

Stop the server before removing a Python plugin.

For wheel installs, delete the matching `.whl` file from `plugins/`. AllayStone will remove the installed files from `plugins/.local` the next time the server starts.

For editable installs, uninstall the distribution from the managed prefix. In PowerShell:

```powershell
$env:PYTHONPATH = "<server>/plugins/.local/Lib/site-packages"
python -m pip uninstall allaystone-<plugin-name>
Remove-Item Env:PYTHONPATH
```

For `./gradlew runServer`, replace `<server>/plugins/.local` with `build/run/plugins/.local`.

## Python Plugin Format

Python plugins are regular Python distributions. In practice, you can either build a wheel or install the project in editable mode.

Requirements:

- The distribution name must match `allaystone-<plugin-name>`.
- The distribution must define exactly one `[project.entry-points.allaystone]` entry.
- The entry point class must inherit from `allaystone.Plugin`.

The Python base class exposes:

- metadata fields such as `version`, `api_version`, `description`, `authors`, `website`, `depend`, and `soft_depend`
- lifecycle methods `on_load`, `on_enable`, and `on_disable`
- runtime fields injected by AllayStone: `server`, `logger`, `data_folder`, `name`, and `java_plugin`

## Reloading

Python plugins are reloadable through Allay's built-in plugin command:

```text
/plugin reload <name>
/plugin reloadall
```

When a Python plugin reloads, AllayStone will:

1. call the current instance's `on_disable()`
2. rebuild the plugin's GraalPy context
3. create a new Python plugin instance
4. call `on_load()` and `on_enable()` on the new instance

Wheel plugins are reinstalled from `plugins/*.whl` before the new context is created.

Editable plugins are reloaded from the current source tree already linked into `plugins/.local`.

This only reloads Python code. Changes to plugin descriptor metadata such as `name`, `version`, `api_version`, and dependencies still require a full server restart because Allay does not recreate the plugin container during `/plugin reload`.

Reload cleanup is still the plugin author's responsibility. If a plugin registers listeners, commands, or long-lived tasks, it should unregister or stop them inside `on_disable()` before the new instance starts.

## Example Plugin

A minimal example plugin is included in [examples/hello-python-plugin/README.md](examples/hello-python-plugin/README.md).

Build it with:

```powershell
cd examples/hello-python-plugin
python -m pip wheel . --no-deps --wheel-dir dist
```

Then either:

1. copy the generated wheel from `examples/hello-python-plugin/dist/` into the server `plugins/` directory, or
2. install the example in editable mode with `python -m pip install -e . --prefix <server>/plugins/.local`

To remove the example editable install later:

```powershell
$env:PYTHONPATH = "<server>/plugins/.local/Lib/site-packages"
python -m pip uninstall allaystone-hello
Remove-Item Env:PYTHONPATH
```

## Local Test

```powershell
./gradlew runServer
```

With the example installed, the server should load plugin `hello` and create `plugins/hello/hello.txt`.

## Python Stubs

AllayStone also generates and bundles `allay.api` Python stubs plus the `allaystone` helper package used by Python plugins.

Build the local stub package with:

```powershell
./gradlew preparePythonStubPackage
cd build/generated/python-stub-package
python -m pip install -e .
```

GitHub Releases also publish a wheel for the generated stubs. Install it directly with:

```powershell
python -m pip install https://github.com/smartcmd/AllayStone/releases/download/<tag>/<wheel-file>.whl
```

The wheel is intended for editor support and GraalPy interop. In normal CPython, importing `allay.api.*` at runtime will fail because the generated package uses GraalPy `java.type()` bindings.
