#!/usr/bin/env bash
# Создаёт новый мод из шаблона: ./scripts/new-mod.sh <mod_id> ["Название мода"]
set -euo pipefail

cd "$(dirname "$0")/.."

mod_id="${1:-}"
mod_name="${2:-$mod_id}"

if [[ ! "$mod_id" =~ ^[a-z][a-z0-9_]{1,63}$ ]]; then
    echo "Использование: $0 <mod_id> [\"Название мода\"]" >&2
    echo "mod_id должен соответствовать [a-z][a-z0-9_]{1,63}" >&2
    exit 1
fi

target="mods/$mod_id"
if [[ -e "$target" ]]; then
    echo "$target уже существует" >&2
    exit 1
fi

# snake_case -> PascalCase для имени главного класса
class_name="$(sed -E 's/(^|_)([a-z0-9])/\U\2/g' <<<"$mod_id")"

mkdir -p mods
cp -r templates/mod "$target"

# Переименовываем файлы и папки с плейсхолдерами (глубокие пути первыми)
find "$target" -depth -name '*__*' | while read -r path; do
    new="$(dirname "$path")/$(basename "$path" | sed "s/__MODID__/$mod_id/g; s/__CLASS__/$class_name/g")"
    [[ "$path" != "$new" ]] && mv "$path" "$new"
done

find "$target" -type f -exec sed -i \
    -e "s/__MODID__/$mod_id/g" \
    -e "s/__CLASS__/$class_name/g" \
    -e "s/__MODNAME__/$mod_name/g" {} +

echo "Создан мод $target (главный класс ru.shedevrograd.$mod_id.$class_name)"
echo "Запуск клиента: ./gradlew :mods:$mod_id:runClient"
