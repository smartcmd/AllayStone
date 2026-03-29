# AllayStone

A python plugin loader and runtime for AllayMC using GraalPython, inspired by Endstone.

## Build

```powershell
./gradlew shadowJar
```

This produces `build/libs/AllayStone-0.1.0-shaded.jar`.

## Install

1. Copy the shaded jar into the Allay server `plugins/` directory.
2. Put Python plugin wheels (`.whl`) into the same `plugins/` directory.
3. Start the server.

AllayStone registers a custom plugin loader that scans `plugins/*.whl` and loads Python plugins through GraalPy.

## Python Plugin Format

Python plugins are distributed as wheels.

Requirements:

- The wheel distribution name must match `allaystone-<plugin-name>`.
- The wheel must define exactly one `[project.entry-points.allaystone]` entry.
- The entry point class must inherit from `allaystone.Plugin`.

The Python base class exposes:

- metadata fields such as `version`, `api_version`, `description`, `authors`, `website`, `depend`, and `soft_depend`
- lifecycle methods `on_load`, `on_enable`, and `on_disable`
- runtime fields injected by AllayStone: `server`, `logger`, `data_folder`, `name`, and `java_plugin`

## Reloading

Python wheel plugins are reloadable through Allay's built-in plugin command:

```text
/plugin reload <name>
/plugin reloadall
```

When a Python plugin reloads, AllayStone will:

1. call the current instance's `on_disable()`
2. rebuild the plugin's GraalPy context from the wheel on disk
3. create a new Python plugin instance
4. call `on_load()` and `on_enable()` on the new instance

This only reloads Python code. Changes to plugin descriptor metadata such as `name`, `version`, `api_version`, and dependencies still require a full server restart because Allay does not recreate the plugin container during `/plugin reload`.

Reload cleanup is still the plugin author's responsibility. If a plugin registers listeners, commands, or long-lived tasks, it should unregister or stop them inside `on_disable()` before the new instance starts.

## Example Plugin

A minimal example plugin is included in [examples/hello-python-plugin/README.md](C:/Users/35232/IdeaProjects/AllayStone/examples/hello-python-plugin/README.md).

Build it with:

```powershell
cd examples/hello-python-plugin
python -m pip wheel . --no-deps --wheel-dir dist
```

Then copy the generated wheel from `examples/hello-python-plugin/dist/` into the server `plugins/` directory.

## Local Test

```powershell
./gradlew runServer
```

With the example wheel installed, the server should load plugin `hello` and create `plugins/hello/hello.txt`.

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
