#!/usr/bin/env bash
# Разовая подготовка чистого VPS. Запускать от root:
#
#   sudo bash server-setup.sh "ssh-ed25519 AAAA... deploy@ci"
#
# Создаёт пользователя deploy с доступом только по ключу, ставит Docker, готовит /srv/vitz-music
# и закрывает всё, кроме 22/80/443. Ничего, кроме этого, не трогает.
set -euo pipefail

PUBKEY=${1:?передайте публичный ключ для деплоя одной строкой в кавычках}
DEPLOY_USER=${DEPLOY_USER:-deploy}
APP_DIR=${APP_DIR:-/srv/vitz-music}

[ "$(id -u)" = "0" ] || { echo "Нужны права root"; exit 1; }

echo "== Пользователь $DEPLOY_USER =="
if id "$DEPLOY_USER" >/dev/null 2>&1; then
    echo "уже есть"
else
    # Без пароля: вход только по ключу, sudo этому пользователю не нужен —
    # для управления контейнерами хватает группы docker.
    adduser --disabled-password --gecos "" "$DEPLOY_USER"
fi

install -d -m 700 -o "$DEPLOY_USER" -g "$DEPLOY_USER" "/home/$DEPLOY_USER/.ssh"
touch "/home/$DEPLOY_USER/.ssh/authorized_keys"
grep -qxF "$PUBKEY" "/home/$DEPLOY_USER/.ssh/authorized_keys" || echo "$PUBKEY" >> "/home/$DEPLOY_USER/.ssh/authorized_keys"
chown "$DEPLOY_USER:$DEPLOY_USER" "/home/$DEPLOY_USER/.ssh/authorized_keys"
chmod 600 "/home/$DEPLOY_USER/.ssh/authorized_keys"

echo "== Docker =="
if command -v docker >/dev/null 2>&1; then
    echo "уже стоит: $(docker --version)"
else
    curl -fsSL https://get.docker.com | sh
fi
usermod -aG docker "$DEPLOY_USER"
systemctl enable --now docker

echo "== Каталог $APP_DIR =="
install -d -m 755 -o "$DEPLOY_USER" -g "$DEPLOY_USER" "$APP_DIR" "$APP_DIR/scripts"

echo "== Файрвол =="
if command -v ufw >/dev/null 2>&1; then
    ufw allow OpenSSH
    ufw allow 80/tcp
    ufw allow 443/tcp
    ufw --force enable
    ufw status
else
    echo "ufw не установлен — пропускаю (проверьте файрвол провайдера)"
fi

echo "== Своп =="
if [ "$(free -m | awk '/^Mem:/{print $2}')" -lt 3000 ] && [ ! -f /swapfile ]; then
    fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
    grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
    echo "добавлено 2 ГБ свопа"
else
    echo "не требуется"
fi

cat <<EOF

Готово. Дальше руками, один раз:

1. Положить на сервер $APP_DIR/.env (по .env.example), в нём:
      VM_DOMAIN=music.nethound.ru
      секреты, POSTGRES_PASSWORD, VM_BOOTSTRAP_ADMIN_*

2. Разрешить серверу забирать образ из GHCR (пакет приватный):
      docker login ghcr.io -u <логин на GitHub> --password-stdin
   в качестве пароля — токен GitHub с правом read:packages.

3. Проверить, что деплой-ключ работает:
      ssh -i <приватный ключ> $DEPLOY_USER@<адрес> "docker ps"

4. Отпечаток хоста для секрета DEPLOY_HOST_KEY:
      ssh-keyscan -H <адрес>
EOF
