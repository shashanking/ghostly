// Load a pack JSON (stdin) into the editable content tables. Idempotent: rows upsert by id.
import { tx } from "./db.js";

let raw = "";
for await (const chunk of process.stdin) raw += chunk;
const pack = JSON.parse(raw);

const n = await tx(async (c) => {
  for (const [species, data] of Object.entries(pack.species ?? {})) {
    await c.query(
      `insert into content.species_profiles (species, data) values ($1, $2)
       on conflict (species) do update set data = excluded.data, updated_at = now()`,
      [species, data]
    );
  }
  let count = 0;
  for (const r of pack.reactions ?? []) {
    const tags = r.weight === 1 && r.cooldownSec >= 21600 ? ["haunting"] : [];
    await c.query(
      `insert into content.reactions (id, data, tags) values ($1, $2, $3)
       on conflict (id) do update set data = excluded.data, updated_at = now()`,
      [r.id, r, tags]
    );
    count++;
  }
  return count;
});
console.log(`seeded ${n} reactions, ${Object.keys(pack.species ?? {}).length} species`);
process.exit(0);
