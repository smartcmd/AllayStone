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
