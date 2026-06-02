import sys
from pathlib import Path

_on_disk_plugins = str(Path(__file__).parent / "plugin" / "plugins")

try:
    if "plugin.plugins" not in sys.modules:
        import plugin.plugins
    _pp = sys.modules.get("plugin.plugins")
    if _pp and hasattr(_pp, "__path__") and _on_disk_plugins not in _pp.__path__:
        _pp.__path__.append(_on_disk_plugins)
    from plugin.plugins.neko_minecraft import NekoMinecraftPlugin
except (ImportError, ModuleNotFoundError):
    from neko_minecraft import NekoMinecraftPlugin
