import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { TransactionExplorer } from "./transaction-explorer";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  window.history.replaceState(null, "", "/transactions");
});

describe("TransactionExplorer", () => {
  it("renders backend data and opens the read-only entry drawer", async () => {
    vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(new Response(JSON.stringify({ items: [{
        transactionId: "11111111-1111-4111-8111-111111111111",
        transactionReference: "TX-100", currencyCode: "USD", minorUnitExponent: 2,
        totalDebitMinor: "900719925474099312345", status: "POSTED", postedAt: "2025-01-01T12:00:00Z",
        reversalOfTransactionId: null,
      }], page: 0, size: 25, totalElements: 1, totalPages: 1 }), { status: 200, headers: { "content-type": "application/json" } }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        transactionId: "11111111-1111-4111-8111-111111111111", transactionReference: "TX-100",
        currencyCode: "USD", minorUnitExponent: 2, totalDebitMinor: "900719925474099312345", status: "POSTED",
        postedAt: "2025-01-01T12:00:00Z", reversalOfTransactionId: null, description: "Invoice settlement",
        entries: [
          { entryId: "e1", accountId: "a1", accountCode: "WALLET:source", accountDisplayName: "Shubham demo wallet", accountRole: "CUSTOMER_WALLET", direction: "DEBIT", amountMinor: "900719925474099312345", currencyCode: "USD", minorUnitExponent: 2 },
          { entryId: "e2", accountId: "a2", accountCode: "WALLET:destination", accountDisplayName: "Ashwin demo wallet", accountRole: "CUSTOMER_WALLET", direction: "CREDIT", amountMinor: "900719925474099312345", currencyCode: "USD", minorUnitExponent: 2 },
        ],
      }), { status: 200, headers: { "content-type": "application/json" } }));

    render(<TransactionExplorer />);
    await screen.findByRole("button", { name: "TX-100" });
    expect(screen.getByText("USD 9,007,199,254,740,993,123.45")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "TX-100" }));
    expect(await screen.findByText("Invoice settlement")).toBeInTheDocument();
    expect(await screen.findByText("Shubham demo wallet")).toBeInTheDocument();
    expect(screen.getByText("Ashwin demo wallet")).toBeInTheDocument();
    expect(screen.getByText("Balanced")).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Transfer flow" })).toHaveTextContent("Transfer posted");
    expect(screen.getByRole("region", { name: "Transfer flow" })).toHaveTextContent("debited from Shubham demo wallet");
    expect(screen.getByRole("region", { name: "Transfer flow" })).toHaveTextContent("credited to Ashwin demo wallet");
    expect(screen.getByRole("region", { name: "Transfer flow" })).toHaveTextContent("No bank or external payment service was involved.");
    expect(screen.getByText(/read-only in this console/)).toBeInTheDocument();
  });

  it("opens transaction details from a receipt deep link", async () => {
    const id = "22222222-2222-4222-8222-222222222222";
    window.history.replaceState(null, "", `/transactions?transactionId=${id}`);
    const fetchMock = vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(new Response(JSON.stringify({ items: [], page: 0, size: 25, totalElements: 0, totalPages: 0 }), {
        status: 200, headers: { "content-type": "application/json" },
      }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        transactionId: id, transactionReference: "XFER-deep-link", currencyCode: "INR", minorUnitExponent: 2,
        totalDebitMinor: "2500", status: "POSTED", postedAt: "2026-10-28T10:00:00Z", reversalOfTransactionId: null,
        description: "Simulated internal wallet transfer", entries: [],
      }), { status: 200, headers: { "content-type": "application/json" } }));

    render(<TransactionExplorer />);
    expect(await screen.findByText("XFER-deep-link")).toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes(`/api/v1/transactions/${id}`))).toBe(true);
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });

  it("shows the empty state when the API returns no rows", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValueOnce(new Response(JSON.stringify({ items: [], page: 0, size: 25, totalElements: 0, totalPages: 0 }), {
      status: 200, headers: { "content-type": "application/json" },
    }));
    render(<TransactionExplorer />);
    await waitFor(() => expect(screen.getByText("No transactions found")).toBeInTheDocument());
  });
});
