"""Validation and normalization for public maid action arguments."""

from copy import deepcopy
from typing import Any, Dict


class ActionValidationError(ValueError):
    pass


class ActionRegistry:
    SUPPORTED_KINDS = frozenset({"navigate", "harvest_blocks", "excavate_segment"})

    def normalize(self, kind: str, args: Dict[str, Any]) -> Dict[str, Any]:
        kind = str(kind or "").strip().lower()
        if kind not in self.SUPPORTED_KINDS:
            raise ActionValidationError(
                f"Unsupported maid action kind: {kind or '<empty>'}. "
                f"Supported kinds: {', '.join(sorted(self.SUPPORTED_KINDS))}"
            )
        if not isinstance(args, dict):
            raise ActionValidationError("args must be an object")
        if kind == "navigate":
            return self._navigate(args)
        if kind == "excavate_segment":
            return self._excavate_segment(args)
        return self._harvest(args)

    def _excavate_segment(self, args: Dict[str, Any]) -> Dict[str, Any]:
        allowed = {"direction", "shape", "length"}
        unknown = sorted(set(args) - allowed)
        if unknown:
            raise ActionValidationError(
                f"excavate_segment has unsupported fields: {', '.join(unknown)}"
            )
        direction = str(args.get("direction") or "").strip().lower()
        if direction not in {"north", "south", "east", "west"}:
            raise ActionValidationError(
                "excavate_segment.direction must be north, south, east or west"
            )
        shape = str(args.get("shape", "level") or "").strip().lower()
        if shape not in {"level", "staircase_down"}:
            raise ActionValidationError(
                "excavate_segment.shape must be level or staircase_down"
            )
        return {
            "direction": direction,
            "shape": shape,
            "length": self._integer(
                args.get("length", 1), "excavate_segment.length", 1, 8
            ),
        }

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

        normalized_target = None
        normalized_selector = None
        ore_selector = False
        if has_target:
            normalized_target = self._position(args.get("target_pos"), "target_pos")
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
            normalized_selector = {"type": selector_type, "id": selector_id}
            selector_path = selector_id.split(":", 1)[1]
            ore_selector = (
                (
                    selector_type == "tag"
                    and (
                        selector_path.endswith("_ores")
                        or selector_path == "ores"
                        or selector_path.startswith("ores/")
                    )
                )
                or (selector_type == "block" and selector_path.endswith("_ore"))
            )

        if "vein_mining" in args:
            vein_mining = self._boolean(args.get("vein_mining"), "vein_mining")
        else:
            vein_mining = ore_selector
        if vein_mining and not has_selector:
            raise ActionValidationError("vein_mining=true requires selector targeting")

        max_blocks_limit = 64 if vein_mining else 8
        max_blocks_default = 64 if vein_mining else 1
        data = {
            "search_radius": self._integer(
                args.get("search_radius", 12), "search_radius", 1, 12
            ),
            "max_blocks": self._integer(
                args.get("max_blocks", max_blocks_default),
                "max_blocks", 1, max_blocks_limit,
            ),
            "vein_mining": vein_mining,
            "tool_policy": str(args.get("tool_policy", "require_correct") or "").lower(),
            "speed": self._number(args.get("speed", 0.7), "speed", 0.4, 1.0),
        }
        if data["tool_policy"] not in ("require_correct", "allow_wrong"):
            raise ActionValidationError(
                "tool_policy must be require_correct or allow_wrong"
            )
        if has_target:
            data["target_pos"] = normalized_target
        else:
            data["selector"] = normalized_selector
        if args.get("mining_plan") is not None:
            data["mining_plan"] = self._mining_plan(
                args.get("mining_plan"), has_selector=has_selector
            )
        return deepcopy(data)

    def _mining_plan(self, value: Any, *, has_selector: bool) -> Dict[str, Any]:
        if not isinstance(value, dict):
            raise ActionValidationError("mining_plan must be an object")
        allowed = {
            "mode", "direction", "max_distance", "max_depth",
            "max_segments", "excavation_budget",
        }
        unknown = sorted(set(value) - allowed)
        if unknown:
            raise ActionValidationError(
                f"mining_plan has unsupported fields: {', '.join(unknown)}"
            )

        mode = str(value.get("mode", "nearby") or "").strip().lower()
        if mode not in ("nearby", "forward_tunnel", "staircase_down", "auto"):
            raise ActionValidationError(
                "mining_plan.mode must be nearby, forward_tunnel, staircase_down or auto"
            )
        if mode != "nearby" and not has_selector:
            raise ActionValidationError(
                "non-nearby mining_plan modes require selector targeting"
            )

        direction = str(
            value.get("direction", "maid_facing") or ""
        ).strip().lower()
        if direction not in ("maid_facing", "north", "south", "east", "west"):
            raise ActionValidationError(
                "mining_plan.direction must be maid_facing, north, south, east or west"
            )

        default_depth = 4 if mode in ("staircase_down", "auto") else 0
        max_depth = self._integer(
            value.get("max_depth", default_depth), "mining_plan.max_depth", 0, 12
        )
        if mode == "forward_tunnel" and max_depth != 0:
            raise ActionValidationError(
                "forward_tunnel requires mining_plan.max_depth=0"
            )
        if mode == "staircase_down" and max_depth == 0:
            raise ActionValidationError(
                "staircase_down requires positive mining_plan.max_depth"
            )

        max_distance = self._integer(
            value.get("max_distance", 8), "mining_plan.max_distance", 1, 16
        )
        if mode == "staircase_down" and max_depth > max_distance:
            raise ActionValidationError(
                "staircase_down requires max_distance >= max_depth"
            )
        if mode == "auto" and max_depth >= max_distance:
            raise ActionValidationError(
                "auto requires max_distance > max_depth"
            )

        max_segments = self._integer(
            value.get("max_segments", 1), "mining_plan.max_segments", 1, 4
        )
        default_excavation_budget = 64 if max_segments > 1 else 24

        return {
            "mode": mode,
            "direction": direction,
            "max_distance": max_distance,
            "max_depth": max_depth,
            "max_segments": max_segments,
            "excavation_budget": self._integer(
                value.get("excavation_budget", default_excavation_budget),
                "mining_plan.excavation_budget", 0, 256,
            ),
        }

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
    def _boolean(value: Any, name: str) -> bool:
        if not isinstance(value, bool):
            raise ActionValidationError(f"{name} must be a boolean")
        return value

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
