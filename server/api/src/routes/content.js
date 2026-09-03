import { q } from "../db.js";

/** Content is public and cacheable — there is nothing per-user in it, so no auth. */
export default async function routes(app) {
  app.get("/content/manifest", async () => {
    const { rows } = await q(`select version, published_at, notes from content.packs where active order by version desc limit 1`);
    const p = rows[0];
    return p ? { version: p.version, publishedAt: p.published_at, notes: p.notes } : { version: 0 };
  });

  app.get("/content/pack", async (request, reply) => {
    const { rows } = await q(`select version, pack from content.packs where active order by version desc limit 1`);
    if (!rows[0]) return reply.code(404).send({ error: "no_pack" });
    const etag = `"v${rows[0].version}"`;
    if (request.headers["if-none-match"] === etag) return reply.code(304).send();
    reply.header("ETag", etag).header("Cache-Control", "public, max-age=3600");
    return rows[0].pack;
  });

  /** Today's event cluster, if the editor set one. */
  app.get("/content/daily", async () => {
    const { rows } = await q(`select day, data from content.daily where day = current_date`);
    return rows[0] ? { day: rows[0].day, ...rows[0].data } : { day: null };
  });

  /** Tunables the editor may adjust between releases, clamped so a typo cannot break the app. */
  app.get("/content/settings", async () => {
    const { rows } = await q(`select key, value from content.settings`);
    const out = {};
    for (const r of rows) out[r.key] = r.value;
    const clamp = (k, lo, hi, d) => { const v = Number(out[k]); out[k] = Number.isFinite(v) ? Math.min(hi, Math.max(lo, v)) : d; };
    clamp("dailyTokens", 0, 50, 5);
    clamp("behaviourIntervalSec", 5, 120, 15);
    clamp("hauntingsPerDay", 0, 10, 2);
    clamp("minCooldownSec", 0, 3600, 60);
    return out;
  });
}
