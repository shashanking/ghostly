import { q, tx } from "../db.js";
import { verifyGoogleIdToken, signSession, requireUser } from "../auth.js";

export default async function routes(app) {
  /** Exchange a Google ID token for a Ghostly session. Creates the user on first sight. */
  app.post("/auth/google", async (request, reply) => {
    const { idToken, deviceId, model } = request.body ?? {};
    if (!idToken) return reply.code(400).send({ error: "idToken required" });
    let g;
    try {
      g = await verifyGoogleIdToken(idToken);
    } catch (e) {
      request.log.warn({ err: e.message }, "google token rejected");
      return reply.code(401).send({ error: "google_token_invalid" });
    }
    const user = await tx(async (c) => {
      const { rows } = await c.query(
        `insert into app.users (google_sub, email, display_name)
         values ($1, $2, $3)
         on conflict (google_sub) do update
           set email = excluded.email, display_name = coalesce(excluded.display_name, app.users.display_name),
               deleted_at = null, last_seen_at = now()
         returning id, email, display_name`,
        [g.sub, g.email, g.name]
      );
      const u = rows[0];
      await c.query(`insert into app.wallet (user_id) values ($1) on conflict do nothing`, [u.id]);
      await c.query(`insert into app.streaks (user_id) values ($1) on conflict do nothing`, [u.id]);
      if (deviceId) {
        await c.query(
          `insert into app.devices (user_id, device_id, model) values ($1, $2, $3)
           on conflict (user_id, device_id) do update set model = excluded.model, last_seen_at = now()`,
          [u.id, deviceId, model ?? null]
        );
      }
      return u;
    });
    return { sessionToken: await signSession(user.id), user: { id: user.id, email: user.email, displayName: user.display_name } };
  });

  app.get("/me", { preHandler: requireUser }, async (request, reply) => {
    const { rows } = await q(`select id, email, display_name, created_at from app.users where id = $1 and deleted_at is null`, [request.userId]);
    if (!rows[0]) return reply.code(404).send({ error: "no_user" });
    const pets = await q(`select id, slot, species, name, traits, appearance from app.pets where user_id = $1 order by slot`, [request.userId]);
    const u = rows[0];
    return { id: u.id, email: u.email, displayName: u.display_name, createdAt: u.created_at, pets: pets.rows };
  });

  /** Play requires this to exist. Everything cascades from the user row. */
  app.delete("/account", { preHandler: requireUser }, async (request) => {
    await q(`delete from app.users where id = $1`, [request.userId]);
    return { deleted: true };
  });
}
