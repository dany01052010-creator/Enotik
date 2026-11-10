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

# 4. Качество графики и яркость ниже системной
import shutil, os
here = os.path.dirname(os.path.abspath(__file__)) + "/extra/"
J = P + "src/main/java/net/kdt/pojavlaunch/"
for f in ("EnotikBrightness.java", "EnotikQuality.java"):
    shutil.copy(here + f, root + J + "utils/" + f)
shutil.copy(here + "enotik_values.xml", root + P + "src/main/res/values/enotik_values.xml")

patch(J + "BaseActivity.java", [
    ('        Tools.checkStorageInteractive(this);\n',
     '        Tools.checkStorageInteractive(this);\n        net.kdt.pojavlaunch.utils.EnotikBrightness.apply(this);\n'),
])
patch(J + "MainActivity.java", [
    ('        MCOptionUtils.load(gameDirPath);\n',
     '        MCOptionUtils.load(gameDirPath);\n        net.kdt.pojavlaunch.utils.EnotikQuality.apply(this);\n'),
])
patch(P + "src/main/res/xml/pref_video.xml", [
    ('    <PreferenceCategory android:title="@string/preference_category_video" >',
     '    <PreferenceCategory android:title="@string/enotik_cat_title" >\n'
     '        <ListPreference\n'
     '            android:key="enotikQuality"\n'
     '            android:title="@string/enotik_quality_title"\n'
     '            android:summary="@string/enotik_quality_summary"\n'
     '            android:entries="@array/enotik_quality_entries"\n'
     '            android:entryValues="@array/enotik_quality_values"\n'
     '            android:defaultValue="0"\n'
     '            />\n'
     '        <net.kdt.pojavlaunch.prefs.CustomSeekBarPreference\n'
     '            android:key="enotikBrightness"\n'
     '            android:title="@string/enotik_brightness_title"\n'
     '            android:summary="@string/enotik_brightness_summary"\n'
     '            android:defaultValue="100"\n'
     '            app2:showSeekBarValue="true"\n'
     '            app2:selectable="false"\n'
     '            app2:min="1"\n'
     '            app2:seekBarIncrement="1"\n'
     '            />\n'
     '    </PreferenceCategory>\n\n'
     '    <PreferenceCategory android:title="@string/preference_category_video" >'),
])
patch(J + "prefs/screens/LauncherPreferenceVideoFragment.java", [
    ('        resolutionSeekbar.setSuffix(" %");\n',
     '        resolutionSeekbar.setSuffix(" %");\n'
     '        CustomSeekBarPreference enotikBright = requirePreference("enotikBrightness", CustomSeekBarPreference.class);\n'
     '        enotikBright.setSuffix(" %");\n'
     '        enotikBright.setOnPreferenceChangeListener((pref, value) -> {\n'
     '            net.kdt.pojavlaunch.utils.EnotikBrightness.apply(requireActivity(), (Integer) value);\n'
     '            return true;\n'
     '        });\n'),
])
print("patched OK")

# 5. Раздел модов (Modrinth): Activity, кнопка в главном меню, запись в манифесте
shutil.copy(here + "EnotikModsActivity.java", root + J + "EnotikModsActivity.java")
patch(P + "src/main/AndroidManifest.xml", [
    ('        <activity\n            android:name=".LauncherActivity"\n',
     '        <activity\n            android:name=".EnotikModsActivity"\n            android:exported="false"\n'
     '            android:label="Моды" />\n'
     '        <activity\n            android:name=".LauncherActivity"\n'),
])
OPEN_BTN = '\t\t\t<com.kdt.mcgui.LauncherMenuButton\n\t\t\t\tandroid:id="@+id/open_files_button"'
MODS_BTN = (
    '\t\t\t<com.kdt.mcgui.LauncherMenuButton\n'
    '\t\t\t\tandroid:id="@+id/enotik_mods_button"\n'
    '\t\t\t\tstyle="@style/LauncherMenuButton.Universal"\n'
    '\t\t\t\tandroid:layout_width="match_parent"\n'
    '\t\t\t\tandroid:drawableStart="@drawable/ic_menu_install_jar"\n'
    '\t\t\t\tandroid:text="@string/enotik_mods_button"\n'
    '\t\t\t\tapp:layout_constraintTop_toBottomOf="@id/share_logs_button" />\n\n')
patch(P + "src/main/res/layout/fragment_launcher.xml", [
    (OPEN_BTN, MODS_BTN + OPEN_BTN),
    ('\t\t\t\tapp:layout_constraintTop_toBottomOf="@id/share_logs_button" />\n\t\t</androidx',
     '\t\t\t\tapp:layout_constraintTop_toBottomOf="@id/enotik_mods_button" />\n\t\t</androidx'),
])
patch(J + "fragments/MainMenuFragment.java", [
    ('        Button mOpenDirectoryButton = view.findViewById(R.id.open_files_button);\n',
     '        Button mOpenDirectoryButton = view.findViewById(R.id.open_files_button);\n'
     '        view.findViewById(R.id.enotik_mods_button).setOnClickListener(v ->\n'
     '                startActivity(new Intent(requireContext(), net.kdt.pojavlaunch.EnotikModsActivity.class)));\n'),
])
print("patched mods OK")
