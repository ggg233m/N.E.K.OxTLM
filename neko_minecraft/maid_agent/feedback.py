"""Routes useful maid action feedback into the N.E.K.O conversation."""

import asyncio
import time
from typing import Dict, Optional

from .models import ActionRecord


_KIND_NAMES = {
    "navigate": "寻路",
    "harvest_blocks": "采集",
    "attack": "攻击",
}


class ActionFeedbackHandler:
    def __init__(self, plugin, progress_interval: float = 1.5, clock=None):
        self._plugin = plugin
        self._progress_interval = max(1.0, min(2.0, float(progress_interval)))
        self._clock = clock or time.monotonic
        self._last_progress: Dict[str, tuple] = {}
        self._finished = set()
        self._decisions = set()

    async def progress(self, record: ActionRecord) -> bool:
        key = self._key(record)
        now = self._clock()
        previous = self._last_progress.get(key)
        if previous and previous[0] == record.stage and now - previous[1] < self._progress_interval:
            return False
        self._last_progress[key] = (record.stage, now)
        await self._plugin._push_minecraft_context(
            self._progress_text(record),
            ai_behavior="read",
            priority=1,
            metadata={
                "description": "Minecraft 女仆 Agent 动作进度",
                "action_id": record.action_id,
                "generation": record.generation,
            },
            aggregate=True,
            coalesce_key=f"mc_maid_action_progress:{record.maid_id or record.action_id}",
        )
        return True

    async def decision_required(self, record: ActionRecord) -> bool:
        key = f"{self._key(record)}:{record.sequence}"
        if key in self._decisions:
            return False
        await self._push_with_retry(
            self._progress_text(record)
                + " 服务端标记此阶段需要新的决策，请结合动作状态决定重试、取消或改换目标。",
            ai_behavior="respond", priority=4,
            metadata={"description": "Minecraft 女仆 Agent 动作需要决策",
                      "action_id": record.action_id, "generation": record.generation},
            aggregate=False, coalesce_key=None,
        )
        self._decisions.add(key)
        return True

    async def finished(self, record: ActionRecord) -> bool:
        key = self._key(record)
        if key in self._finished:
            return False
        await self._push_with_retry(
            self._finished_text(record),
            ai_behavior="respond",
            priority=5,
            metadata={
                "description": "Minecraft 女仆 Agent 动作结束",
                "action_id": record.action_id,
                "generation": record.generation,
                "status": record.status,
                "end_reason": record.end_reason,
            },
            aggregate=False,
            coalesce_key=None,
        )
        self._finished.add(key)
        self._last_progress.pop(key, None)
        return True

    async def _push_with_retry(self, text: str, **kwargs) -> None:
        """Retry important decision/terminal feedback before marking it delivered."""
        for attempt, delay in enumerate((0.0, 0.5, 1.5)):
            if delay:
                await asyncio.sleep(delay)
            try:
                await self._plugin._push_minecraft_context(text, **kwargs)
                return
            except Exception:
                if attempt == 2:
                    raise

    @staticmethod
    def _key(record: ActionRecord) -> str:
        return f"{record.action_id}:{record.generation}"

    @staticmethod
    def _progress_text(record: ActionRecord) -> str:
        kind = _KIND_NAMES.get(record.kind, record.kind or "动作")
        text = f"女仆 Agent 的{kind}动作正在执行，阶段：{record.stage or record.status}。"
        text += "这是执行进度，不要仅因这条进度消息打断玩家。"
        return text

    @staticmethod
    def _finished_text(record: ActionRecord) -> str:
        kind = _KIND_NAMES.get(record.kind, record.kind or "动作")
        if record.status == "SUCCEEDED":
            outcome = "已经成功完成"
        else:
            reason = record.end_reason or record.status
            outcome = f"已结束，状态为 {record.status}，原因是 {reason}"
        text = f"女仆 Agent 的{kind}动作（action_id={record.action_id}）{outcome}。"
        message = record.result.get("message") if isinstance(record.result, dict) else None
        if message:
            text += f" 服务端信息：{message}。"
        text += "请根据真实终态简短回应玩家；失败时不要声称动作成功。"
        return text
