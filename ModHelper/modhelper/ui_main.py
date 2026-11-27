"""Главное окно: чат, правила, таблица, запись и голос, настройки."""
import os
import queue
import threading
import time
import traceback
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

from PIL import ImageTk

from .ai import AIClient, AIError, extract_violations, strip_json
from .complaint import ComplaintWindow
from .config import Settings, buffer_dir, clips_dir
from .overlay import Overlay
from .recorder import ScreenRecorder, capture_screen_image
from .util import (ACCENT, BG, DANGER, FG, MUTED, OK, PANEL, PANEL2, WARN, fmt_time, grab_clipboard_image,
                   open_path, rule_label)
from .violations import ViolationStore
from .voice import TranscriptLog, VoiceListener, list_input_devices

WELCOME = (
    "Привет! Как пользоваться:\n"
    "1. Вкладка «Правила» — вставь правила игры и сроки наказаний (можно со скриншота).\n"
    "2. Вкладка «Настройки» — вставь API-ключ Anthropic.\n"
    "3. В игре (режим «в окне» или «без рамки») нажми F9 — появится маленькое окно, F8 — жалоба.\n"
    "4. Здесь в чате можно вставить скриншот (Ctrl+V) или написать текст — я найду ники, пункт правил и срок.")


def edit_dialog(master, title, initial=None):
    initial = initial or {}
    win = tk.Toplevel(master)
    win.title(title)
    win.configure(bg=BG)
    win.transient(master)
    win.grab_set()
    fields = [("nick", "Ник игрока"), ("rule", "Пункт правил"), ("reason", "Причина"),
              ("punishment", "Наказание (срок)")]
    vars_ = {}
    for r, (k, label) in enumerate(fields):
        ttk.Label(win, text=label).grid(row=r, column=0, sticky="w", padx=12, pady=6)
        v = tk.StringVar(value=initial.get(k, ""))
        vars_[k] = v
        ttk.Entry(win, textvariable=v, width=50).grid(row=r, column=1, padx=12, pady=6)
    result = {}

    def ok():
        result.update({k: v.get().strip() for k, v in vars_.items()})
        win.destroy()

    row = ttk.Frame(win)
    row.grid(row=len(fields), column=0, columnspan=2, pady=10)
    ttk.Button(row, text="Сохранить", style="Accent.TButton", command=ok).pack(side="left", padx=6)
    ttk.Button(row, text="Отмена", command=win.destroy).pack(side="left", padx=6)
    win.wait_window()
    return result or None


class MainWindow(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("ModHelper — помощник модератора")
        self.geometry("1000x720")
        self.minsize(860, 580)
        self.settings = Settings()
        self.violations = ViolationStore()
        self.transcript = TranscriptLog()
        self.ai = AIClient(self.settings)
        ScreenRecorder.cleanup_folder(buffer_dir())
        self.recorder = ScreenRecorder(buffer_dir())
        self.voice = None
        self.history = []
        self.attachments = []
        self._thumbs = []
        self._busy = False
        self._q = queue.Queue()
        self._hk = None
        self._complaint = None
        self._complaint_from_button = False
        self.status_var = tk.StringVar(value="Готов")
        self._style()
        self._build()
        self.overlay = Overlay(self)
        self.violations.listeners.append(lambda: self.post(self.refresh_all))
        self.protocol("WM_DELETE_WINDOW", self.on_close)
        self.after(80, self._pump)
        self.after(1000, self._tick)
        self._setup_hotkeys()
        self.refresh_all()
        self.chat_append("system", WELCOME)
        if not (self.settings.get("api_key") or "").strip():
            self.nb.select(self.tab_settings)

    # ------------------------------------------------------------------ служебное
    def post(self, fn, *args):
        self._q.put((fn, args))

    def _pump(self):
        try:
            while True:
                fn, args = self._q.get_nowait()
                try:
                    fn(*args)
                except Exception:
                    traceback.print_exc()
        except queue.Empty:
            pass
        self.after(80, self._pump)

    def _tick(self):
        self.rec_status.config(text=self.recorder.status())
        if self.recorder.running:
            self.rec_btn.config(text="■ Остановить запись")
        else:
            self.rec_btn.config(text="● Запустить запись")
        self.after(1000, self._tick)

    def set_status(self, text):
        self.status_var.set(text)

    def _style(self):
        st = ttk.Style(self)
        st.theme_use("clam")
        self.configure(bg=BG)
        st.configure(".", background=BG, foreground=FG, fieldbackground=PANEL, bordercolor=PANEL2,
                     lightcolor=PANEL2, darkcolor=PANEL2, troughcolor=PANEL, font=("Segoe UI", 10))
        st.configure("TNotebook", background=BG, borderwidth=0)
        st.configure("TNotebook.Tab", background=PANEL, foreground=MUTED, padding=(16, 8))
        st.map("TNotebook.Tab", background=[("selected", PANEL2)], foreground=[("selected", FG)])
        st.configure("TButton", background=PANEL2, foreground=FG, padding=(10, 6), borderwidth=0)
        st.map("TButton", background=[("active", "#3d4150")])
        st.configure("Accent.TButton", background=ACCENT, foreground="#ffffff")
        st.map("Accent.TButton", background=[("active", "#7ab0ff")])
        st.configure("TLabel", background=BG, foreground=FG)
        st.configure("Muted.TLabel", foreground=MUTED)
        st.configure("TFrame", background=BG)
        st.configure("TLabelframe", background=BG, bordercolor=PANEL2)
        st.configure("TLabelframe.Label", background=BG, foreground=MUTED)
        st.configure("TCheckbutton", background=BG, foreground=FG)
        st.configure("TEntry", fieldbackground=PANEL, foreground=FG, insertcolor=FG)
        st.configure("TCombobox", fieldbackground=PANEL, foreground=FG, arrowcolor=FG)
        st.configure("TSpinbox", fieldbackground=PANEL, foreground=FG, arrowcolor=FG)
        st.configure("Treeview", background=PANEL, fieldbackground=PANEL, foreground=FG, rowheight=28, borderwidth=0)
        st.configure("Treeview.Heading", background=PANEL2, foreground=FG, relief="flat", padding=6)
        st.map("Treeview", background=[("selected", ACCENT)], foreground=[("selected", "#ffffff")])
        self.option_add("*TCombobox*Listbox.background", PANEL)
        self.option_add("*TCombobox*Listbox.foreground", FG)

    def _text(self, parent, **kw):
        return tk.Text(parent, bg=PANEL, fg=FG, insertbackground=FG, relief="flat", padx=10, pady=8,
                       font=("Segoe UI", 10), selectbackground=ACCENT, **kw)

    # ------------------------------------------------------------------ сборка интерфейса
    def _build(self):
        top = ttk.Frame(self)
        top.pack(fill="x", padx=10, pady=(10, 4))
        ttk.Button(top, text="🪟 Мини-окно (F9)", command=self.toggle_overlay).pack(side="left", padx=(0, 6))
        ttk.Button(top, text="🚩 Жалоба (F8)", style="Accent.TButton",
                   command=lambda: self.start_complaint(from_button=True)).pack(side="left", padx=6)
        ttk.Button(top, text="✂ Клип (F10)", command=self.quick_clip).pack(side="left", padx=6)
        self.nb = ttk.Notebook(self)
        self.nb.pack(fill="both", expand=True, padx=10, pady=6)
        self.tab_chat = ttk.Frame(self.nb)
        self.tab_rules = ttk.Frame(self.nb)
        self.tab_table = ttk.Frame(self.nb)
        self.tab_rec = ttk.Frame(self.nb)
        self.tab_settings = ttk.Frame(self.nb)
        for tab, name in ((self.tab_chat, "💬 Чат"), (self.tab_rules, "📜 Правила"), (self.tab_table, "📋 Таблица"),
                          (self.tab_rec, "🎥 Запись и голос"), (self.tab_settings, "⚙ Настройки")):
            self.nb.add(tab, text=name)
        self._build_chat()
        self._build_rules()
        self._build_table()
        self._build_rec()
        self._build_settings()
        tk.Label(self, textvariable=self.status_var, bg=PANEL, fg=MUTED, anchor="w", padx=10, pady=4,
                 font=("Segoe UI", 9)).pack(fill="x", side="bottom")

    # ---- чат
    def _build_chat(self):
        f = self.tab_chat
        wrap = ttk.Frame(f)
        wrap.pack(fill="both", expand=True, pady=(8, 4))
        self.chat_view = self._text(wrap, wrap="word", state="disabled")
        sb = ttk.Scrollbar(wrap, command=self.chat_view.yview)
        self.chat_view.config(yscrollcommand=sb.set)
        sb.pack(side="right", fill="y")
        self.chat_view.pack(side="left", fill="both", expand=True)
        v = self.chat_view
        v.tag_config("user_h", foreground=ACCENT, font=("Segoe UI", 9, "bold"))
        v.tag_config("bot_h", foreground=OK, font=("Segoe UI", 9, "bold"))
        v.tag_config("system_h", foreground=MUTED, font=("Segoe UI", 9, "bold"))
        v.tag_config("user", foreground=FG)
        v.tag_config("bot", foreground=FG)
        v.tag_config("system", foreground=MUTED, font=("Segoe UI", 9, "italic"))

        self.thumb_frame = ttk.Frame(f)
        self.thumb_frame.pack(fill="x")
        row = ttk.Frame(f)
        row.pack(fill="x", pady=(4, 6))
        self.input = self._text(row, height=3, wrap="word")
        self.input.pack(side="left", fill="x", expand=True)
        self.input.bind("<Control-v>", self._on_paste)
        self.input.bind("<Control-V>", self._on_paste)
        self.input.bind("<Control-Return>", self.send)
        side = ttk.Frame(row)
        side.pack(side="left", padx=(8, 0))
        ttk.Button(side, text="Отправить (Ctrl+Enter)", style="Accent.TButton", command=self.send).pack(fill="x")
        ttk.Button(side, text="📋 Вставить скриншот", command=self.attach_clipboard).pack(fill="x", pady=2)
        b2 = ttk.Frame(side)
        b2.pack(fill="x")
        ttk.Button(b2, text="🖥 Снять экран", command=self.attach_screen).pack(side="left", fill="x", expand=True)
        ttk.Button(b2, text="📁 Файл", command=self.attach_file).pack(side="left", fill="x", expand=True, padx=(2, 0))
        opts = ttk.Frame(f)
        opts.pack(fill="x")
        self.use_voice_var = tk.BooleanVar(value=False)
        ttk.Checkbutton(opts, text="Учитывать распознанную речь за последние 90 сек",
                        variable=self.use_voice_var).pack(side="left")
        ttk.Button(opts, text="Очистить чат", command=self.clear_chat).pack(side="right")

    def chat_append(self, role, text):
        v = self.chat_view
        names = {"user": "Ты", "bot": "Помощник", "system": "•"}
        v.config(state="normal")
        v.insert("end", names[role] + "\n", (role + "_h",))
        v.insert("end", text.strip() + "\n\n", (role,))
        v.config(state="disabled")
        v.see("end")

    def clear_chat(self):
        self.history = []
        self.chat_view.config(state="normal")
        self.chat_view.delete("1.0", "end")
        self.chat_view.config(state="disabled")

    def _on_paste(self, event=None):
        img = grab_clipboard_image()
        if img is not None:
            self.add_attachment(img)
            return "break"
        return None

    def attach_clipboard(self):
        img = grab_clipboard_image()
        if img is None:
            messagebox.showinfo("Нет скриншота", "В буфере обмена нет картинки.\n"
                                "Сделай скриншот (Win+Shift+S или PrintScreen) и нажми снова.")
            return
        self.add_attachment(img)

    def attach_screen(self):
        self.iconify()
        self.after(500, self._attach_screen_now)

    def _attach_screen_now(self):
        img = self.take_screenshot()
        self.deiconify()
        self.add_attachment(img)

    def attach_file(self):
        paths = filedialog.askopenfilenames(title="Выбери картинки",
                                            filetypes=[("Картинки", "*.png *.jpg *.jpeg *.bmp *.webp"), ("Все", "*.*")])
        from PIL import Image
        for p in paths:
            try:
                self.add_attachment(Image.open(p).convert("RGB"))
            except Exception as e:
                messagebox.showerror("Ошибка", "Не открылась картинка:\n%s" % e)

    def add_attachment(self, img):
        self.attachments.append(img)
        self._refresh_thumbs()

    def remove_attachment(self, i):
        if 0 <= i < len(self.attachments):
            del self.attachments[i]
            self._refresh_thumbs()

    def _refresh_thumbs(self):
        for w in self.thumb_frame.winfo_children():
            w.destroy()
        self._thumbs = []
        for i, img in enumerate(self.attachments):
            t = img.copy()
            t.thumbnail((110, 66))
            ph = ImageTk.PhotoImage(t)
            self._thumbs.append(ph)
            f = tk.Frame(self.thumb_frame, bg=PANEL2)
            f.pack(side="left", padx=3, pady=2)
            tk.Label(f, image=ph, bg=PANEL2).pack()
            b = tk.Label(f, text="✕ убрать", fg=DANGER, bg=PANEL2, cursor="hand2", font=("Segoe UI", 8))
            b.pack()
            b.bind("<Button-1>", lambda e, i=i: self.remove_attachment(i))

    def send(self, event=None):
        if self._busy:
            return "break"
        text = self.input.get("1.0", "end").strip()
        imgs = list(self.attachments)
        if not text and not imgs:
            return "break"
        self.input.delete("1.0", "end")
        self.attachments = []
        self._refresh_thumbs()
        msg = {"role": "user", "text": text, "images": imgs}
        shown = text + ("\n[скриншотов: %d]" % len(imgs) if imgs else "")
        self.chat_append("user", shown.strip())

        def restore(_err):
            self.input.insert("1.0", text)
            self.attachments = imgs
            self._refresh_thumbs()

        self._ask_ai(msg, "Думаю…", on_fail=restore)
        return "break"

    # ---- общий вызов ИИ
    def _ask_ai(self, user_msg, label, on_ok=None, on_fail=None, clip_holder=None):
        self._busy = True
        self.set_status(label)
        hist = self.history + [user_msg]
        transcript = self.transcript.as_text(90) if self.use_voice_var.get() else ""

        def work():
            try:
                reply = self.ai.chat(hist, transcript)
            except AIError as e:
                self.post(self._ai_fail, str(e), on_fail)
                return
            except Exception as e:
                self.post(self._ai_fail, "Непредвиденная ошибка: %s" % e, on_fail)
                return
            clip = ""
            if clip_holder is not None:
                clip_holder["done"].wait(40)
                clip = clip_holder.get("path") or ""
            self.post(self._ai_ok, user_msg, reply, clip, on_ok)

        threading.Thread(target=work, daemon=True).start()

    def _ai_fail(self, msg, on_fail):
        self._busy = False
        self.set_status("Ошибка")
        self.chat_append("system", "⚠ " + msg)
        if on_fail:
            on_fail(msg)

    def _ai_ok(self, user_msg, reply, clip, on_ok):
        self._busy = False
        self.set_status("Готов")
        self.history.append(user_msg)
        self.history.append({"role": "assistant", "text": reply, "images": []})
        found = extract_violations(reply)
        shown = strip_json(reply) or "(пустой ответ)"
        self.chat_append("bot", shown)
        added = []
        if found:
            added = self.violations.add_many(found, clip=clip)
            if added:
                self.chat_append("system", "В таблицу добавлено: %d" % len(added))
                names = ", ".join(sorted({a["nick"] for a in added}))
                self.overlay.flash("Новое нарушение: " + names)
                if not self.overlay.visible():
                    self.overlay.show()
            else:
                self.chat_append("system", "Такие нарушения уже есть в таблице.")
        if on_ok:
            on_ok(found, added)

    # ---- правила
    def _build_rules(self):
        f = self.tab_rules
        ttk.Label(f, text="Правила игры и сроки наказаний (чем точнее — тем лучше работает помощник):").pack(
            anchor="w", pady=(10, 4))
        self.rules_text = self._text(f, wrap="word", height=16)
        self.rules_text.pack(fill="both", expand=True)
        self.rules_text.insert("1.0", self.settings.get("rules_text") or "")
        bar = ttk.Frame(f)
        bar.pack(fill="x", pady=6)
        ttk.Button(bar, text="💾 Сохранить", style="Accent.TButton", command=self.save_rules).pack(side="left")
        ttk.Button(bar, text="📁 Загрузить из .txt", command=self.load_rules_file).pack(side="left", padx=6)
        ttk.Button(bar, text="🖼 Распознать со скриншота (из буфера)", command=self.rules_from_clipboard).pack(
            side="left")
        ttk.Label(f, text="Как оформлять жалобы (образец — необязательно). Например: «Ник — пункт — причина — срок»:").pack(
            anchor="w", pady=(8, 4))
        self.template_text = self._text(f, wrap="word", height=5)
        self.template_text.pack(fill="x")
        self.template_text.insert("1.0", self.settings.get("complaint_template") or "")
        self.template_text.bind("<FocusOut>", lambda e: self.save_rules(quiet=True))
        self.rules_text.bind("<FocusOut>", lambda e: self.save_rules(quiet=True))

    def save_rules(self, quiet=False):
        self.settings.set("rules_text", self.rules_text.get("1.0", "end").strip(), save=False)
        self.settings.set("complaint_template", self.template_text.get("1.0", "end").strip())
        if not quiet:
            self.set_status("Правила сохранены")

    def load_rules_file(self):
        p = filedialog.askopenfilename(filetypes=[("Текст", "*.txt *.md"), ("Все", "*.*")])
        if not p:
            return
        for enc in ("utf-8", "cp1251"):
            try:
                with open(p, "r", encoding=enc) as fh:
                    data = fh.read()
                break
            except (UnicodeDecodeError, OSError):
                data = None
        if data is None:
            messagebox.showerror("Ошибка", "Не удалось прочитать файл.")
            return
        self.rules_text.delete("1.0", "end")
        self.rules_text.insert("1.0", data)
        self.save_rules()

    def rules_from_clipboard(self):
        img = grab_clipboard_image()
        if img is None:
            messagebox.showinfo("Нет скриншота", "Скопируй скриншот правил в буфер (Win+Shift+S) и нажми снова.")
            return
        self.set_status("Распознаю правила…")

        def work():
            try:
                text = self.ai.transcribe_rules(img)
                self.post(self._rules_ready, text)
            except AIError as e:
                self.post(self._ai_fail, str(e), None)

        threading.Thread(target=work, daemon=True).start()

    def _rules_ready(self, text):
        self.rules_text.insert("end", ("\n" if self.rules_text.get("1.0", "end").strip() else "") + text)
        self.save_rules()
        self.set_status("Правила добавлены — проверь текст")

    # ---- таблица
    def _build_table(self):
        f = self.tab_table
        cols = ("ts", "nick", "rule", "reason", "punishment", "clip", "ov")
        heads = ("Время", "Ник", "Пункт", "Причина", "Наказание", "Клип", "Мини-окно")
        widths = (70, 140, 80, 330, 150, 50, 80)
        self.tree = ttk.Treeview(f, columns=cols, show="headings", selectmode="extended")
        for c, h, w in zip(cols, heads, widths):
            self.tree.heading(c, text=h)
            self.tree.column(c, width=w, anchor="w", stretch=(c == "reason"))
        sb = ttk.Scrollbar(f, command=self.tree.yview)
        self.tree.config(yscrollcommand=sb.set)
        bar = ttk.Frame(f)
        bar.pack(side="bottom", fill="x", pady=6)
        sb.pack(side="right", fill="y")
        self.tree.pack(fill="both", expand=True, pady=(10, 0))
        self.tree.bind("<Double-1>", lambda e: self.edit_selected())
        for text, cmd in (("➕ Добавить", self.add_manual), ("✏ Изменить", self.edit_selected),
                          ("🗑 Удалить", self.delete_selected), ("👁 Показать/скрыть в мини-окне", self.toggle_selected),
                          ("🎞 Открыть клип", self.open_selected_clip), ("📤 CSV", self.export_csv),
                          ("Очистить всё", self.clear_all)):
            ttk.Button(bar, text=text, command=cmd).pack(side="left", padx=(0, 6))

    def refresh_table(self):
        sel = set(self.tree.selection())
        self.tree.delete(*self.tree.get_children())
        for it in self.violations.items:
            self.tree.insert("", "end", iid=it["id"], values=(
                fmt_time(it["ts"]), it["nick"], it["rule"], it["reason"], it["punishment"],
                "🎞" if it.get("clip") else "", "скрыто" if it.get("hidden") else "показано"))
        for i in sel:
            if self.tree.exists(i):
                self.tree.selection_add(i)

    def refresh_all(self):
        self.refresh_table()
        self.overlay.render()

    def add_manual(self):
        r = edit_dialog(self, "Новая запись")
        if r:
            self.violations.add(r["nick"], r["rule"], r["reason"], r["punishment"])

    def edit_selected(self):
        sel = self.tree.selection()
        if not sel:
            return
        it = self.violations.get(sel[0])
        r = edit_dialog(self, "Изменить запись", it)
        if r:
            self.violations.update(sel[0], **r)

    def delete_selected(self):
        for i in self.tree.selection():
            self.violations.remove(i)

    def toggle_selected(self):
        for i in self.tree.selection():
            it = self.violations.get(i)
            if it:
                self.violations.update(i, hidden=not it.get("hidden"))

    def open_selected_clip(self):
        for i in self.tree.selection():
            it = self.violations.get(i)
            if it and it.get("clip") and os.path.exists(it["clip"]):
                open_path(it["clip"])
                return
        messagebox.showinfo("Клип", "У выбранной записи нет клипа.")

    def export_csv(self):
        p = filedialog.asksaveasfilename(defaultextension=".csv", filetypes=[("CSV", "*.csv")],
                                         initialfile="narusheniya.csv")
        if p:
            self.violations.export_csv(p)
            self.set_status("Таблица сохранена: " + p)

    def clear_all(self):
        if messagebox.askyesno("Очистить", "Удалить все записи из таблицы?"):
            self.violations.clear()

    # ---- мини-окно
    def toggle_overlay(self):
        if self.overlay.visible():
            self.overlay.hide()
        else:
            self.overlay.render()
            self.overlay.show()

    def hide_nick(self, nick):
        self.violations.hide_nick(nick)

    def hide_item(self, vid):
        self.violations.update(vid, hidden=True)

    # ---- запись и голос
    def _build_rec(self):
        f = self.tab_rec
        box = ttk.LabelFrame(f, text="Запись экрана (как Fraps, видео без звука)")
        box.pack(fill="x", pady=(10, 6), padx=2)
        r = ttk.Frame(box)
        r.pack(fill="x", padx=10, pady=8)
        self.rec_btn = ttk.Button(r, text="● Запустить запись", style="Accent.TButton", command=self.toggle_recording)
        self.rec_btn.pack(side="left")
        ttk.Label(r, text="  Кадров/с:").pack(side="left")
        self.fps_var = tk.StringVar(value=str(self.settings.get("fps")))
        ttk.Spinbox(r, from_=3, to=30, width=4, textvariable=self.fps_var).pack(side="left")
        ttk.Label(r, text="  Размер:").pack(side="left")
        self.scale_var = tk.StringVar(value=str(self.settings.get("scale")))
        ttk.Combobox(r, width=5, state="readonly", textvariable=self.scale_var,
                     values=("0.25", "0.35", "0.5", "0.75", "1.0")).pack(side="left")
        ttk.Label(r, text="  Хранить минут:").pack(side="left")
        self.buf_var = tk.StringVar(value=str(self.settings.get("buffer_minutes")))
        ttk.Spinbox(r, from_=1, to=60, width=4, textvariable=self.buf_var).pack(side="left")
        self.rec_status = ttk.Label(box, text="", style="Muted.TLabel")
        self.rec_status.pack(anchor="w", padx=10, pady=(0, 8))

        cb = ttk.LabelFrame(f, text="Вырезать нарушение")
        cb.pack(fill="x", pady=6, padx=2)
        r2 = ttk.Frame(cb)
        r2.pack(fill="x", padx=10, pady=8)
        ttk.Label(r2, text="Нарушение было от").pack(side="left")
        self.clip_from = tk.StringVar(value=str(self.settings.get("clip_before")))
        ttk.Spinbox(r2, from_=1, to=3600, width=6, textvariable=self.clip_from).pack(side="left", padx=4)
        ttk.Label(r2, text="до").pack(side="left")
        self.clip_to = tk.StringVar(value="0")
        ttk.Spinbox(r2, from_=0, to=3600, width=6, textvariable=self.clip_to).pack(side="left", padx=4)
        ttk.Label(r2, text="секунд назад").pack(side="left")
        ttk.Button(r2, text="✂ Вырезать", style="Accent.TButton", command=self.manual_clip).pack(side="left", padx=10)
        ttk.Button(r2, text="📂 Папка с клипами", command=lambda: open_path(clips_dir())).pack(side="left")
        ttk.Label(cb, style="Muted.TLabel",
                  text="Клип прикрепляется к выделенной записи в таблице. Рядом сохраняется .txt с речью за этот отрезок.").pack(
            anchor="w", padx=10, pady=(0, 8))

        vb = ttk.LabelFrame(f, text="Голос → текст (показывается в мини-окне)")
        vb.pack(fill="both", expand=True, pady=6, padx=2)
        r3 = ttk.Frame(vb)
        r3.pack(fill="x", padx=10, pady=8)
        self.dev_var = tk.StringVar(value=self.settings.get("voice_device") or "")
        self.dev_combo = ttk.Combobox(r3, width=48, state="readonly", textvariable=self.dev_var)
        self.dev_combo.pack(side="left")
        ttk.Button(r3, text="↻", width=3, command=self.load_devices).pack(side="left", padx=4)
        self.voice_btn = ttk.Button(r3, text="🎤 Слушать", style="Accent.TButton", command=self.toggle_voice)
        self.voice_btn.pack(side="left", padx=6)
        self.ov_voice_var = tk.BooleanVar(value=bool(self.settings.get("overlay_voice")))
        ttk.Checkbutton(r3, text="В мини-окне", variable=self.ov_voice_var, command=self.change_ov_voice).pack(
            side="left", padx=6)
        ttk.Label(vb, style="Muted.TLabel", wraplength=900,
                  text="Чтобы слышать игроков из игры, выбери «Стерео микшер / Stereo Mix» (включается в настройках "
                       "звука Windows) или виртуальный кабель. Микрофон слышит только тебя. Нужен интернет.").pack(
            anchor="w", padx=10)
        self.voice_view = self._text(vb, height=8, wrap="word", state="disabled")
        self.voice_view.pack(fill="both", expand=True, padx=10, pady=8)
        self.load_devices()

    def load_devices(self):
        try:
            devs = list_input_devices()
            self.dev_combo.config(values=["%d: %s" % d for d in devs])
            if devs and not self.dev_var.get():
                self.dev_var.set("%d: %s" % devs[0])
        except RuntimeError as e:
            self.dev_combo.config(values=[])
            self.set_status("Голос недоступен: %s" % e)

    def change_ov_voice(self):
        self.settings.set("overlay_voice", bool(self.ov_voice_var.get()))
        self.overlay.render()

    def toggle_recording(self):
        if self.recorder.running:
            self.recorder.stop()
            return
        try:
            fps = int(float(self.fps_var.get()))
            scale = float(self.scale_var.get())
            mins = int(float(self.buf_var.get()))
        except ValueError:
            messagebox.showerror("Запись", "Проверь числа: кадры, размер, минуты.")
            return
        self.settings.set("fps", fps, save=False)
        self.settings.set("scale", scale, save=False)
        self.settings.set("buffer_minutes", mins)
        self.recorder.configure(fps, scale, mins * 60)
        self.recorder.start()
        self.set_status("Запись экрана запущена")

    def toggle_voice(self):
        if self.voice and self.voice.running:
            self.voice.stop()
            self.voice_btn.config(text="🎤 Слушать")
            self.set_status("Прослушивание остановлено")
            return
        dev = self.dev_var.get().strip()
        idx = None
        if dev:
            try:
                idx = int(dev.split(":")[0])
            except ValueError:
                idx = None
        self.settings.set("voice_device", dev)
        self.voice = VoiceListener(lambda t, ts: self.post(self._voice_line, t, ts), device=idx,
                                   lang=self.settings.get("voice_lang") or "ru-RU",
                                   on_error=lambda m: self.post(self.set_status, m))
        try:
            self.voice.start()
        except RuntimeError as e:
            messagebox.showerror("Голос", str(e))
            return
        self.voice_btn.config(text="■ Остановить")
        self.set_status("Слушаю…")

    def _voice_line(self, text, ts):
        self.transcript.add(text, ts)
        v = self.voice_view
        v.config(state="normal")
        v.insert("end", "[%s] %s\n" % (fmt_time(ts), text))
        v.config(state="disabled")
        v.see("end")
        self.overlay.render()

    def export_clip_async(self, start_ago, end_ago):
        holder = {"path": None, "done": threading.Event()}
        if not self.recorder.segments and not self.recorder.running:
            holder["done"].set()
            return holder
        now = time.time()
        out = clips_dir() / time.strftime("clip_%Y%m%d_%H%M%S.mp4")

        def work():
            try:
                p = self.recorder.export_clip(start_ago, end_ago, out)
            except Exception:
                traceback.print_exc()
                p = None
            if p:
                lines = self.transcript.between(now - start_ago, now - end_ago)
                try:
                    with open(str(p)[:-4] + ".txt", "w", encoding="utf-8") as fh:
                        fh.write(TranscriptLog.format(lines) or "(речи в этом отрезке нет)")
                except OSError:
                    pass
            holder["path"] = str(p) if p else None
            holder["done"].set()

        threading.Thread(target=work, daemon=True).start()
        return holder

    def manual_clip(self):
        try:
            a, b = float(self.clip_from.get()), float(self.clip_to.get())
        except ValueError:
            messagebox.showerror("Клип", "Укажи числа секунд.")
            return
        if not self.recorder.segments and not self.recorder.running:
            messagebox.showinfo("Клип", "Запись не запущена, вырезать нечего.")
            return
        self.set_status("Вырезаю клип…")
        holder = self.export_clip_async(a, b)
        sel = self.tree.selection()

        def wait():
            holder["done"].wait(60)
            self.post(self._clip_ready, holder.get("path"), sel[0] if sel else None)

        threading.Thread(target=wait, daemon=True).start()

    def _clip_ready(self, path, vid):
        if not path:
            self.set_status("Клип не получился: в буфере нет записи за этот отрезок")
            return
        if vid and self.violations.get(vid):
            self.violations.update(vid, clip=path)
        self.set_status("Клип сохранён: " + path)
        self.overlay.flash("Клип сохранён: " + os.path.basename(path))
        open_path(clips_dir())

    def quick_clip(self):
        if not self.recorder.segments and not self.recorder.running:
            self.overlay.flash("Запись не запущена — клип не вырезать")
            return
        holder = self.export_clip_async(float(self.settings.get("clip_before")), 0)
        last = self.violations.items[-1]["id"] if self.violations.items else None

        def wait():
            holder["done"].wait(60)
            self.post(self._clip_ready, holder.get("path"), last)

        threading.Thread(target=wait, daemon=True).start()

    # ---- жалоба
    def take_screenshot(self):
        was_visible = self.overlay.visible()
        if was_visible:
            self.overlay.withdraw()
            self.update()
            time.sleep(0.15)
        try:
            return capture_screen_image()
        finally:
            if was_visible:
                self.overlay.deiconify()

    def start_complaint(self, from_button=False):
        if self._complaint is not None or self._busy:
            return
        self._complaint_from_button = from_button
        if from_button:
            self.iconify()
            self.after(600, self._do_complaint)
        else:
            self._do_complaint()

    def _do_complaint(self):
        shot = self.take_screenshot()
        self._clip_holder = self.export_clip_async(float(self.settings.get("clip_before")), 0)
        self._complaint_shot = shot
        self._complaint = ComplaintWindow(self, shot, self._submit_complaint, self._cancel_complaint)

    def _end_complaint(self):
        if self._complaint is not None:
            self._complaint.close()
        self._complaint = None
        if self._complaint_from_button:
            self.deiconify()

    def _cancel_complaint(self):
        self._end_complaint()

    def _submit_complaint(self, note, extra):
        prompt = ("ЖАЛОБА. Первый скриншот — экран игры в момент жалобы. "
                  + ("Остальные скриншоты — доказательства модератора. " if extra else "")
                  + "Определи ник нарушителя, нарушенный пункт правил, причину и срок наказания."
                  + (("\nПояснение модератора: " + note) if note else ""))
        msg = {"role": "user", "text": prompt, "images": [self._complaint_shot] + extra}
        self.chat_append("user", "🚩 Жалоба" + (": " + note if note else "") + " [скриншотов: %d]" % (1 + len(extra)))

        def ok(found, added):
            self._end_complaint()
            if not found:
                self.overlay.flash("Нарушение не найдено — смотри ответ в чате")
                if not self.overlay.visible():
                    self.overlay.show()

        def fail(err):
            if self._complaint is not None:
                self._complaint.show_error(err)

        self._ask_ai(msg, "Анализирую жалобу…", on_ok=ok, on_fail=fail, clip_holder=self._clip_holder)

    # ---- горячие клавиши
    def _setup_hotkeys(self):
        try:
            from pynput import keyboard
        except Exception:
            self.set_status("Горячие клавиши отключены: не установлен pynput (кнопки в программе работают)")
            return
        mapping = {}
        for key, cb in (("hotkey_complaint", self.start_complaint), ("hotkey_overlay", self.toggle_overlay),
                        ("hotkey_clip", self.quick_clip)):
            combo = (self.settings.get(key) or "").strip()
            if combo:
                mapping[combo] = (lambda cb=cb: self.post(cb))
        try:
            if self._hk:
                self._hk.stop()
            self._hk = keyboard.GlobalHotKeys(mapping)
            self._hk.start()
        except Exception as e:
            self.set_status("Горячие клавиши не заработали: %s" % e)

    # ---- настройки
    def _build_settings(self):
        f = self.tab_settings
        g = ttk.Frame(f)
        g.pack(anchor="nw", padx=10, pady=14)
        self.api_var = tk.StringVar(value=self.settings.get("api_key"))
        self.model_var = tk.StringVar(value=self.settings.get("model"))
        self.alpha_var = tk.DoubleVar(value=float(self.settings.get("overlay_alpha")))
        self.hk_vars = {k: tk.StringVar(value=self.settings.get(k)) for k in
                        ("hotkey_complaint", "hotkey_overlay", "hotkey_clip")}
        self.clipb_var = tk.StringVar(value=str(self.settings.get("clip_before")))
        rows = [("API-ключ Anthropic", ttk.Entry(g, textvariable=self.api_var, width=60, show="•")),
                ("Модель", ttk.Entry(g, textvariable=self.model_var, width=30)),
                ("Клип до жалобы, сек", ttk.Entry(g, textvariable=self.clipb_var, width=8)),
                ("Клавиша: жалоба", ttk.Entry(g, textvariable=self.hk_vars["hotkey_complaint"], width=14)),
                ("Клавиша: мини-окно", ttk.Entry(g, textvariable=self.hk_vars["hotkey_overlay"], width=14)),
                ("Клавиша: быстрый клип", ttk.Entry(g, textvariable=self.hk_vars["hotkey_clip"], width=14)),
                ("Прозрачность мини-окна", ttk.Scale(g, from_=0.4, to=1.0, variable=self.alpha_var, length=200,
                                                     command=lambda v: hasattr(self, "overlay") and self.overlay.set_alpha(v)))]
        for r, (label, w) in enumerate(rows):
            ttk.Label(g, text=label).grid(row=r, column=0, sticky="w", pady=6, padx=(0, 14))
            w.grid(row=r, column=1, sticky="w", pady=6)
        ttk.Label(g, style="Muted.TLabel", text="Клавиши в формате pynput: <f8>, <ctrl>+<alt>+j, <f12>").grid(
            row=len(rows), column=1, sticky="w")
        bar = ttk.Frame(g)
        bar.grid(row=len(rows) + 1, column=0, columnspan=2, sticky="w", pady=14)
        ttk.Button(bar, text="💾 Сохранить", style="Accent.TButton", command=self.save_settings).pack(side="left")
        ttk.Button(bar, text="Проверить связь с Claude", command=self.test_api).pack(side="left", padx=8)

    def save_settings(self):
        self.settings.set("api_key", self.api_var.get().strip(), save=False)
        self.settings.set("model", self.model_var.get().strip() or "claude-sonnet-5-5", save=False)
        self.settings.set("overlay_alpha", round(float(self.alpha_var.get()), 2), save=False)
        try:
            self.settings.set("clip_before", int(float(self.clipb_var.get())), save=False)
        except ValueError:
            pass
        for k, v in self.hk_vars.items():
            self.settings.set(k, v.get().strip(), save=False)
        self.settings.save()
        self._setup_hotkeys()
        self.set_status("Настройки сохранены")

    def test_api(self):
        self.save_settings()
        self.set_status("Проверяю связь…")

        def work():
            try:
                ans = self.ai.ping()
                self.post(self.set_status, "Связь с Claude работает (ответ: %s)" % ans)
            except AIError as e:
                self.post(messagebox.showerror, "Нет связи", str(e))
                self.post(self.set_status, "Ошибка связи")

        threading.Thread(target=work, daemon=True).start()

    # ---- выход
    def on_close(self):
        try:
            self.save_rules(quiet=True)
            if self.recorder.running:
                self.recorder.stop()
            if self.voice and self.voice.running:
                self.voice.stop()
            if self._hk:
                self._hk.stop()
        finally:
            self.destroy()
