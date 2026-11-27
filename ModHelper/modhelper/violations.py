"""Таблица нарушений (хранится в JSON)."""
import csv
import json
import threading
import time
import uuid

from .config import data_dir


class ViolationStore:
    def __init__(self, path=None):
        self.path = path or (data_dir() / "violations.json")
        self.items = []
        self.listeners = []
        self._lock = threading.RLock()
        self.load()

    # --- хранение ---
    def load(self):
        try:
            with open(self.path, "r", encoding="utf-8") as f:
                data = json.load(f)
            if isinstance(data, list):
                self.items = [d for d in data if isinstance(d, dict) and "id" in d]
        except (FileNotFoundError, ValueError, OSError):
            self.items = []

    def save(self):
        with self._lock:
            tmp = str(self.path) + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(self.items, f, ensure_ascii=False, indent=2)
            import os
            os.replace(tmp, self.path)

    def _changed(self):
        self.save()
        for cb in list(self.listeners):
            try:
                cb()
            except Exception:
                pass

    # --- операции ---
    def get(self, vid):
        with self._lock:
            for it in self.items:
                if it["id"] == vid:
                    return it
        return None

    def add(self, nick, rule="", reason="", punishment="", clip=""):
        """Добавляет запись. Возвращает её, или None если такая уже есть."""
        nick = (nick or "").strip() or "?"
        rule = (rule or "").strip()
        reason = (reason or "").strip()
        with self._lock:
            for it in self.items:
                if (it["nick"].casefold() == nick.casefold()
                        and it["rule"].casefold() == rule.casefold()
                        and it["reason"].casefold() == reason.casefold()):
                    if it.get("hidden"):
                        it["hidden"] = False
                        self._changed()
                    return None
            item = {
                "id": uuid.uuid4().hex[:10],
                "ts": time.time(),
                "nick": nick,
                "rule": rule,
                "reason": reason,
                "punishment": (punishment or "").strip(),
                "clip": clip or "",
                "hidden": False,
            }
            self.items.append(item)
        self._changed()
        return item

    def add_many(self, entries, clip=""):
        added = []
        for e in entries:
            it = self.add(e.get("nick"), e.get("rule"), e.get("reason"), e.get("punishment"), clip)
            if it:
                added.append(it)
        return added

    def update(self, vid, **fields):
        with self._lock:
            it = self.get(vid)
            if not it:
                return
            for k, v in fields.items():
                if k in ("nick", "rule", "reason", "punishment", "clip", "hidden"):
                    it[k] = v
        self._changed()

    def remove(self, vid):
        with self._lock:
            self.items = [it for it in self.items if it["id"] != vid]
        self._changed()

    def clear(self):
        with self._lock:
            self.items = []
        self._changed()

    def hide_nick(self, nick):
        with self._lock:
            for it in self.items:
                if it["nick"] == nick:
                    it["hidden"] = True
        self._changed()

    def show_all(self):
        with self._lock:
            for it in self.items:
                it["hidden"] = False
        self._changed()

    def visible(self):
        with self._lock:
            return [it for it in self.items if not it.get("hidden")]

    def export_csv(self, path):
        with open(path, "w", newline="", encoding="utf-8-sig") as f:
            w = csv.writer(f, delimiter=";")
            w.writerow(["Время", "Ник", "Пункт", "Причина", "Наказание", "Клип"])
            for it in self.items:
                w.writerow([
                    time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(it["ts"])),
                    it["nick"], it["rule"], it["reason"], it["punishment"], it.get("clip", ""),
                ])
