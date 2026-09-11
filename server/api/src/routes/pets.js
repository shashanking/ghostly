import { q, tx } from "../db.js";
import { SPECIES as VALID_SPECIES } from "../validate.js";
import { requireUser } from "../auth.js";
import { applyDecay } from "../decay.js";

const MAX_PETS = 5;

/** Slot 1 is the pet every install already has: permanent, never lent, never given an expiry. */
const PRIMARY_SLOT = 1;

// One list, imported, not a second copy. The copy that used to live here said three while the app
// shipped twelve, and nine species' worth of users synced nothing for it — see validate.js.
const SPECIES = new Set(VALID_SPECIES);

/** Epoch millis, or null for a slot that never expires. app.pets.lease_expires_at is timestamptz;
 *  the phone speaks millis everywhere else, so the conversion happens at this edge. */
const leaseMs = (r) => (r.lease_expires_ms == null ? null : Number(r.lease_expires_ms));

const rowToState = (r) => ({
  hunger: r.hunger, energy: r.energy, happiness: r.happiness, anger: r.anger,
  sleeping: r.sleeping, sleepStartedAt: r.sleep_started_at ? Number(r.sleep_started_at) : 0,
  updatedAt: Number(r.updated_at_ms),
});

async function ownedPet(c, userId, petId) {
  const { rows } = await c.query(`select id from app.pets where id = $1 and user_id = $2`, [petId, userId]);
  return rows[0];
}

export default async function routes(app) {
  app.addHook("preHandler", requireUser);

  /**
   * Every pet on the account, lapsed leases included.
   *
   * A lapsed lease is deliberately not a filter here. The pet still exists — his name and his stats
   * are untouched, and renewing brings back the same pet rather than a new one wearing his name —
   * and this is the call a fresh install uses to find out who it is restoring. Dropping him from
   * the list would make "your lease ran out while your phone was in a drawer" indistinguishable
   * from "that pet never existed". The client gets `leaseExpiresAt` and `expired` and decides.
   */
  app.get("/pets", async (request) => {
    const { rows } = await q(
      `select p.id, p.slot, p.species, p.name, p.traits, p.appearance, p.created_at,
              (extract(epoch from p.lease_expires_at) * 1000)::bigint as lease_expires_ms,
              s.hunger, s.energy, s.happiness, s.anger, s.sleeping, s.sleep_started_at,
              (extract(epoch from s.updated_at) * 1000)::bigint as updated_at_ms
       from app.pets p join app.pet_state s on s.pet_id = p.id
       where p.user_id = $1 order by p.slot`,
      [request.userId]
    );
    const now = Date.now();
    return rows.map((r) => ({
      id: r.id, slot: r.slot, species: r.species, name: r.name, traits: r.traits, appearance: r.appearance,
      leaseExpiresAt: leaseMs(r),
      expired: leaseMs(r) != null && leaseMs(r) <= now,
      state: applyDecay(rowToState(r), now),
    }));
  });

  /** Create or replace the pet in a slot (1..MAX_PETS). The client's current state seeds the server copy. */
  app.post("/pets", async (request, reply) => {
    const { slot = PRIMARY_SLOT, species, name, traits = {}, appearance = {}, state, leaseExpiresAt } = request.body ?? {};
    if (!SPECIES.has(species)) return reply.code(400).send({ error: "bad_species" });
    if (!(slot >= PRIMARY_SLOT && slot <= MAX_PETS)) return reply.code(400).send({ error: "bad_slot" });
    // Whether a slot is lent is the server's call, not the phone's: an expiry on slot 1 would make
    // the pet every install already has quietly lapse. `leaseExpiresAt == null` and not a falsy
    // check, because Number(null) is 0 — a lease that expired in 1970.
    const leaseAt =
      slot === PRIMARY_SLOT || leaseExpiresAt == null || !Number.isFinite(Number(leaseExpiresAt))
        ? null
        : Number(leaseExpiresAt);
    const pet = await tx(async (c) => {
      const { rows } = await c.query(
        `insert into app.pets (user_id, slot, species, name, traits, appearance, lease_expires_at)
         values ($1, $2, $3, $4, $5, $6, to_timestamp($7::double precision / 1000.0))
         on conflict (user_id, slot) do update
           set species = excluded.species, name = excluded.name, traits = excluded.traits, appearance = excluded.appearance,
               -- coalesce, not excluded: a re-POST that carries no lease (an older client, or a
               -- retry after the id cache was cleared) must not silently turn a lent pet permanent.
               -- Ending a lease early is a client-side decision and needs no call.
               lease_expires_at = coalesce(excluded.lease_expires_at, app.pets.lease_expires_at)
         returning id, slot, species, name, traits, appearance,
                   (extract(epoch from lease_expires_at) * 1000)::bigint as lease_expires_ms`,
        [request.userId, slot, species, name ?? null, traits, appearance, leaseAt]
      );
      const p = rows[0];
      const s = state ?? {};
      await c.query(
        `insert into app.pet_state (pet_id, hunger, energy, happiness, anger, sleeping, sleep_started_at, updated_at)
         values ($1, $2, $3, $4, $5, $6, $7, to_timestamp($8 / 1000.0))
         on conflict (pet_id) do update set hunger = excluded.hunger, energy = excluded.energy,
           happiness = excluded.happiness, anger = excluded.anger, sleeping = excluded.sleeping,
           sleep_started_at = excluded.sleep_started_at, updated_at = excluded.updated_at`,
        [p.id, s.hunger ?? 100, s.energy ?? 100, s.happiness ?? 100, s.anger ?? 0, s.sleeping ?? false, s.sleepStartedAt ?? null, s.updatedAt ?? Date.now()]
      );
      return p;
    });
    const { lease_expires_ms, ...row } = pet;
    return { ...row, leaseExpiresAt: leaseMs(pet) };
  });

  app.get("/pets/:id/state", async (request, reply) => {
    const { rows } = await q(
      `select s.*, (extract(epoch from s.updated_at) * 1000)::bigint as updated_at_ms
       from app.pet_state s join app.pets p on p.id = s.pet_id where p.id = $1 and p.user_id = $2`,
      [request.params.id, request.userId]
    );
    if (!rows[0]) return reply.code(404).send({ error: "no_pet" });
    return applyDecay(rowToState(rows[0]), Date.now());
  });

  /**
   * Last writer wins by updatedAt. A phone that was offline for a day sends a stale timestamp and
   * gets the server's decayed copy back instead — that is the point.
   *
   * The body may also carry `species` and `name`. Who he is can change long after he was created —
   * a species swap, a rename — and this is the only call the phone makes afterwards, so it is the
   * only way that change can arrive. Without it the server keeps serving whoever he was on the day
   * the row was written, and a restored device brings back the wrong animal.
   */
  app.put("/pets/:id/state", async (request, reply) => {
    const b = request.body ?? {};
    const result = await tx(async (c) => {
      if (!(await ownedPet(c, request.userId, request.params.id))) return null;
      // Identity first, and independent of whether the state below is accepted: a stale timestamp
      // is a race about numbers, and a rename is not one.
      //
      // An unrecognised species is ignored rather than 400'd. The app offers twelve species and
      // this table allows three — widening that is a content change and not this migration's job —
      // so rejecting the whole request would leave those owners unable to sync stats at all.
      if ("name" in b || SPECIES.has(b.species)) {
        await c.query(
          `update app.pets
             set name    = case when $3::boolean then $2 else name end,
                 species = coalesce($4, species)
           where id = $1`,
          [request.params.id, typeof b.name === "string" ? b.name : null, "name" in b, SPECIES.has(b.species) ? b.species : null]
        );
      }
      const { rows } = await c.query(
        `select s.*, (extract(epoch from s.updated_at) * 1000)::bigint as updated_at_ms
         from app.pet_state s where pet_id = $1 for update`,
        [request.params.id]
      );
      const server = rowToState(rows[0]);
      const clientAt = Number(b.updatedAt ?? 0);
      if (clientAt >= server.updatedAt) {
        await c.query(
          `update app.pet_state set hunger = $2, energy = $3, happiness = $4, anger = $5, sleeping = $6,
             sleep_started_at = $7, updated_at = to_timestamp($8 / 1000.0) where pet_id = $1`,
          [request.params.id, clamp(b.hunger, server.hunger), clamp(b.energy, server.energy), clamp(b.happiness, server.happiness),
           clamp(b.anger, server.anger), b.sleeping ?? server.sleeping, b.sleepStartedAt ?? server.sleepStartedAt ?? null, clientAt]
        );
        return { accepted: true, state: applyDecay({ ...server, ...b, updatedAt: clientAt }, Date.now()) };
      }
      return { accepted: false, state: applyDecay(server, Date.now()) };
    });
    if (!result) return reply.code(404).send({ error: "no_pet" });
    return result;
  });

  /** Append-only. This is the memory stream — what the diary/reflection reads later. */
  app.post("/pets/:id/events", async (request, reply) => {
    const events = Array.isArray(request.body) ? request.body : [];
    if (events.length > 200) return reply.code(400).send({ error: "too_many" });
    const inserted = await tx(async (c) => {
      if (!(await ownedPet(c, request.userId, request.params.id))) return null;
      let n = 0;
      for (const e of events) {
        if (!e?.type) continue;
        await c.query(
          `insert into app.events (pet_id, type, at, meta) values ($1, $2, to_timestamp($3 / 1000.0), $4)`,
          [request.params.id, String(e.type).slice(0, 32), Number(e.at ?? Date.now()), e.meta ?? {}]
        );
        n++;
      }
      return n;
    });
    if (inserted === null) return reply.code(404).send({ error: "no_pet" });
    return { inserted };
  });
}

const clamp = (v, fallback) => (typeof v === "number" && Number.isFinite(v) ? Math.min(100, Math.max(0, v)) : fallback);
