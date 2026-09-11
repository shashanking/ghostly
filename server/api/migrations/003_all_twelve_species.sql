-- 003: let the server accept the nine species the app has been shipping.
--
-- This is a bug fix, not a feature. The app has had twelve species since 1.2.0; app.pets has
-- accepted three since 001. POST /pets answered `bad_species` for the other nine, GhostlyApi's
-- ensurePet turned that into a null, syncState returned false, and the whole sync path gave up
-- without a word. A signed-in user whose pet was a fox or a frog or a dragon therefore synced
-- nothing, ever — no stats, no events, no restore — while the Settings screen told them signing in
-- would keep their ghost if they changed phone.
--
-- Nothing about it was visible: no crash, no message, no retry limit. ensurePet never reached
-- savePetServerId, so every sync re-POSTed the same doomed row.
--
-- This file is independent of 002 and can be applied on its own merits; it is numbered after it
-- only because 002 was written first. Applying it does not require any client change.
begin;

-- ------------------------------------------------------------------------------------------------
-- app.pets.species
--
-- Same approach as 002's slot check and for the same reason: 001 wrote the constraint inline and
-- unnamed, so its name is whatever Postgres generated. Guessing it wrong would drop nothing, add a
-- second constraint alongside, and leave the three-species rule quietly in force — which is exactly
-- the failure being fixed here, so it is worth not repeating.
--
-- `contype = 'c'` keeps `unique (user_id, slot)` out of the loop; the definition filter keeps 002's
-- slot check out of it.
do $$
declare c text;
begin
  for c in
    select conname from pg_constraint
    where conrelid = 'app.pets'::regclass
      and contype = 'c'
      and pg_get_constraintdef(oid) ilike '%species%'
  loop
    execute format('alter table app.pets drop constraint %I', c);
  end loop;
end $$;

alter table app.pets add constraint pets_species_check check (species in (
  'ghost', 'cat', 'dog', 'bunny', 'fox', 'bear',
  'mouse', 'deer', 'bat', 'frog', 'dragon', 'axolotl'
));

-- ------------------------------------------------------------------------------------------------
-- content.species_profiles.species
--
-- A second, separate three-species ceiling, with a quieter symptom. ghostly-publish builds a pack's
-- `species` block from whatever rows are in this table, so a published pack could only ever carry
-- three voices however many the app knew about. Phones take the newer of the bundled and downloaded
-- packs, so once the server published anything at all, the nine newer species lost the voices that
-- shipped in the APK and fell back to their kin's.
--
-- That fallback is deliberate and works (Species.kinId in the app, Vocalisation.resolve's profile
-- lookup), which is why this was degradation rather than silence — and why validate.js still
-- requires voices only for the original three. Widening the table means the editor *can* give the
-- other nine voices of their own; it does not oblige anyone to.
do $$
declare c text;
begin
  for c in
    select conname from pg_constraint
    where conrelid = 'content.species_profiles'::regclass
      and contype = 'c'
  loop
    execute format('alter table content.species_profiles drop constraint %I', c);
  end loop;
end $$;

alter table content.species_profiles add constraint species_profiles_species_check check (species in (
  'ghost', 'cat', 'dog', 'bunny', 'fox', 'bear',
  'mouse', 'deer', 'bat', 'frog', 'dragon', 'axolotl'
));

-- ------------------------------------------------------------------------------------------------
-- app.aggregates: one column for the whole distribution, not nine more.
--
-- The view has had `ghosts`, `cats` and `dogs` since 001. Appending nine more counts would make it
-- twenty-three columns wide and would need amending again the next time a species is drawn, so the
-- mix goes in as a single jsonb instead. The three original columns stay exactly as they were —
-- `create or replace` cannot drop or reorder columns anyway, and something on the editor's side may
-- well be reading them.
--
-- Species with no pets are absent from the object rather than present as zero: this is read by a
-- person looking at a dashboard, and a list of the nine nobody picked is noise.
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
                                                                                       as pets_per_user,
  -- Appended by 003: the full species mix, so this never needs another column per species.
  (select coalesce(jsonb_object_agg(species, n), '{}'::jsonb)
     from (select species, count(*) as n from app.pets group by species) mix)          as species_mix;

commit;
