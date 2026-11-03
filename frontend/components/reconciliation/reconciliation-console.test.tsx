import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ReconciliationConsole } from "./reconciliation-console";

afterEach(() => vi.restoreAllMocks());

const jsonResponse = (body: unknown) => new Response(JSON.stringify(body), {
  status: 200,
  headers: { "content-type": "application/json" },
});

describe("ReconciliationConsole", () => {
  it("starts a reconciliation run through the API and refreshes persisted results", async () => {
    const batchId = "11111111-1111-4111-8111-111111111111";
    const runId = "22222222-2222-4222-8222-222222222222";
    let postCompleted = false;
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const url = String(input);
      if (url.includes("/reconciliation-runs") && init?.method === "POST") {
        postCompleted = true;
        return new Response(JSON.stringify({ batchId, runId }), {
          status: 201, headers: { "content-type": "application/json" },
        });
      }
      if (url.includes("settlement-batches?page=")) return jsonResponse({
        items: [{ batchId, sourceCode: "BANK", reportId: "REPORT-1", fileSha256: "a".repeat(64),
          status: postCompleted ? "RECONCILED" : "IMPORTED", reportingPeriodStart: "2026-01-01T00:00:00Z",
          reportingPeriodEnd: "2026-02-01T00:00:00Z", createdAt: "2026-02-02T00:00:00Z",
          recordCount: 1, validRecordCount: 1, invalidRecordCount: 0 }],
        page: 0, size: 25, totalElements: 1, totalPages: 1,
      });
      if (url.includes("/records?")) return jsonResponse({
        items: [], page: 0, size: 25, totalElements: 0, totalPages: 0,
      });
      if (url.includes("/reconciliation-runs?")) return jsonResponse({
        items: postCompleted ? [{ runId, batchId, runNumber: 1, ruleVersion: "settlement-v1",
          status: "COMPLETED", startedAt: "2026-02-02T00:00:00Z", completedAt: "2026-02-02T00:01:00Z" }] : [],
        page: 0, size: 25, totalElements: postCompleted ? 1 : 0, totalPages: postCompleted ? 1 : 0,
      });
      if (url.includes("/results?")) return jsonResponse({
        items: [{ resultId: "33333333-3333-4333-8333-333333333333", batchId, runId,
          externalRecordId: "44444444-4444-4444-8444-444444444444", internalTransactionId: null,
          outcome: "EXTERNAL_ONLY", transactionReference: "TX-1", currencyCode: "INR",
          minorUnitExponent: 2, reportedGrossMinor: "12550", internalDebitTotalMinor: null,
          ruleVersion: "settlement-v1", validationErrors: [], createdAt: "2026-02-02T00:01:00Z" }],
        page: 0, size: 25, totalElements: 1, totalPages: 1,
      });
      throw new Error(`Unexpected API path: ${url}`);
    });

    render(<ReconciliationConsole />);
    fireEvent.click(await screen.findByRole("button", { name: "Run reconciliation" }));

    expect(await screen.findByText(/completed and saved/)).toBeInTheDocument();
    expect(await screen.findByText("external only")).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/ledger/api/v1/settlement-batches/${batchId}/reconciliation-runs`,
      expect.objectContaining({ method: "POST" }),
    );
  });

  it("loads persisted evidence and requests the next page of batch records", async () => {
    const batchId = "11111111-1111-4111-8111-111111111111";
    const runId = "22222222-2222-4222-8222-222222222222";
    const fetchMock = vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const url = String(input);
      if (url.includes("settlement-batches?page=")) return jsonResponse({
        items: [{ batchId, sourceCode: "BANK", reportId: "REPORT-1", fileSha256: "a".repeat(64),
          status: "RECONCILED", reportingPeriodStart: "2026-01-01T00:00:00Z",
          reportingPeriodEnd: "2026-02-01T00:00:00Z", createdAt: "2026-02-02T00:00:00Z",
          recordCount: 26, validRecordCount: 26, invalidRecordCount: 0 }],
        page: 0, size: 25, totalElements: 26, totalPages: 2,
      });
      if (url.includes("/records?")) {
        const page = Number(new URL(url, "http://localhost").searchParams.get("page"));
        return jsonResponse({ items: [{ recordId: `record-${page}`, rowNumber: page + 1,
          externalTransactionId: `external-${page}`, transactionReference: `TX-${page}`,
          currencyCode: "INR", grossAmountMinor: "1050", feeAmountMinor: "50",
          adjustmentAmountMinor: "0", netAmountMinor: "1000", validationStatus: "VALID",
          validationErrors: [] }], page, size: 25, totalElements: 26, totalPages: 2 });
      }
      if (url.includes("/reconciliation-runs?")) return jsonResponse({
        items: [{ runId, batchId, runNumber: 1, ruleVersion: "read-api-v1", status: "COMPLETED",
          startedAt: "2026-02-02T00:00:00Z", completedAt: "2026-02-02T00:01:00Z" }],
        page: 0, size: 25, totalElements: 1, totalPages: 1,
      });
      if (url.includes("/results?")) return jsonResponse({
        items: [{ resultId: "33333333-3333-4333-8333-333333333333", batchId, runId,
          externalRecordId: null, internalTransactionId: null, outcome: "MATCHED",
          transactionReference: "TX-0", currencyCode: "INR", minorUnitExponent: 2,
          reportedGrossMinor: "1050", internalDebitTotalMinor: "1050", ruleVersion: "read-api-v1",
          validationErrors: [], createdAt: "2026-02-02T00:01:00Z" }],
        page: 0, size: 25, totalElements: 26, totalPages: 2,
      });
      throw new Error(`Unexpected API path: ${url}`);
    });

    render(<ReconciliationConsole />);
    expect(await screen.findByText("BANK / REPORT-1")).toBeInTheDocument();
    expect(await screen.findByText("TX-0")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Next Batch records page" }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      "/api/ledger/api/v1/settlement-batches/11111111-1111-4111-8111-111111111111/records?page=1&size=25",
      expect.anything(),
    ));
  });
});
