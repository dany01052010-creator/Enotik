"""Работа с Claude API: чат, разбор скриншотов, поиск нарушений в ответе."""
import base64
import io
import json
import re

import requests
from PIL import Image

API_URL = "https://api.anthropic.com/v1/messages"
API_VERSION = "2023-06-01"


class AIError(Exception):
    pass


BASE_PROMPT = """Ты — помощник модератора онлайн-игры. Отвечай по-русски, коротко и по делу.

Твоя задача: по скриншотам, тексту жалобы и (если есть) распознанной речи найти игроков-нарушителей, определить пункт правил, который нарушен, и предложить наказание СТРОГО по таблице наказаний из правил ниже.

Важные принципы:
- Ник читай внимательно, с точным регистром и символами. Если ник не виден или неразборчив — пиши "?" и попроси уточнить.
- Называй только те нарушения, которые реально видны или слышны в материалах. Ничего не придумывай.
- Если подходящего пункта в правилах нет — так и скажи, не выдумывай пункт.
- Срок наказания бери из правил. Если срок зависит от повторности — укажи минимальный и напиши об этом в причине.
- Окончательное решение принимает модератор-человек; ты только подсказываешь.

Формат ответа: 1–4 коротких предложения для модератора. Если нашёл нарушения, в конце добавь блок JSON строго такого вида (и только его, без комментариев внутри):

```json
{"violations": [{"nick": "Ник", "rule": "3.2", "reason": "кратко: что сделал", "punishment": "мут 30 минут"}]}
```

Если нарушений нет или данных не хватает — JSON-блок не добавляй."""


def build_system(rules, template):
    parts = [BASE_PROMPT]
    rules = (rules or "").strip()
    parts.append("ПРАВИЛА ИГРЫ И НАКАЗАНИЯ:\n" + (
        rules if rules else "(правила ещё не добавлены — скажи модератору, что их нужно вставить во вкладке «Правила»)"))
    template = (template or "").strip()
    if template:
        parts.append("КАК МОДЕРАТОР ОФОРМЛЯЕТ ЖАЛОБЫ (образец, следуй ему в поле reason):\n" + template)
    return "\n\n".join(parts)


def encode_image(img, max_side=1568):
    img = img.convert("RGB")
    w, h = img.size
    scale = min(1.0, max_side / float(max(w, h)))
    if scale < 1.0:
        img = img.resize((max(1, int(w * scale)), max(1, int(h * scale))), Image.LANCZOS)
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=88)
    return base64.b64encode(buf.getvalue()).decode("ascii")


def _image_block(img):
    return {"type": "image",
            "source": {"type": "base64", "media_type": "image/jpeg", "data": encode_image(img)}}


def _error_text(resp):
    try:
        msg = resp.json().get("error", {}).get("message", "")
    except Exception:
        msg = resp.text[:200]
    code = resp.status_code
    if code in (401, 403):
        return "API-ключ не подошёл (ошибка %d). Проверь ключ во вкладке «Настройки»." % code
    if code == 429:
        return "Слишком много запросов или закончился лимит. Подожди немного. " + msg
    if code == 529 or code >= 500:
        return "Сервер Claude сейчас перегружен (%d). Попробуй ещё раз." % code
    return "Ошибка API %d: %s" % (code, msg)


class AIClient:
    def __init__(self, settings):
        self.settings = settings

    # --- низкий уровень ---
    def ask(self, system, messages, max_tokens=1500):
        key = (self.settings.get("api_key") or "").strip()
        if not key:
            raise AIError("Не указан API-ключ. Открой вкладку «Настройки» и вставь ключ Anthropic.")
        body = {
            "model": self.settings.get("model") or "claude-sonnet-5-5",
            "max_tokens": max_tokens,
            "messages": messages,
        }
        if system:
            body["system"] = system
        headers = {"x-api-key": key, "anthropic-version": API_VERSION, "content-type": "application/json"}
        try:
            resp = requests.post(API_URL, headers=headers, json=body, timeout=120)
        except requests.RequestException as e:
            raise AIError("Нет связи с сервером Claude: %s" % e)
        if resp.status_code != 200:
            raise AIError(_error_text(resp))
        try:
            data = resp.json()
        except ValueError:
            raise AIError("Сервер вернул непонятный ответ.")
        text = "".join(b.get("text", "") for b in data.get("content", []) if b.get("type") == "text")
        return text.strip()

    # --- сообщения ---
    def to_messages(self, history, transcript=""):
        hist = history[-30:]
        with_imgs = [i for i, m in enumerate(hist) if m.get("images")]
        keep = set(with_imgs[-4:])           # картинки шлём только из последних 4 сообщений
        msgs = []
        for i, m in enumerate(hist):
            blocks = []
            imgs = m.get("images") or []
            if m["role"] == "user" and i in keep:
                for n, img in enumerate(imgs, 1):
                    if len(imgs) > 1:
                        blocks.append({"type": "text", "text": "Скриншот %d:" % n})
                    blocks.append(_image_block(img))
            text = m.get("text") or ""
            if imgs and i not in keep:
                text += "\n[к сообщению были приложены скриншоты, их уже нет в контексте]"
            if text.strip():
                blocks.append({"type": "text", "text": text})
            if not blocks:
                continue
            if msgs and msgs[-1]["role"] == m["role"]:
                msgs[-1]["content"].extend(blocks)
            else:
                msgs.append({"role": m["role"], "content": blocks})
        while msgs and msgs[0]["role"] != "user":
            msgs.pop(0)
        if transcript and msgs and msgs[-1]["role"] == "user":
            msgs[-1]["content"].append({
                "type": "text",
                "text": "Недавняя речь из голосового чата (распознана автоматически, может содержать ошибки, "
                        "кто именно говорит — неизвестно):\n" + transcript})
        return msgs

    def chat(self, history, transcript=""):
        system = build_system(self.settings.get("rules_text"), self.settings.get("complaint_template"))
        return self.ask(system, self.to_messages(history, transcript))

    def transcribe_rules(self, img):
        msgs = [{"role": "user", "content": [
            _image_block(img),
            {"type": "text", "text": "Перепиши весь текст со скриншота дословно, сохраняя нумерацию пунктов и "
                                     "сроки наказаний. Без комментариев, только текст."}]}]
        return self.ask("Ты точно переписываешь текст со скриншотов.", msgs, max_tokens=4000)

    def ping(self):
        return self.ask("", [{"role": "user", "content": [{"type": "text", "text": "Ответь одним словом: ок"}]}],
                        max_tokens=20)


# --- разбор ответа ---
_FENCE = re.compile(r"```(?:json)?\s*(.*?)```", re.S | re.I)
_BARE = re.compile(r'\{\s*"violations"\s*:')


def _parse_obj(s):
    s = s.strip()
    start = s.find("{")
    if start < 0:
        return None
    try:
        obj, _ = json.JSONDecoder().raw_decode(s[start:])
        return obj
    except ValueError:
        return None


def extract_violations(text):
    candidates = [m.group(1) for m in _FENCE.finditer(text or "")]
    if not candidates:
        m = _BARE.search(text or "")
        if m:
            candidates = [text[m.start():]]
    out = []
    for c in candidates:
        obj = _parse_obj(c)
        items = obj.get("violations") if isinstance(obj, dict) else None
        if not isinstance(items, list):
            continue
        for it in items:
            if isinstance(it, dict) and (it.get("nick") or it.get("reason")):
                out.append({k: str(it.get(k, "") or "").strip()
                            for k in ("nick", "rule", "reason", "punishment")})
    return out


def strip_json(text):
    """Убирает JSON-блок из текста, чтобы не показывать его в чате."""
    t = _FENCE.sub(lambda m: "" if '"violations"' in m.group(1) else m.group(0), text or "")
    m = _BARE.search(t)
    if m:
        try:
            _, end = json.JSONDecoder().raw_decode(t[m.start():])
            t = t[:m.start()] + t[m.start() + end:]
        except ValueError:
            pass
    return t.strip()
