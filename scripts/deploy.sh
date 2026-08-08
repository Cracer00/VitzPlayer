#!/usr/bin/env bash
# Выполняется НА СЕРВЕРЕ, обычно из GitHub Actions по SSH.
#
#   ./scripts/deploy.sh ghcr.io/<владелец>/vitz-music-server:sha-<коммит>
#
# Тянет указанный образ, поднимает сервис и убеждается, что он ожил. Если не ожил —
# возвращает предыдущий образ: пусть лучше работает старая версия, чем никакая.
set -euo pipefail

IMAGE=${1:?укажите образ}
cd "$(dirname "$0")/.."

[ -f .env ] || { echo "Нет .env на сервере — создайте по .env.example"; exit 1; }

# Боевой оверлей обязателен: в нём лейблы Traefik и подключение к dokploy-network.
# Без него сервис поднимется, но снаружи будет недоступен.
compose() { docker compose -f docker-compose.yml -f docker-compose.prod.yml "$@"; }

PREVIOUS=$(grep -E '^VM_IMAGE=' .env | cut -d= -f2- || true)
echo "Было:  ${PREVIOUS:-(не задан)}"
echo "Ставим: $IMAGE"

set_image() {
    if grep -qE '^VM_IMAGE=' .env; then
        sed -i "s|^VM_IMAGE=.*|VM_IMAGE=$1|" .env
    else
        printf 'VM_IMAGE=%s\n' "$1" >> .env
    fi
}

set_image "$IMAGE"

compose pull server
compose up -d

echo "== Ждём, пока сервер ответит =="
healthy=0
for _ in $(seq 1 30); do
    if compose exec -T server curl -fsS http://localhost:8080/healthz >/dev/null 2>&1; then
        healthy=1
        break
    fi
    sleep 2
done

if [ "$healthy" != "1" ]; then
    echo "Новая версия не отвечает на /healthz. Логи:"
    compose logs --tail 40 server || true
    if [ -n "$PREVIOUS" ]; then
        echo "== Откат на $PREVIOUS =="
        set_image "$PREVIOUS"
        compose up -d server
    fi
    exit 1
fi

echo "== Готово: $(compose exec -T server curl -fsS http://localhost:8080/healthz) =="
docker image prune -f --filter "until=168h" >/dev/null 2>&1 || true
