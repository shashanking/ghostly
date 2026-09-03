import Fastify from "fastify";
import { q } from "./db.js";
import authRoutes from "./routes/auth.js";
import petRoutes from "./routes/pets.js";
import walletRoutes from "./routes/wallet.js";
import contentRoutes from "./routes/content.js";

const app = Fastify({ logger: { level: process.env.LOG_LEVEL ?? "info" }, bodyLimit: 512 * 1024 });

// Registered before any route so it applies everywhere. Never echo a database error to a phone.
app.setErrorHandler((err, request, reply) => {
  request.log.error(err);
  const status = err.statusCode && err.statusCode < 500 ? err.statusCode : 500;
  reply.code(status).send({ error: status === 500 ? "internal" : err.message });
});

app.get("/v1/health", async () => {
  await q("select 1");
  return { ok: true, at: Date.now() };
});

await app.register(authRoutes, { prefix: "/v1" });
await app.register(petRoutes, { prefix: "/v1" });
await app.register(walletRoutes, { prefix: "/v1" });
await app.register(contentRoutes, { prefix: "/v1" });

const port = Number(process.env.PORT ?? 3000);
await app.listen({ port, host: "0.0.0.0" });
