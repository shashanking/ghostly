import { q, tx } from "../db.js";
import { requireUser } from "../auth.js";
import { applyDecay } from "../decay.js";

const MAX_PETS = 2;
const SPECIES = new Set(["ghost", "cat", "dog"]);

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

  app.get("/pets", async (request) => {
    const { rows } = await q(
      `select p.id, p.slot, p.species, p.name, p.traits, p.appearance, p.created_at,
              s.hunger, s.energy, s.happiness, s.anger, s.sleeping, s.sleep_started_at,
              (extract(epoch from s.updated_at) * 1000)::bigint as updated_at_ms
       from app.pets p join app.pet_state s on s.pet_id = p.id
       where p.user_id = $1 order by p.slot`,
      [request.userId]
    );
    const now = Date.now();
    return rows.map((r) => ({
      id: r.id, slot: r.slot, species: r.species, name: r.name, traits: r.traits, appearance: r.appearance,
      state: applyDecay(rowToState(r), now),
    }));
  });

  /** Create or replace the pet in a slot (1 or 2). The client's current state seeds the server copy. */
  app.post("/pets", async (request, reply) => {
    const { slot = 1, species, name, traits = {}, appearance = {}, state } = request.body ?? {};
    if (!SPECIES.has(species)) return reply.code(400).send({ error: "bad_species" });
    if (!(slot >= 1 && slot <= MAX_PETS)) return reply.code(400).send({ error: "bad_slot" });
    const pet = await tx(async (c) => {
      const { rows } = await c.query(
        `insert into app.pets (user_id, slot, species, name, traits, appearance)
         values ($1, $2, $3, $4, $5, $6)
         on conflict (user_id, slot) do update
           set species = excluded.species, name = excluded.name, traits = excluded.traits, appearance = excluded.appearance
         returning id, slot, species, name, traits, appearance`,
        [request.userId, slot, species, name ?? null, traits, appearance]
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
    return pet;
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
   */
  app.put("/pets/:id/state", async (request, reply) => {
    const b = request.body ?? {};
    const result = await tx(async (c) => {
      if (!(await ownedPet(c, request.userId, request.params.id))) return null;
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
