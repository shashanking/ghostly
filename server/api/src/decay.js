/**
 * Mirrors PetStats.kt so the server can bring a state up to date with real elapsed time on its own.
 * Rates are "hours from full to empty" (or, asleep, empty to full for energy).
 */
const MAX = 100;
const HUNGER_EMPTY_HOURS = 10;
const ENERGY_AWAKE_EMPTY_HOURS = 14;
const ENERGY_ASLEEP_FILL_HOURS = 3;
const HAPPINESS_EMPTY_HOURS = 20;
const MAX_CAUGHT_UP_SECONDS = 3 * 24 * 3600;

const clamp = (v) => Math.min(MAX, Math.max(0, v));

export function applyDecay(state, nowMs) {
  const elapsed = Math.min(Math.max(0, (nowMs - state.updatedAt) / 1000), MAX_CAUGHT_UP_SECONDS);
  if (elapsed < 1) return { ...state };
  const hunger = clamp(state.hunger - (elapsed * MAX) / (HUNGER_EMPTY_HOURS * 3600));
  const happiness = clamp(state.happiness - (elapsed * MAX) / (HAPPINESS_EMPTY_HOURS * 3600));
  const energy = state.sleeping
    ? clamp(state.energy + (elapsed * MAX) / (ENERGY_ASLEEP_FILL_HOURS * 3600))
    : clamp(state.energy - (elapsed * MAX) / (ENERGY_AWAKE_EMPTY_HOURS * 3600));
  return { ...state, hunger, energy, happiness, updatedAt: nowMs };
}
