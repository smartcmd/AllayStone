# AllayStone

A python plugin loader and runtime for AllayMC using GraalPython, inspired by Endstone.

## Build

```powershell
./gradlew shadowJar
```

This produces `build/libs/AllayStone-<version>-shaded.jar`.

## Install

1. Copy the shaded jar into the Allay server `plugins/` directory.
2. Start the server.

AllayStone manages a Python prefix at `plugins/.local`.

- Wheel files copied into `plugins/*.whl` are installed into `plugins/.local` automatically during startup.
- Editable installs are also supported:

```powershell
python -m pip install -e <plugin-project> --prefix <server>/plugins/.local
```

For `./gradlew runServer`, the managed prefix is `build/run/plugins/.local`.

## Remove Plugin

Stop the server before removing a Python plugin.

- Wheel install: delete the matching `.whl` file from `plugins/`. AllayStone will remove the installed files from `plugins/.local` on the next startup.
- Editable install: uninstall from the managed prefix.

```powershell
& { $env:PYTHONPATH = "<server>/plugins/.local/Lib/site-packages"; try { python -m pip uninstall allaystone-<plugin-name> } finally { Remove-Item Env:PYTHONPATH -ErrorAction SilentlyContinue } }
```

For `./gradlew runServer`, replace `<server>/plugins/.local` with `build/run/plugins/.local`.

## Python Plugin Format

Python plugins are regular Python distributions.

- Distribution name: `allaystone-<plugin-name>`
- Exactly one `[project.entry-points.allaystone]` entry
- Entry point class must inherit from `allaystone.Plugin`

`allaystone.Plugin` exposes:

- metadata fields such as `version`, `api_version`, `description`, `authors`, `website`, `depend`, and `soft_depend`
- lifecycle methods `on_load`, `on_enable`, and `on_disable`
- runtime fields injected by AllayStone: `server`, `logger`, `data_folder`, `name`, and `java_plugin`

## Reloading

```text
/plugin reload <name>
/plugin reloadall
```

Reloading does this:

1. call the current instance's `on_disable()`
2. rebuild the plugin's GraalPy context
3. create a new Python plugin instance
4. call `on_load()` and `on_enable()` on the new instance

- Wheel plugins are reinstalled from `plugins/*.whl` before the new context is created.
- Editable plugins are reloaded from the current source tree already linked into `plugins/.local`.
- Metadata changes such as `name`, `version`, `api_version`, and dependencies still require a full restart.
- Plugin cleanup is still the plugin author's responsibility. Unregister listeners, commands, and long-lived tasks in `on_disable()`.

## Plugin Template

Use the template repository to start a new Python plugin:

https://github.com/smartcmd/AllayStoneTemplate

Typical workflow:

```powershell
git clone https://github.com/smartcmd/AllayStoneTemplate.git
cd AllayStoneTemplate
python -m pip wheel . --no-deps --wheel-dir dist
```

- Wheel: copy `dist/*.whl` into the server `plugins/` directory.
- Editable:

```powershell
python -m pip install -e . --prefix <server>/plugins/.local
```

## Python Stubs

AllayStone also generates and bundles:

- `allay.api` Python stubs
- the `allaystone` helper package used by Python plugins

Build the local stub package:

```powershell
./gradlew preparePythonStubPackage
cd build/generated/python-stub-package
python -m pip install -e .
```

GitHub Releases also publish a wheel for the generated stubs:

```powershell
python -m pip install https://github.com/smartcmd/AllayStone/releases/download/<tag>/<wheel-file>.whl
```

This wheel is for editor support and GraalPy interop. In normal CPython, importing `allay.api.*` at runtime will fail because the generated package uses GraalPy `java.type()` bindings.
