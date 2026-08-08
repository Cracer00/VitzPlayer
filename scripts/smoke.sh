#!/usr/bin/env bash
# Сквозная проверка сервера: вход в админку → загрузка файла → ингест → инвайт →
# регистрация пользователя → каталог → стрим с Range → плейлист и лайк.
#
#   ./scripts/smoke.sh http://localhost:8080 admin@example.com <пароль> <файл.mp3>
#
# Всё, что скрипт создаёт, остаётся в базе: он рассчитан на тестовый стенд, не на боевой.
set -euo pipefail

BASE=${1:-http://localhost:8080}
ADMIN_EMAIL=${2:-admin@example.com}
ADMIN_PASSWORD=${3:?нужен пароль администратора}
AUDIO=${4:?нужен путь к аудиофайлу}

JAR=$(mktemp)
trap 'rm -f "$JAR"' EXIT

step() { printf '\n\033[1m== %s\033[0m\n' "$1"; }
json() { python -c "import sys,json;d=json.load(sys.stdin);print(eval('d'+sys.argv[1]))" "$1"; }

step "Здоровье"
curl -fsS "$BASE/healthz" && echo

step "Вход в админку"
curl -fsS -c "$JAR" -X POST "$BASE/admin/login" \
  --data-urlencode "email=$ADMIN_EMAIL" --data-urlencode "password=$ADMIN_PASSWORD" -o /dev/null
CSRF=$(curl -fsS -b "$JAR" "$BASE/admin/upload" | grep -o 'data-csrf="[^"]*"' | head -1 | cut -d'"' -f2)
[ -n "$CSRF" ] || { echo "не удалось войти: нет csrf"; exit 1; }
echo "csrf получен"

step "Загрузка файла"
NAME=$(basename "$AUDIO")
curl -fsS -b "$JAR" -H "X-CSRF: $CSRF" --data-binary "@$AUDIO" \
  "$BASE/admin/upload?name=$NAME" && echo

step "Ожидание обработки"
for _ in $(seq 1 60); do
  FRAGMENT=$(curl -fsS -b "$JAR" "$BASE/admin/jobs/fragment")
  if echo "$FRAGMENT" | grep -q 'pill done'; then echo "задание выполнено"; break; fi
  if echo "$FRAGMENT" | grep -qE 'pill (failed|needs_attention)'; then
    echo "задание не прошло:"; echo "$FRAGMENT" | grep -o '<span class="log">[^<]*' | tail -3; exit 1
  fi
  sleep 2
done

step "Инвайт"
curl -fsS -b "$JAR" -X POST "$BASE/admin/invites" \
  --data-urlencode "csrf=$CSRF" --data-urlencode "note=smoke" -o /dev/null
INVITE=$(curl -fsS -b "$JAR" "$BASE/admin/users" | grep -oE '[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}' | head -1)
echo "код: $INVITE"

step "Регистрация и вход пользователя"
EMAIL="smoke-$RANDOM@example.com"
TOKENS=$(curl -fsS -X POST "$BASE/api/v1/auth/register" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"smoke-password\",\"displayName\":\"Проверка\",\"invite\":\"$INVITE\"}")
ACCESS=$(echo "$TOKENS" | json "['accessToken']")
REFRESH=$(echo "$TOKENS" | json "['refreshToken']")
AUTH="Authorization: Bearer $ACCESS"
curl -fsS -H "$AUTH" "$BASE/api/v1/me" && echo

step "Обновление токена"
curl -fsS -X POST "$BASE/api/v1/auth/refresh" -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$REFRESH\"}" -o /dev/null && echo "refresh работает"

step "Каталог"
TRACKS=$(curl -fsS -H "$AUTH" "$BASE/api/v1/tracks?limit=5")
TRACK_ID=$(echo "$TRACKS" | json "['items'][0]['id']")
MEDIA=$(echo "$TRACKS" | json "['items'][0]['media'][0]['url']")
echo "трек $TRACK_ID"

step "Стрим с Range"
CODE=$(curl -fsS -o /dev/null -w '%{http_code}' -r 0-1023 "$MEDIA")
[ "$CODE" = "206" ] || { echo "ожидали 206 Partial Content, получили $CODE"; exit 1; }
echo "206 Partial Content, докачка работает"

step "Подпись обязательна"
CODE=$(curl -s -o /dev/null -w '%{http_code}' "${MEDIA%%\?*}")
[ "$CODE" = "400" ] || [ "$CODE" = "403" ] || { echo "без подписи отдалось с кодом $CODE"; exit 1; }
echo "без подписи не отдаётся ($CODE)"

step "Плейлист"
PL=$(curl -fsS -H "$AUTH" -H 'Content-Type: application/json' -X POST "$BASE/api/v1/playlists" \
  -d '{"title":"Проверка связи"}')
PL_ID=$(echo "$PL" | json "['id']")
curl -fsS -H "$AUTH" -H 'Content-Type: application/json' -X POST "$BASE/api/v1/playlists/$PL_ID/items" \
  -d "{\"trackIds\":[\"$TRACK_ID\"]}" -o /dev/null
COUNT=$(curl -fsS -H "$AUTH" "$BASE/api/v1/playlists/$PL_ID" | json "['playlist']['trackCount']")
[ "$COUNT" = "1" ] || { echo "трек в плейлист не попал"; exit 1; }
echo "плейлист $PL_ID, треков: $COUNT"

step "Лайк"
curl -fsS -H "$AUTH" -X PUT "$BASE/api/v1/likes/$TRACK_ID" -o /dev/null
LIKED=$(curl -fsS -H "$AUTH" "$BASE/api/v1/likes" | json "['total']")
[ "$LIKED" = "1" ] || { echo "лайк не сохранился"; exit 1; }
echo "понравившихся: $LIKED"

step "Синхронизация"
SYNC=$(curl -fsS -H "$AUTH" "$BASE/api/v1/sync?limit=100")
echo "$SYNC" | python -c "import sys,json;d=json.load(sys.stdin);print('треков: %d, плейлистов: %d, лайков: %d' % (len(d['tracks']), len(d['playlists']), len(d['likedTrackIds'])))"
CURSOR=$(echo "$SYNC" | json "['cursor']")
AGAIN=$(curl -fsS -H "$AUTH" --get --data-urlencode "since=$CURSOR" "$BASE/api/v1/sync")
echo "$AGAIN" | python -c "
import sys,json
d=json.load(sys.stdin)
n=len(d['tracks'])
print('повторный запрос с курсором вернул треков: %d' % n)
sys.exit(0 if n == 0 else 1)
" || { echo 'курсор не двигается — синхронизация зациклится'; exit 1; }

printf '\n\033[32mВсё сошлось.\033[0m\n'
