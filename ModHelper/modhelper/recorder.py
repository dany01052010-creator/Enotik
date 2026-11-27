"""Постоянная запись экрана (как Fraps) в кольцевой буфер + вырезка клипов."""
import threading
import time
from pathlib import Path

import cv2
import numpy as np


def _make_grabber():
    """Создаётся внутри потока записи (mss привязан к потоку)."""
    try:
        import mss
        sct = mss.mss()
        mon = sct.monitors[1]

        def grab():
            return np.ascontiguousarray(np.array(sct.grab(mon))[:, :, :3])
        return grab
    except Exception:
        from PIL import ImageGrab

        def grab():
            img = ImageGrab.grab().convert("RGB")
            return cv2.cvtColor(np.array(img), cv2.COLOR_RGB2BGR)
        return grab


def capture_screen_image():
    """Скриншот основного экрана в виде PIL-картинки."""
    try:
        import mss
        from PIL import Image
        with mss.mss() as sct:
            shot = sct.grab(sct.monitors[1])
            return Image.frombytes("RGB", shot.size, shot.bgra, "raw", "BGRX")
    except Exception:
        from PIL import ImageGrab
        return ImageGrab.grab().convert("RGB")


class Segment:
    def __init__(self, path, start, frames, fps):
        self.path = Path(path)
        self.start = start
        self.frames = frames
        self.fps = fps

    @property
    def end(self):
        return self.start + self.frames / float(self.fps)


class ScreenRecorder:
    SEG_SEC = 15

    def __init__(self, folder, grab_factory=None):
        self.folder = Path(folder)
        self.fps = 10
        self.scale = 0.5
        self.keep_sec = 600
        self.grab_factory = grab_factory or _make_grabber
        self.segments = []
        self.error = None
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._flush_req = threading.Event()
        self._flush_done = threading.Event()
        self._thread = None

    @staticmethod
    def cleanup_folder(folder):
        for p in Path(folder).glob("seg_*.mp4"):
            try:
                p.unlink()
            except OSError:
                pass

    @property
    def running(self):
        return self._thread is not None and self._thread.is_alive()

    def configure(self, fps, scale, keep_sec):
        self.fps = max(1, int(fps))
        self.scale = min(1.0, max(0.1, float(scale)))
        self.keep_sec = max(30, int(keep_sec))

    def start(self):
        if self.running:
            return
        self.folder.mkdir(parents=True, exist_ok=True)
        self._stop.clear()
        self.error = None
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def stop(self):
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=6)

    def status(self):
        with self._lock:
            n = len(self.segments)
            secs = sum(s.frames / float(s.fps) for s in self.segments)
        if self.error:
            return "Ошибка записи: " + self.error
        state = "идёт запись" if self.running else "остановлена"
        return "Запись %s · в буфере %d сек (%d файлов)" % (state, secs, n)

    # --- поток записи ---
    def _prep(self, frame, size):
        h, w = frame.shape[:2]
        if size is None:
            nw = max(2, int(w * self.scale) // 2 * 2)
            nh = max(2, int(h * self.scale) // 2 * 2)
            size = (nw, nh)
        if (w, h) != size:
            frame = cv2.resize(frame, size, interpolation=cv2.INTER_AREA)
        return frame, size

    def _register(self, path, start, frames):
        if frames <= 0:
            try:
                Path(path).unlink()
            except OSError:
                pass
            return
        with self._lock:
            self.segments.append(Segment(path, start, frames, self.fps))
            limit = time.time() - self.keep_sec
            keep = []
            for s in self.segments:
                if s.end < limit:
                    try:
                        s.path.unlink()
                    except OSError:
                        pass
                else:
                    keep.append(s)
            self.segments = keep

    def _run(self):
        writer = None
        path = None
        seg_start = 0.0
        frames = 0
        size = None
        try:
            grab = self.grab_factory()
            interval = 1.0 / self.fps
            next_t = time.time()
            fourcc = cv2.VideoWriter_fourcc(*"mp4v")
            while not self._stop.is_set():
                if self._flush_req.is_set() and writer is None:
                    self._flush_req.clear()
                    self._flush_done.set()
                now = time.time()
                if now < next_t:
                    time.sleep(min(next_t - now, 0.02))
                    continue
                frame, size = self._prep(grab(), size)
                if writer is None:
                    path = self.folder / ("seg_%d.mp4" % int(now * 1000))
                    writer = cv2.VideoWriter(str(path), fourcc, self.fps, size)
                    if not writer.isOpened():
                        raise RuntimeError("не удалось создать видеофайл (кодек mp4v)")
                    seg_start = now
                    frames = 0
                n = 0
                while next_t <= now and n < self.fps * 2:
                    writer.write(frame)
                    frames += 1
                    next_t += interval
                    n += 1
                if next_t <= now:
                    next_t = now + interval
                if frames / float(self.fps) >= self.SEG_SEC or self._flush_req.is_set():
                    writer.release()
                    writer = None
                    self._register(path, seg_start, frames)
                    if self._flush_req.is_set():
                        self._flush_req.clear()
                        self._flush_done.set()
        except Exception as e:
            self.error = str(e)
        finally:
            if writer is not None:
                writer.release()
                self._register(path, seg_start, frames)
            self._flush_done.set()

    # --- вырезка ---
    def export_clip(self, start_ago, end_ago, out_path):
        """Вырезает кусок: от start_ago до end_ago секунд назад (например 45 -> 15)."""
        if start_ago < end_ago:
            start_ago, end_ago = end_ago, start_ago
        if self.running:
            self._flush_done.clear()
            self._flush_req.set()
            self._flush_done.wait(timeout=4)
        now = time.time()
        t0, t1 = now - start_ago, now - end_ago
        with self._lock:
            segs = [s for s in self.segments if s.end > t0 and s.start < t1]
        if not segs:
            return None
        out_path = Path(out_path)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        writer = None
        size = None
        fourcc = cv2.VideoWriter_fourcc(*"mp4v")
        for s in segs:
            cap = cv2.VideoCapture(str(s.path))
            if not cap.isOpened():
                continue
            i0 = max(0, int((t0 - s.start) * s.fps))
            i1 = min(s.frames, int((t1 - s.start) * s.fps) + 1)
            if i0 > 0:
                cap.set(cv2.CAP_PROP_POS_FRAMES, i0)
            idx = i0
            while idx < i1:
                ok, frame = cap.read()
                if not ok:
                    break
                if writer is None:
                    h, w = frame.shape[:2]
                    size = (w, h)
                    writer = cv2.VideoWriter(str(out_path), fourcc, s.fps, size)
                elif (frame.shape[1], frame.shape[0]) != size:
                    frame = cv2.resize(frame, size)
                writer.write(frame)
                idx += 1
            cap.release()
        if writer is None:
            return None
        writer.release()
        return out_path
