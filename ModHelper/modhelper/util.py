"""Общие мелочи: цвета, буфер обмена, открытие папок."""
import ctypes
import os
import subprocess
import sys
import time

BG = "#1b1c21"
PANEL = "#24262d"
PANEL2 = "#2f323b"
FG = "#e9e9ee"
MUTED = "#8d919c"
ACCENT = "#5b9dff"
WARN = "#ffb454"
DANGER = "#ef5b5b"
OK = "#5fd38d"


def enable_dpi_awareness():
    """Чтобы координаты в программе совпадали с реальными пикселями экрана."""
    if sys.platform == "win32":
        try:
            ctypes.windll.shcore.SetProcessDpiAwareness(2)
        except Exception:
            try:
                ctypes.windll.user32.SetProcessDPIAware()
            except Exception:
                pass


def grab_clipboard_image():
    """Картинка из буфера обмена (Win+Shift+S, PrintScreen) или None."""
    try:
        from PIL import Image, ImageGrab
        data = ImageGrab.grabclipboard()
    except Exception:
        return None
    if data is None:
        return None
    if isinstance(data, Image.Image):
        return data.convert("RGB")
    if isinstance(data, (list, tuple)):          # скопированные файлы
        for p in data:
            try:
                return Image.open(p).convert("RGB")
            except Exception:
                continue
    return None


def open_path(path):
    try:
        if sys.platform == "win32":
            os.startfile(str(path))
        elif sys.platform == "darwin":
            subprocess.Popen(["open", str(path)])
        else:
            subprocess.Popen(["xdg-open", str(path)])
    except Exception:
        pass


def fmt_time(ts):
    return time.strftime("%H:%M:%S", time.localtime(ts))


def rule_label(rule):
    rule = (rule or "").strip()
    if not rule:
        return ""
    if rule.lower().startswith(("п", "p", "§", "ст")):
        return rule
    return "п. " + rule
