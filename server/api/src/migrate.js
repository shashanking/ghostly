import { readdir, readFile } from "node:fs/promises";
import { q } from "./db.js";

await q(`create table if not exists public.migrations (name text primary key, applied_at timestamptz default now())`);
const files = (await readdir(new URL("../migrations/", import.meta.url))).filter((f) => f.endsWith(".sql")).sort();
for (const f of files) {
  const { rows } = await q(`select 1 from public.migrations where name = $1`, [f]);
  if (rows.length) continue;
  const sql = await readFile(new URL(`../migrations/${f}`, import.meta.url), "utf8");
  await q(sql);
  await q(`insert into public.migrations (name) values ($1)`, [f]);
  console.log("applied", f);
}
console.log("migrations up to date");
process.exit(0);
