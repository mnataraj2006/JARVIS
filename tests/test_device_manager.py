"""
Unit tests for DeviceManager, DeviceRouter, and Mobile Agent command routing in JARVIS.
"""
import asyncio
import json
import os
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import pytest

from core.device_manager import (
    DeviceManager,
    DeviceRouter,
    DeviceType,
    TargetDevice,
    get_device_manager,
)
from core.fast_router import get_fast_router, CommandClass


def test_device_router_resolution():
    """Verify target device intent classification."""
    # PC by default
    assert DeviceRouter.resolve_target("open chrome") == TargetDevice.PC
    assert DeviceRouter.resolve_target("volume up") == TargetDevice.PC
    assert DeviceRouter.resolve_target("lock computer") == TargetDevice.PC

    # Phone explicit targets
    assert DeviceRouter.resolve_target("open whatsapp on my phone") == TargetDevice.PHONE
    assert DeviceRouter.resolve_target("launch spotify on phone") == TargetDevice.PHONE
    assert DeviceRouter.resolve_target("what is my phone battery") == TargetDevice.PHONE
    assert DeviceRouter.resolve_target("take screenshot on phone") == TargetDevice.PHONE
    assert DeviceRouter.resolve_target("phone volume up") == TargetDevice.PHONE

    # Cross-device / both
    assert DeviceRouter.resolve_target("send this file to my phone") == TargetDevice.BOTH
    assert DeviceRouter.resolve_target("sync clipboard") == TargetDevice.BOTH
    assert DeviceRouter.resolve_target("lock pc and phone") == TargetDevice.BOTH


def test_fast_router_phone_commands():
    """Verify FastRouter parses phone commands in sub-10ms with zero LLM escalation."""
    router = get_fast_router()

    # Phone app launch
    res = router.route("open youtube on my phone")
    assert res.matched is True
    assert res.action == "phone_launch_app"
    assert res.parameters.get("app_name") == "youtube"
    assert res.target_device == TargetDevice.PHONE
    assert res.routing_latency_ms < 10.0

    # Phone battery
    res = router.route("phone battery")
    assert res.matched is True
    assert res.action == "phone_battery"
    assert res.target_device == TargetDevice.PHONE

    res = router.route("what is my phone battery level")
    assert res.matched is True
    assert res.action == "phone_battery"

    # Phone screenshot
    res = router.route("take screenshot on phone")
    assert res.matched is True
    assert res.action == "phone_screenshot"
    assert res.target_device == TargetDevice.PHONE

    # Phone volume
    res = router.route("phone volume up")
    assert res.matched is True
    assert res.action == "phone_volume_up"
    assert res.target_device == TargetDevice.PHONE

    res = router.route("mute phone")
    assert res.matched is True
    assert res.action == "phone_mute"

    # Phone navigation & security
    res = router.route("lock my phone")
    assert res.matched is True
    assert res.action == "phone_lock"

    res = router.route("go home on phone")
    assert res.matched is True
    assert res.action == "phone_home"

    res = router.route("go back on phone")
    assert res.matched is True
    assert res.action == "phone_back"

    # Ring phone & notifications
    res = router.route("find my phone")
    assert res.matched is True
    assert res.action == "phone_ring"

    res = router.route("read phone notifications")
    assert res.matched is True
    assert res.action == "phone_notifications"

    # Clipboard sync
    res = router.route("sync clipboard")
    assert res.matched is True
    assert res.action == "clipboard_sync"
    assert res.target_device == TargetDevice.BOTH


def test_device_manager_registration():
    """Verify device registration, listing, and heartbeat."""
    mgr = DeviceManager()

    # PC should be pre-registered
    devices = mgr.list_devices()
    assert any(d["id"] == "pc-main" for d in devices)

    # Register mock Android phone
    phone_info = {
        "id": "phone-pixel8",
        "name": "Pixel 8 Pro",
        "type": DeviceType.ANDROID,
        "os": "Android",
        "os_version": "14",
        "manufacturer": "Google",
        "model": "Pixel 8 Pro",
        "battery_level": 88,
        "is_charging": False,
        "network": "WiFi",
        "capabilities": ["apps", "accessibility", "screen_capture", "volume"],
    }
    dev = mgr.register_device(phone_info)
    assert dev.id == "phone-pixel8"
    assert dev.online is True

    primary = mgr.get_primary_phone()
    assert primary is not None
    assert primary.id == "phone-pixel8"
    assert "screen_capture" in primary.capabilities

    # Update heartbeat
    mgr.update_heartbeat("phone-pixel8", {"battery": {"level": 89, "charging": True}})
    updated = mgr.get_device("phone-pixel8")
    assert updated.battery_level == 89
    assert updated.is_charging is True

    # Disconnect
    mgr.unregister_device("phone-pixel8")
    assert mgr.get_device("phone-pixel8").online is False


@pytest.mark.asyncio
async def test_device_manager_rpc_no_phone():
    """Verify sending command when no phone is connected returns informative error."""
    mgr = DeviceManager()
    res = await mgr.send_command("launch_app", {"app_name": "WhatsApp"})
    assert res.success is False
    assert "No Android phone is currently connected" in res.error


@pytest.mark.asyncio
async def test_device_manager_rpc_roundtrip():
    """Verify asynchronous JSON-RPC command dispatch and response correlation."""
    mgr = DeviceManager()

    class MockWebSocket:
        def __init__(self):
            self.sent_frames = []

        async def send_text(self, text: str):
            self.sent_frames.append(text)

    mock_ws = MockWebSocket()
    phone_info = {
        "id": "test-phone",
        "name": "Test Phone",
        "type": DeviceType.ANDROID,
        "capabilities": ["apps"],
    }
    mgr.register_device(phone_info, ws=mock_ws)

    # Launch background task that answers the command
    async def _simulate_phone_reply():
        await asyncio.sleep(0.05)
        assert len(mock_ws.sent_frames) > 0
        last_frame = json.loads(mock_ws.sent_frames[-1])
        cmd_id = last_frame["id"]
        action = last_frame["action"]

        mgr.handle_command_response({
            "type": "response",
            "id": cmd_id,
            "device_id": "test-phone",
            "success": True,
            "result": {"message": f"Successfully launched {action}"},
            "execution_time_ms": 12.5,
        })

    reply_task = asyncio.create_task(_simulate_phone_reply())

    res = await mgr.send_command("launch_app", {"app_name": "Spotify"}, timeout=2.0)
    await reply_task

    assert res.success is True
    assert res.data == {"message": "Successfully launched launch_app"}
    assert res.execution_time_ms == 12.5
