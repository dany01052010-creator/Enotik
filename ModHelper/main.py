import sys


def main():
    try:
        import tkinter  # noqa: F401
    except ImportError:
        print("Не найден tkinter. Переустанови Python с сайта python.org (галочка «tcl/tk and IDLE»).")
        sys.exit(1)
    from modhelper.util import enable_dpi_awareness
    enable_dpi_awareness()
    from modhelper.ui_main import MainWindow
    MainWindow().mainloop()


if __name__ == "__main__":
    main()
