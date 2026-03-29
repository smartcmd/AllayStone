class Plugin:
    version = None
    api_version = None
    description = None
    authors = None
    website = None
    depend = None
    soft_depend = None

    server = None
    logger = None
    data_folder = None
    name = None
    java_plugin = None

    def on_load(self):
        pass

    def on_enable(self):
        pass

    def on_disable(self):
        pass
