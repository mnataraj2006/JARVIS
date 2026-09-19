"""
Centralized Latency Instrumentation for JARVIS.

Tracks per-request metrics with unique Request IDs (e.g. JARVIS-20260919-001)
and computes percentiles (P50, P90, P95), fast-path ratios, and component breakdown.
"""
from __future__ import annotations

import time
import threading
from dataclasses import dataclass, field
from datetime import datetime
from typing import Optional, Dict, List


@dataclass
class RequestMetrics:
    request_id: str
    command_text: str = ""
    command_class: str = "DIRECT"  # DIRECT, LOCAL_TOOL, BROWSER, VISION, REASONING, etc.
    is_fast_path: bool = False
    
    start_time: float = field(default_factory=time.monotonic)
    end_time: Optional[float] = None
    
    # Granular phase durations in milliseconds
    stt_ms: float = 0.0
    router_ms: float = 0.0
    llm_request_ms: float = 0.0
    llm_first_token_ms: float = 0.0
    llm_total_ms: float = 0.0
    tool_selection_ms: float = 0.0
    tool_execution_ms: float = 0.0
    screenshot_capture_ms: float = 0.0
    vision_ms: float = 0.0
    tts_startup_ms: float = 0.0
    tts_total_ms: float = 0.0
    ui_render_ms: float = 0.0
    
    # Milestone timestamps (monotonic)
    first_response_time: Optional[float] = None
    first_audio_time: Optional[float] = None
    action_start_time: Optional[float] = None
    action_end_time: Optional[float] = None

    def mark_first_response(self) -> None:
        if self.first_response_time is None:
            self.first_response_time = time.monotonic()

    def mark_first_audio(self) -> None:
        if self.first_audio_time is None:
            self.first_audio_time = time.monotonic()

    def mark_action_start(self) -> None:
        if self.action_start_time is None:
            self.action_start_time = time.monotonic()

    def mark_action_end(self) -> None:
        self.action_end_time = time.monotonic()
        if self.action_start_time is not None:
            self.tool_execution_ms = max(0.0, (self.action_end_time - self.action_start_time) * 1000)

    def finish(self) -> float:
        if self.end_time is None:
            self.end_time = time.monotonic()
        return self.total_ms

    @property
    def total_ms(self) -> float:
        end = self.end_time if self.end_time is not None else time.monotonic()
        return max(0.0, (end - self.start_time) * 1000)

    @property
    def time_to_first_response_ms(self) -> float:
        if self.first_response_time:
            return max(0.0, (self.first_response_time - self.start_time) * 1000)
        return self.total_ms

    @property
    def time_to_first_audio_ms(self) -> float:
        if self.first_audio_time:
            return max(0.0, (self.first_audio_time - self.start_time) * 1000)
        return self.total_ms

    @property
    def time_to_action_ms(self) -> float:
        if self.action_start_time:
            return max(0.0, (self.action_start_time - self.start_time) * 1000)
        return self.total_ms

    def format_summary(self) -> str:
        lines = [
            f"[JARVIS][LATENCY][{self.request_id}] Class: {self.command_class} | FastPath: {self.is_fast_path}",
            f"  Command:          \"{self.command_text[:40]}\"",
        ]
        if self.stt_ms > 0:
            lines.append(f"  STT:              {self.stt_ms:6.1f} ms")
        if self.router_ms > 0:
            lines.append(f"  Intent Router:    {self.router_ms:6.1f} ms")
        if self.llm_total_ms > 0:
            lines.append(f"  LLM (TTFT: {self.llm_first_token_ms:4.1f}ms): {self.llm_total_ms:6.1f} ms")
        if self.tool_selection_ms > 0:
            lines.append(f"  Tool Selection:   {self.tool_selection_ms:6.1f} ms")
        if self.tool_execution_ms > 0:
            lines.append(f"  Tool Execution:   {self.tool_execution_ms:6.1f} ms")
        if self.screenshot_capture_ms > 0:
            lines.append(f"  Screenshot:       {self.screenshot_capture_ms:6.1f} ms")
        if self.vision_ms > 0:
            lines.append(f"  Vision Model:     {self.vision_ms:6.1f} ms")
        if self.tts_startup_ms > 0:
            lines.append(f"  TTS Startup:      {self.tts_startup_ms:6.1f} ms")
        if self.first_audio_time:
            lines.append(f"  Time To First Audio: {self.time_to_first_audio_ms:4.1f} ms")
        lines.append(f"  TOTAL:            {self.total_ms:6.1f} ms")
        return "\n".join(lines)


class LatencyTracker:
    """Central manager for tracking, logging, and aggregating latency across JARVIS."""

    def __init__(self):
        self._lock = threading.Lock()
        self._counter = 0
        self._history: List[RequestMetrics] = []
        self._max_history = 500

    def start_request(self, command_text: str = "", command_class: str = "DIRECT") -> RequestMetrics:
        with self._lock:
            self._counter += 1
            now_str = datetime.now().strftime("%Y%m%d")
            req_id = f"JARVIS-{now_str}-{self._counter:03d}"
            req = RequestMetrics(
                request_id=req_id,
                command_text=command_text,
                command_class=command_class,
            )
            self._history.append(req)
            if len(self._history) > self._max_history:
                self._history.pop(0)
            return req

    def record_completed(self, req: RequestMetrics, log_summary: bool = True) -> str:
        req.finish()
        summary = req.format_summary()
        if log_summary:
            print(summary)
        return summary

    def get_aggregate_stats(self) -> Dict[str, float]:
        with self._lock:
            completed = [r for r in self._history if r.end_time is not None]
            if not completed:
                return {
                    "count": 0,
                    "avg_total_ms": 0.0,
                    "p50_total_ms": 0.0,
                    "p90_total_ms": 0.0,
                    "p95_total_ms": 0.0,
                    "fast_path_pct": 0.0,
                    "avg_first_audio_ms": 0.0,
                }

            totals = sorted([r.total_ms for r in completed])
            n = len(totals)
            p50 = totals[int(n * 0.50)]
            p90 = totals[min(int(n * 0.90), n - 1)]
            p95 = totals[min(int(n * 0.95), n - 1)]
            avg_total = sum(totals) / n
            fast_count = sum(1 for r in completed if r.is_fast_path)
            fast_pct = (fast_count / n) * 100.0

            audio_latencies = [r.time_to_first_audio_ms for r in completed if r.first_audio_time is not None]
            avg_first_audio = (sum(audio_latencies) / len(audio_latencies)) if audio_latencies else 0.0

            return {
                "count": n,
                "avg_total_ms": round(avg_total, 1),
                "p50_total_ms": round(p50, 1),
                "p90_total_ms": round(p90, 1),
                "p95_total_ms": round(p95, 1),
                "fast_path_pct": round(fast_pct, 1),
                "avg_first_audio_ms": round(avg_first_audio, 1),
            }


_GLOBAL_TRACKER: Optional[LatencyTracker] = None
_GLOBAL_LOCK = threading.Lock()

def get_latency_tracker() -> LatencyTracker:
    global _GLOBAL_TRACKER
    if _GLOBAL_TRACKER is None:
        with _GLOBAL_LOCK:
            if _GLOBAL_TRACKER is None:
                _GLOBAL_TRACKER = LatencyTracker()
    return _GLOBAL_TRACKER
