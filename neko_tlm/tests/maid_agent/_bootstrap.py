"""Load plugin modules without executing the N.E.K.O runtime entrypoint."""

import sys
import types
from pathlib import Path

PLUGIN_ROOT = Path(__file__).resolve().parents[2]


def bootstrap():
    if "neko_tlm" not in sys.modules:
        package = types.ModuleType("neko_tlm")
        package.__path__ = [str(PLUGIN_ROOT)]
        sys.modules["neko_tlm"] = package


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
