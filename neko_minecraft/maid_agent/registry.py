"""Validation and normalization for public maid action arguments."""

from copy import deepcopy
from typing import Any, Dict


class ActionValidationError(ValueError):
    pass


class ActionRegistry:
    SUPPORTED_KINDS = frozenset({"navigate", "harvest_blocks"})

    def normalize(self, kind: str, args: Dict[str, Any]) -> Dict[str, Any]:
        kind = str(kind or "").strip().lower()
        if kind not in self.SUPPORTED_KINDS:
            raise ActionValidationError(
                f"Unsupported maid action kind: {kind or '<empty>'}. "
                f"Supported kinds: {', '.join(sorted(self.SUPPORTED_KINDS))}"
            )
        if not isinstance(args, dict):
            raise ActionValidationError("args must be an object")
        return self._navigate(args) if kind == "navigate" else self._harvest(args)

    def _navigate(self, args: Dict[str, Any]) -> Dict[str, Any]:
        target = self._position(args.get("target"), "target")
        return {
            "target": target,
            "speed": self._number(args.get("speed", 0.7), "speed", 0.4, 1.0),
            "stop_distance": self._number(
                args.get("stop_distance", 1.5), "stop_distance", 1.0, 4.0
            ),
        }

    def _harvest(self, args: Dict[str, Any]) -> Dict[str, Any]:
        has_target = args.get("target_pos") is not None
        has_selector = args.get("selector") is not None
        if has_target == has_selector:
            raise ActionValidationError(
                "harvest_blocks requires exactly one of target_pos or selector"
            )
        data = {
            "search_radius": self._integer(
                args.get("search_radius", 12), "search_radius", 1, 12
            ),
            "max_blocks": self._integer(
                args.get("max_blocks", 1), "max_blocks", 1, 8
            ),
            "tool_policy": str(args.get("tool_policy", "require_correct") or "").lower(),
            "speed": self._number(args.get("speed", 0.7), "speed", 0.4, 1.0),
        }
        if data["tool_policy"] not in ("require_correct", "allow_wrong"):
            raise ActionValidationError(
                "tool_policy must be require_correct or allow_wrong"
            )
        if has_target:
            data["target_pos"] = self._position(args.get("target_pos"), "target_pos")
        else:
            selector = args.get("selector")
            if not isinstance(selector, dict):
                raise ActionValidationError("selector must be an object")
            selector_type = str(selector.get("type") or "").strip().lower()
            selector_id = str(selector.get("id") or "").strip().lower()
            if selector_type not in ("block", "tag"):
                raise ActionValidationError("selector.type must be block or tag")
            if not selector_id or ":" not in selector_id:
                raise ActionValidationError(
                    "selector.id must be a namespaced Minecraft resource id"
                )
            data["selector"] = {"type": selector_type, "id": selector_id}
        return deepcopy(data)

    @staticmethod
    def _position(value: Any, name: str) -> Dict[str, int]:
        if not isinstance(value, dict):
            raise ActionValidationError(f"{name} must be an object with x, y and z")
        missing = [axis for axis in ("x", "y", "z") if axis not in value]
        if missing:
            raise ActionValidationError(f"{name} is missing {', '.join(missing)}")
        return {
            axis: ActionRegistry._integer(value.get(axis), f"{name}.{axis}", -30_000_000, 30_000_000)
            for axis in ("x", "y", "z")
        }

    @staticmethod
    def _number(value: Any, name: str, minimum: float, maximum: float) -> float:
        if isinstance(value, bool):
            raise ActionValidationError(f"{name} must be a number")
        try:
            number = float(value)
        except (TypeError, ValueError):
            raise ActionValidationError(f"{name} must be a number") from None
        if number < minimum or number > maximum:
            raise ActionValidationError(f"{name} must be between {minimum} and {maximum}")
        return number

    @staticmethod
    def _integer(value: Any, name: str, minimum: int, maximum: int) -> int:
        if isinstance(value, bool):
            raise ActionValidationError(f"{name} must be an integer")
        try:
            number = int(value)
        except (TypeError, ValueError):
            raise ActionValidationError(f"{name} must be an integer") from None
        if str(value).strip() not in (str(number), f"{number}.0") and not isinstance(value, int):
            raise ActionValidationError(f"{name} must be an integer")
        if number < minimum or number > maximum:
            raise ActionValidationError(f"{name} must be between {minimum} and {maximum}")
        return number
