@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo Устанавливаю нужные библиотеки (при первом запуске это займёт пару минут)...
python -m pip install -r requirements.txt
echo.
echo Запускаю ModHelper...
python main.py
if errorlevel 1 pause
