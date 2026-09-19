"""
core/device_manager.py — Centralized Multi-Device Abstraction & Coordination for JARVIS.

Manages connected devices (Desktop PC + Android Phone Agents),
tracks capabilities and heartbeats, correlates async JSON-RPC commands,
and provides intelligent cross-device routing (PC vs PHONE vs BOTH).
"""
from __future__ import annotations

import asyncio
import json
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Callable, Dict, List, Optional, Set, Tuple


class DeviceType:
    DESKTOP = "desktop"
    ANDROID = "android"
    IOS = "ios"


class TargetDevice:
    PC = "pc"
    PHONE = "phone"
    BOTH = "both"


@dataclass
class DeviceInfo:
    id: str
    name: str
    type: str  # desktop | android | ios
    online: bool = False
    os: str = ""
    os_version: str = ""
    manufacturer: str = ""
    model: str = ""
    battery_level: Optional[int] = None
    is_charging: bool = False
    network: str = "wifi"
    ip: str = ""
    last_seen: float = field(default_factory=time.monotonic)
    capabilities: Set[str] = field(default_factory=set)
    agent_version: str = "1.0.0"

    def update_heartbeat(self, data: Optional[Dict[str, Any]] = None) -> None:
        self.last_seen = time.monotonic()
        self.online = True
        if data:
            if "battery" in data:
                self.battery_level = data["battery"].get("level", self.battery_level)
                self.is_charging = data["battery"].get("charging", self.is_charging)
            if "network" in data:
                self.network = data.get("network", self.network)


@dataclass
class DeviceCommand:
    id: str = field(default_factory=lambda: f"cmd-{uuid.uuid4().hex[:8]}")
    action: str = ""
    parameters: Dict[str, Any] = field(default_factory=dict)
    target_device: str = TargetDevice.PHONE
    timeout_seconds: float = 12.0
    created_at: float = field(default_factory=time.monotonic)


@dataclass
class DeviceResult:
    command_id: str
    device_id: str
    success: bool
    data: Any = None
    error: Optional[str] = None
    execution_time_ms: float = 0.0


class DeviceManager:
    """Manages all devices, RPC command dispatch, and event propagation."""

    def __init__(self):
        self._devices: Dict[str, DeviceInfo] = {}
        self._ws_connections: Dict[str, Any] = {}  # device_id -> WebSocket
        self._pending_commands: Dict[str, asyncio.Future[DeviceResult]] = {}
        self._event_listeners: List[Callable[[str, Dict[str, Any]], None]] = []
        self._screen_frame_listeners: List[Callable[[bytes], None]] = []
        
        # Register local PC by default
        import platform
        self._devices["pc-main"] = DeviceInfo(
            id="pc-main",
            name=f"{platform.node()} PC",
            type=DeviceType.DESKTOP,
            online=True,
            os=platform.system(),
            os_version=platform.release(),
            capabilities={
                "mouse", "keyboard", "apps", "files", "browser",
                "screen_capture", "screen_stream", "system_settings", "audio",
            },
        )

    # ── Device Registration & Heartbeat ──────────────────────────────────────

    def register_device(self, info_dict: Dict[str, Any], ws: Optional[Any] = None) -> DeviceInfo:
        dev_id = info_dict.get("id") or f"phone-{uuid.uuid4().hex[:6]}"
        capabilities = set(info_dict.get("capabilities", []))
        
        dev = DeviceInfo(
            id=dev_id,
            name=info_dict.get("name", "Android Phone"),
            type=info_dict.get("type", DeviceType.ANDROID),
            online=True,
            os=info_dict.get("os", "Android"),
            os_version=str(info_dict.get("os_version", "")),
            manufacturer=info_dict.get("manufacturer", ""),
            model=info_dict.get("model", ""),
            battery_level=info_dict.get("battery_level"),
            is_charging=bool(info_dict.get("is_charging", False)),
            network=info_dict.get("network", "wifi"),
            ip=info_dict.get("ip", ""),
            capabilities=capabilities,
            agent_version=info_dict.get("agent_version", "1.0.0"),
        )
        self._devices[dev_id] = dev
        if ws is not None:
            self._ws_connections[dev_id] = ws
            
        print(f"[DeviceManager] 📱 Device registered: {dev.name} ({dev.id}) - Caps: {len(capabilities)}")
        return dev

    def unregister_device(self, device_id: str) -> None:
        if device_id in self._devices and device_id != "pc-main":
            self._devices[device_id].online = False
            self._ws_connections.pop(device_id, None)
            print(f"[DeviceManager] 📱 Device disconnected: {device_id}")

    def update_heartbeat(self, device_id: str, data: Optional[Dict[str, Any]] = None) -> None:
        if device_id in self._devices:
            self._devices[device_id].update_heartbeat(data)

    def get_device(self, device_id: str) -> Optional[DeviceInfo]:
        return self._devices.get(device_id)

    def get_primary_phone(self) -> Optional[DeviceInfo]:
        """Returns the first online Android device, or None."""
        for d in self._devices.values():
            if d.type == DeviceType.ANDROID and d.online:
                return d
        # Fallback to any registered phone even if recently unverified
        for d in self._devices.values():
            if d.type == DeviceType.ANDROID:
                return d
        return None

    def list_devices(self) -> List[Dict[str, Any]]:
        result = []
        now = time.monotonic()
        for d in self._devices.values():
            # Check timeout for external devices (30 seconds)
            if d.id != "pc-main" and (now - d.last_seen) > 30.0:
                d.online = False
            result.append({
                "id": d.id,
                "name": d.name,
                "type": d.type,
                "online": d.online,
                "os": d.os,
                "os_version": d.os_version,
                "model": d.model,
                "battery_level": d.battery_level,
                "is_charging": d.is_charging,
                "capabilities": list(d.capabilities),
            })
        return result

    # ── Command Dispatching ──────────────────────────────────────────────────

    async def send_command(
        self,
        action: str,
        parameters: Optional[Dict[str, Any]] = None,
        target_device: str = TargetDevice.PHONE,
        timeout: float = 12.0,
    ) -> DeviceResult:
        """Dispatches command to the specified device and awaits the response asynchronously."""
        params = parameters or {}
        cmd = DeviceCommand(
            action=action,
            parameters=params,
            target_device=target_device,
            timeout_seconds=timeout,
        )

        phone = self.get_primary_phone()
        if not phone or not phone.online:
            return DeviceResult(
                command_id=cmd.id,
                device_id="phone-none",
                success=False,
                error="No Android phone is currently connected to JARVIS.",
            )

        ws = self._ws_connections.get(phone.id)
        if not ws:
            return DeviceResult(
                command_id=cmd.id,
                device_id=phone.id,
                success=False,
                error=f"Phone '{phone.name}' has no active WebSocket connection.",
            )

        loop = asyncio.get_running_loop()
        future: asyncio.Future[DeviceResult] = loop.create_future()
        self._pending_commands[cmd.id] = future

        # Construct JSON-RPC style frame
        frame = {
            "type": "command",
            "id": cmd.id,
            "action": cmd.action,
            "parameters": cmd.parameters,
            "timestamp": time.time(),
        }

        try:
            await ws.send_text(json.dumps(frame))
            result = await asyncio.wait_for(future, timeout=timeout)
            return result
        except asyncio.TimeoutError:
            self._pending_commands.pop(cmd.id, None)
            return DeviceResult(
                command_id=cmd.id,
                device_id=phone.id,
                success=False,
                error=f"Command '{action}' timed out after {timeout:.1f}s waiting for phone.",
            )
        except Exception as e:
            self._pending_commands.pop(cmd.id, None)
            return DeviceResult(
                command_id=cmd.id,
                device_id=phone.id,
                success=False,
                error=f"Failed to communicate with phone: {e}",
            )

    def handle_command_response(self, data: Dict[str, Any]) -> None:
        """Called by the WebSocket server when the mobile agent replies to a command."""
        cmd_id = data.get("id")
        if not cmd_id or cmd_id not in self._pending_commands:
            return

        future = self._pending_commands.pop(cmd_id)
        if not future.done():
            dev_id = data.get("device_id", "phone")
            success = bool(data.get("success", True))
            result = DeviceResult(
                command_id=cmd_id,
                device_id=dev_id,
                success=success,
                data=data.get("result"),
                error=data.get("error"),
                execution_time_ms=float(data.get("execution_time_ms", 0.0)),
            )
            future.set_result(result)

    # ── Real-Time Event & Stream Handling ────────────────────────────────────

    def handle_incoming_event(self, event_type: str, data: Dict[str, Any]) -> None:
        """Propagate push events from phone (e.g. notifications, clipboard, battery)."""
        for listener in self._event_listeners:
            try:
                listener(event_type, data)
            except Exception as e:
                print(f"[DeviceManager] Listener error: {e}")

    def handle_screen_frame(self, frame_bytes: bytes) -> None:
        """Receive binary JPEG frame from phone screen stream."""
        for listener in self._screen_frame_listeners:
            try:
                listener(frame_bytes)
            except Exception as e:
                print(f"[DeviceManager] Screen frame listener error: {e}")

    def add_event_listener(self, fn: Callable[[str, Dict[str, Any]], None]) -> None:
        self._event_listeners.append(fn)

    def add_screen_frame_listener(self, fn: Callable[[bytes], None]) -> None:
        self._screen_frame_listeners.append(fn)


class DeviceRouter:
    """Classifies user intent to determine whether an action belongs to PC, PHONE, or BOTH."""

    @staticmethod
    def resolve_target(text: str) -> str:
        t = text.lower()
        has_phone = any(k in t for k in ("phone", "mobile", "android"))
        has_pc = any(k in t for k in ("pc", "computer", "laptop", "desktop"))

        if any(k in t for k in ("both", "pc and phone", "phone and pc", "sync clipboard", "send this file to my phone", "send this to my phone", "copy this to my phone")):
            return TargetDevice.BOTH
        if has_phone and has_pc:
            return TargetDevice.BOTH
        if has_phone:
            return TargetDevice.PHONE
        return TargetDevice.PC


_GLOBAL_DEVICE_MANAGER: Optional[DeviceManager] = None

def get_device_manager() -> DeviceManager:
    global _GLOBAL_DEVICE_MANAGER
    if _GLOBAL_DEVICE_MANAGER is None:
        _GLOBAL_DEVICE_MANAGER = DeviceManager()
    return _GLOBAL_DEVICE_MANAGER
