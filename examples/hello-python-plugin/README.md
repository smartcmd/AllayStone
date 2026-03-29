# Hello Python Plugin

Example Python plugin for AllayStone.

## Files

- `pyproject.toml`: package metadata and the `[project.entry-points.allaystone]` entry that AllayStone reads.
- `src/allaystone_hello/plugin.py`: the example plugin implementation.

## Build A Wheel

```powershell
python -m pip wheel . --no-deps --wheel-dir dist
```

The wheel will be created in `dist/`.

## Install Editable

```powershell
python -m pip install -e . --prefix <server>/plugins/.local
```

## Remove Editable

Stop the server first, then uninstall the distribution from the same prefix:

```powershell
$env:PYTHONPATH = "<server>/plugins/.local/Lib/site-packages"
python -m pip uninstall allaystone-hello
Remove-Item Env:PYTHONPATH
```

## Install

1. Build AllayStone with `./gradlew shadowJar`.
2. Copy `build/libs/AllayStone-0.1.0-shaded.jar` into the server `plugins/` directory.
3. Either build this example wheel and copy the generated `.whl` file into the same `plugins/` directory, or install it into `plugins/.local` with editable mode.
4. Start the server.

## Expected Result

The server log should show `Loading plugin hello` and `HelloPlugin enabled.`.

On enable, the plugin writes `plugins/hello/hello.txt` with a small marker payload so you can confirm it ran.
