"use client";

import { ChevronLeft, ChevronRight, Play, RefreshCw, ShieldCheck } from "lucide-react";
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
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);
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
        setSelectedRunId((current) => {
          if (current && response.items.some((r) => r.id === current)) return current;
          return response.items[0]?.id ?? null;
        });
      })
      .catch((err) => {
        if (err instanceof Error && err.name === "AbortError") return;
        setError(err instanceof Error ? err.message : "Could not load reconciliation runs.");
      })
      .finally(() => setLoadingRuns(false));

    return () => controller.abort();
  }, [runPage, refreshKey]);

  useEffect(() => {
    if (!selectedRunId) return;
    const controller = new AbortController();
    const params = new URLSearchParams({ page: String(resultsPage), size: "25" });
    if (outcomeFilter) params.set("outcome", outcomeFilter);

    apiGet<Page<ProviderPaymentReconciliationResult>>(
      `api/v1/provider-payments/reconciliation-runs/${encodeURIComponent(selectedRunId)}/results?${params}`,
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
  }, [selectedRunId, resultsPage, outcomeFilter, refreshKey]);

  const selectRun = (runId: string) => {
    if (runId === selectedRunId) return;
    setError("");
    setResults(null);
    setResultsPage(0);
    setLoadingResults(true);
    setSelectedRunId(runId);
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
      setRunPage(0); setResultsPage(0); setResults(null); setLoadingResults(true); setSelectedRunId(summary.id); setRefreshKey((k) => k + 1);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Reconciliation run failed.");
    } finally {
      setReconciling(false);
    }
  };

  const activeRun = runs?.items.find((r) => r.id === selectedRunId);

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

      <div className="split-layout">
        {/* Left Column: Reconciliation Runs */}
        <section className="surface-card" aria-label="Provider reconciliation runs">
          <div className="toolbar">
            <strong className="section-title" style={{ margin: 0 }}>
              Reconciliation Runs
            </strong>
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
              {runs.items.map((run) => {
                const isSelected = run.id === selectedRunId;
                const discrepancies =
                  run.unmatchedInternalCount +
                  run.unmatchedProviderCount +
                  run.duplicateCount +
                  run.conflictingCount;
                return (
                  <button
                    key={run.id}
                    type="button"
                    className={`batch-item ${isSelected ? "selected" : ""}`}
                    onClick={() => {
                      selectRun(run.id);
                    }}
                  >
                    <div className="batch-main">
                      <span>Run #{run.runNumber}</span>
                      <span className={`badge ${run.status === "COMPLETED" ? "good" : "warn"}`}>
                        {run.status}
                      </span>
                    </div>
                    <div className="batch-sub">
                      <span>{run.matchedCount} matched</span>
                      {discrepancies > 0 ? (
                        <span style={{ color: "var(--amber)" }}> · {discrepancies} discrepancies</span>
                      ) : (
                        <span> · 0 discrepancies</span>
                      )}
                    </div>
                    <div className="batch-sub muted">
                      {formatDate(run.startedAt)} · rule: {run.ruleVersion}
                    </div>
                  </button>
                );
              })}
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

        {/* Right Column: Run Results */}
        <section className="surface-card" aria-label="Reconciliation results">
          <div className="toolbar">
            <div>
              <strong className="section-title" style={{ margin: 0 }}>
                {activeRun ? `Results: Run #${activeRun.runNumber}` : "Reconciliation Results"}
              </strong>
            </div>
            <div className="toolbar-spacer" />
            <select
              className="field"
              value={outcomeFilter}
              onChange={(e) => {
                setOutcomeFilter(e.target.value);
                setResultsPage(0);
              }}
              aria-label="Filter by outcome"
            >
              <option value="">All Outcomes</option>
              <option value="MATCHED">MATCHED</option>
              <option value="UNMATCHED_INTERNAL">UNMATCHED_INTERNAL</option>
              <option value="UNMATCHED_PROVIDER">UNMATCHED_PROVIDER</option>
              <option value="DUPLICATE">DUPLICATE</option>
              <option value="CONFLICTING">CONFLICTING</option>
            </select>
          </div>

          {activeRun && (
            <div style={{ padding: "12px 16px", background: "var(--surface-2)", borderBottom: "1px solid var(--border)", display: "flex", flexWrap: "wrap", gap: "12px", fontSize: "12px" }}>
              <span>Total Internal: <strong>{activeRun.totalInternalRecords}</strong></span>
              <span>Total Provider: <strong>{activeRun.totalProviderRecords}</strong></span>
              <span style={{ color: "var(--green)" }}>Matched: <strong>{activeRun.matchedCount}</strong></span>
              <span style={{ color: "var(--amber)" }}>Unmatched Internal: <strong>{activeRun.unmatchedInternalCount}</strong></span>
              <span style={{ color: "var(--amber)" }}>Unmatched Provider: <strong>{activeRun.unmatchedProviderCount}</strong></span>
              {activeRun.duplicateCount > 0 && <span style={{ color: "var(--red)" }}>Duplicates: <strong>{activeRun.duplicateCount}</strong></span>}
              {activeRun.conflictingCount > 0 && <span style={{ color: "var(--red)" }}>Conflicting: <strong>{activeRun.conflictingCount}</strong></span>}
            </div>
          )}

          {loadingResults ? (
            <Loading label="Loading reconciliation results" />
          ) : !selectedRunId ? (
            <Empty
              title="Select a reconciliation run"
              detail="Choose a run from the list on the left to inspect its detailed matching outcomes."
            />
          ) : !results || results.items.length === 0 ? (
            <Empty
              title="No results found"
              detail={outcomeFilter ? `No results with outcome '${outcomeFilter}'.` : "No reconciliation results recorded for this run."}
            />
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
                          <td style={{ maxWidth: "260px", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "normal" }}>
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
        </section>
      </div>

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
