"""Маленькое перетаскиваемое окно поверх игры."""
import tkinter as tk

from .util import ACCENT, BG, DANGER, FG, MUTED, PANEL, PANEL2, WARN, rule_label


class Overlay(tk.Toplevel):
    """Режимы: compact — узкая колонка (ник сверху, причины и сроки ниже),
    table — широкая таблица, dot — маленькая кнопка."""

    def __init__(self, app):
        super().__init__(app)
        self.app = app
        self.overrideredirect(True)
        self.attributes("-topmost", True)
        try:
            self.attributes("-alpha", float(app.settings.get("overlay_alpha")))
        except (tk.TclError, ValueError, TypeError):
            pass
        self.configure(bg=BG)
        mode = app.settings.get("overlay_mode")
        self.mode = mode if mode in ("compact", "table", "dot") else "compact"
        self._last_full = "compact" if self.mode == "dot" else self.mode
        self._drag = None
        self._moved = False
        self._toast = ""
        self._toast_job = None
        x, y = app.settings.get("overlay_pos")
        self.geometry("+%d+%d" % (int(x), int(y)))
        self.body = tk.Frame(self, bg=BG, highlightthickness=1, highlightbackground=PANEL2)
        self.body.pack(fill="both", expand=True)
        self.render()
        self.withdraw()
        self.after(2000, self._keep_on_top)

    # --- служебное ---
    def _keep_on_top(self):
        try:
            if self.winfo_viewable():
                self.attributes("-topmost", True)
                self.lift()
        except tk.TclError:
            return
        self.after(2000, self._keep_on_top)

    def set_alpha(self, value):
        try:
            self.attributes("-alpha", float(value))
        except (tk.TclError, ValueError):
            pass

    def visible(self):
        return self.state() != "withdrawn"

    def show(self):
        self.deiconify()
        self.lift()
        self.attributes("-topmost", True)

    def hide(self):
        self.withdraw()

    def set_mode(self, mode):
        if mode != "dot":
            self._last_full = mode
        self.mode = mode
        self.app.settings.set("overlay_mode", mode)
        self.render()

    def flash(self, text, ms=6000):
        self._toast = text
        if self._toast_job:
            self.after_cancel(self._toast_job)
        self._toast_job = self.after(ms, self._clear_toast)
        self.render()

    def _clear_toast(self):
        self._toast = ""
        self._toast_job = None
        self.render()

    # --- перетаскивание ---
    def _drag_bind(self, w):
        w.bind("<ButtonPress-1>", self._drag_start, add="+")
        w.bind("<B1-Motion>", self._drag_move, add="+")
        w.bind("<ButtonRelease-1>", self._drag_end, add="+")

    def _drag_start(self, e):
        self._drag = (e.x_root - self.winfo_x(), e.y_root - self.winfo_y(), e.x_root, e.y_root)
        self._moved = False

    def _drag_move(self, e):
        if not self._drag:
            return
        dx, dy, sx, sy = self._drag
        if abs(e.x_root - sx) + abs(e.y_root - sy) > 4:
            self._moved = True
        if self._moved:
            self.geometry("+%d+%d" % (e.x_root - dx, e.y_root - dy))

    def _drag_end(self, e):
        if self._moved:
            self.app.settings.set("overlay_pos", [self.winfo_x(), self.winfo_y()])
        elif self.mode == "dot":
            self.set_mode(self._last_full)
        self._drag = None

    def _btn(self, parent, text, cmd, bg, fg=MUTED, size=10):
        lb = tk.Label(parent, text=text, bg=bg, fg=fg, cursor="hand2", font=("Segoe UI", size, "bold"), padx=5)
        lb.bind("<Button-1>", lambda e: cmd())
        lb.bind("<Enter>", lambda e: lb.config(fg=FG))
        lb.bind("<Leave>", lambda e: lb.config(fg=fg))
        return lb

    # --- отрисовка ---
    def render(self):
        for w in self.body.winfo_children():
            w.destroy()
        items = self.app.violations.visible()
        if self.mode == "dot":
            self._render_dot(len(items))
            return
        self._render_header()
        if self._toast:
            t = tk.Label(self.body, text=self._toast, bg=PANEL, fg=WARN, wraplength=250, justify="left",
                         font=("Segoe UI", 9), padx=8, pady=5, anchor="w")
            t.pack(fill="x", padx=6, pady=(6, 0))
            self._drag_bind(t)
        if not items:
            e = tk.Label(self.body, text="Нарушений нет\nF8 — жалоба", bg=BG, fg=MUTED, font=("Segoe UI", 9),
                         padx=14, pady=10)
            e.pack()
            self._drag_bind(e)
        elif self.mode == "compact":
            self._render_compact(items)
        else:
            self._render_table(items)
        self._render_voice()
        tk.Frame(self.body, bg=BG, height=6).pack()

    def _render_dot(self, n):
        txt = "⚖ %d" % n if n else "⚖"
        lb = tk.Label(self.body, text=txt, bg=ACCENT, fg="#ffffff", font=("Segoe UI", 11, "bold"),
                      padx=10, pady=6, cursor="fleur")
        lb.pack()
        self._drag_bind(lb)

    def _render_header(self):
        h = tk.Frame(self.body, bg=PANEL2)
        h.pack(fill="x")
        t = tk.Label(h, text="⚖ ModHelper", bg=PANEL2, fg=MUTED, font=("Segoe UI", 8, "bold"), padx=6, pady=3,
                     cursor="fleur")
        t.pack(side="left")
        self._drag_bind(h)
        self._drag_bind(t)
        self._btn(h, "✕", self.app.toggle_overlay, PANEL2).pack(side="right")
        self._btn(h, "●", lambda: self.set_mode("dot"), PANEL2).pack(side="right")
        if self.mode == "compact":
            self._btn(h, "▦", lambda: self.set_mode("table"), PANEL2).pack(side="right")
        else:
            self._btn(h, "▭", lambda: self.set_mode("compact"), PANEL2).pack(side="right")

    def _render_compact(self, items):
        groups = {}
        for it in items:
            groups.setdefault(it["nick"], []).append(it)
        for nick, lst in groups.items():
            card = tk.Frame(self.body, bg=PANEL, padx=8, pady=6)
            card.pack(fill="x", padx=6, pady=(6, 0))
            top = tk.Frame(card, bg=PANEL)
            top.pack(fill="x")
            nl = tk.Label(top, text=nick, bg=PANEL, fg=ACCENT, font=("Segoe UI", 11, "bold"), anchor="w")
            nl.pack(side="left")
            self._btn(top, "✕", lambda n=nick: self.app.hide_nick(n), PANEL).pack(side="right")
            self._drag_bind(card)
            self._drag_bind(nl)
            for it in lst:
                row = tk.Frame(card, bg=PANEL)
                row.pack(fill="x", pady=(4, 0))
                row.columnconfigure(0, weight=1)
                rl = rule_label(it["rule"])
                reason = (rl + " · " if rl else "") + it["reason"]
                a = tk.Label(row, text=reason, bg=PANEL, fg=FG, wraplength=215, justify="left", anchor="w",
                             font=("Segoe UI", 9))
                a.grid(row=0, column=0, sticky="w")
                b = tk.Label(row, text="→ " + (it["punishment"] or "срок не указан"), bg=PANEL, fg=WARN,
                             wraplength=215, justify="left", anchor="w", font=("Segoe UI", 9, "bold"))
                b.grid(row=1, column=0, sticky="w")
                self._btn(row, "✕", lambda i=it["id"]: self.app.hide_item(i), PANEL, size=9).grid(
                    row=0, column=1, rowspan=2, sticky="ne")
                for w in (row, a, b):
                    self._drag_bind(w)

    def _render_table(self, items):
        grid = tk.Frame(self.body, bg=BG, padx=6)
        grid.pack(fill="x", pady=(6, 0))
        heads = ("Ник", "Пункт", "Причина", "Наказание", "")
        for c, h in enumerate(heads):
            tk.Label(grid, text=h, bg=BG, fg=MUTED, font=("Segoe UI", 8, "bold"), anchor="w", padx=4).grid(
                row=0, column=c, sticky="w")
        for r, it in enumerate(items, 1):
            bg = PANEL if r % 2 else BG
            cells = [(it["nick"], ACCENT, 130), (rule_label(it["rule"]) or "—", FG, 60),
                     (it["reason"], FG, 260), (it["punishment"] or "—", WARN, 130)]
            for c, (text, color, wrap) in enumerate(cells):
                lb = tk.Label(grid, text=text, bg=bg, fg=color, wraplength=wrap, justify="left", anchor="w",
                              font=("Segoe UI", 9, "bold" if c in (0, 3) else "normal"), padx=4, pady=3)
                lb.grid(row=r, column=c, sticky="nsew")
                self._drag_bind(lb)
            self._btn(grid, "✕", lambda i=it["id"]: self.app.hide_item(i), bg, size=9).grid(row=r, column=4)

    def _render_voice(self):
        if not self.app.settings.get("overlay_voice"):
            return
        lines = self.app.transcript.recent(120)[-3:]
        if not lines:
            return
        box = tk.Frame(self.body, bg=BG)
        box.pack(fill="x", padx=8, pady=(8, 0))
        for _, text in lines:
            lb = tk.Label(box, text="🎤 " + text, bg=BG, fg=MUTED, wraplength=250, justify="left", anchor="w",
                          font=("Segoe UI", 8, "italic"))
            lb.pack(fill="x")
            self._drag_bind(lb)
