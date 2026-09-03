import { createRemoteJWKSet, jwtVerify, SignJWT } from "jose";

const GOOGLE_JWKS = createRemoteJWKSet(new URL("https://www.googleapis.com/oauth2/v3/certs"));
const GOOGLE_ISSUERS = ["https://accounts.google.com", "accounts.google.com"];
const SESSION_TTL_DAYS = 30;

const secret = () => new TextEncoder().encode(process.env.SESSION_SECRET);

/** Verify a Google ID token from the app. Throws on anything not signed by Google for us. */
export async function verifyGoogleIdToken(idToken) {
  const { payload } = await jwtVerify(idToken, GOOGLE_JWKS, {
    issuer: GOOGLE_ISSUERS,
    audience: process.env.GOOGLE_CLIENT_ID,
  });
  if (!payload.sub) throw new Error("no subject");
  return {
    sub: payload.sub,
    email: payload.email ?? null,
    emailVerified: payload.email_verified === true,
    name: payload.name ?? null,
  };
}

export async function signSession(userId) {
  return new SignJWT({ uid: userId })
    .setProtectedHeader({ alg: "HS256" })
    .setIssuedAt()
    .setIssuer("ghostly")
    .setExpirationTime(`${SESSION_TTL_DAYS}d`)
    .sign(secret());
}

export async function verifySession(token) {
  const { payload } = await jwtVerify(token, secret(), { issuer: "ghostly" });
  return payload.uid;
}

/** Fastify preHandler: requires a valid session and sets request.userId. */
export async function requireUser(request, reply) {
  const header = request.headers.authorization ?? "";
  const token = header.startsWith("Bearer ") ? header.slice(7) : null;
  if (!token) return reply.code(401).send({ error: "missing_token" });
  try {
    request.userId = await verifySession(token);
  } catch {
    return reply.code(401).send({ error: "invalid_token" });
  }
}
