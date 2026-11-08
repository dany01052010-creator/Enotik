#!/usr/bin/env python3
"""Применяет оптимизации Enotik Launcher к исходникам Amethyst-Android.
Каждая замена должна найтись ровно один раз, иначе сборка падает (чтобы не получить молча «не тот» APK).
Использование: python3 patch.py <путь к Amethyst-Android>
"""
import sys

root = sys.argv[1].rstrip("/") + "/"
P = "app_pojavlauncher/"

def patch(path, pairs):
    s = open(root + path, encoding="utf-8").read()
    for old, new in pairs:
        n = s.count(old)
        if n != 1:
            sys.exit("PATCH FAILED: %r found %d times in %s" % (old[:70], n, path))
        s = s.replace(old, new)
    open(root + path, "w", encoding="utf-8").write(s)

# 1. Название
patch(P + "build.gradle", [
    ('resValue "string", "app_name", "Amethyst (Debug)"', 'resValue "string", "app_name", "Enotik Launcher"'),
    ('resValue "string", "app_short_name", "Amethyst (Debug)"', 'resValue "string", "app_short_name", "Enotik Launcher"'),
    ('resValue "string", "app_name", "Amethyst"\n', 'resValue "string", "app_name", "Enotik Launcher"\n'),
    ('resValue "string", "app_short_name", "Amethyst"\n', 'resValue "string", "app_short_name", "Enotik Launcher"\n'),
])
patch(P + "src/main/res/values/strings.xml", [
    ('<string name="app_name" translatable="false">Amethyst</string>',
     '<string name="app_name" translatable="false">Enotik Launcher</string>'),
])

# 2. Настройки по умолчанию: большие ядра на телефонах с разными ядрами, больше памяти на 8+ ГБ
patch(P + "src/main/java/net/kdt/pojavlaunch/prefs/LauncherPreferences.java", [
    ('DEFAULT_PREF.getBoolean("bigCoreAffinity", false)',
     'DEFAULT_PREF.getBoolean("bigCoreAffinity", !hasAllCoreSameFreq())'),
    ('        return 2048; //Default RAM allocation for 64 bits',
     '        if (deviceRam >= 8192) return 3072;\n        return 2048; //Default RAM allocation for 64 bits'),
])

# 3. JVM: Serial GC на небольших кучах (меньше накладных расходов, чем у G1 на телефоне),
#    если пользователь сам не указал сборщик мусора
patch(P + "src/main/java/net/kdt/pojavlaunch/utils/JREUtils.java", [
    ('        userArgs.add("-Xmx" + LauncherPreferences.PREF_RAM_ALLOCATION + "M");\n',
     '        userArgs.add("-Xmx" + LauncherPreferences.PREF_RAM_ALLOCATION + "M");\n'
     '        // Enotik: быстрый сборщик мусора для небольших куч, если пользователь не выбрал свой\n'
     '        boolean enotikUserGc = false;\n'
     '        for (String enotikArg : userArgs) {\n'
     '            if (enotikArg.startsWith("-XX:+Use") && enotikArg.endsWith("GC")) enotikUserGc = true;\n'
     '        }\n'
     '        if (!enotikUserGc && LauncherPreferences.PREF_RAM_ALLOCATION <= 3072) userArgs.add("-XX:+UseSerialGC");\n'
     '        userArgs.add("-XX:+DisableExplicitGC");\n'),
])
print("patched OK")
