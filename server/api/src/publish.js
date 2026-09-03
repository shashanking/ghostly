// Snapshot the editable content into a new immutable pack version — only if it validates.
import { q, tx } from "./db.js";
import { validatePack } from "./validate.js";

const dryRun = process.argv.includes("--dry-run");
const notes = process.argv.find((a) => a.startsWith("--notes="))?.slice(8) ?? null;

const species = {};
for (const r of (await q(`select species, data from content.species_profiles`)).rows) species[r.species] = r.data;
const reactions = (await q(`select data from content.reactions where enabled order by id`)).rows.map((r) => r.data);
const pack = { version: 0, species, reactions };

const problems = validatePack(pack);
if (problems.length) {
  console.error(`REFUSED: ${problems.length} problem(s). Nothing published.`);
  for (const p of problems.slice(0, 40)) console.error("  -", p);
  process.exit(2);
}

const hauntings = reactions.filter((r) => r.weight === 1 && r.cooldownSec >= 21600).length;
const summary = `${reactions.length} reactions (${hauntings} hauntings), species: ${Object.keys(species).join(", ")}`;
if (dryRun) { console.log(`valid — ${summary}`); process.exit(0); }

const version = await tx(async (c) => {
  const { rows } = await c.query(`select coalesce(max(version), 0) + 1 as v from content.packs`);
  const v = rows[0].v;
  pack.version = v;
  await c.query(`update content.packs set active = false where active`);
  await c.query(`insert into content.packs (version, pack, notes, active) values ($1, $2, $3, true)`, [v, pack, notes]);
  return v;
});
console.log(`published v${version} — ${summary}`);
process.exit(0);
