# Развёртывание

Домен — **`music.nethound.ru`**.

Сервер не пустой: на нём стоит **Dokploy**, и его Traefik уже держит 80 и 443. Поэтому своего
обратного прокси у нас нет — сервис встраивается в существующий Traefik лейблами, а сертификат
тот выпускает сам. Dokploy мы не трогаем и через его интерфейс ничего не заводим.

```
git push → Actions ──▶ тесты ──▶ образ ──▶ ghcr.io/<владелец>/vitz-music-server:sha-<коммит>
                                              │
                                   ssh deploy@music.nethound.ru
                                              ▼
                            scripts/deploy.sh: pull → up -d → /healthz
                                   не ожил ──▶ откат на предыдущий образ

           внешний мир ──▶ dokploy-traefik :443 ──▶ (dokploy-network) ──▶ server:8080
```

**Gradle на сервере не запускается никогда**: сборка съедает до 2 ГБ, ей место в CI.

---

## 1. Файлы конфигурации

| Файл | Когда применяется |
|---|---|
| `docker-compose.yml` | всегда: база и сервер, без прокси и без опубликованных портов |
| `docker-compose.prod.yml` | на сервере: лейблы Traefik и подключение к `dokploy-network` |
| `docker-compose.dev.yml` | локально: порты наружу (5434 у базы, 8080 у сервера) |

На сервере всегда оба файла:

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml <команда>
```

`scripts/deploy.sh` это делает сам. Если запускаете команды руками — не забывайте второй `-f`,
иначе контейнер поднимется без лейблов и снаружи будет недоступен.

## 2. Что ожидается от сервера

Проверено на этой машине:

| | |
|---|---|
| сеть | `dokploy-network`, overlay, `attachable=true` |
| entrypoints | `web` (:80) и `websecure` (:443) |
| резолвер сертификатов | `letsencrypt`, httpChallenge через `web` |
| provider | docker, `exposedByDefault: false` |

Если Dokploy обновится и что-то из этого переименует — править `docker-compose.prod.yml`,
больше нигде эти имена не встречаются. Посмотреть текущее:

```bash
docker run --rm -v /etc/dokploy/traefik:/t:ro alpine cat /t/traefik.yml
```

## 3. DNS

A-запись `music.nethound.ru` на адрес сервера. Traefik выпускает сертификат по httpChallenge,
то есть 80-й порт снаружи должен доходить до сервера — для SSH он уже доходит, значит
маршрут есть.

## 4. Ключ для деплоя

На своей машине:

```bash
ssh-keygen -t ed25519 -f ~/.ssh/vitz-deploy -C "deploy@ci"
```

Парольную фразу оставить пустой — иначе ключом не сможет пользоваться CI.

## 5. Разовая подготовка сервера

```bash
sudo bash server-setup.sh "$(cat ~/.ssh/vitz-deploy.pub)"
```

Создаёт пользователя `deploy` (только по ключу, без пароля и sudo — для контейнеров хватает
группы `docker`), ставит Docker, если его нет, готовит `/srv/vitz-music`. На машине с Dokploy
Docker уже стоит, скрипт это увидит и переустанавливать не станет.

> Файрвол скрипт настраивает по умолчанию (22/80/443). На сервере с Dokploy проверьте, что
> это не отрежет его собственные порты — например 3000 у панели. При сомнениях запускайте
> с уже включённым ufw: правила только добавляются.

## 6. Настройки на сервере

`/srv/vitz-music/.env` по образцу `.env.example`. Секреты — по отдельности:

```bash
openssl rand -hex 32
```

```
VM_DOMAIN=music.nethound.ru
POSTGRES_PASSWORD=<первый секрет>
VM_JWT_SECRET=<второй секрет>
VM_MEDIA_SIGN_SECRET=<третий секрет>
VM_BOOTSTRAP_ADMIN_EMAIL=<ваша почта>
VM_BOOTSTRAP_ADMIN_PASSWORD=<временный пароль>
```

`VM_DOMAIN` попадает и в правило маршрутизации Traefik, и в `VM_PUBLIC_URL` — то есть в
подписанные ссылки на медиа. Менять его потом можно, но выданные ссылки протухнут.

Сервер откажется стартовать, если домен не `localhost`, а секреты остались из примера.
`VM_IMAGE` вписывать не нужно — её ставит деплой-скрипт, по ней же видно текущую версию.

## 7. Доступ к GHCR

Пакет приватный, поэтому серверу нужен токен на чтение. GitHub → Settings → Developer
settings → Personal access tokens (classic), право `read:packages`. На сервере под `deploy`:

```bash
echo <токен> | docker login ghcr.io -u <логин на GitHub> --password-stdin
```

## 8. Секреты GitHub

Settings → Secrets and variables → Actions:

| Секрет | Значение |
|---|---|
| `DEPLOY_HOST` | `music.nethound.ru` |
| `DEPLOY_USER` | `deploy` |
| `DEPLOY_SSH_KEY` | содержимое `~/.ssh/vitz-deploy` целиком, вместе со строками `BEGIN`/`END` |
| `DEPLOY_HOST_KEY` | вывод `ssh-keyscan -H music.nethound.ru` |

Токен для публикации образа не нужен — Actions пользуется своим `GITHUB_TOKEN`.

## 9. Первый деплой

```bash
git push origin main
```

Первый прогон — минут пять (кеш слоёв пустой), дальше полторы-две.

```bash
curl -fsS https://music.nethound.ru/healthz
```

Сертификат появляется при первом обращении к домену, не мгновенно после запуска контейнера.
Если отдаётся 404 от Traefik — значит лейблы не доехали: почти всегда это `up -d` без
второго `-f docker-compose.prod.yml`.

## 10. Первый вход

`https://music.nethound.ru/admin` — почта и пароль из `VM_BOOTSTRAP_ADMIN_*`.

1. Сменить пароль: `/admin/users`, поле «Новый пароль» в своей строке → «сменить».
   Прежние сессии этого пользователя при этом отзываются — важно, если пароль уходил
   куда-то в переписку или лежал в файле.
2. Убрать `VM_BOOTSTRAP_ADMIN_*` из `.env` и перезапустить сервер:

   ```bash
   cd /srv/vitz-music
   sed -i '/^VM_BOOTSTRAP_ADMIN_/d' .env
   docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d server
   ```

   Администратор уже создан, эти переменные больше ни на что не влияют, а пароль открытым
   текстом на диске — лишний риск. Даже если оставить их, второго администратора они не
   создадут: пользователь заводится только когда в базе нет вообще ни одного.
3. Выпустить себе инвайт на `/admin/users` — под этим пользователем будет ходить плеер.

Обычный пользователь меняет себе пароль через API — `POST /api/v1/me/password`
(`currentPassword`, `newPassword`); все прочие его сессии при этом обрываются, текущая
остаётся. Это же будет использовать Android-приложение.

## 11. Откат

Каждый образ помечен коммитом:

```bash
cd /srv/vitz-music
./scripts/deploy.sh ghcr.io/<владелец>/vitz-music-server:sha-<коммит>
```

Автоматический откат встроен: если новая версия не ответила на `/healthz` за минуту, деплой
сам возвращает предыдущий образ и падает с ошибкой в Actions.

**Чего откат не отменяет — миграции базы.** Flyway катит их только вперёд. Пока схема растёт,
это безопасно; когда дойдёт до удаления колонок — удалять отдельным релизом, через пару версий
после того, как код перестал их читать.

## 12. Бэкапы

```bash
cd /srv/vitz-music && ./scripts/backup.sh
```

В cron:

```
20 3 * * * cd /srv/vitz-music && ./scripts/backup.sh >> /var/log/vitz-backup.log 2>&1
```

Копию уносить с сервера: бэкап на том же диске спасает от «удалил не то», но не от смерти
диска. **Восстановление проверьте сразу** — бэкап, который ни разу не разворачивали, бэкапом
не является:

```bash
./scripts/restore.sh /var/backups/vitz-music/db/<дата>.dump
```

## 13. Диагностика

```bash
cd /srv/vitz-music
alias c='docker compose -f docker-compose.yml -f docker-compose.prod.yml'

c logs -f server                  # ошибки приложения, миграции, задания ингеста
c ps                              # что поднято и здорово ли
docker logs dokploy-traefik --tail 50 | grep -i music   # маршрут и сертификат
grep VM_IMAGE .env                # какая версия сейчас работает
docker stats --no-stream          # память: ffmpeg на ингесте самый прожорливый
```

Проверить, что Traefik увидел сервис (панель Traefik слушает локально, `api.insecure: true`):

```bash
curl -s http://localhost:8080/api/http/routers | grep -o 'vitzmusic[^"]*'
```

Предел размера загружаемого файла теперь один — `VM_UPLOAD_MAX_BYTES` в `.env`. Traefik тело
запроса по умолчанию не ограничивает, отдельной настройки прокси больше нет.

## 14. Собрать и залить руками, без CI

```bash
docker build -f server/Dockerfile -t ghcr.io/<владелец>/vitz-music-server:manual .
docker push ghcr.io/<владелец>/vitz-music-server:manual
ssh deploy@music.nethound.ru "/srv/vitz-music/scripts/deploy.sh ghcr.io/<владелец>/vitz-music-server:manual"
```
