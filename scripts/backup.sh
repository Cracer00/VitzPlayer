#!/usr/bin/env bash
# Бэкап: дамп базы + инкрементальная копия медиа.
#
# Медиа копируются, а не архивируются: файлы адресуются по SHA-256 и никогда не меняются,
# поэтому `cp -au` каждый раз переносит только новое. Архив пришлось бы пересобирать целиком.
#
#   ./scripts/backup.sh
#   BACKUP_DIR=/mnt/nas/vitz-music ./scripts/backup.sh
set -euo pipefail

cd "$(dirname "$0")/.."
set -a && . ./.env && set +a

BACKUP_DIR=${BACKUP_DIR:-/var/backups/vitz-music}
KEEP_DAYS=${KEEP_DAYS:-14}
PROJECT=${COMPOSE_PROJECT_NAME:-vitzmusic}
MEDIA_VOLUME=${MEDIA_VOLUME:-${PROJECT}_media}
STAMP=$(date +%Y-%m-%d_%H%M)

mkdir -p "$BACKUP_DIR/db" "$BACKUP_DIR/media"

echo "== База =="
docker compose exec -T postgres pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom \
    > "$BACKUP_DIR/db/$STAMP.dump"
echo "$BACKUP_DIR/db/$STAMP.dump — $(du -h "$BACKUP_DIR/db/$STAMP.dump" | cut -f1)"

echo "== Медиа (том $MEDIA_VOLUME) =="
docker run --rm \
    -v "$MEDIA_VOLUME":/media:ro \
    -v "$BACKUP_DIR/media":/backup \
    alpine sh -c 'cp -au /media/. /backup/ && du -sh /backup'

echo "== Чистка дампов старше $KEEP_DAYS дней =="
find "$BACKUP_DIR/db" -name '*.dump' -mtime +"$KEEP_DAYS" -print -delete

echo
echo "Готово. Бэкап, который ни разу не разворачивали, — это не бэкап:"
echo "раз в квартал прогоняйте ./scripts/restore.sh $BACKUP_DIR/db/$STAMP.dump"
