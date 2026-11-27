"""Настройки программы и пути к папкам."""
import json
import os
import threading
from pathlib import Path

DEFAULTS = {
    "api_key": "",
    "model": "claude-sonnet-5-5",
    "rules_text": "",
    "complaint_template": "",
    "overlay_mode": "compact",      # compact | table | dot
    "overlay_pos": [40, 40],
    "overlay_alpha": 0.93,
    "overlay_voice": True,
    "fps": 10,
    "scale": 0.5,
    "buffer_minutes": 10,
    "clip_before": 40,              # сколько секунд до жалобы вырезать в клип
    "voice_device": "",
    "voice_lang": "ru-RU",
    "hotkey_complaint": "<f8>",
    "hotkey_overlay": "<f9>",
    "hotkey_clip": "<f10>",
}


def data_dir():
    if os.environ.get("APPDATA"):
        p = Path(os.environ["APPDATA"]) / "ModHelper"
    else:
        p = Path.home() / ".modhelper"
    p.mkdir(parents=True, exist_ok=True)
    return p


def clips_dir():
    p = data_dir() / "clips"
    p.mkdir(parents=True, exist_ok=True)
    return p


def buffer_dir():
    p = data_dir() / "buffer"
    p.mkdir(parents=True, exist_ok=True)
    return p


class Settings:
    def __init__(self, path=None):
        self.path = Path(path) if path else data_dir() / "settings.json"
        self._lock = threading.Lock()
        self.data = dict(DEFAULTS)
        self.load()

    def load(self):
        try:
            loaded = json.loads(self.path.read_text(encoding="utf-8"))
            if isinstance(loaded, dict):
                self.data.update(loaded)
        except (FileNotFoundError, ValueError, OSError):
            pass

    def save(self):
        with self._lock:
            tmp = self.path.with_suffix(".tmp")
            tmp.write_text(json.dumps(self.data, ensure_ascii=False, indent=2), encoding="utf-8")
            tmp.replace(self.path)

    def get(self, key):
        return self.data.get(key, DEFAULTS.get(key))

    def set(self, key, value, save=True):
        self.data[key] = value
        if save:
            self.save()
