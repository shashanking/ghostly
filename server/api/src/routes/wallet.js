import { q, tx } from "../db.js";
import { requireUser } from "../auth.js";

const epochDay = () => Math.floor(Date.now() / 86_400_000);

async function setting(c, key, fallback) {
  const { rows } = await c.query(`select value from content.settings where key = $1`, [key]);
  return rows[0]?.value ?? fallback;
}

export default async function routes(app) {
  app.addHook("preHandler", requireUser);

  app.get("/wallet", async (request) => {
    const { rows } = await q(`select tokens, granted_day from app.wallet where user_id = $1`, [request.userId]);
    const w = rows[0] ?? { tokens: 0, granted_day: 0 };
    return { tokens: w.tokens, grantedDay: Number(w.granted_day), today: epochDay() };
  });

  /** Idempotent per day: the client can call this on every open. */
  app.post("/wallet/grant", async (request) =>
    tx(async (c) => {
      const today = epochDay();
      const daily = Number(await setting(c, "dailyTokens", 5));
      const { rows } = await c.query(`select tokens, granted_day from app.wallet where user_id = $1 for update`, [request.userId]);
      const w = rows[0];
      if (Number(w.granted_day) >= today) return { tokens: w.tokens, granted: 0 };
      const { rows: upd } = await c.query(
        `update app.wallet set tokens = tokens + $2, granted_day = $3, updated_at = now() where user_id = $1 returning tokens`,
        [request.userId, daily, today]
      );
      return { tokens: upd[0].tokens, granted: daily };
    })
  );

  /** Spend, server-side, so the balance cannot be edited on the phone. */
  app.post("/wallet/spend", async (request, reply) => {
    const amount = Number(request.body?.amount ?? 0);
    if (!(amount > 0 && amount <= 1000)) return reply.code(400).send({ error: "bad_amount" });
    return tx(async (c) => {
      const { rows } = await c.query(`select tokens from app.wallet where user_id = $1 for update`, [request.userId]);
      if (rows[0].tokens < amount) return reply.code(409).send({ error: "insufficient", tokens: rows[0].tokens });
      const { rows: upd } = await c.query(`update app.wallet set tokens = tokens - $2, updated_at = now() where user_id = $1 returning tokens`, [request.userId, amount]);
      return { tokens: upd[0].tokens };
    });
  });

  app.get("/streak", async (request) => {
    const { rows } = await q(`select streak, last_day from app.streaks where user_id = $1`, [request.userId]);
    return { streak: rows[0]?.streak ?? 0, lastDay: Number(rows[0]?.last_day ?? 0) };
  });

  /** Touch today's streak. Consecutive days increment; a gap resets to 1. */
  app.post("/streak/touch", async (request) =>
    tx(async (c) => {
      const today = epochDay();
      const { rows } = await c.query(`select streak, last_day from app.streaks where user_id = $1 for update`, [request.userId]);
      const last = Number(rows[0].last_day);
      let streak = rows[0].streak;
      if (last === today) return { streak };
      streak = last === today - 1 ? streak + 1 : 1;
      await c.query(`update app.streaks set streak = $2, last_day = $3 where user_id = $1`, [request.userId, streak, today]);
      return { streak };
    })
  );
}
