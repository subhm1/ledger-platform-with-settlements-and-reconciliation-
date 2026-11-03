import { afterEach, describe, expect, it, vi } from "vitest";
import { NextRequest } from "next/server";
import { createSession, SESSION_COOKIE } from "@/lib/server-auth";
import { GET, POST } from "./[...path]/route";

describe("ledger proxy security", () => {
  afterEach(() => { vi.unstubAllGlobals(); });

  it("rejects unauthenticated financial writes", async () => {
    const request = new NextRequest("http://127.0.0.1:3000/api/ledger/api/v1/settlement-batches", {
      method: "POST", headers: { origin: "http://127.0.0.1:3000" },
    });
    const response = await POST(request, { params: Promise.resolve({ path: ["api", "v1", "settlement-batches"] }) });
    expect(response.status).toBe(401);
  });

  it("rejects a cross-origin mutation with a valid operator session", async () => {
    process.env.LEDGER_SESSION_SECRET = "test-session-secret-long-enough-for-tests-123";
    process.env.LEDGER_APP_ORIGIN = "http://127.0.0.1:3000";
    const session = createSession();
    const request = new NextRequest("http://127.0.0.1:3000/api/ledger/api/v1/settlement-batches", {
      method: "POST", headers: { origin: "http://evil.example", cookie: `${SESSION_COOKIE}=${session.value}` },
    });
    const response = await POST(request, { params: Promise.resolve({ path: ["api", "v1", "settlement-batches"] }) });
    expect(response.status).toBe(403);
  });

  it("accepts the localhost loopback alias on the configured local port", async () => {
    process.env.LEDGER_APP_ORIGIN = "http://127.0.0.1:3000";
    process.env.LEDGER_SESSION_SECRET = "test-session-secret-long-enough-for-tests-123";
    const session = createSession();
    process.env.LEDGER_OPERATOR_USERNAME = "operator";
    process.env.LEDGER_OPERATOR_PASSWORD = "test-password-long-enough-123456";
    const fetchMock = vi.fn().mockResolvedValue(new Response("{}", { status: 200, headers: { "content-type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);
    const request = new NextRequest("http://localhost:3000/api/ledger/api/v1/transactions", {
      headers: { origin: "http://localhost:3000", cookie: `${SESSION_COOKIE}=${session.value}` },
    });
    const response = await GET(request, { params: Promise.resolve({ path: ["api", "v1", "transactions"] }) });
    expect(response.status).toBe(200);
  });

  it("does not expose retired Razorpay API routes", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    const getResponse = await GET(
      new NextRequest("http://127.0.0.1:3000/api/ledger/api/v1/payment-attempts"),
      { params: Promise.resolve({ path: ["api", "v1", "payment-attempts"] }) },
    );
    const postResponse = await POST(
      new NextRequest("http://127.0.0.1:3000/api/ledger/api/v1/payment-attempts", {
        method: "POST", headers: { origin: "http://127.0.0.1:3000" }, body: "{}",
      }),
      { params: Promise.resolve({ path: ["api", "v1", "payment-attempts"] }) },
    );
    expect(getResponse.status).toBe(404);
    expect(postResponse.status).toBe(404);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("forwards a valid same-origin mutation with server-side Basic credentials", async () => {
    process.env.LEDGER_SESSION_SECRET = "test-session-secret-long-enough-for-tests-123";
    process.env.LEDGER_APP_ORIGIN = "http://127.0.0.1:3000";
    process.env.LEDGER_OPERATOR_USERNAME = "operator";
    process.env.LEDGER_OPERATOR_PASSWORD = "test-password-long-enough-123456";
    process.env.LEDGER_API_BASE_URL = "http://backend:8080";
    const session = createSession();
    const fetchMock = vi.fn().mockResolvedValue(new Response("{}", { status: 201, headers: { "content-type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);
    const request = new NextRequest("http://127.0.0.1:3000/api/ledger/api/v1/settlement-batches", {
      method: "POST", headers: { origin: "http://127.0.0.1:3000", cookie: `${SESSION_COOKIE}=${session.value}` }, body: "{}",
    });
    const response = await POST(request, { params: Promise.resolve({ path: ["api", "v1", "settlement-batches"] }) });
    expect(response.status).toBe(201);
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect(new Headers(init.headers).get("authorization")).toBe(`Basic ${Buffer.from("operator:test-password-long-enough-123456").toString("base64")}`);
  });
});
