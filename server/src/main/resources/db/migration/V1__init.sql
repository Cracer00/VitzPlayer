-- Триграммы нужны для поиска по каталогу и для поимки почти-дублей на ингесте.
create extension if not exists pg_trgm;

-- ---------- пользователи ----------

create table users (
    id            uuid primary key default gen_random_uuid(),
    email         text not null,
    email_norm    text not null unique,
    password_hash text not null,
    display_name  text not null,
    role          text not null default 'user' check (role in ('user', 'admin')),
    created_at    timestamptz not null default now(),
    disabled_at   timestamptz
);

create table invites (
    code       text primary key,
    created_by uuid references users (id) on delete set null,
    note       text,
    created_at timestamptz not null default now(),
    expires_at timestamptz,
    used_by    uuid references users (id) on delete set null,
    used_at    timestamptz
);

create table sessions (
    id           uuid primary key default gen_random_uuid(),
    user_id      uuid not null references users (id) on delete cascade,
    refresh_hash text not null unique,
    device       text,
    created_at   timestamptz not null default now(),
    last_seen_at timestamptz not null default now(),
    expires_at   timestamptz not null,
    revoked_at   timestamptz
);

create index sessions_user_idx on sessions (user_id);

-- ---------- каталог ----------

create table covers (
    id         uuid primary key default gen_random_uuid(),
    sha256     text not null unique,
    width      int  not null,
    height     int  not null,
    created_at timestamptz not null default now()
);

create table artists (
    id         uuid primary key default gen_random_uuid(),
    name       text not null,
    name_norm  text not null unique,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index artists_name_trgm on artists using gin (name_norm gin_trgm_ops);

create table albums (
    id         uuid primary key default gen_random_uuid(),
    artist_id  uuid not null references artists (id) on delete cascade,
    title      text not null,
    title_norm text not null,
    year       int,
    cover_id   uuid references covers (id) on delete set null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (artist_id, title_norm)
);

create index albums_title_trgm on albums using gin (title_norm gin_trgm_ops);

create table tracks (
    id           uuid primary key default gen_random_uuid(),
    artist_id    uuid   not null references artists (id) on delete restrict,
    album_id     uuid references albums (id) on delete set null,
    title        text   not null,
    title_norm   text   not null,
    track_no     int,
    disc_no      int,
    duration_ms  bigint not null,
    year         int,
    genre        text,
    cover_id     uuid references covers (id) on delete set null,
    audio_sha256 text   not null,
    size_bytes   bigint not null,
    format       text   not null,
    bitrate      int,
    sample_rate  int,
    channels     int,
    gain_db      double precision,
    source       text   not null default 'upload',
    source_url   text,
    added_by     uuid references users (id) on delete set null,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    deleted_at   timestamptz
);

-- Побайтовый дубль отклоняется на ингесте; после удаления трека тот же файл можно залить снова.
create unique index tracks_sha_unique on tracks (audio_sha256) where deleted_at is null;
create index tracks_title_trgm on tracks using gin (title_norm gin_trgm_ops);
create index tracks_artist_idx on tracks (artist_id);
create index tracks_album_idx on tracks (album_id);
-- Курсор синхронизации: (updated_at, id) строго возрастает и уникален в паре.
create index tracks_sync_idx on tracks (updated_at, id);

create table renditions (
    track_id   uuid   not null references tracks (id) on delete cascade,
    kind       text   not null,
    sha256     text   not null,
    ext        text   not null,
    size_bytes bigint not null,
    bitrate    int,
    created_at timestamptz not null default now(),
    primary key (track_id, kind)
);

-- ---------- плейлисты и лайки ----------

create table playlists (
    id          uuid primary key default gen_random_uuid(),
    user_id     uuid not null references users (id) on delete cascade,
    title       text not null,
    description text,
    cover_id    uuid references covers (id) on delete set null,
    is_public   boolean not null default false,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    deleted_at  timestamptz
);

create index playlists_user_idx on playlists (user_id);
create index playlists_sync_idx on playlists (updated_at, id);

create table playlist_items (
    playlist_id uuid not null references playlists (id) on delete cascade,
    track_id    uuid not null references tracks (id) on delete cascade,
    position    int  not null,
    added_at    timestamptz not null default now(),
    primary key (playlist_id, track_id)
);

-- Отложенная проверка: перенумерация всего плейлиста одним апдейтом временно ломает
-- уникальность позиций внутри транзакции, и это нормально.
alter table playlist_items
    add constraint playlist_items_position_unique unique (playlist_id, position)
        deferrable initially deferred;

create table track_likes (
    user_id  uuid not null references users (id) on delete cascade,
    track_id uuid not null references tracks (id) on delete cascade,
    liked_at timestamptz not null default now(),
    primary key (user_id, track_id)
);

create index track_likes_user_idx on track_likes (user_id, liked_at desc);

create table plays (
    id         uuid primary key default gen_random_uuid(),
    user_id    uuid   not null references users (id) on delete cascade,
    track_id   uuid   not null references tracks (id) on delete cascade,
    started_at timestamptz not null,
    ms_played  bigint not null,
    completed  boolean not null,
    created_at timestamptz not null default now()
);

create index plays_user_idx on plays (user_id, started_at desc);

-- ---------- очередь заданий ----------

create table jobs (
    id           uuid primary key default gen_random_uuid(),
    kind         text  not null,
    payload      jsonb not null,
    status       text  not null default 'queued'
        check (status in ('queued', 'running', 'done', 'failed', 'needs_attention')),
    attempts     int   not null default 0,
    max_attempts int   not null default 3,
    run_after    timestamptz not null default now(),
    locked_by    text,
    locked_at    timestamptz,
    error        text,
    log          text  not null default '',
    result       jsonb,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now()
);

create index jobs_pick_idx on jobs (run_after) where status = 'queued';
create index jobs_recent_idx on jobs (created_at desc);
