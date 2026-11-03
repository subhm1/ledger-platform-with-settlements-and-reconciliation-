import { describe, expect, it, vi } from "vitest";
import { ApiClientError, apiGet, apiPostJson } from "./client";

describe("API client", () => {
  it("returns typed JSON from the same-origin API path", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValueOnce(new Response('{"items":[]}', {
      status: 200, headers: { "content-type": "application/json" },
    }));
    await expect(apiGet<{ items: unknown[] }>("api/v1/transactions?page=0&size=25")).resolves.toEqual({ items: [] });
    expect(fetchMock).toHaveBeenCalledWith("/api/ledger/api/v1/transactions?page=0&size=25", expect.objectContaining({ cache: "no-store" }));
    fetchMock.mockRestore();
  });

  it("posts JSON through the same-origin proxy with an idempotency header", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValueOnce(new Response('{"transactionId":"t"}', {
      status: 201, headers: { "content-type": "application/json" },
    }));
    await expect(apiPostJson<{ transactionId: string }>("api/v1/wallets/transfers",
      { amountMinor: 1250 }, { "Idempotency-Key": "idem-test" }))
      .resolves.toEqual({ transactionId: "t" });
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect(fetchMock.mock.calls[0][0]).toBe("/api/ledger/api/v1/wallets/transfers");
    expect(new Headers(init.headers).get("Content-Type")).toBe("application/json");
    expect(new Headers(init.headers).get("Idempotency-Key")).toBe("idem-test");
    expect(JSON.parse(String(init.body))).toEqual({ amountMinor: 1250 });
  });

  it("maps backend errors consistently and rejects malformed or empty success bodies", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValueOnce(new Response('{"code":"DENIED","message":"Not allowed"}', {
      status: 403, headers: { "content-type": "application/json" },
    }));
    await expect(apiGet("api/v1/transactions")).rejects.toMatchObject({ status: 403, code: "DENIED" });
    vi.mocked(fetch).mockResolvedValueOnce(new Response("not-json", { status: 200, headers: { "content-type": "application/json" } }));
    await expect(apiGet("api/v1/transactions")).rejects.toBeInstanceOf(ApiClientError);
    vi.mocked(fetch).mockResolvedValueOnce(new Response(null, { status: 204 }));
    await expect(apiGet("api/v1/transactions")).rejects.toThrow("empty response");
    vi.restoreAllMocks();
  });
});
