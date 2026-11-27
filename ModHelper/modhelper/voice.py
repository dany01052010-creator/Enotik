"""Прослушивание голоса и перевод в текст (с метками времени)."""
import queue
import threading
import time

import numpy as np


class TranscriptLog:
    def __init__(self, maxlen=3000):
        self.lines = []
        self.maxlen = maxlen
        self._lock = threading.Lock()

    def add(self, text, ts=None):
        ts = ts or time.time()
        with self._lock:
            self.lines.append((ts, text))
            if len(self.lines) > self.maxlen:
                self.lines = self.lines[-self.maxlen:]

    def recent(self, seconds):
        limit = time.time() - seconds
        with self._lock:
            return [(t, x) for t, x in self.lines if t >= limit]

    def between(self, t0, t1):
        with self._lock:
            return [(t, x) for t, x in self.lines if t0 <= t <= t1]

    @staticmethod
    def format(lines):
        return "\n".join("[%s] %s" % (time.strftime("%H:%M:%S", time.localtime(t)), x) for t, x in lines)

    def as_text(self, seconds):
        return self.format(self.recent(seconds))


def list_input_devices():
    """Список (индекс, название) устройств записи. Бросает RuntimeError, если нет sounddevice."""
    try:
        import sounddevice as sd
    except Exception as e:
        raise RuntimeError("не установлен sounddevice (%s)" % e)
    out = []
    for i, d in enumerate(sd.query_devices()):
        if d.get("max_input_channels", 0) > 0:
            out.append((i, d["name"]))
    return out


class VoiceListener:
    """Слушает выбранное устройство, режет речь по паузам и отправляет в распознавание.

    Чтобы слышать игроков из игры, выбери устройство «Стерео микшер / Stereo Mix»
    (или виртуальный кабель), а не микрофон.
    """
    TARGET_RATE = 16000

    def __init__(self, on_text, device=None, lang="ru-RU", on_error=None):
        self.on_text = on_text
        self.on_error = on_error or (lambda m: None)
        self.device = device
        self.lang = lang
        self._stop = threading.Event()
        self._threads = []
        self._jobs = queue.Queue()
        self.running = False
        self._last_err = 0

    def start(self):
        if self.running:
            return
        try:
            import sounddevice  # noqa: F401
            import speech_recognition  # noqa: F401
        except Exception as e:
            raise RuntimeError("не хватает библиотек для голоса: %s" % e)
        self._stop.clear()
        self.running = True
        for target in (self._capture, self._recognize):
            t = threading.Thread(target=target, daemon=True)
            t.start()
            self._threads.append(t)

    def stop(self):
        self._stop.set()
        self.running = False
        self._jobs.put(None)
        self._threads = []

    def _err(self, msg):
        if time.time() - self._last_err > 20:
            self._last_err = time.time()
            self.on_error(msg)

    # --- захват и поиск речи ---
    def _open_stream(self, sd, cb):
        info = sd.query_devices(self.device if self.device is not None else None, "input")
        channels = 1 if info["max_input_channels"] < 2 else 2
        last = None
        for rate in (self.TARGET_RATE, int(info["default_samplerate"])):
            for ch in (1, channels):
                try:
                    stream = sd.InputStream(samplerate=rate, channels=ch, dtype="int16", device=self.device,
                                            blocksize=int(rate * 0.1), callback=cb)
                    stream.start()
                    return stream, rate, ch
                except Exception as e:
                    last = e
        raise RuntimeError("не удалось открыть устройство записи: %s" % last)

    def _capture(self):
        try:
            import sounddevice as sd
            q = queue.Queue()

            def cb(indata, frames, t, status):
                q.put(indata.copy())

            stream, rate, ch = self._open_stream(sd, cb)
        except Exception as e:
            self.running = False
            self._err(str(e))
            return
        floor = 150.0
        pre = []
        buf = []
        speaking = False
        silence = 0
        started = 0.0
        try:
            while not self._stop.is_set():
                try:
                    block = q.get(timeout=0.3)
                except queue.Empty:
                    continue
                mono = block.mean(axis=1) if block.ndim > 1 else block.astype(np.float32)
                mono = mono.astype(np.float32).reshape(-1)
                rms = float(np.sqrt(np.mean(mono ** 2))) if mono.size else 0.0
                thr = max(250.0, floor * 2.5)
                voiced = rms > thr
                if not speaking:
                    floor = 0.97 * floor + 0.03 * rms
                    pre.append(mono)
                    pre = pre[-3:]
                    if voiced:
                        speaking = True
                        started = time.time()
                        buf = list(pre)
                        silence = 0
                else:
                    buf.append(mono)
                    silence = 0 if voiced else silence + 1
                    if silence >= 8 or time.time() - started > 12:
                        audio = np.concatenate(buf)
                        speaking = False
                        buf = []
                        pre = []
                        if audio.size > rate * 0.5:
                            self._jobs.put((time.time() - audio.size / float(rate), audio, rate))
        finally:
            try:
                stream.stop()
                stream.close()
            except Exception:
                pass

    # --- распознавание ---
    def _recognize(self):
        import speech_recognition as sr
        rec = sr.Recognizer()
        while not self._stop.is_set():
            job = self._jobs.get()
            if job is None:
                break
            ts, audio, rate = job
            if rate != self.TARGET_RATE:
                n = int(audio.size * self.TARGET_RATE / float(rate))
                audio = np.interp(np.linspace(0, audio.size - 1, n), np.arange(audio.size), audio)
            raw = np.clip(audio, -32768, 32767).astype(np.int16).tobytes()
            try:
                text = rec.recognize_google(sr.AudioData(raw, self.TARGET_RATE, 2), language=self.lang)
            except sr.UnknownValueError:
                continue
            except sr.RequestError:
                self._err("Нет связи с сервисом распознавания речи (нужен интернет).")
                continue
            except Exception as e:
                self._err("Ошибка распознавания: %s" % e)
                continue
            if text.strip():
                self.on_text(text.strip(), ts)
