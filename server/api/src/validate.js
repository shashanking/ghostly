/** The same rules the app's loader applies — a pack that fails here never reaches a phone. */
export const EMOTES = new Set(["GOOFY", "MOODY", "SPOOKED", "AFFECTION", "HUNGRY", "CONFIDENT", "HAPPY", "SLEEPY", "CURIOUS"]);
export const LOCOMOTIONS = new Set([
  // Plain ways of getting about.
  "DRIFT", "FLEE", "APPROACH", "PERCH_CORNER", "ZOOMIES", "STILL",
  // Set pieces: they take him over for a few seconds. An app too old to know one of these
  // falls back to DRIFT, so they are safe to publish ahead of a release.
  "ROLLOVER", "BOUNCE", "ORBIT", "PACE", "EDGE_SLIDE", "PEEK",
]);
export const BUBBLES = new Set(["sun", "moon", "star", "heart", "heart_broken", "fish", "bone", "food", "dash", "zzz", "note", "cloud", "rain", "sweat", "anger", "question", "sparkle", "bug", "ball", "yarn", "battery_low", "charging", "party", "eyes", "none"]);
export const TIMES = new Set(["lateNight", "dawn", "morning", "afternoon", "evening", "night"]);
export const SPECIES = ["ghost", "cat", "dog"];
const CATEGORICAL = new Set(["timeOfDay", "lastEvent", "species", "batteryBucket", "dayOfWeek"]);

export function validatePack(pack) {
  const problems = [];
  const species = pack.species ?? {};
  for (const s of SPECIES) {
    if (!species[s]?.vocal?.byMood) problems.push(`species ${s}: missing vocal.byMood`);
  }
  const ids = new Set();
  for (const r of pack.reactions ?? []) {
    const tag = `reaction ${r.id ?? "?"}`;
    if (!r.id) problems.push("reaction without id");
    else if (ids.has(r.id)) problems.push(`${tag}: duplicate id`);
    ids.add(r.id);
    if (!EMOTES.has(r.emote)) problems.push(`${tag}: bad emote ${r.emote}`);
    if (!LOCOMOTIONS.has(r.locomotion)) problems.push(`${tag}: bad locomotion ${r.locomotion}`);
    if (r.bubble && !BUBBLES.has(r.bubble)) problems.push(`${tag}: bad bubble ${r.bubble}`);
    if (!(r.weight >= 1)) problems.push(`${tag}: weight`);
    if (!(r.cooldownSec >= 0)) problems.push(`${tag}: cooldownSec`);
    const w = r.when ?? {};
    for (const [k, v] of Object.entries(w)) {
      if (v === null) problems.push(`${tag}: explicit null for ${k}`);
      if (CATEGORICAL.has(k) && !Array.isArray(v)) problems.push(`${tag}: ${k} must be an array`);
      if (k === "timeOfDay") for (const t of v) if (!TIMES.has(t)) problems.push(`${tag}: bad timeOfDay ${t}`);
    }
    if (r.vocal && !String(r.vocal).startsWith("!")) {
      for (const s of w.species ?? SPECIES) {
        if (!species[s]?.vocal?.byMood?.[r.vocal]) problems.push(`${tag}: vocal '${r.vocal}' not in ${s}`);
      }
    }
  }
  return problems;
}
