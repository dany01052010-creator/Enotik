# Сюда клади свои игры (Android-проекты)

Положи сюда проект целиком, либо одним **zip-файлом** (Add file → Upload files), либо папкой с файлами.
В проекте должен быть `settings.gradle` или `settings.gradle.kts`.

После загрузки (Commit changes) сборка запустится сама:
Actions → «Build games» → верхний запуск → Artifacts → `games-apk`.

Править файлы можно прямо на сайте: открыть файл → карандаш → Commit changes (zip сначала нужно распаковать у себя и загрузить папкой).
Версия Java: положи в папку проекта файл `java-version` с числом (например `21`), по умолчанию 17.

## Большой файл (больше 25 МБ)
Сайт не принимает такие файлы в обычную загрузку. Используй Releases (до 2 ГБ):
1. Репозиторий → справа «Releases» → «Create a new release».
2. В поле тега впиши ровно `games` и выбери «Create new tag».
3. Внизу в «Attach binaries» добавь свой **zip** с проектом (в нём должен быть settings.gradle).
4. Нажми «Publish release». Сборка запустится сама, APK будет в Actions → «Build games» → Artifacts.
