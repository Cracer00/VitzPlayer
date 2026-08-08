#!/usr/bin/env bash
# Восстановление базы из дампа. Бэкап, который ни разу не разворачивали, — это не бэкап,
# поэтому прогоняйте это хотя бы раз в квартал (можно на отдельной машине).
#
#   ./scripts/restore.sh /var/backups/vitz-music/db/2026-08-08_0300.dump
set -euo pipefail

DUMP=${1:?укажите файл дампа}
cd "$(dirname "$0")/.."
set -a && . ./.env && set +a

echo "Восстановление $DUMP в базу $POSTGRES_DB."
echo "ВНИМАНИЕ: текущее содержимое базы будет заменено."
read -r -p "Продолжить? [y/N] " answer
[ "$answer" = "y" ] || { echo "Отменено"; exit 1; }

# Сервер останавливаем: иначе он держит соединения и пишет в базу во время восстановления.
docker compose stop server

docker compose exec -T postgres pg_restore \
    -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
    --clean --if-exists --no-owner --single-transaction < "$DUMP"

docker compose start server

echo "Готово. Проверьте: curl -fsS https://\$VM_DOMAIN/healthz"
