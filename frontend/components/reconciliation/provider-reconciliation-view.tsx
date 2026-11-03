"use client";

import * as Dialog from "@radix-ui/react-dialog";
import { ArrowRight, ChevronLeft, ChevronRight, Play, RefreshCw, ShieldCheck, X } from "lucide-react";
import { useCallback, useEffect, useState } from "react";
import { apiGet, apiPostJson, type Page } from "@/lib/api/client";
import type {
  ProviderPaymentReconciliationResult,
  ProviderPaymentReconciliationRun,
} from "@/lib/api/types";
import { formatDate } from "@/lib/format";
import { Empty, ErrorState, Loading } from "@/components/ui/feedback";

export function ProviderReconciliationView() {
  const [runs, setRuns] = useState<Page<ProviderPaymentReconciliationRun> | null>(null);
  const [modalRun, setModalRun] = useState<ProviderPaymentReconciliationRun | null>(null);
  const [runPage, setRunPage] = useState(0);
  const [results, setResults] = useState<Page<ProviderPaymentReconciliationResult> | null>(null);
  const [resultsPage, setResultsPage] = useState(0);
  const [outcomeFilter, setOutcomeFilter] = useState("");
  const [loadingRuns, setLoadingRuns] = useState(true);
  const [loadingResults, setLoadingResults] = useState(false);
  const [reconciling, setReconciling] = useState(false);
  const [error, setError] = useState("");
  const [actionMessage, setActionMessage] = useState("");
  const [refreshKey, setRefreshKey] = useState(0);

  const refreshRuns = useCallback(() => {
    setLoadingRuns(true);
    setError("");
    setRefreshKey((k) => k + 1);
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    apiGet<Page<ProviderPaymentReconciliationRun>>(
      `api/v1/provider-payments/reconciliation-runs?page=${runPage}&size=25`,
      controller.signal
    )
      .then((response) => {
        setRuns(response);
      })
      .catch((err) => {
        if (err instanceof Error && err.name === "AbortError") return;
        setError(err instanceof Error ? err.message : "Could not load reconciliation runs.");
      })
      .finally(() => setLoadingRuns(false));

    return () => controller.abort();
  }, [runPage, refreshKey]);

  useEffect(() => {
    if (!modalRun) return;
    const controller = new AbortController();
    const params = new URLSearchParams({ page: String(resultsPage), size: "25" });
    if (outcomeFilter) params.set("outcome", outcomeFilter);

    apiGet<Page<ProviderPaymentReconciliationResult>>(
      `api/v1/provider-payments/reconciliation-runs/${encodeURIComponent(modalRun.id)}/results?${params}`,
      controller.signal
    )
      .then((res) => {
        setResults(res);
      })
      .catch((err) => {
        if (err instanceof Error && err.name === "AbortError") return;
        setError(err instanceof Error ? err.message : "Could not load reconciliation results.");
      })
      .finally(() => setLoadingResults(false));

    return () => controller.abort();
  }, [modalRun, resultsPage, outcomeFilter, refreshKey]);

  const openRunModal = (run: ProviderPaymentReconciliationRun) => {
    setError("");
    setResults(null);
    setLoadingResults(true);
    setResultsPage(0);
    setOutcomeFilter("");
    setModalRun(run);
  };

  const handleRunReconciliation = async () => {
    setReconciling(true);
    setActionMessage("");
    setError("");
    try {
      const summary = await apiPostJson<ProviderPaymentReconciliationRun>(
        "api/v1/provider-payments/reconciliation-runs",
        {}
      );
      setActionMessage(`Reconciliation Run #${summary.runNumber} completed successfully (${summary.matchedCount} matched).`);
      setRunPage(0);
      setRefreshKey((k) => k + 1);
      openRunModal(summary);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Reconciliation run failed.");
    } finally {
      setReconciling(false);
    }
  };

  return (
    <div className="detail-stack">
      <div className="surface-card toolbar">
        <div>
          <span className="eyebrow">RECONCILIATION · PROVIDER PAYMENT EVIDENCE</span>
          <h2 className="section-title" style={{ margin: "4px 0 0" }}>
            Provider Payment Reconciliation
          </h2>
        </div>
        <div className="toolbar-spacer" />
        <button
          className="button secondary"
          type="button"
          onClick={refreshRuns}
          disabled={loadingRuns || reconciling}
        >
          <RefreshCw size={14} className={loadingRuns ? "spin" : undefined} /> Refresh
        </button>
        <button
          className="button"
          type="button"
          onClick={() => void handleRunReconciliation()}
          disabled={reconciling || loadingRuns}
        >
          <Play size={14} className={reconciling ? "spin" : undefined} />
          {reconciling ? "Reconciling…" : "Run Reconciliation"}
        </button>
      </div>

      {actionMessage && (
        <div className="notice" role="status" style={{ borderLeft: "4px solid var(--green)" }}>
          {actionMessage}
        </div>
      )}

      {error && <ErrorState message={error} onRetry={refreshRuns} />}

      {/* Main Full-Width Section: Reconciliation Runs */}
      <section className="surface-card" aria-label="Provider reconciliation runs">
        <div className="toolbar">
          <div>
            <strong className="section-title" style={{ margin: 0 }}>
              Reconciliation Runs
            </strong>
            <p className="muted" style={{ margin: "3px 0 0", fontSize: "12px" }}>
              Click any run to view detailed discrepancy breakdown and evidence matching in a pop-up dialog.
            </p>
          </div>
        </div>

        {loadingRuns ? (
          <Loading label="Loading reconciliation runs" />
        ) : !runs || runs.items.length === 0 ? (
          <Empty
            title="No reconciliation runs yet"
            detail="Click 'Run Reconciliation' to evaluate current internal attempts against webhook evidence."
          />
        ) : (
          <div>
            <div className="run-card-list">
              {runs.items.map((run) => {
                const discrepancies =
                  run.unmatchedInternalCount +
                  run.unmatchedProviderCount +
                  run.duplicateCount +
                  run.conflictingCount;
                return (
                  <button
                    key={run.id}
                    type="button"
                    className="run-card-item"
                    onClick={() => openRunModal(run)}
                  >
                    <div className="run-card-left">
                      <div className="run-card-title">
                        <span>Run #{run.runNumber}</span>
                        <span className={`badge ${run.status === "COMPLETED" ? "good" : "warn"}`}>
                          {run.status}
                        </span>
                      </div>
                      <div className="run-card-details">
                        <span style={{ color: run.matchedCount > 0 ? "var(--green)" : undefined }}>
                          {run.matchedCount} matched
                        </span>
                        {discrepancies > 0 ? (
                          <span style={{ color: "var(--amber)", fontWeight: 600 }}>
                            · {discrepancies} discrepancies
                          </span>
                        ) : (
                          <span> · 0 discrepancies</span>
                        )}
                        <span>· {formatDate(run.startedAt)}</span>
                        <span className="mono">· rule: {run.ruleVersion}</span>
                      </div>
                    </div>
                    <div className="run-card-action">
                      View result details <ArrowRight size={15} />
                    </div>
                  </button>
                );
              })}
            </div>

            {runs.totalPages > 1 && (
              <div className="table-footer">
                <span>Page {runPage + 1} of {runs.totalPages}</span>
                <div className="pagination">
                  <button
                    type="button"
                    disabled={runPage === 0}
                    onClick={() => setRunPage((p) => p - 1)}
                    aria-label="Previous runs page"
                  >
                    <ChevronLeft size={14} />
                  </button>
                  <button
                    type="button"
                    disabled={runPage >= runs.totalPages - 1}
                    onClick={() => setRunPage((p) => p + 1)}
                    aria-label="Next runs page"
                  >
                    <ChevronRight size={14} />
                  </button>
                </div>
              </div>
            )}
          </div>
        )}
      </section>

      {/* Pop-up Page Modal Dialog: Reconciliation Results */}
      <Dialog.Root open={!!modalRun} onOpenChange={(open) => {
        if (!open) {
          setModalRun(null);
          setResults(null);
        }
      }}>
        <Dialog.Portal>
          <Dialog.Overlay className="modal-overlay" />
          <Dialog.Content className="modal-popup" aria-describedby={undefined}>
            {modalRun && (
              <>
                <div className="modal-header">
                  <div>
                    <div style={{ display: "flex", alignItems: "center", gap: "10px" }}>
                      <Dialog.Title asChild>
                        <h2 style={{ margin: 0, fontSize: "18px" }}>
                          Results: Run #{modalRun.runNumber}
                        </h2>
                      </Dialog.Title>
                      <span className={`badge ${modalRun.status === "COMPLETED" ? "good" : "warn"}`}>
                        {modalRun.status}
                      </span>
                    </div>
                    <Dialog.Description asChild>
                      <p style={{ margin: "3px 0 0", fontSize: "12px", color: "var(--muted)" }}>
                        Started {formatDate(modalRun.startedAt)} · Rule version: {modalRun.ruleVersion}
                      </p>
                    </Dialog.Description>
                  </div>

                  <div style={{ display: "flex", alignItems: "center", gap: "12px" }}>
                    <select
                      className="field"
                      value={outcomeFilter}
                      onChange={(e) => {
                        setOutcomeFilter(e.target.value);
                        setResultsPage(0);
                      }}
                      aria-label="Filter by outcome"
                      style={{ minWidth: "170px" }}
                    >
                      <option value="">All Outcomes</option>
                      <option value="MATCHED">MATCHED</option>
                      <option value="UNMATCHED_INTERNAL">UNMATCHED_INTERNAL</option>
                      <option value="UNMATCHED_PROVIDER">UNMATCHED_PROVIDER</option>
                      <option value="DUPLICATE">DUPLICATE</option>
                      <option value="CONFLICTING">CONFLICTING</option>
                    </select>

                    <Dialog.Close asChild>
                      <button className="icon-button" type="button" aria-label="Close result details">
                        <X size={17} />
                      </button>
                    </Dialog.Close>
                  </div>
                </div>

                <div className="modal-metrics">
                  <span>Total Internal: <strong>{modalRun.totalInternalRecords}</strong></span>
                  <span>Total Provider: <strong>{modalRun.totalProviderRecords}</strong></span>
                  <span style={{ color: "var(--green)" }}>Matched: <strong>{modalRun.matchedCount}</strong></span>
                  <span style={{ color: "var(--amber)" }}>Unmatched Internal: <strong>{modalRun.unmatchedInternalCount}</strong></span>
                  <span style={{ color: "var(--amber)" }}>Unmatched Provider: <strong>{modalRun.unmatchedProviderCount}</strong></span>
                  {modalRun.duplicateCount > 0 && <span style={{ color: "var(--red)" }}>Duplicates: <strong>{modalRun.duplicateCount}</strong></span>}
                  {modalRun.conflictingCount > 0 && <span style={{ color: "var(--red)" }}>Conflicting: <strong>{modalRun.conflictingCount}</strong></span>}
                </div>

                <div className="modal-body">
                  {loadingResults ? (
                    <div style={{ padding: "40px" }}>
                      <Loading label="Loading reconciliation results" />
                    </div>
                  ) : !results || results.items.length === 0 ? (
                    <div style={{ padding: "40px" }}>
                      <Empty
                        title="No results found"
                        detail={outcomeFilter ? `No results with outcome '${outcomeFilter}'.` : "No reconciliation results recorded for this run."}
                      />
                    </div>
                  ) : (
                    <div>
                      <div className="table-wrap">
                        <table>
                          <thead>
                            <tr>
                              <th scope="col">Outcome</th>
                              <th scope="col">Provider Payment</th>
                              <th scope="col">Provider Order</th>
                              <th scope="col">Discrepancy / Details</th>
                              <th scope="col">Webhook Evidence ID</th>
                              <th scope="col">Recorded</th>
                            </tr>
                          </thead>
                          <tbody>
                            {results.items.map((item) => {
                              const isGood = item.outcome === "MATCHED";
                              const isBad = item.outcome === "CONFLICTING" || item.outcome === "DUPLICATE";
                              const badgeClass = isGood ? "good" : isBad ? "bad" : "warn";
                              return (
                                <tr key={item.id}>
                                  <td>
                                    <span className={`badge ${badgeClass}`}>{item.outcome}</span>
                                  </td>
                                  <td>
                                    <code>{item.providerPaymentId ?? "—"}</code>
                                  </td>
                                  <td>
                                    <code>{item.providerOrderId ?? "—"}</code>
                                  </td>
                                  <td style={{ maxWidth: "340px", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "normal" }}>
                                    <span style={{ fontSize: "12px" }}>{item.discrepancyReason ?? "Evidence verified."}</span>
                                  </td>
                                  <td>
                                    {item.webhookEventId ? (
                                      <code title={item.webhookEventId}>
                                        {item.webhookEventId.slice(0, 8)}…
                                      </code>
                                    ) : (
                                      <span className="muted">—</span>
                                    )}
                                  </td>
                                  <td className="muted" style={{ fontSize: "11px" }}>
                                    {formatDate(item.createdAt)}
                                  </td>
                                </tr>
                              );
                            })}
                          </tbody>
                        </table>
                      </div>

                      {results.totalPages > 1 && (
                        <div className="table-footer">
                          <span>Page {resultsPage + 1} of {results.totalPages} ({results.totalElements} results)</span>
                          <div className="pagination">
                            <button
                              type="button"
                              disabled={resultsPage === 0}
                              onClick={() => setResultsPage((p) => p - 1)}
                              aria-label="Previous results page"
                            >
                              <ChevronLeft size={14} />
                            </button>
                            <button
                              type="button"
                              disabled={resultsPage >= results.totalPages - 1}
                              onClick={() => setResultsPage((p) => p + 1)}
                              aria-label="Next results page"
                            >
                              <ChevronRight size={14} />
                            </button>
                          </div>
                        </div>
                      )}
                    </div>
                  )}
                </div>
              </>
            )}
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>

      <div className="notice" style={{ display: "flex", alignItems: "center", gap: "10px" }}>
        <ShieldCheck size={18} style={{ color: "var(--green)", flexShrink: 0 }} />
        <span>
          <strong>Accounting Gate Preserved:</strong> All provider payment collections remain strictly behind{" "}
          <code>ACCOUNTING_GATE_PENDING</code>. Reconciliation matches provider evidence deterministically without posting any journal entries or transactions to the general ledger.
        </span>
      </div>
    </div>
  );
}
