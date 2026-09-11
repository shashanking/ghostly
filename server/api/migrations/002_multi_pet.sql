-- 002: five pets instead of two, and a lease on every slot past the first.
--
-- Nothing here moves a row. Every pet created under 001 keeps exactly the meaning it had: no lease
-- means permanent, which is what "the only pet you can have" already was.
--
-- migrate.js runs each file through one q(sql) with no parameters, so the whole file goes over the
-- simple query protocol as one string. Postgres would wrap that in an implicit transaction anyway;
-- the explicit begin/commit is here because there are no down-migrations — a file that fails half
-- way leaves the schema in a state nobody wrote by hand.
begin;

-- ------------------------------------------------------------------------------------------------
-- The slot ceiling: 2 -> 5.
--
-- 001 wrote the check inline and unnamed, so Postgres generated `pets_slot_check`. Rather than
-- trust that name (a wrong guess would drop nothing, add a second constraint under the name it
-- guessed, and leave the 1..2 rule quietly in force), drop every *check* constraint on the table
-- whose definition mentions the column, then add exactly one back.
--
-- `contype = 'c'` is the load-bearing part of that filter: `unique (user_id, slot)` mentions `slot`
-- too, and the upsert in routes/pets.js conflicts on it. It must come through untouched.
do $$
declare c text;
begin
  for c in
    select conname from pg_constraint
    where conrelid = 'app.pets'::regclass
      and contype = 'c'
      and pg_get_constraintdef(oid) ilike '%slot%'
  loop
    execute format('alter table app.pets drop constraint %I', c);
  end loop;
end $$;

alter table app.pets add constraint pets_slot_check check (slot between 1 and 5);

-- ------------------------------------------------------------------------------------------------
-- Leases live in a column on app.pets, not in an app.pet_slots table.
--
-- A lease is one timestamp per (user_id, slot), and app.pets is already the table holding exactly
-- one row per (user_id, slot) — its unique constraint guarantees that. A second table would restate
-- that key, need its own unique constraint and its own foreign key to keep the two in step, and put
-- a join in front of every read of a pet, all for one nullable column.
--
-- Two things settle it past tidiness:
--
--   1. An expiring lease must not destroy a pet. He keeps his name, his look and his stats and
--      comes back as the same pet when the lease is renewed — a pet fed for a week should not be
--      deleted by a clock (PetStore.kt says the same thing on the client side). As a column,
--      expiry is a comparison against now() and no row is ever touched, so there is no delete to
--      get wrong. As a table, the obvious implementation — remove the slot row when the lease
--      lapses — sits one `on delete cascade` away from taking app.pet_state and app.events with it.
--   2. A new table in schema app is new grant surface. deploy.sh revokes app.* from the editor role
--      `raman` once, over the tables that exist at that moment, and the default privileges it sets
--      in schema app only ever grant, and only to ghostly_api. A column inherits the grants of the
--      table it sits on, so this adds nothing to audit and cannot leak user rows to the editor.
--
-- Null means "never expires": slot 1, and every pet that existed before leases did.
alter table app.pets add column if not exists lease_expires_at timestamptz;

comment on column app.pets.lease_expires_at is
  'When a lent slot lapses. Null = permanent (slot 1, and everything created before 002). A lapsed pet is hidden from the roster, never deleted.';

-- No index on it. The only query that reads it is "every pet for this user", which the existing
-- unique (user_id, slot) index already serves, and which returns at most five rows.

-- ------------------------------------------------------------------------------------------------
-- app.aggregates: `pets` stopped standing in for `users`.
--
-- With one pet each, `pets` and the three species counts were a headcount of users by another name.
-- At five apiece they are not, and the question the editor actually has now — is anyone taking a
-- second pet, and do the lent ones get renewed — was never in here at all.
--
-- So the ten existing columns keep their existing definitions. Redefining a column called `pets`
-- underneath whatever the editor has built on it is worse than adding one next to it. Note that
-- avg_hunger, avg_happiness and avg_energy therefore still average every pet row including lapsed
-- ones, which is right: a pet whose lease ran out yesterday is still a pet somebody was feeding.
--
-- `create or replace`, not drop-then-create: replacing keeps the `grant select ... to raman` that
-- deploy.sh applies to this view, and dropping it would leave the editor unable to read the
-- dashboard until somebody happened to run a deploy. The price is that existing columns cannot be
-- renamed, retyped or reordered and new ones may only be appended — which is why the additions are
-- tacked on the end rather than grouped where they belong.
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
  (select count(distinct pet_id) from app.events where at > now() - interval '7 days') as active_pets_7d,
  -- Appended by 002. Everything below this line is about there being more than one of him.
  (select count(*) from app.pets
    where lease_expires_at is null or lease_expires_at > now())                        as live_pets,
  (select count(*) from app.pets
    where lease_expires_at is not null and lease_expires_at <= now())                  as lapsed_pets,
  (select count(*) from app.users u
    where u.deleted_at is null
      and (select count(*) from app.pets p where p.user_id = u.id) > 1)                as users_with_extra_pets,
  (select round(avg(n), 2) from (select count(*) as n from app.pets group by user_id) per_user)
                                                                                       as pets_per_user;

commit;
