# Shedevrograd Mods

Моды и плагины для сервера Shedevrograd — Minecraft **1.21.1**, **NeoForge 21.1.x**.

## Структура

| Папка | Что там |
|---|---|
| [`mods/`](mods/) | Готовые jar-файлы модов (сторонние и собранные здесь) |
| [`plugins/`](plugins/) | Готовые jar-файлы плагинов |
| [`dev/`](dev/) | Исходники: Gradle мульти-проект для разработки новых модов и плагинов |

```
dev/
├── build-logic/           # общая конфигурация сборки (convention-плагин shedevrograd.neoforge-mod)
├── templates/mod/         # шаблон нового мода
├── scripts/new-mod.sh     # генератор мода из шаблона
├── mods/<mod_id>/         # исходники модов — подключаются автоматически как :mods:<mod_id>
├── plugins/<id>/          # исходники плагинов — подключаются как :plugins:<id>
└── gradle.properties      # общие версии Minecraft / NeoForge / Parchment
```

## Требования

- JDK 21 (`sudo pacman -S jdk21-openjdk` на Arch)
- Gradle ставить не нужно — используется wrapper `dev/gradlew`

## Работа с модами

Все команды выполняются из папки `dev/`.

```sh
# Создать новый мод (пакет ru.shedevrograd.<mod_id>)
./scripts/new-mod.sh my_mod "My Mod"

# Запустить клиент / сервер с модом
./gradlew :mods:my_mod:runClient
./gradlew :mods:my_mod:runServer

# Собрать jar (dev/mods/my_mod/build/libs/)
./gradlew :mods:my_mod:build

# Собрать и положить jar в корневую папку mods/
./gradlew :mods:my_mod:deploy

# Собрать и выложить все моды разом
./gradlew deploy
```

Метаданные мода (`mod_id`, название, версия) — в `dev/mods/<mod_id>/gradle.properties`,
описание и зависимости — в `src/main/templates/META-INF/neoforge.mods.toml`.

Версии NeoForge/Minecraft меняются в одном месте — `dev/gradle.properties`.
