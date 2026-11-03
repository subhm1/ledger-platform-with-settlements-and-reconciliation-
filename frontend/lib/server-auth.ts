import { createHmac, timingSafeEqual } from "node:crypto";

export const SESSION_COOKIE = "ledger_operator_session";
const SESSION_SECONDS = 8 * 60 * 60;

function secret() {
  const value = process.env.LEDGER_SESSION_SECRET;
  if (!value || value.length < 32) throw new Error("Session configuration is unavailable.");
  return value;
}

function signature(expiry: string) {
  return createHmac("sha256", secret()).update(`v1:${expiry}`).digest("base64url");
}

export function createSession() {
  const expiry = String(Math.floor(Date.now() / 1000) + SESSION_SECONDS);
  return { value: `v1.${expiry}.${signature(expiry)}`, maxAge: SESSION_SECONDS };
}

export function hasValidSession(value: string | undefined) {
  if (!value) return false;
  const match = /^v1\.(\d{10})\.([A-Za-z0-9_-]{43})$/.exec(value);
  if (!match || Number(match[1]) <= Math.floor(Date.now() / 1000)) return false;
  const expected = Buffer.from(signature(match[1]));
  const supplied = Buffer.from(match[2]);
  return expected.length === supplied.length && timingSafeEqual(expected, supplied);
}

export function credentialsMatch(username: string, password: string) {
  const expectedUser = process.env.LEDGER_OPERATOR_USERNAME ?? "";
  const expectedPassword = process.env.LEDGER_OPERATOR_PASSWORD ?? "";
  return constantEqual(username, expectedUser) && constantEqual(password, expectedPassword)
    && expectedUser.length > 0 && expectedPassword.length >= 24;
}

function constantEqual(value: string, expected: string) {
  const left = Buffer.from(value);
  const right = Buffer.from(expected);
  return left.length === right.length && timingSafeEqual(left, right);
}

export function isLoopbackOrigin(requestOrigin: string | null) {
  if (!requestOrigin) return false;
  try {
    const origin = new URL(requestOrigin);
    const configured = new URL(process.env.LEDGER_APP_ORIGIN ?? "http://127.0.0.1:3000");
    return origin.port === configured.port && origin.protocol === "http:"
      && ["localhost", "127.0.0.1", "[::1]"].includes(origin.hostname);
  } catch { return false; }
}
