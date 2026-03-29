# Hello Python Plugin

Example Python wheel plugin for AllayStone.

## Files

- `pyproject.toml`: package metadata and the `[project.entry-points.allaystone]` entry that AllayStone reads.
- `src/allaystone_hello/plugin.py`: the example plugin implementation.

## Build

```powershell
python -m pip wheel . --no-deps --wheel-dir dist
```

The wheel will be created in `dist/`.

## Install

1. Build AllayStone with `./gradlew shadowJar`.
2. Copy `build/libs/AllayStone-0.1.0-shaded.jar` into the server `plugins/` directory.
3. Build this example wheel.
4. Copy the generated `.whl` file into the same `plugins/` directory.
5. Start the server.

## Expected Result

The server log should show `Loading plugin hello` and `HelloPlugin enabled.`.

On enable, the plugin writes `plugins/hello/hello.txt` with a small marker payload so you can confirm it ran.
