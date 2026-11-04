import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { WalletTransfersConsole } from "./wallet-transfers-console";

const walletList = [
  { walletId: "wallet-shubham", displayName: "Shubham", currencyCode: "INR", minorUnitExponent: 2, status: "ACTIVE", balanceMinor: "10000" },
  { walletId: "wallet-ashwin", displayName: "Ashwin", currencyCode: "INR", minorUnitExponent: 2, status: "ACTIVE", balanceMinor: "10000" },
  { walletId: "wallet-arnav", displayName: "Arnav", currencyCode: "INR", minorUnitExponent: 2, status: "ACTIVE", balanceMinor: "10000" },
  { walletId: "wallet-shreya", displayName: "Shreya", currencyCode: "INR", minorUnitExponent: 2, status: "ACTIVE", balanceMinor: "10000" },
  { walletId: "wallet-swati", displayName: "Swati", currencyCode: "INR", minorUnitExponent: 2, status: "ACTIVE", balanceMinor: "10000" },
];

const receipt = {
  transactionId: "transaction-1",
  transactionReference: "XFER-test-1",
  sourceWalletId: "wallet-shubham",
  sourceName: "Shubham",
  destinationWalletId: "wallet-ashwin",
  destinationName: "Ashwin",
  amountMinor: "2500",
  currencyCode: "INR",
  minorUnitExponent: 2,
  status: "POSTED",
  postedAt: "2026-10-28T10:00:00Z",
};

function response(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), { status, headers: { "content-type": "application/json" } });
}

describe("WalletTransfersConsole", () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    window.sessionStorage.clear();
  });

  it("posts one transfer and shows an honest ledger receipt with the illustrative SBI mark", async () => {
    vi.stubGlobal("crypto", { randomUUID: vi.fn().mockReturnValueOnce("ref-1").mockReturnValueOnce("key-1") });
    const fetchMock = vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(response(walletList))
      .mockResolvedValueOnce(response(receipt, 201))
      .mockResolvedValueOnce(response(walletList));

    render(<WalletTransfersConsole />);
    await screen.findByText("Simulated wallet transfer");
    fireEvent.change(screen.getByLabelText(/Amount \(INR\)/), { target: { value: "25.00" } });
    fireEvent.change(screen.getByLabelText("Reference (optional)"), { target: { value: "lunch split" } });
    fireEvent.click(screen.getByRole("button", { name: "Pay" }));

    expect(await screen.findByRole("heading", { name: "Transfer successful" })).toBeInTheDocument();
    expect(decodeURIComponent(screen.getByAltText(/State Bank of India logo/).getAttribute("src") ?? "")).toContain("/brands/sbi.png");
    expect(screen.getByText(/INR 25.00 debited from Shubham's demo wallet/)).toBeInTheDocument();
    expect(screen.getByText(/INR 25.00 credited to Ashwin's demo wallet/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "View ledger transaction" })).toHaveAttribute(
      "href", "/transactions?transactionId=transaction-1",
    );
    expect(screen.queryByRole("link", { name: /history/i })).not.toBeInTheDocument();

    const post = fetchMock.mock.calls.find((call) => (call[1] as RequestInit | undefined)?.method === "POST");
    expect(post).toBeDefined();
    expect(JSON.parse(String((post?.[1] as RequestInit).body))).toMatchObject({
      sourceWalletId: "wallet-shubham",
      destinationWalletId: "wallet-ashwin",
      amountMinor: 2500,
      reference: "lunch split",
    });
    expect(new Headers((post?.[1] as RequestInit).headers).get("Idempotency-Key")).toBe("key-1");
  });

  it("reuses the idempotency key after an ambiguous failure and after tab refresh", async () => {
    vi.stubGlobal("crypto", { randomUUID: vi.fn().mockReturnValueOnce("ref-1").mockReturnValueOnce("key-1") });
    const fetchMock = vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(response(walletList))
      .mockResolvedValueOnce(response({ message: "Backend unavailable" }, 503))
      .mockResolvedValueOnce(response(walletList))
      .mockResolvedValueOnce(response(receipt, 201))
      .mockResolvedValueOnce(response(walletList));

    const view = render(<WalletTransfersConsole />);
    await screen.findByText("Simulated wallet transfer");
    fireEvent.change(screen.getByLabelText(/Amount \(INR\)/), { target: { value: "25.00" } });
    fireEvent.click(screen.getByRole("button", { name: "Pay" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("reuses the same idempotency key");
    const firstPost = fetchMock.mock.calls.find((call) => (call[1] as RequestInit | undefined)?.method === "POST");
    const firstKey = new Headers((firstPost?.[1] as RequestInit).headers).get("Idempotency-Key");
    expect(window.sessionStorage.getItem("ledger-wallet-transfer-pending")).toContain("key-1");

    view.unmount();
    render(<WalletTransfersConsole />);
    await screen.findByText("Simulated wallet transfer");
    fireEvent.click(screen.getByRole("button", { name: "Pay" }));
    expect(await screen.findByRole("heading", { name: "Transfer successful" })).toBeInTheDocument();
    const posts = fetchMock.mock.calls.filter((call) => (call[1] as RequestInit | undefined)?.method === "POST");
    const secondKey = new Headers((posts[1][1] as RequestInit).headers).get("Idempotency-Key");
    expect(secondKey).toBe(firstKey);
    expect(window.sessionStorage.getItem("ledger-wallet-transfer-pending")).toBeNull();
  });

  it("refreshes balances and shows feedback timestamp", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(response(walletList))
      .mockResolvedValueOnce(response(walletList));

    render(<WalletTransfersConsole />);
    await screen.findByText("Simulated wallet transfer");
    const refreshButton = screen.getByRole("button", { name: /Refresh balances/i });
    fireEvent.click(refreshButton);

    expect(await screen.findByText(/Balances refreshed from ledger at/)).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("reports when all demo wallets are already at 100 INR on reset", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(response(walletList))
      .mockResolvedValueOnce(response(walletList));

    render(<WalletTransfersConsole />);
    await screen.findByText("Simulated wallet transfer");
    const resetButton = screen.getByRole("button", { name: /Reset demo balances/i });
    fireEvent.click(resetButton);

    expect(await screen.findByText("All 5 demo wallets are already at INR 100.00.")).toBeInTheDocument();
    const posts = fetchMock.mock.calls.filter((call) => (call[1] as RequestInit | undefined)?.method === "POST");
    expect(posts).toHaveLength(0);
  });

  it("resets uneven demo balances back to 100 INR using compensating double-entry transfers", async () => {
    vi.stubGlobal("crypto", { randomUUID: vi.fn().mockReturnValue("reset-key") });
    const unevenWallets = [
      { walletId: "wallet-shubham", displayName: "Shubham", currencyCode: "INR", minorUnitExponent: 2, status: "ACTIVE", balanceMinor: "7900" },
      { walletId: "wallet-ashwin", displayName: "Ashwin", currencyCode: "INR", minorUnitExponent: 2, status: "ACTIVE", balanceMinor: "12100" },
      { walletId: "wallet-arnav", displayName: "Arnav", currencyCode: "INR", minorUnitExponent: 2, status: "ACTIVE", balanceMinor: "10000" },
      { walletId: "wallet-shreya", displayName: "Shreya", currencyCode: "INR", minorUnitExponent: 2, status: "ACTIVE", balanceMinor: "10000" },
      { walletId: "wallet-swati", displayName: "Swati", currencyCode: "INR", minorUnitExponent: 2, status: "ACTIVE", balanceMinor: "10000" },
    ];

    const resetReceipt = {
      ...receipt,
      sourceWalletId: "wallet-ashwin",
      sourceName: "Ashwin",
      destinationWalletId: "wallet-shubham",
      destinationName: "Shubham",
      amountMinor: "2100",
    };

    const fetchMock = vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(response(unevenWallets))
      .mockResolvedValueOnce(response(unevenWallets))
      .mockResolvedValueOnce(response(resetReceipt, 201))
      .mockResolvedValueOnce(response(walletList));

    render(<WalletTransfersConsole />);
    await screen.findByText("Simulated wallet transfer");
    expect(screen.getByText("INR 79.00")).toBeInTheDocument();
    expect(screen.getByText("INR 121.00")).toBeInTheDocument();

    const resetButton = screen.getByRole("button", { name: /Reset demo balances/i });
    fireEvent.click(resetButton);

    expect(await screen.findByText("All 5 demo wallets restored to INR 100.00 via balanced ledger transactions.")).toBeInTheDocument();
    const posts = fetchMock.mock.calls.filter((call) => (call[1] as RequestInit | undefined)?.method === "POST");
    expect(posts).toHaveLength(1);
    expect(JSON.parse(String((posts[0][1] as RequestInit).body))).toMatchObject({
      sourceWalletId: "wallet-ashwin",
      destinationWalletId: "wallet-shubham",
      amountMinor: 2100,
      reference: "Demo balance reset to INR 100.00",
    });
  });
});
