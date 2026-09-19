"""
Intelligent Short-Lived Caching & App Indexing for JARVIS.

Provides:
- General TTL in-memory caching
- Instant App Index (scans Start Menu/Registry/ProgramFiles once, cached in memory)
- Screenshot caching with perceptual hash & age validity
- System info short TTL cache
"""
from __future__ import annotations

import os
import sys
import time
import shutil
import platform
import threading
from pathlib import Path
from typing import Any, Callable, Dict, Optional, Tuple

_OS = platform.system()


class CacheManager:
    """Thread-safe in-memory cache with Time-To-Live (TTL)."""

    def __init__(self):
        self._cache: Dict[str, Tuple[Any, float]] = {}
        self._lock = threading.Lock()

    def get(self, key: str) -> Optional[Any]:
        with self._lock:
            item = self._cache.get(key)
            if item is None:
                return None
            val, expiry = item
            if time.monotonic() > expiry:
                del self._cache[key]
                return None
            return val

    def set(self, key: str, value: Any, ttl_seconds: float) -> None:
        with self._lock:
            self._cache[key] = (value, time.monotonic() + ttl_seconds)

    def get_or_compute(self, key: str, compute_fn: Callable[[], Any], ttl_seconds: float) -> Any:
        val = self.get(key)
        if val is not None:
            return val
        val = compute_fn()
        self.set(key, val, ttl_seconds)
        return val

    def invalidate(self, key: str) -> None:
        with self._lock:
            self._cache.pop(key, None)

    def clear(self) -> None:
        with self._lock:
            self._cache.clear()


class AppIndex:
    """Pre-built application index for Windows, macOS, and Linux to avoid Start Menu typing delays."""

    def __init__(self):
        self._index: Dict[str, str] = {}
        self._initialized = False
        self._lock = threading.Lock()
        self._start_background_scan()

    def _start_background_scan(self) -> None:
        t = threading.Thread(target=self._scan, daemon=True, name="AppIndexScan")
        t.start()

    def _scan(self) -> None:
        entries: Dict[str, str] = {}
        if _OS == "Windows":
            # 1. Common executable search paths
            roots = [
                os.environ.get("PROGRAMFILES", r"C:\Program Files"),
                os.environ.get("PROGRAMFILES(X86)", r"C:\Program Files (x86)"),
                os.environ.get("LOCALAPPDATA", ""),
                os.environ.get("APPDATA", ""),
            ]

            # 2. Check Start Menu .lnk shortcuts
            start_menu_dirs = [
                Path(os.environ.get("APPDATA", "")) / r"Microsoft\Windows\Start Menu\Programs",
                Path(os.environ.get("PROGRAMDATA", r"C:\ProgramData")) / r"Microsoft\Windows\Start Menu\Programs",
            ]
            for s_dir in start_menu_dirs:
                if s_dir.exists():
                    try:
                        for item in s_dir.rglob("*.lnk"):
                            name = item.stem.lower()
                            entries[name] = str(item)
                    except Exception:
                        pass

            # 3. Fast check for prominent applications
            common_exes = {
                "chrome": [
                    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
                    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
                ],
                "edge": [
                    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
                    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
                ],
                "code": [
                    os.path.join(os.environ.get("LOCALAPPDATA", ""), r"Programs\Microsoft VS Code\Code.exe"),
                    r"C:\Program Files\Microsoft VS Code\Code.exe",
                ],
                "spotify": [
                    os.path.join(os.environ.get("APPDATA", ""), r"Spotify\Spotify.exe"),
                ],
                "whatsapp": [
                    os.path.join(os.environ.get("LOCALAPPDATA", ""), r"WhatsApp\WhatsApp.exe"),
                ],
                "discord": [
                    os.path.join(os.environ.get("LOCALAPPDATA", ""), r"Discord\Update.exe --processStart Discord.exe"),
                ],
                "vlc": [
                    r"C:\Program Files\VideoLAN\VLC\vlc.exe",
                    r"C:\Program Files (x86)\VideoLAN\VLC\vlc.exe",
                ],
                "notepad": ["notepad.exe"],
                "calc": ["calc.exe"],
                "cmd": ["cmd.exe"],
                "powershell": ["powershell.exe"],
                "terminal": ["wt.exe"],
                "explorer": ["explorer.exe"],
                "taskmgr": ["taskmgr.exe"],
            }
            for app_k, paths in common_exes.items():
                for p in paths:
                    if os.path.exists(p) or shutil.which(p):
                        entries[app_k] = p
                        break

        elif _OS == "Darwin":
            app_dirs = [Path("/Applications"), Path.home() / "Applications"]
            for a_dir in app_dirs:
                if a_dir.exists():
                    for item in a_dir.glob("*.app"):
                        entries[item.stem.lower()] = item.stem

        with self._lock:
            self._index.update(entries)
            self._initialized = True

    def find_app(self, app_name: str) -> Optional[str]:
        target = app_name.lower().strip()
        with self._lock:
            # 1. Exact match
            if target in self._index:
                return self._index[target]
            # 2. Substring match
            for k, path in self._index.items():
                if target == k or target in k or k in target:
                    return path
            # 3. Path / which match
            w = shutil.which(target)
            if w:
                return w
        return None

    def is_ready(self) -> bool:
        return self._initialized


class ScreenshotCache:
    """Reuses screenshots captured within a short TTL if the screen hash is unchanged."""

    def __init__(self, max_age_seconds: float = 1.5):
        self.max_age = max_age_seconds
        self._cached_bytes: Optional[bytes] = None
        self._cached_mime: str = "image/jpeg"
        self._cached_time: float = 0.0
        self._cached_hash: Optional[int] = None
        self._lock = threading.Lock()

    def get_valid_screenshot(self, current_hash: Optional[int] = None) -> Optional[Tuple[bytes, str]]:
        with self._lock:
            if not self._cached_bytes:
                return None
            now = time.monotonic()
            if (now - self._cached_time) > self.max_age:
                return None
            if current_hash is not None and self._cached_hash is not None:
                if current_hash != self._cached_hash:
                    return None
            return self._cached_bytes, self._cached_mime

    def store(self, img_bytes: bytes, mime_type: str = "image/jpeg", screen_hash: Optional[int] = None) -> None:
        with self._lock:
            self._cached_bytes = img_bytes
            self._cached_mime = mime_type
            self._cached_time = time.monotonic()
            self._cached_hash = screen_hash


# Singletons
_CACHE = CacheManager()
_APP_INDEX = AppIndex()
_SCREEN_CACHE = ScreenshotCache(max_age_seconds=1.5)

def get_cache() -> CacheManager:
    return _CACHE

def get_app_index() -> AppIndex:
    return _APP_INDEX

def get_screenshot_cache() -> ScreenshotCache:
    return _SCREEN_CACHE
