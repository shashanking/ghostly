-- Ghostly: two schemas, two trust levels.
--   app.*     — user data. Written only by the API.
--   content.* — the pet's personality. Written by the editor (Raman); the API only reads it.
create extension if not exists pgcrypto;

create schema if not exists app;
create schema if not exists content;

create table if not exists app.users (
  id            uuid primary key default gen_random_uuid(),
  google_sub    text not null unique,
  email         text,
  display_name  text,
  created_at    timestamptz not null default now(),
  last_seen_at  timestamptz not null default now(),
  deleted_at    timestamptz
);

create table if not exists app.devices (
  user_id      uuid not null references app.users(id) on delete cascade,
  device_id    text not null,
  model        text,
  last_seen_at timestamptz not null default now(),
  primary key (user_id, device_id)
);

create table if not exists app.pets (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null references app.users(id) on delete cascade,
  slot        smallint not null check (slot between 1 and 2),
  species     text not null check (species in ('ghost','cat','dog')),
  name        text,
  traits      jsonb not null default '{}',
  appearance  jsonb not null default '{}',
  created_at  timestamptz not null default now(),
  unique (user_id, slot)
);

create table if not exists app.pet_state (
  pet_id           uuid primary key references app.pets(id) on delete cascade,
  hunger           real not null default 100,
  energy           real not null default 100,
  happiness        real not null default 100,
  anger            real not null default 0,
  sleeping         boolean not null default false,
  sleep_started_at bigint,
  updated_at       timestamptz not null default now()
);

create table if not exists app.wallet (
  user_id     uuid primary key references app.users(id) on delete cascade,
  tokens      integer not null default 0 check (tokens >= 0),
  granted_day bigint not null default 0,
  updated_at  timestamptz not null default now()
);

create table if not exists app.streaks (
  user_id  uuid primary key references app.users(id) on delete cascade,
  streak   integer not null default 0,
  last_day bigint not null default 0
);

create table if not exists app.events (
  id      bigserial primary key,
  pet_id  uuid not null references app.pets(id) on delete cascade,
  type    text not null,
  at      timestamptz not null default now(),
  meta    jsonb not null default '{}'
);
create index if not exists events_pet_at on app.events (pet_id, at desc);

-- Editable rows. The editor changes these; publish() snapshots them into a versioned pack.
create table if not exists content.species_profiles (
  species    text primary key check (species in ('ghost','cat','dog')),
  data       jsonb not null,
  updated_at timestamptz not null default now()
);

create table if not exists content.reactions (
  id         text primary key,
  data       jsonb not null,
  enabled    boolean not null default true,
  tags       text[] not null default '{}',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists content.daily (
  day        date primary key,
  data       jsonb not null,
  updated_at timestamptz not null default now()
);

create table if not exists content.settings (
  key        text primary key,
  value      jsonb not null,
  updated_at timestamptz not null default now()
);

-- Immutable snapshots the phones download. Exactly one is active.
create table if not exists content.packs (
  version      integer primary key,
  pack         jsonb not null,
  notes        text,
  published_at timestamptz not null default now(),
  active       boolean not null default false
);
create unique index if not exists packs_one_active on content.packs (active) where active;

-- What the editor is allowed to see of users: numbers, never rows.
create or replace view app.aggregates as
select
  (select count(*) from app.users where deleted_at is null)                          as users,
  (select count(*) from app.pets)                                                     as pets,
  (select count(*) from app.pets where species = 'ghost')                             as ghosts,
  (select count(*) from app.pets where species = 'cat')                               as cats,
  (select count(*) from app.pets where species = 'dog')                               as dogs,
  (select round(avg(hunger)) from app.pet_state)                                      as avg_hunger,
  (select round(avg(happiness)) from app.pet_state)                                   as avg_happiness,
  (select round(avg(energy)) from app.pet_state)                                      as avg_energy,
  (select count(*) from app.events where at > now() - interval '24 hours')            as events_24h,
  (select count(distinct pet_id) from app.events where at > now() - interval '7 days') as active_pets_7d;

create or replace view app.event_mix_7d as
select type, count(*) as n
from app.events where at > now() - interval '7 days'
group by type order by n desc;

insert into content.settings (key, value) values
  ('dailyTokens', '5'), ('behaviourIntervalSec', '15'), ('hauntingsPerDay', '2'), ('minCooldownSec', '60')
on conflict do nothing;
