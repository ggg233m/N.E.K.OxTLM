"""Deterministic checkpointed ore-mining skill."""

from __future__ import annotations

from typing import Any, Mapping, Optional

from .base import Blocked, Complete, Fail, SkillRun, StartAction


_DIRECTIONS = ("north", "east", "south", "west")
_LEFT = {"north": "west", "west": "south", "south": "east", "east": "north"}
_RIGHT = {value: key for key, value in _LEFT.items()}
_OPPOSITE = {"north": "south", "south": "north", "east": "west", "west": "east"}
_SEGMENT_LENGTH = 8
_BENIGN_SCAN_MESSAGES = frozenset({
    "no_matching_block_found",
    "all_targets_changed_before_planning",
    "target_is_air",
})


class MineOreSkill:
    """Mine complete veins using a main tunnel with deterministic side branches."""

    name = "mine_ore"
    version = 1

    def normalize_args(self, raw: Mapping[str, Any]) -> dict[str, Any]:
        if not isinstance(raw, Mapping):
            raise ValueError("mine_ore args must be an object")
        allowed = {
            "selector", "target_count", "target_metric", "strategy", "direction", "shape",
        }
        unknown = sorted(set(raw) - allowed)
        if unknown:
            raise ValueError(f"mine_ore has unsupported fields: {', '.join(unknown)}")

        selector = raw.get("selector")
        if not isinstance(selector, Mapping):
            raise ValueError("mine_ore.selector must be an object")
        selector_unknown = sorted(set(selector) - {"type", "id"})
        if selector_unknown:
            raise ValueError(
                "mine_ore.selector has unsupported fields: "
                + ", ".join(selector_unknown)
            )
        selector_type = str(selector.get("type") or "").strip().lower()
        selector_id = str(selector.get("id") or "").strip().lower()
        if selector_type not in {"block", "tag"}:
            raise ValueError("mine_ore.selector.type must be block or tag")
        if not selector_id or ":" not in selector_id:
            raise ValueError("mine_ore.selector.id must be a namespaced resource id")

        target_count = _positive_integer(raw.get("target_count"), "target_count")
        if target_count > 4096:
            raise ValueError("mine_ore.target_count must be between 1 and 4096")
        target_metric = str(raw.get("target_metric") or "").strip().lower()
        if target_metric != "blocks_harvested":
            raise ValueError("mine_ore.target_metric must be blocks_harvested")

        strategy = str(raw.get("strategy", "fishbone") or "").strip().lower()
        if strategy == "auto":
            strategy = "fishbone"
        if strategy != "fishbone":
            raise ValueError("mine_ore.strategy must be fishbone (auto is accepted as an alias)")
        direction = str(raw.get("direction", "north") or "").strip().lower()
        if direction not in _DIRECTIONS:
            raise ValueError("mine_ore.direction must be north, east, south or west")
        shape = str(raw.get("shape", "staircase_down") or "").strip().lower()
        if shape not in {"level", "staircase_down"}:
            raise ValueError("mine_ore.shape must be level or staircase_down")
        return {
            "selector": {"type": selector_type, "id": selector_id},
            "target_count": target_count,
            "target_metric": target_metric,
            "strategy": strategy,
            "direction": direction,
            "shape": shape,
        }

    def initialize(self, run: SkillRun) -> None:
        run.collected_count = 0
        run.main_direction = str(run.args["direction"])
        run.main_segment_index = 0
        run.branch_index = 0
        run.tried_directions_at_current = []
        run.origin_pos = None
        run.current_pos = None
        run.result = {
            "stage": "scanning_origin",
            "phase": "harvest",
            "harvest_purpose": "junction_scan",
            "after_harvest_phase": "choose_direction",
            "junction_established": False,
            "junction_pos": None,
        }

    def next_directive(
        self,
        run: SkillRun,
        terminal_snapshot: Optional[Mapping[str, Any]],
    ):
        if run.collected_count >= int(run.args["target_count"]):
            return self._complete(run)
        if terminal_snapshot is not None:
            terminal = dict(terminal_snapshot)
            kind = str(
                terminal.get("kind")
                or run.current_action_request.get("kind")
                or ""
            ).strip().lower()
            if kind == "harvest_blocks":
                directive = self._consume_harvest(run, terminal)
            elif kind == "excavate_segment":
                directive = self._consume_excavation(run, terminal)
            elif kind == "navigate":
                directive = self._consume_navigation(run, terminal)
            else:
                return Fail(
                    "UNKNOWN_CHILD_ACTION",
                    {"message": f"MineOreSkill cannot consume child kind {kind!r}"},
                )
            if directive is not None:
                return directive
        return self._directive_for_phase(run)

    def _directive_for_phase(self, run: SkillRun):
        scratch = run.result
        phase = str(scratch.get("phase") or "")
        if phase == "harvest":
            scratch["stage"] = "harvesting_nearby_vein"
            return self._harvest_action(run)
        if phase == "choose_direction":
            return self._choose_direction(run)
        if phase == "dig":
            direction = str(scratch.get("dig_direction") or "")
            remaining = max(1, min(_SEGMENT_LENGTH, _integer(scratch.get("segment_remaining"), 8)))
            scratch["stage"] = f"excavating_{scratch.get('dig_role') or 'segment'}"
            return StartAction(
                "excavate_segment",
                {
                    "direction": direction,
                    "shape": run.args["shape"]
                    if scratch.get("dig_role") == "main" else "level",
                    "length": remaining,
                },
                timeout_ms=0,
            )
        if phase == "navigate":
            target = _position(scratch.get("navigate_target"))
            if target is None:
                return self._blocked(
                    run, "RETURN_POSITION_UNKNOWN",
                    message="The skill cannot safely return because its checkpoint has no target",
                )
            scratch["stage"] = "returning_to_mining_anchor"
            return StartAction(
                "navigate",
                {"target": target, "speed": 0.7, "stop_distance": 1.0},
                timeout_ms=60000,
            )
        if phase == "finish_dig":
            return self._finish_direction(
                run,
                str(scratch.get("dig_role") or "opposite"),
                run.current_pos,
            )
        if phase == "blocked":
            return self._blocked(run, str(scratch.get("blocked_reason") or "BLOCKED"))
        return Fail(
            "INVALID_SKILL_PHASE",
            {"message": f"Unknown MineOreSkill phase: {phase or '<empty>'}"},
        )

    def _consume_harvest(self, run: SkillRun, terminal: Mapping[str, Any]):
        scratch = run.result
        result = _result(terminal)
        harvested = _harvested_count(result)
        if harvested > 0:
            run.collected_count += harvested
        if run.collected_count >= int(run.args["target_count"]):
            return self._complete(run)

        status = _status(terminal)
        message = str(result.get("message") or "")
        if status != "SUCCEEDED" and message not in _BENIGN_SCAN_MESSAGES:
            reason = str(terminal.get("end_reason") or message or status or "HARVEST_FAILED")
            return self._blocked(
                run,
                reason,
                message="A discovered or nearby ore vein could not be harvested safely",
                child=terminal,
            )

        after_phase = str(scratch.get("after_harvest_phase") or "choose_direction")
        return_target = _position(scratch.get("return_after_harvest"))
        scratch.pop("return_after_harvest", None)
        scratch.pop("harvest_purpose", None)
        if return_target is not None:
            scratch["phase"] = "navigate"
            scratch["navigate_target"] = return_target
            scratch["after_navigate_phase"] = after_phase
            return None
        scratch["phase"] = after_phase
        return None

    def _consume_excavation(self, run: SkillRun, terminal: Mapping[str, Any]):
        scratch = run.result
        result = _result(terminal)
        status = _status(terminal)
        stop_reason = str(result.get("stop_reason") or "").strip().lower()
        direction = str(scratch.get("dig_direction") or result.get("direction") or "")
        role = str(scratch.get("dig_role") or _role_for_direction(run.main_direction, direction))
        real_end = _position(result.get("real_end"))
        dug = max(0, _integer(result.get("segments_dug"), 0))
        if real_end is not None:
            run.current_pos = real_end
            if run.origin_pos is None:
                shape = str(
                    result.get("shape")
                    or (run.args["shape"] if role == "main" else "level")
                ).lower()
                run.origin_pos = _segment_origin(real_end, direction, shape, dug)
        remaining = max(0, _integer(scratch.get("segment_remaining"), _SEGMENT_LENGTH) - dug)
        scratch["segment_remaining"] = remaining

        if status == "SUCCEEDED" and stop_reason == "ore_encountered":
            if real_end is None:
                return self._blocked(
                    run, "ORE_ENCOUNTER_POSITION_UNKNOWN",
                    message="excavate_segment found ore but did not report real_end",
                    child=terminal,
                )
            scratch["phase"] = "harvest"
            scratch["harvest_purpose"] = "ore_encountered"
            scratch["after_harvest_phase"] = "dig" if remaining > 0 else "finish_dig"
            scratch["return_after_harvest"] = real_end
            return None

        if status == "SUCCEEDED" and stop_reason == "completed":
            return self._finish_direction(run, role, real_end)

        failure = str(
            result.get("message") or stop_reason
            or terminal.get("end_reason") or status or "EXCAVATION_FAILED"
        )
        return self._handle_direction_end(run, role, real_end, failure, terminal)

    def _consume_navigation(self, run: SkillRun, terminal: Mapping[str, Any]):
        scratch = run.result
        if _status(terminal) != "SUCCEEDED":
            return self._blocked(
                run,
                str(terminal.get("end_reason") or "RETURN_PATH_FAILED"),
                message="The maid could not safely return to the fishbone junction",
                child=terminal,
            )
        target = _position(scratch.get("navigate_target"))
        if target is not None:
            run.current_pos = target
        scratch.pop("navigate_target", None)
        scratch["phase"] = str(scratch.pop("after_navigate_phase", "choose_direction"))
        return None

    def _choose_direction(self, run: SkillRun):
        scratch = run.result
        order = _direction_order(
            run.main_direction,
            bool(scratch.get("junction_established")),
        )
        tried = set(run.tried_directions_at_current)
        direction = next((value for value in order if value not in tried), None)
        if direction is None:
            return self._blocked(
                run,
                "ALL_DIRECTIONS_BLOCKED",
                message="Every horizontal direction at the current fishbone junction was tried",
            )
        run.tried_directions_at_current.append(direction)
        role = _role_for_direction(run.main_direction, direction)
        run.branch_index = {"left": 0, "right": 1, "opposite": 2}.get(role, 0)
        scratch["phase"] = "dig"
        scratch["dig_direction"] = direction
        scratch["dig_role"] = role
        scratch["segment_remaining"] = _SEGMENT_LENGTH
        return self._directive_for_phase(run)

    def _finish_direction(
        self, run: SkillRun, role: str, real_end: Optional[dict[str, int]]
    ):
        scratch = run.result
        if real_end is None:
            return self._blocked(
                run, "SEGMENT_END_UNKNOWN",
                message="excavate_segment completed without a real_end position",
            )
        if role == "main":
            run.main_segment_index += 1
            run.current_pos = real_end
            run.tried_directions_at_current = []
            scratch.clear()
            scratch.update({
                "stage": "scanning_junction",
                "phase": "harvest",
                "harvest_purpose": "junction_scan",
                "after_harvest_phase": "choose_direction",
                "junction_established": True,
                "junction_pos": real_end,
            })
            return self._harvest_action(run)
        return self._return_to_junction_or_continue(run)

    def _handle_direction_end(
        self,
        run: SkillRun,
        role: str,
        real_end: Optional[dict[str, int]],
        failure: str,
        terminal: Mapping[str, Any],
    ):
        scratch = run.result
        scratch["last_direction_failure"] = {
            "direction": scratch.get("dig_direction"),
            "role": role,
            "reason": failure,
            "child_result": _result(terminal),
        }
        if scratch.get("junction_pos") is None and real_end is not None:
            scratch["junction_pos"] = real_end
        if len(set(run.tried_directions_at_current)) >= 4:
            return self._blocked(run, "ALL_DIRECTIONS_BLOCKED", child=terminal)
        return self._return_to_junction_or_continue(run)

    def _return_to_junction_or_continue(self, run: SkillRun):
        scratch = run.result
        junction = _position(scratch.get("junction_pos"))
        if junction is None:
            scratch["phase"] = "choose_direction"
            return None
        if run.current_pos == junction:
            scratch["phase"] = "choose_direction"
            return None
        scratch["phase"] = "navigate"
        scratch["navigate_target"] = junction
        scratch["after_navigate_phase"] = "choose_direction"
        return None

    def _harvest_action(self, run: SkillRun) -> StartAction:
        # target_count is a minimum target.  Always collect the complete
        # connected vein, even when that makes the final count overshoot.
        return StartAction(
            "harvest_blocks",
            {
                "selector": dict(run.args["selector"]),
                "max_blocks": 64,
                "vein_mining": True,
                "tool_policy": "require_correct",
                "mining_plan": {"mode": "nearby"},
            },
            timeout_ms=0,
        )

    def _complete(self, run: SkillRun) -> Complete:
        return Complete({
            "message": "mine_ore_target_reached",
            "selector": dict(run.args["selector"]),
            "target_metric": "blocks_harvested",
            "target_count": int(run.args["target_count"]),
            "blocks_harvested": run.collected_count,
            "target_overshoot": max(
                0, run.collected_count - int(run.args["target_count"])
            ),
            "main_segments_completed": run.main_segment_index,
            "strategy": "fishbone",
            "shape": run.args["shape"],
        })

    def _blocked(
        self,
        run: SkillRun,
        reason: str,
        *,
        message: str = "",
        child: Optional[Mapping[str, Any]] = None,
    ) -> Blocked:
        scratch = run.result
        junction = _position(scratch.get("junction_pos"))
        failure = scratch.get("last_direction_failure")
        suggestions: list[dict[str, Any]] = []
        reason_upper = str(reason or "BLOCKED").upper()
        child_result = _result(child or {})
        combined_text = " ".join((
            reason_upper,
            str(child_result.get("message") or ""),
            str(child_result.get("stop_reason") or ""),
        )).lower()
        if "tool" in combined_text:
            suggestions.append({
                "kind": "provide_tool",
                "basis": "correct_harvesting_tool_required",
                "requires_confirmation": True,
            })
        suggestions.append({
            "kind": "reposition",
            "basis": "verified_safe_supported_two_block_clearance"
            if junction is not None else "safe_position_unknown",
            "requires_confirmation": junction is None,
        })
        suggestions.append({
            "kind": "clear_obstruction",
            "basis": f"blocked_direction:{scratch.get('dig_direction') or 'unknown'}",
            "requires_confirmation": True,
        })
        suggestions.append({
            "kind": "change_level",
            "basis": "current_dimension_unknown",
            "requires_confirmation": True,
        })
        suggestions.append({
            "kind": "return_to_origin",
            "basis": "skill_origin_unknown" if run.origin_pos is None else "checkpoint_origin",
            "requires_confirmation": run.origin_pos is None,
        })
        suggestions.append({
            "kind": "abort",
            "basis": "keep_current_safe_terminal_state",
            "requires_confirmation": False,
        })
        return Blocked(
            reason_upper,
            {
                "message": message or reason_upper.lower(),
                "selector": dict(run.args["selector"]),
                "target_metric": "blocks_harvested",
                "target_count": int(run.args["target_count"]),
                "blocks_harvested": run.collected_count,
                "junction_pos": junction,
                "main_direction": run.main_direction,
                "tried_directions": list(run.tried_directions_at_current),
                "last_direction_failure": failure,
                "child_terminal": dict(child or {}),
                "suggestions": suggestions,
            },
        )


def _direction_order(main: str, junction_established: bool) -> tuple[str, ...]:
    sides = (_LEFT[main], _RIGHT[main])
    return (*sides, main, _OPPOSITE[main]) if junction_established \
        else (main, *sides, _OPPOSITE[main])


def _role_for_direction(main: str, direction: str) -> str:
    if direction == main:
        return "main"
    if direction == _LEFT[main]:
        return "left"
    if direction == _RIGHT[main]:
        return "right"
    return "opposite"


def _positive_integer(value: Any, name: str) -> int:
    if isinstance(value, bool):
        raise ValueError(f"mine_ore.{name} must be an integer")
    try:
        number = int(value)
    except (TypeError, ValueError):
        raise ValueError(f"mine_ore.{name} must be an integer") from None
    if str(value).strip() not in {str(number), f"{number}.0"} and not isinstance(value, int):
        raise ValueError(f"mine_ore.{name} must be an integer")
    if number < 1:
        raise ValueError(f"mine_ore.{name} must be positive")
    return number


def _integer(value: Any, fallback: int = 0) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return fallback


def _position(value: Any) -> Optional[dict[str, int]]:
    if not isinstance(value, Mapping):
        return None
    try:
        return {axis: int(value[axis]) for axis in ("x", "y", "z")}
    except (KeyError, TypeError, ValueError):
        return None


def _result(snapshot: Mapping[str, Any]) -> dict[str, Any]:
    value = snapshot.get("result") if isinstance(snapshot, Mapping) else None
    return dict(value) if isinstance(value, Mapping) else {}


def _status(snapshot: Mapping[str, Any]) -> str:
    return str(snapshot.get("status") or "").strip().upper()


def _harvested_count(result: Mapping[str, Any]) -> int:
    # Never infer collection from cleared blocks, inventory deltas or vein size.
    for key in ("blocks_harvested", "harvested"):
        if key in result:
            return max(0, _integer(result.get(key), 0))
    return 0


def _segment_origin(
    real_end: Mapping[str, int], direction: str, shape: str, segments_dug: int
) -> dict[str, int]:
    origin = {axis: int(real_end[axis]) for axis in ("x", "y", "z")}
    distance = max(0, int(segments_dug))
    if direction == "north":
        origin["z"] += distance
    elif direction == "south":
        origin["z"] -= distance
    elif direction == "east":
        origin["x"] -= distance
    elif direction == "west":
        origin["x"] += distance
    if shape == "staircase_down":
        origin["y"] += distance
    return origin
