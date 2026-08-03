"""Load plugin modules without executing the N.E.K.O runtime entrypoint."""

from pathlib import Path
import sys
import types


ROOT = Path(__file__).resolve().parents[3]


def bootstrap():
    if "neko_minecraft" not in sys.modules:
        package = types.ModuleType("neko_minecraft")
        package.__path__ = [str(ROOT / "neko_minecraft")]
        sys.modules["neko_minecraft"] = package


def bootstrap_sdk():
    bootstrap()
    if "plugin.sdk.plugin" in sys.modules:
        return
    plugin_package = types.ModuleType("plugin")
    sdk_package = types.ModuleType("plugin.sdk")
    sdk_module = types.ModuleType("plugin.sdk.plugin")
    sdk_module.Ok = lambda value: {"output": value, "is_error": False}
    sdk_module.Err = lambda value: {
        "output": {"error": str(value)},
        "is_error": True,
        "error": "ERROR",
    }
    sys.modules["plugin"] = plugin_package
    sys.modules["plugin.sdk"] = sdk_package
    sys.modules["plugin.sdk.plugin"] = sdk_module
