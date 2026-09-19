"""
Fast Intent Router & Command Classifier for JARVIS.

Evaluates user commands locally in < 10 ms.
Routes deterministic commands to direct tool execution without LLM calls,
and classifies complex requests into appropriate intelligence escalation levels.
"""
from __future__ import annotations

import re
import time
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Callable, Dict, List, Optional, Tuple


class CommandClass:
    DIRECT = "DIRECT"
    LOCAL_TOOL = "LOCAL_TOOL"
    BROWSER = "BROWSER"
    VISION = "VISION"
    REASONING = "REASONING"
    RESEARCH = "RESEARCH"
    CODING = "CODING"
    AUTONOMOUS = "AUTONOMOUS"
    CONVERSATIONAL = "CONVERSATIONAL"
    BACKGROUND = "BACKGROUND"


@dataclass
class RouteResult:
    matched: bool = False
    command_class: str = CommandClass.CONVERSATIONAL
    escalation_level: int = 2  # 0: Direct/No LLM, 1: Fast Intent, 2: LLM, 3: Vision, 4: Autonomous
    action: Optional[str] = None
    parameters: Dict[str, Any] = field(default_factory=dict)
    response_template: str = ""
    target_device: str = "pc"  # "pc", "phone", "both"
    is_interruption: bool = False
    is_dangerous: bool = False
    requires_confirmation: bool = False
    routing_latency_ms: float = 0.0


# Stop words and natural conversational prefixes to strip when normalizing commands
_POLITE_PREFIXES = re.compile(
    r"^(?:could\s+you\s+(?:please\s+)?|can\s+you\s+(?:please\s+)?|please\s+|jarvis\s+|hey\s+jarvis\s+|would\s+you\s+|kindly\s+)",
    re.IGNORECASE,
)
_POLITE_SUFFIXES = re.compile(
    r"(?:\s+for\s+me|\s+please|\s+now)?$",
    re.IGNORECASE,
)


def normalize_command(text: str) -> str:
    """Strip politeness, greetings, and trailing fluff for crisp matching."""
    s = text.strip()
    s = _POLITE_PREFIXES.sub("", s)
    s = _POLITE_SUFFIXES.sub("", s)
    # Remove punctuation at end
    s = re.sub(r"[.!?]+$", "", s).strip()
    return s


class FastRouter:
    """High-speed local intent classifier and deterministic router."""

    def __init__(self):
        # Pre-compile regexes for P0 interruptions
        self._re_stop = re.compile(
            r"^(?:stop|cancel|halt|quiet|shut\s*up|pause|wait|jarvis\s+stop|stop\s+talking|stop\s+speaking)$",
            re.IGNORECASE,
        )

        # Pre-compile volume patterns
        self._re_vol_up = re.compile(r"^(?:volume\s+up|increase\s+volume|raise\s+volume|louder)$", re.IGNORECASE)
        self._re_vol_down = re.compile(r"^(?:volume\s+down|decrease\s+volume|lower\s+volume|quieter)$", re.IGNORECASE)
        self._re_vol_mute = re.compile(r"^(?:mute|unmute|toggle\s+mute|silence)$", re.IGNORECASE)
        self._re_vol_set = re.compile(r"^(?:set\s+volume\s+(?:to\s+)?|volume\s+)(\d{1,3})%?$", re.IGNORECASE)

        # Brightness patterns
        self._re_bright_up = re.compile(r"^(?:brightness\s+up|increase\s+brightness|brighter)$", re.IGNORECASE)
        self._re_bright_down = re.compile(r"^(?:brightness\s+down|decrease\s+brightness|dimmer|dim)$", re.IGNORECASE)
        self._re_bright_set = re.compile(r"^(?:set\s+brightness\s+(?:to\s+)?|brightness\s+)(\d{1,3})%?$", re.IGNORECASE)

        # Screen / Window actions
        self._re_screenshot = re.compile(r"^(?:take\s+screenshot|screenshot|capture\s+screen|screen\s+capture)$", re.IGNORECASE)
        self._re_lock = re.compile(r"^(?:lock\s+computer|lock\s+pc|lock\s+screen|lock)$", re.IGNORECASE)
        self._re_restart = re.compile(r"^(?:restart\s+computer|restart\s+pc|reboot)$", re.IGNORECASE)
        self._re_shutdown = re.compile(r"^(?:shutdown\s+computer|shutdown\s+pc|turn\s+off\s+pc|power\s+off)$", re.IGNORECASE)

        # System Metrics
        self._re_cpu = re.compile(r"^(?:show\s+cpu|what\s+is\s+my\s+cpu(?:\s+usage)?|cpu\s+usage|cpu)$", re.IGNORECASE)
        self._re_ram = re.compile(r"^(?:show\s+ram|what\s+is\s+my\s+ram(?:\s+usage)?|ram\s+usage|memory\s+usage)$", re.IGNORECASE)
        self._re_gpu = re.compile(r"^(?:show\s+gpu|what\s+is\s+my\s+gpu(?:\s+usage)?|gpu\s+usage|gpu)$", re.IGNORECASE)
        self._re_status = re.compile(r"^(?:system\s+status|system\s+info|how\s+is\s+my\s+computer\s+doing)$", re.IGNORECASE)

        # Time / Date
        self._re_time = re.compile(r"^(?:what\s+time\s+is\s+it|time|current\s+time)$", re.IGNORECASE)
        self._re_date = re.compile(r"^(?:what\s+is\s+the\s+date|what\s+date\s+is\s+it|date|today\'?s\s+date)$", re.IGNORECASE)

        # Keyboard / Navigation
        self._re_enter = re.compile(r"^(?:press\s+enter|hit\s+enter|enter)$", re.IGNORECASE)
        self._re_escape = re.compile(r"^(?:press\s+escape|hit\s+escape|escape|esc)$", re.IGNORECASE)
        self._re_scroll_up = re.compile(r"^(?:scroll\s+up|page\s+up)$", re.IGNORECASE)
        self._re_scroll_down = re.compile(r"^(?:scroll\s+down|page\s+down)$", re.IGNORECASE)

        # Type text
        self._re_type = re.compile(r"^(?:type\s+(?:this\s+)?text\s+|type\s+|write\s+)(.+)$", re.IGNORECASE)

        # App Launch / Close
        self._re_open_app = re.compile(r"^(?:open|launch|start|run)\s+([a-zA-Z0-9\s._-]+)$", re.IGNORECASE)
        self._re_close_app = re.compile(r"^(?:close|quit|exit|kill)\s+([a-zA-Z0-9\s._-]+)$", re.IGNORECASE)

        # Web & Search
        self._re_search = re.compile(r"^(?:search\s+google\s+for|search\s+for|google)\s+(.+)$", re.IGNORECASE)
        self._re_open_site = re.compile(r"^(?:open\s+website\s+|open\s+this\s+website\s+|go\s+to\s+)(https?://\S+|\S+\.(?:com|org|net|io|edu|gov|dev|ai)\S*)$", re.IGNORECASE)
        self._re_youtube_play = re.compile(r"^(?:play\s+youtube\s+|play\s+on\s+youtube\s+|play\s+video\s+)(.+)$", re.IGNORECASE)

        # Vision cues
        self._re_vision = re.compile(
            r"(?:what(?:\'s|\s+is)\s+(?:on|in)\s+my\s+screen|look\s+at\s+my\s+screen|see\s+my\s+screen|what\s+error|read\s+this\s+screen|describe\s+screen)",
            re.IGNORECASE,
        )

        # Coding / Autonomous cues
        self._re_autonomous = re.compile(
            r"(?:build\s+me|create\s+(?:a|an)\s+(?:app|project|website|tool|script)|implement\s+(?:a|an)\s+full)",
            re.IGNORECASE,
        )
        self._re_coding = re.compile(
            r"(?:write\s+code|debug\s+this\s+code|explain\s+this\s+code|refactor\s+this)",
            re.IGNORECASE,
        )

        # ── Phone / Mobile Agent Direct Actions ─────────────────────────
        self._re_phone_open = re.compile(
            r"^(?:open|launch|start|run)\s+([a-zA-Z0-9\s._-]+?)\s+(?:on|in)\s+(?:my\s+)?(?:phone|mobile|android)$",
            re.IGNORECASE,
        )
        self._re_phone_battery = re.compile(
            r"^(?:what(?:\'s|\s+is)\s+(?:my\s+)?phone\s+battery(?:\s+level)?|phone\s+battery(?:\s+status|\s+level)?|battery\s+(?:on|of)\s+(?:my\s+)?phone)$",
            re.IGNORECASE,
        )
        self._re_phone_screenshot = re.compile(
            r"^(?:(?:take\s+)?screenshot\s+(?:of|on)\s+(?:my\s+)?phone|phone\s+screenshot|capture\s+phone\s+screen)$",
            re.IGNORECASE,
        )
        self._re_phone_vol_up = re.compile(
            r"^(?:phone\s+volume\s+up|volume\s+up\s+on\s+(?:my\s+)?phone|increase\s+phone\s+volume)$",
            re.IGNORECASE,
        )
        self._re_phone_vol_down = re.compile(
            r"^(?:phone\s+volume\s+down|volume\s+down\s+on\s+(?:my\s+)?phone|decrease\s+phone\s+volume)$",
            re.IGNORECASE,
        )
        self._re_phone_mute = re.compile(
            r"^(?:mute\s+(?:my\s+)?phone|phone\s+mute|silence\s+(?:my\s+)?phone)$",
            re.IGNORECASE,
        )
        self._re_phone_lock = re.compile(
            r"^(?:lock\s+(?:my\s+)?phone|phone\s+lock)$",
            re.IGNORECASE,
        )
        self._re_phone_home = re.compile(
            r"^(?:(?:go\s+)?home\s+on\s+(?:my\s+)?phone|phone\s+home)$",
            re.IGNORECASE,
        )
        self._re_phone_back = re.compile(
            r"^(?:(?:go\s+)?back\s+on\s+(?:my\s+)?phone|phone\s+back)$",
            re.IGNORECASE,
        )
        self._re_phone_notifications = re.compile(
            r"^(?:(?:read|show|check|get)\s+(?:my\s+)?phone\s+notifications|phone\s+notifications)$",
            re.IGNORECASE,
        )
        self._re_phone_ring = re.compile(
            r"^(?:find\s+my\s+phone|ring\s+(?:my\s+)?phone|where\s+is\s+my\s+phone)$",
            re.IGNORECASE,
        )
        self._re_clipboard_sync = re.compile(
            r"^(?:copy\s+clipboard\s+to\s+(?:my\s+)?phone|send\s+clipboard\s+to\s+(?:my\s+)?phone|sync\s+clipboard)$",
            re.IGNORECASE,
        )

    def route(self, raw_input: str) -> RouteResult:
        t0 = time.monotonic()
        text = raw_input.strip()
        norm = normalize_command(text)
        
        # ── P0: Interruption ──────────────────────────────────────────────
        if self._re_stop.match(norm) or norm in ("stop", "cancel", "pause", "halt"):
            latency = (time.monotonic() - t0) * 1000
            return RouteResult(
                matched=True,
                command_class=CommandClass.DIRECT,
                escalation_level=0,
                action="interrupt",
                response_template="Stopped.",
                is_interruption=True,
                routing_latency_ms=latency,
            )

        # ── Level 0: Phone Specific Direct Actions ────────────────────────
        m_phone_open = self._re_phone_open.match(norm)
        if m_phone_open:
            app = m_phone_open.group(1).strip()
            return self._res(CommandClass.DIRECT, 0, "phone_launch_app", {"app_name": app}, f"Opening {app} on your phone.", t0, target_device="phone")

        if self._re_phone_battery.match(norm):
            return self._res(CommandClass.LOCAL_TOOL, 0, "phone_battery", {}, "", t0, target_device="phone")

        if self._re_phone_screenshot.match(norm):
            return self._res(CommandClass.LOCAL_TOOL, 0, "phone_screenshot", {}, "Capturing phone screen.", t0, target_device="phone")

        if self._re_phone_vol_up.match(norm):
            return self._res(CommandClass.LOCAL_TOOL, 0, "phone_volume_up", {}, "Phone volume increased.", t0, target_device="phone")

        if self._re_phone_vol_down.match(norm):
            return self._res(CommandClass.LOCAL_TOOL, 0, "phone_volume_down", {}, "Phone volume decreased.", t0, target_device="phone")

        if self._re_phone_mute.match(norm):
            return self._res(CommandClass.LOCAL_TOOL, 0, "phone_mute", {}, "Phone muted.", t0, target_device="phone")

        if self._re_phone_lock.match(norm):
            return self._res(CommandClass.LOCAL_TOOL, 0, "phone_lock", {}, "Locking your phone.", t0, target_device="phone")

        if self._re_phone_home.match(norm):
            return self._res(CommandClass.LOCAL_TOOL, 0, "phone_home", {}, "", t0, target_device="phone")

        if self._re_phone_back.match(norm):
            return self._res(CommandClass.LOCAL_TOOL, 0, "phone_back", {}, "", t0, target_device="phone")

        if self._re_phone_notifications.match(norm):
            return self._res(CommandClass.LOCAL_TOOL, 0, "phone_notifications", {}, "", t0, target_device="phone")

        if self._re_phone_ring.match(norm):
            return self._res(CommandClass.LOCAL_TOOL, 0, "phone_ring", {}, "Ringing your phone now.", t0, target_device="phone")

        if self._re_clipboard_sync.match(norm):
            return self._res(CommandClass.LOCAL_TOOL, 0, "clipboard_sync", {}, "Syncing clipboard with phone.", t0, target_device="both")

        # ── Level 0: Volume ──────────────────────────────────────────────
        if self._re_vol_up.match(norm):
            return self._res(CommandClass.LOCAL_TOOL, 0, "volume_up", {}, "Volume increased.", t0)
        if self._re_vol_down.match(norm):
            return self._res(CommandClass.LOCAL_TOOL, 0, "volume_down", {}, "Volume decreased.", t0)
        if self._re_vol_mute.match(norm):
            return self._res(CommandClass.LOCAL_TOOL, 0, "mute", {}, "Audio muted.", t0)
        m_vol = self._re_vol_set.match(norm)
        if m_vol:
            val = int(m_vol.group(1))
            return self._res(CommandClass.LOCAL_TOOL, 0, "volume_set", {"value": val}, f"Volume set to {val} percent.", t0)

        # ── Level 0: Brightness ──────────────────────────────────────────
        if self._re_bright_up.match(norm):
            return self._res(CommandClass.LOCAL_TOOL, 0, "brightness_up", {}, "Brightness increased.", t0)
        if self._re_bright_down.match(norm):
            return self._res(CommandClass.LOCAL_TOOL, 0, "brightness_down", {}, "Brightness decreased.", t0)
        m_br = self._re_bright_set.match(norm)
        if m_br:
            val = int(m_br.group(1))
            return self._res(CommandClass.LOCAL_TOOL, 0, "brightness_set", {"value": val}, f"Brightness set to {val} percent.", t0)

        # ── Level 0: Display & Security ──────────────────────────────────
        if self._re_screenshot.match(norm):
            return self._res(CommandClass.LOCAL_TOOL, 0, "screenshot", {}, "Done. I captured the screenshot.", t0)
        if self._re_lock.match(norm):
            return self._res(CommandClass.LOCAL_TOOL, 0, "lock_screen", {}, "Locking your computer.", t0)

        # Dangerous operations: enforce confirmation
        if self._re_restart.match(norm):
            return RouteResult(
                matched=True,
                command_class=CommandClass.LOCAL_TOOL,
                escalation_level=0,
                action="restart",
                is_dangerous=True,
                requires_confirmation=True,
                response_template="Please confirm: do you really want to restart your computer?",
                routing_latency_ms=(time.monotonic() - t0) * 1000,
            )
        if self._re_shutdown.match(norm):
            return RouteResult(
                matched=True,
                command_class=CommandClass.LOCAL_TOOL,
                escalation_level=0,
                action="shutdown",
                is_dangerous=True,
                requires_confirmation=True,
                response_template="Please confirm: do you really want to shut down your computer?",
                routing_latency_ms=(time.monotonic() - t0) * 1000,
            )

        # ── Level 0: System Metrics ──────────────────────────────────────
        if self._re_cpu.match(norm):
            return self._res(CommandClass.LOCAL_TOOL, 0, "system_status_cpu", {}, "", t0)
        if self._re_ram.match(norm):
            return self._res(CommandClass.LOCAL_TOOL, 0, "system_status_ram", {}, "", t0)
        if self._re_gpu.match(norm):
            return self._res(CommandClass.LOCAL_TOOL, 0, "system_status_gpu", {}, "", t0)
        if self._re_status.match(norm):
            return self._res(CommandClass.LOCAL_TOOL, 0, "system_status", {}, "", t0)

        # ── Level 0: Time & Date ─────────────────────────────────────────
        if self._re_time.match(norm):
            now_str = datetime.now().strftime("%I:%M %p")
            return self._res(CommandClass.LOCAL_TOOL, 0, "time", {}, f"It is {now_str}.", t0)
        if self._re_date.match(norm):
            date_str = datetime.now().strftime("%A, %B %d, %Y")
            return self._res(CommandClass.LOCAL_TOOL, 0, "date", {}, f"Today is {date_str}.", t0)

        # ── Level 0: Keyboard / Navigation ───────────────────────────────
        if self._re_enter.match(norm):
            return self._res(CommandClass.LOCAL_TOOL, 0, "enter", {}, "", t0)
        if self._re_escape.match(norm):
            return self._res(CommandClass.LOCAL_TOOL, 0, "escape", {}, "", t0)
        if self._re_scroll_up.match(norm):
            return self._res(CommandClass.LOCAL_TOOL, 0, "scroll_up", {"value": 500}, "", t0)
        if self._re_scroll_down.match(norm):
            return self._res(CommandClass.LOCAL_TOOL, 0, "scroll_down", {"value": 500}, "", t0)

        m_type = self._re_type.match(norm)
        if m_type:
            text_to_type = m_type.group(1).strip()
            return self._res(CommandClass.LOCAL_TOOL, 0, "type_text", {"text": text_to_type}, "", t0)

        # ── Level 0/1: Open Application ──────────────────────────────────
        m_open = self._re_open_app.match(norm)
        if m_open:
            app_raw = m_open.group(1).strip()
            # Avoid matching URLs or search phrases as apps
            if not any(app_raw.endswith(ext) for ext in (".com", ".org", ".net", ".io", ".dev", ".ai")):
                return self._res(
                    CommandClass.DIRECT, 0, "open_app",
                    {"app_name": app_raw},
                    f"Opening {app_raw}.",
                    t0,
                )

        # ── Level 0/1: Close Application ─────────────────────────────────
        m_close = self._re_close_app.match(norm)
        if m_close:
            app_raw = m_close.group(1).strip()
            return self._res(
                CommandClass.LOCAL_TOOL, 0, "close_app",
                {"app_name": app_raw},
                f"Closing {app_raw}.",
                t0,
            )

        # ── Level 1: Web Navigation / Search ─────────────────────────────
        m_search = self._re_search.match(norm)
        if m_search:
            q = m_search.group(1).strip()
            return self._res(
                CommandClass.BROWSER, 1, "web_search",
                {"query": q, "mode": "search"},
                f"Searching Google for {q}.",
                t0,
            )

        m_site = self._re_open_site.match(norm)
        if m_site:
            url = m_site.group(1).strip()
            return self._res(
                CommandClass.BROWSER, 1, "browser_control",
                {"action": "go_to", "url": url},
                f"Opening {url}.",
                t0,
            )

        m_yt = self._re_youtube_play.match(norm)
        if m_yt:
            q = m_yt.group(1).strip()
            return self._res(
                CommandClass.BROWSER, 1, "youtube_video",
                {"action": "play", "query": q},
                f"Playing {q} on YouTube.",
                t0,
            )

        # ── Level 3: Vision Request Classification ───────────────────────
        if self._re_vision.search(norm):
            latency = (time.monotonic() - t0) * 1000
            return RouteResult(
                matched=False,
                command_class=CommandClass.VISION,
                escalation_level=3,
                action="screen_process",
                response_template="Checking your screen now.",
                routing_latency_ms=latency,
            )

        # ── Level 4: Autonomous / Coding Classification ──────────────────
        if self._re_autonomous.search(norm):
            latency = (time.monotonic() - t0) * 1000
            return RouteResult(
                matched=False,
                command_class=CommandClass.AUTONOMOUS,
                escalation_level=4,
                response_template="Starting autonomous project development.",
                routing_latency_ms=latency,
            )

        if self._re_coding.search(norm):
            latency = (time.monotonic() - t0) * 1000
            return RouteResult(
                matched=False,
                command_class=CommandClass.CODING,
                escalation_level=4,
                response_template="Analyzing code request.",
                routing_latency_ms=latency,
            )

        # ── Default: Level 2 Conversational LLM ──────────────────────────
        latency = (time.monotonic() - t0) * 1000
        return RouteResult(
            matched=False,
            command_class=CommandClass.CONVERSATIONAL,
            escalation_level=2,
            routing_latency_ms=latency,
        )

    def _res(
        self,
        cmd_class: str,
        level: int,
        action: str,
        params: Dict[str, Any],
        template: str,
        t0: float,
        target_device: str = "pc",
    ) -> RouteResult:
        latency = (time.monotonic() - t0) * 1000
        return RouteResult(
            matched=True,
            command_class=cmd_class,
            escalation_level=level,
            action=action,
            parameters=params,
            response_template=template,
            target_device=target_device,
            routing_latency_ms=latency,
        )


_GLOBAL_ROUTER = FastRouter()

def get_fast_router() -> FastRouter:
    return _GLOBAL_ROUTER
