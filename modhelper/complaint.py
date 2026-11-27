"""Режим жалобы: экран «замирает», программа ждёт доказательство/пояснение, потом анализирует."""
import tkinter as tk

from PIL import ImageTk

from .util import ACCENT, BG, DANGER, FG, MUTED, OK, PANEL, PANEL2, WARN, grab_clipboard_image


class ComplaintWindow(tk.Toplevel):
    def __init__(self, app, shot, on_submit, on_cancel):
        super().__init__(app)
        self.app = app
        self.shot = shot
        self.on_submit = on_submit
        self.on_cancel = on_cancel
        self.extra = []
        self._thumbs = []
        self._busy = False
        sw, sh = self.winfo_screenwidth(), self.winfo_screenheight()
        self.overrideredirect(True)
        self.attributes("-topmost", True)
        self.geometry("%dx%d+0+0" % (sw, sh))
        self._bg = ImageTk.PhotoImage(shot.resize((sw, sh)))
        self.canvas = tk.Canvas(self, width=sw, height=sh, highlightthickness=0, bg="black")
        self.canvas.pack(fill="both", expand=True)
        self.canvas.create_image(0, 0, anchor="nw", image=self._bg)

        panel = tk.Frame(self.canvas, bg=PANEL, padx=16, pady=12, highlightthickness=2, highlightbackground=ACCENT)
        tk.Label(panel, text="🚩 Жалоба — экран заморожен, скриншот сделан", bg=PANEL, fg=FG,
                 font=("Segoe UI", 12, "bold")).pack(anchor="w")
        tk.Label(panel, justify="left", bg=PANEL, fg=MUTED, font=("Segoe UI", 9),
                 text="1) Если нужно — вставь ещё скриншот-доказательство (Ctrl+V).\n"
                      "2) Напиши ник или пояснение (можно пусто).\n"
                      "3) Enter — анализировать.   Esc — отмена.").pack(anchor="w", pady=(4, 8))
        self.thumb_frame = tk.Frame(panel, bg=PANEL)
        self.thumb_frame.pack(anchor="w")
        self.entry = tk.Entry(panel, width=70, bg=PANEL2, fg=FG, insertbackground=FG, relief="flat",
                              font=("Segoe UI", 11))
        self.entry.pack(fill="x", ipady=6, pady=(6, 6))
        self.status = tk.Label(panel, text="", bg=PANEL, fg=WARN, font=("Segoe UI", 9), wraplength=620,
                               justify="left")
        self.status.pack(anchor="w")
        row = tk.Frame(panel, bg=PANEL)
        row.pack(anchor="e", pady=(6, 0))
        self.btn_cancel = tk.Button(row, text="Отмена (Esc)", command=self.cancel, bg=PANEL2, fg=FG, relief="flat",
                                    padx=12, pady=4)
        self.btn_cancel.pack(side="left", padx=4)
        self.btn_ok = tk.Button(row, text="Анализировать (Enter)", command=self.submit, bg=ACCENT, fg="white",
                                relief="flat", padx=12, pady=4)
        self.btn_ok.pack(side="left", padx=4)
        self.canvas.create_window(sw // 2, sh - 30, window=panel, anchor="s")

        self.bind("<Escape>", lambda e: self.cancel())
        self.entry.bind("<Return>", lambda e: self.submit())
        self.entry.bind("<Control-v>", self._paste)
        self.entry.bind("<Control-V>", self._paste)
        self.after(120, self._focus)

    def _focus(self):
        try:
            self.focus_force()
            self.entry.focus_set()
        except tk.TclError:
            pass

    def _paste(self, event=None):
        if self._busy:
            return "break"
        img = grab_clipboard_image()
        if img is not None:
            self.extra.append(img)
            self._refresh_thumbs()
            self.status.config(text="Скриншот добавлен (%d). Теперь Enter." % len(self.extra), fg=OK)
            return "break"
        return None

    def _refresh_thumbs(self):
        for w in self.thumb_frame.winfo_children():
            w.destroy()
        self._thumbs = []
        for img in self.extra:
            t = img.copy()
            t.thumbnail((110, 66))
            ph = ImageTk.PhotoImage(t)
            self._thumbs.append(ph)
            tk.Label(self.thumb_frame, image=ph, bg=PANEL).pack(side="left", padx=3)

    def set_busy(self, text):
        self._busy = True
        self.status.config(text=text, fg=WARN)
        self.btn_ok.config(state="disabled")
        self.entry.config(state="disabled")

    def show_error(self, text):
        self._busy = False
        self.status.config(text="⚠ " + text + "\nМожно нажать Enter ещё раз или Esc.", fg=DANGER)
        self.btn_ok.config(state="normal")
        self.entry.config(state="normal")
        self.entry.focus_set()

    def submit(self):
        if self._busy:
            return
        self.set_busy("Анализирую жалобу…")
        self.on_submit(self.entry.get().strip(), list(self.extra))

    def cancel(self):
        if self._busy:
            return
        self.on_cancel()

    def close(self):
        try:
            self.destroy()
        except tk.TclError:
            pass
