from typing import Any, ClassVar, Optional, Sequence


class Plugin:
    version: ClassVar[Optional[str]]
    api_version: ClassVar[Optional[str]]
    description: ClassVar[Optional[str]]
    authors: ClassVar[Optional[Sequence[str]]]
    website: ClassVar[Optional[str]]
    depend: ClassVar[Optional[Sequence[str]]]
    soft_depend: ClassVar[Optional[Sequence[str]]]

    server: Any
    logger: Any
    data_folder: str
    name: str
    java_plugin: Any

    def on_load(self) -> None: ...
    def on_enable(self) -> None: ...
    def on_disable(self) -> None: ...
