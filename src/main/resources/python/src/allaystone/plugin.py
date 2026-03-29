class Plugin:
    """Base class for Python plugins loaded by AllayStone."""

    # Descriptor fields read by the Java-side plugin loader.
    version = None
    api_version = None
    description = None
    authors = None
    website = None
    depend = None
    soft_depend = None

    # Runtime objects injected after the plugin instance is created.
    server = None
    logger = None
    data_folder = None
    name = None
    java_plugin = None

    def on_load(self):
        """Called before worlds are loaded."""
        pass

    def on_enable(self):
        """Called after worlds are loaded and the plugin is being enabled."""
        pass

    def on_disable(self):
        """Called while the server is shutting down and the plugin is being disabled."""
        pass
