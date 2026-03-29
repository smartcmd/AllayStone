from pathlib import Path

from allaystone import Plugin


class HelloPlugin(Plugin):
    version = "0.1.0"
    api_version = "0.27.0"
    description = "Example Python plugin for AllayStone."
    authors = ["Codex"]
    website = "https://github.com/smartcmd/AllayStone"

    def on_load(self):
        self.logger.info("HelloPlugin loaded.")

    def on_enable(self):
        data_folder = Path(self.data_folder)
        data_folder.mkdir(parents=True, exist_ok=True)
        marker = data_folder / "hello.txt"
        marker.write_text(
            "Hello from AllayStone.\n"
            f"name={self.name}\n"
            f"version={self.version}\n",
            encoding="utf-8",
        )
        self.logger.info("HelloPlugin enabled. Wrote " + str(marker))

    def on_disable(self):
        self.logger.info("HelloPlugin disabled.")
