# Enotik sborki

Выбираешь файл, ZIP или папку (или шлёшь через «Поделиться») → ставишь куда нужно.

## Сборка APK
GitHub → вкладка Actions → Build APK → Run workflow → внизу Artifacts → `Enotik-sborki-apk`.

## Что умеет
- **ZIP**: открывает архив и берёт самую первую папку внутри (если папок нет — все файлы).
- **Плавающая кнопка «E»** поверх всех приложений (настройки → «Плавающая кнопка»). Остаётся, даже если выйти из приложения.
- **Куда ставить**: любая папка, выбранная системой или другим проводником (MiXplorer, Solid Explorer, OI File Manager…), путь вручную, либо Android/data и Android/obb выбранного приложения.
- **Android/data**: напрямую (Android ≤10) → **Shizuku** → root → доступ через системный выбор.
- 4 действия: ▶ Поставить, ✚ Записать кнопку, 🗑 Удалить кнопку, ✎ Когда ставить.
- **⚡ Поставить сразу** — без сохранения кнопки, просто выбрать куда.

## Текст «когда ставить»
Локальный разбор по ключевым словам (не нейросеть): «в папку files/mods», «не заменять, если файл есть».

## Shizuku
Установи Shizuku, запусти его, затем в приложении нажми «Shizuku» и дай разрешение.

## Enotik Launcher
Лаунчер Minecraft: Java Edition для Android на базе [Amethyst-Android](https://github.com/AngelAuraMC/Amethyst-Android) (LGPL-3.0, преемник PojavLauncher).
Исходник Amethyst скачивается при сборке, поверх накладываются оптимизации из `launcher/patch.py`.
Сборка: Actions → «Build Enotik Launcher» → Run workflow → Artifacts → `Enotik-Launcher-apk`.
Нужен лицензионный аккаунт Minecraft.
