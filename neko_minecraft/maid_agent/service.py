"""Coordinates action tracking, feedback and reconnect reconciliation."""

from typing import Any, Dict, Iterable

from .feedback import ActionFeedbackHandler
from .models import ActionRecord, ActionTracker, TERMINAL_STATUSES
from .registry import ActionRegistry


ACTION_EVENT_TYPES = frozenset({"maid_action_progress", "maid_action_finished"})


class MaidActionService:
    def __init__(self, plugin, *, progress_interval: float = 1.5, clock=None):
        self.plugin = plugin
        self.tracker = ActionTracker()
        self.registry = ActionRegistry()
        self.feedback = ActionFeedbackHandler(
            plugin, progress_interval=progress_interval, clock=clock
        )

    async def handle_message(self, message: Dict[str, Any]) -> bool:
        msg_type = str((message or {}).get("type") or "")
        if msg_type not in ACTION_EVENT_TYPES:
            return False
        payload = self._payload(message)
        record, accepted = self.tracker.apply(payload)
        if not accepted or record is None:
            return True
        if msg_type == "maid_action_finished" or record.status in TERMINAL_STATUSES:
            await self.feedback.finished(record)
        elif bool(payload.get("requires_decision", False)):
            await self.feedback.decision_required(record)
        else:
            await self.feedback.progress(record)
        return True

    def observe_response(self, message: Dict[str, Any]) -> list:
        """Update local snapshots from request responses without emitting feedback."""
        msg_type = str((message or {}).get("type") or "")
        payload = self._payload(message)
        records = []
        if msg_type == "maid_action_list":
            items = self._extract_actions(payload)
        elif msg_type in {
            "maid_action_start_result",
            "maid_action_cancel_result",
            "maid_action_status",
        }:
            items = [payload]
        else:
            items = []
        for item in items:
            record, _ = self.tracker.apply(item)
            if record is not None:
                records.append(record)
        return records

    async def reconcile(self) -> Dict[str, Any]:
        """Adopt server actions and recover terminal states after a reconnect."""
        maid_id = ""
        resolver = getattr(self.plugin, "_resolve_maid_id", None)
        if callable(resolver):
            maid_id = str(resolver() or "")
        list_data = {"maid_id": maid_id} if maid_id else {}
        response = await self.plugin._send_request(
            {"type": "list_active_maid_actions", "data": list_data}, timeout=5
        )
        if response.get("type") == "error":
            return {"success": False, "error": response.get("data", {})}

        data = self._payload(response)
        server_items = self._extract_actions(data)
        server_ids = set()
        adopted = []
        for item in server_items:
            action_id = str(item.get("action_id") or "")
            if not action_id:
                continue
            server_ids.add(action_id)
            was_known = self.tracker.get(action_id) is not None
            record, accepted = self.tracker.apply(item)
            if accepted and record is not None and not was_known:
                adopted.append(action_id)

        recovered = []
        lost = []
        unresolved = []
        local_active = list(self.tracker.active())
        for record in local_active:
            if record.action_id in server_ids:
                continue
            status_response = await self.plugin._send_request({
                "type": "get_maid_action_status",
                "data": {"action_id": record.action_id},
            }, timeout=5)
            if status_response.get("type") != "error":
                status_data = self._payload(status_response)
                updated, accepted = self.tracker.apply(status_data)
                if accepted and updated is not None:
                    recovered.append(record.action_id)
                    if updated.terminal:
                        await self.feedback.finished(updated)
                continue
            if self._is_not_found(status_response):
                lost_record, accepted = self.tracker.mark_server_state_lost(record)
                if accepted:
                    lost.append(record.action_id)
                    await self.feedback.finished(lost_record)
            else:
                unresolved.append(record.action_id)

        return {
            "success": True,
            "active": [record.as_dict() for record in self.tracker.active()],
            "adopted": adopted,
            "recovered": recovered,
            "lost": lost,
            "unresolved": unresolved,
        }

    @staticmethod
    def _payload(message: Dict[str, Any]) -> Dict[str, Any]:
        data = (message or {}).get("data", {})
        return dict(data) if isinstance(data, dict) else {}

    @staticmethod
    def _extract_actions(payload: Dict[str, Any]) -> Iterable[Dict[str, Any]]:
        for key in ("actions", "active_actions"):
            items = payload.get(key)
            if isinstance(items, list):
                return [item for item in items if isinstance(item, dict)]
        return []

    @staticmethod
    def _is_not_found(message: Dict[str, Any]) -> bool:
        payload = MaidActionService._payload(message)
        code = str(payload.get("error_code") or payload.get("code") or "").upper()
        if code in {"ACTION_NOT_FOUND", "NOT_FOUND", "UNKNOWN_ACTION"}:
            return True
        text = str(payload.get("message") or payload.get("error") or "").lower()
        return "not found" in text or "unknown action" in text
